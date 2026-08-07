package com.example.game2048;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多模型 Provider 统一接入 + 自动切换（故障转移）。
 * 全部走 OpenAI 兼容 /v1/chat/completions 接口。
 * Key 从 SharedPreferences 读取（不再硬编码，避免 secret 泄露）。
 */
public class ProviderManager {

    public static class Provider {
        public String name;
        public String endpoint; // 完整 base，如 https://api.groq.com/openai/v1
        public String apiKey;
        public String model;
        public boolean enabled = true;
        public Provider(String name, String endpoint, String apiKey, String model) {
            this.name = name;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.model = model;
        }
    }

    private final List<Provider> providers = new ArrayList<>();
    private int cursor = 0;

    /**
     * 从配置中构建 provider 列表。未配置的 provider 用内置默认地址+模型名，key 为空则跳过。
     */
    public ProviderManager(SharedPreferences prefs, Context ctx) {
        addProvider(prefs, ctx, "groq",   "Groq",       "https://api.groq.com/openai/v1",              "llama-3.3-70b-versatile");
        addProvider(prefs, ctx, "or1",    "OpenRouter-1","https://openrouter.ai/api/v1",               "openai/gpt-4o-mini");
        addProvider(prefs, ctx, "or2",    "OpenRouter-2","https://openrouter.ai/api/v1",               "openai/gpt-4o-mini");
        addProvider(prefs, ctx, "samba",  "SambaNova",  "https://api.sambanova.ai/v1",                "Llama-3.3-70B-Instruct");
    }

    private void addProvider(SharedPreferences prefs, Context ctx, String keyId,
                             String name, String endpoint, String defaultModel) {
        String key = prefs.getString("pk_" + keyId, "");
        String model = prefs.getString("pm_" + keyId, defaultModel);
        if (!key.isEmpty() && !key.equalsIgnoreCase("SKIP")) {
            providers.add(new Provider(name, endpoint, key, model));
        }
    }

    public List<Provider> getProviders() { return providers; }

    /**
     * 从当前游标开始依次尝试，返回第一个成功的回复文本（带来源前缀）。
     * 若全部失败，抛出最后一条异常。
     */
    public String chat(String userMessage) throws Exception {
        if (providers.isEmpty()) {
            throw new Exception("尚未配置任何 API Key。请在设置中填入至少一个 Key。");
        }
        Exception last = null;
        int n = providers.size();
        for (int i = 0; i < n; i++) {
            Provider p = providers.get((cursor + i) % n);
            cursor = (cursor + i + 1) % n; // 轮转游标
            if (p.apiKey.isEmpty() || "SKIP".equalsIgnoreCase(p.apiKey)) continue;
            try {
                String r = callOnce(p, userMessage);
                return "[" + p.name + "] " + r;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception("全部 " + n + " 个已配置模型都失败，最后错误: " +
                (last == null ? "未知" : last.getMessage()), last);
    }

    private String callOnce(Provider p, String userMessage) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", p.model);
        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", userMessage);
        messages.put(msg);
        body.put("messages", messages);
        body.put("max_tokens", 800);

        URL url = new URL(p.endpoint + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + p.apiKey);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        String resp = readAll(is);
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + resp);
        }
        JSONObject json = new JSONObject(resp);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("无 choices 返回: " + resp);
        }
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        return message.optString("content", "").trim();
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
