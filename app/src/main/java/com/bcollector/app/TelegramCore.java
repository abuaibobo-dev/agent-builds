package com.bcollector.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sun.jna.Pointer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class TelegramCore {
    private static volatile int API_ID = 0; // 用户设置
    private static volatile String API_HASH = ""; // 用户设置
    private static final long WAIT_MS = 10000;
    private static final String STATE_NEED_CREDENTIALS = "authorizationStateNeedCredentials";
    private static volatile TelegramCore instance;
    private String pendingPhone = "";

    private final Context context;
    private volatile Pointer client;
    private final Object clientLock = new Object();
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong extra = new AtomicLong(1000);
    private final AtomicReference<String> authState = new AtomicReference<>("unknown");
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final AtomicReference<JSONObject> me = new AtomicReference<>(null);
    private volatile boolean running = true;

    static TelegramCore get(Context context) {
        if (instance == null) {
            synchronized (TelegramCore.class) {
                if (instance == null) {
                    instance = new TelegramCore(context);
                }
            }
        }
        return instance;
    }

    private TelegramCore(Context context) {
        this.context = context.getApplicationContext();
        // 加载上次保存的 API 凭证
        android.content.SharedPreferences prefs = context.getSharedPreferences("bcollector_prefs", Context.MODE_PRIVATE);
        int savedApiId = prefs.getInt("api_id", 0);
        String savedApiHash = prefs.getString("api_hash", "");
        if (savedApiId > 0 && !savedApiHash.isEmpty()) {
            // 检查是否误存了 Bot Token 格式（API Hash 过长通常是 Bot Token 特征）
            if (savedApiHash.length() < 40) {
                API_ID = savedApiId;
                API_HASH = savedApiHash;
            } else {
                // 看起来像 Bot Token，清除保存的凭证
                android.util.Log.w("TDLib", "检测到已保存的凭证疑似 Bot Token 格式，已自动清除");
                prefs.edit().remove("api_id").remove("api_hash").apply();
            }
        }
        client = TdJson.tdJsonClientCreate();
        startReceiver();
    }

    String handle(String path, String body) {
        try {
            JSONObject input = new JSONObject(body);
            if ("/api/telegram/status".equals(path)) return status().toString();
            if ("/api/telegram/request-code".equals(path)) return requestCode(input).toString();
            if ("/api/telegram/sign-in".equals(path)) return signIn(input).toString();
            if ("/api/telegram/logout".equals(path)) return logout().toString();
            if ("/api/telegram/update-credentials".equals(path)) return updateCredentials(input).toString();
            if ("/api/telegram/restart".equals(path)) return restart().toString();
            if ("/api/telegram/sync-channels".equals(path)) return syncChannels().toString();
            if ("/api/telegram/create-channel".equals(path)) return createChannel(input).toString();
            if ("/api/telegram/collect-once".equals(path)) return collectOnce(input).toString();
            if ("/api/telegram/latest-message-id".equals(path)) return latestMessageId(input).toString();
            if ("/api/telegram/resend-text".equals(path)) return resendText(input).toString();
            if ("/api/runtime/service".equals(path)) return runtimeService(input).toString();
            if ("/api/runtime/state".equals(path)) return CollectorService.snapshot(context).toString();
            if ("/api/runtime/health".equals(path)) return runtimeHealth().toString();
            if ("/api/export-source".equals(path)) return exportSourcePackage().toString();
            return error("未知接口：" + path, 404).toString();
        } catch (Exception error) {
            return error(error.getMessage() == null ? "请求失败" : error.getMessage(), 500).toString();
        }
    }

    private void startReceiver() {
        Thread thread = new Thread(() -> {
            while (running) {
                if (client == null) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    continue;
                }
                String raw = TdJson.tdJsonClientReceive(client, 1.0);
                if (raw == null || raw.isEmpty()) continue;
                try {
                    JSONObject object = new JSONObject(raw);
                    String type = object.optString("@type");
                    if ("updateAuthorizationState".equals(type)) {
                        onAuthState(object.getJSONObject("authorization_state"));
                        continue;
                    }
                    if ("updateUser".equals(type)) {
                        JSONObject user = object.optJSONObject("user");
                        if (user != null && user.optBoolean("is_self", false)) me.set(user);
                    }
                    if ("error".equals(type)) {
                        String errMsg = object.optString("message", "未知错误");
                        lastError.set(errMsg);
                        // 如果是在参数设置阶段出错，立刻转为需要凭证状态
                        if ("authorizationStateWaitTdlibParameters".equals(authState.get())) {
                            android.util.Log.w("TDLib", "TDLib 参数设置失败: " + errMsg);
                            authState.set(STATE_NEED_CREDENTIALS);
                            // 唤醒所有等待中的请求
                            for (Pending p : pending.values()) {
                                if (p.latch.getCount() > 0) p.latch.countDown();
                            }
                        }
                    }
                    long id = object.optLong("@extra", 0L);
                    Pending item = pending.remove(id);
                    if (item != null) {
                        item.result.set(object);
                        item.latch.countDown();
                    }
                } catch (JSONException ignored) {
                    // Ignore malformed TDLib events.
                }
            }
        }, "tdjson-receiver");
        thread.setDaemon(true);
        thread.start();
    }

    private void onAuthState(JSONObject state) throws JSONException {
        String type = state.optString("@type");
        
        // 如果收到关闭/关闭中状态，标记为需要重启
        if ("authorizationStateClosing".equals(type) || "authorizationStateClosed".equals(type)) {
            authState.set(type);
            return;
        }
        
        authState.set(type);
        if ("authorizationStateWaitTdlibParameters".equals(type)) {
            // 检查 API 凭证是否已设置
            if (API_ID <= 0 || API_HASH.isEmpty()) {
                authState.set(STATE_NEED_CREDENTIALS);
                android.util.Log.w("TDLib", "API_ID 或 API_HASH 未设置，请在设置页面配置");
                return;
            }
            JSONObject params = new JSONObject();
            params.put("@type", "setTdlibParameters");
            params.put("use_test_dc", false);
            params.put("database_directory", new File(context.getFilesDir(), "tdlib-db").getAbsolutePath());
            params.put("files_directory", new File(context.getFilesDir(), "tdlib-files").getAbsolutePath());
            params.put("use_file_database", true);
            params.put("use_chat_info_database", true);
            params.put("use_message_database", true);
            params.put("use_secret_chats", false);
            params.put("api_id", API_ID);
            params.put("api_hash", API_HASH);
            params.put("system_language_code", "zh-CN");
            params.put("device_model", "Android");
            params.put("system_version", android.os.Build.VERSION.RELEASE);
            params.put("application_version", "1.0.2");
            tdSend(params);
        } else if ("authorizationStateWaitEncryptionKey".equals(type)) {
            JSONObject req = new JSONObject();
            req.put("@type", "checkDatabaseEncryptionKey");
            req.put("encryption_key", "");
            tdSend(req);
        } else if ("authorizationStateReady".equals(type)) {
            lastError.set("");
            sendAsync(function("getMe"));
        }
    }

    private JSONObject status() throws Exception {
        ensureAuthProgress();
        waitForAuthBootstrap();
        boolean ready = "authorizationStateReady".equals(authState.get());
        boolean hasCredentials = API_ID > 0 && !API_HASH.isEmpty();
        boolean needCredentials = STATE_NEED_CREDENTIALS.equals(authState.get());
        JSONObject out = ok();
        out.put("configured", hasCredentials);
        out.put("apiConfigured", hasCredentials);
        out.put("needCredentials", needCredentials);
        out.put("authorized", ready);
        out.put("connected", ready);
        out.put("authState", authState.get());
        out.put("lastError", lastError.get());
        JSONObject user = me.get();
        if (ready && user != null) {
            JSONObject u = new JSONObject();
            u.put("id", String.valueOf(user.optLong("id")));
            u.put("name", displayName(user));
            u.put("username", "");
            u.put("phone", user.optString("phone_number"));
            out.put("user", u);
        } else {
            out.put("user", JSONObject.NULL);
        }
        out.put("api_id", API_ID);
        out.put("apiHashMask", API_HASH.isEmpty() ? "" : API_HASH.substring(0, Math.min(4, API_HASH.length())) + "****");
        return out;
    }

    private JSONObject requestCode(JSONObject input) throws Exception {
        ensureAuthProgress();
        waitForAuthBootstrap();
        String phone = normalizePhone(input.optString("phone"));
        if (phone.isEmpty()) throw new IllegalArgumentException("手机号不能为空。");
        String state = authState.get();
        if ("authorizationStateReady".equals(state)) {
            return status();
        }
        if (!"authorizationStateWaitPhoneNumber".equals(state) && !"authorizationStateWaitCode".equals(state)) {
            if (STATE_NEED_CREDENTIALS.equals(state)) {
                throw new IllegalStateException("请在「设置」页面配置 API ID 和 API Hash（获取地址：my.telegram.org/apps）");
            }
            throw new IllegalStateException("Telegram 登录状态未就绪，请稍后再点获取验证码。当前状态：" + stateLabel(state) + "，错误：" + lastError.get());
        }
        if ("authorizationStateWaitCode".equals(state)) {
            JSONObject resend = send(function("resendAuthenticationCode"));
            if (isError(resend)) throw new IllegalStateException(translateTdError(resend.optString("message", "验证码重发失败")));
            JSONObject out = ok();
            out.put("phone", phone);
            out.put("codeViaApp", true);
            return out;
        }
        JSONObject req = function("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        JSONObject settings = new JSONObject();
        settings.put("@type", "phoneNumberAuthenticationSettings");
        settings.put("allow_flash_call", false);
        settings.put("allow_missed_call", false);
        settings.put("is_current_phone_number", false);
        settings.put("allow_sms_retriever_api", false);
        settings.put("authentication_tokens", new JSONArray());
        pendingPhone = phone;
        req.put("settings", settings);
        JSONObject result = send(req);
        if (isError(result)) throw new IllegalStateException(translateTdError(result.optString("message", "验证码发送失败")));
        waitForAnyState("authorizationStateWaitCode", "authorizationStateWaitPassword", "authorizationStateReady");
        if (!"authorizationStateWaitCode".equals(authState.get()) && !"authorizationStateReady".equals(authState.get())) {
            throw new IllegalStateException("验证码请求未进入等待验证码状态，请重新获取验证码。当前状态：" + stateLabel(authState.get()));
        }
        JSONObject out = ok();
        out.put("phone", phone);
        out.put("codeViaApp", true);
        return out;
    }

    private JSONObject signIn(JSONObject input) throws Exception {
        ensureAuthProgress();
        waitForAuthBootstrap();
        String code = input.optString("code").trim();
        String password = input.optString("password").trim();
        String state = authState.get();
        if ("authorizationStateWaitPhoneNumber".equals(state) || "unknown".equals(state)) {
            throw new IllegalStateException("请先点击获取验证码，并输入本次收到的验证码。");
        }
        if ("authorizationStateWaitPassword".equals(state)) {
            if (password.isEmpty()) throw new IllegalArgumentException("需要二步验证密码。");
            JSONObject req = function("checkAuthenticationPassword");
            req.put("password", password);
            JSONObject result = send(req);
            if (isError(result)) throw new IllegalStateException(translateTdError(result.optString("message", "二步验证失败")));
            waitForReady();
            return status();
        }
        if (!"authorizationStateWaitCode".equals(state)) {
            throw new IllegalStateException("当前不是验证码登录状态，请重新点击获取验证码。当前状态：" + stateLabel(state));
        }
        if (code.isEmpty()) throw new IllegalArgumentException("验证码不能为空。");
        JSONObject req = function("checkAuthenticationCode");
        req.put("code", code);
        JSONObject result = send(req);
        if (isError(result)) throw new IllegalStateException(translateTdError(result.optString("message", "验证码错误或登录失败")));
        if ("authorizationStateWaitPassword".equals(authState.get())) {
            JSONObject out = error("需要二步验证密码。", 401);
            out.put("needPassword", true);
            return out;
        }
        waitForReady();
        return status();
    }

    private JSONObject logout() throws Exception {
        sendAsync(function("logOut"));
        me.set(null);
        authState.set("authorizationStateLoggingOut");
        return ok();
    }

    private JSONObject syncChannels() throws Exception {
        if (!"authorizationStateReady".equals(authState.get())) throw new IllegalStateException("尚未登录 Telegram。");
        JSONObject req = function("getChats");
        JSONObject chatList = new JSONObject();
        chatList.put("@type", "chatListMain");
        req.put("chat_list", chatList);
        req.put("limit", 1000);
        JSONObject result = send(req);
        if (isError(result)) throw new IllegalStateException(result.optString("message", "频道同步失败"));
        JSONArray ids = result.optJSONArray("chat_ids");
        JSONArray rows = new JSONArray();
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                long chatId = ids.optLong(i);
                JSONObject chat = send(function("getChat").put("chat_id", chatId));
                if (isError(chat)) continue;
                String type = chat.optJSONObject("type") == null ? "" : chat.optJSONObject("type").optString("@type");
                if (!"chatTypeSupergroup".equals(type) && !"chatTypeBasicGroup".equals(type)) continue;
                String username = "";
                String displayType = "group";
                if ("chatTypeSupergroup".equals(type)) {
                    long supergroupId = chat.optJSONObject("type").optLong("supergroup_id", 0L);
                    JSONObject supergroup = send(function("getSupergroup").put("supergroup_id", supergroupId));
                    if (!isError(supergroup)) {
                        username = supergroup.optString("username", "");
                        displayType = supergroup.optBoolean("is_channel", false) ? "channel" : "supergroup";
                    } else {
                        displayType = "supergroup";
                    }
                }
                JSONObject row = new JSONObject();
                row.put("id", String.valueOf(chatId));
                row.put("accessHash", "");
                row.put("name", chat.optString("title", "未命名"));
                row.put("username", username);
                row.put("type", displayType);
                JSONObject permissions = chat.optJSONObject("permissions");
                boolean canPost = "channel".equals(displayType)
                        || permissions == null
                        || permissions.optBoolean("can_send_basic_messages", true);
                row.put("canPostMessages", canPost);
                row.put("status", "active");
                row.put("syncedAt", System.currentTimeMillis());
                rows.put(row);
            }
        }
        JSONObject out = ok();
        out.put("channels", rows);
        return out;
    }

    private JSONObject createChannel(JSONObject input) throws Exception {
        if (!"authorizationStateReady".equals(authState.get())) throw new IllegalStateException("尚未登录 Telegram。");
        String title = input.optString("title", "").trim();
        String username = input.optString("username", "").trim().replace("@", "");
        String description = input.optString("description", "").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("频道名称不能为空。");
        JSONObject req = function("createNewSupergroupChat");
        req.put("title", title);
        req.put("is_channel", true);
        req.put("description", description);
        req.put("location", JSONObject.NULL);
        req.put("for_import", false);
        JSONObject result = send(req);
        if (isError(result)) throw new IllegalStateException(result.optString("message", "新建频道失败"));
        long chatId = result.optLong("id", 0L);
        String usernameError = "";
        String appliedUsername = "";
        if (!username.isEmpty()) {
            long supergroupId = supergroupIdFromChat(result);
            if (supergroupId == 0L && chatId != 0L) {
                JSONObject chat = send(function("getChat").put("chat_id", chatId));
                supergroupId = supergroupIdFromChat(chat);
            }
            if (supergroupId == 0L) {
                usernameError = "频道已创建，但无法设置公开用户名。";
            } else {
                JSONObject setUsername = function("setSupergroupUsername");
                setUsername.put("supergroup_id", supergroupId);
                setUsername.put("username", username);
                JSONObject usernameResult = send(setUsername);
                if (isError(usernameResult)) {
                    usernameError = translateUsernameError(usernameResult.optString("message", "用户名不可用或已被占用"));
                } else {
                    appliedUsername = username;
                }
            }
        }
        JSONObject out = ok();
        out.put("channel", result);
        out.put("id", String.valueOf(chatId));
        out.put("name", result.optString("title", title));
        out.put("username", appliedUsername);
        out.put("usernameError", usernameError);
        return out;
    }

    private JSONObject collectOnce(JSONObject input) throws Exception {
        if (!"authorizationStateReady".equals(authState.get())) throw new IllegalStateException("尚未登录 Telegram。");
        JSONObject target = input.optJSONObject("target");
        JSONObject source = input.optJSONObject("source");
        JSONObject settings = input.optJSONObject("settings");
        JSONObject filters = input.optJSONObject("filters");
        JSONObject ads = input.optJSONObject("ads");
        JSONObject sourceRule = input.optJSONObject("sourceRule");
        if (settings == null) settings = new JSONObject();
        if (filters == null) filters = new JSONObject();
        if (ads == null) ads = new JSONObject();
        if (sourceRule == null) sourceRule = new JSONObject();
        long lastMessageId = input.optLong("lastMessageId", 0L);
        String lastPosition = input.optString("lastPosition", String.valueOf(lastMessageId));
        if (target == null || source == null) throw new IllegalArgumentException("目标频道或来源频道为空。");
        long targetId = parseChatId(target);
        long sourceId = parseChatId(source);
        if (targetId == 0L || sourceId == 0L) throw new IllegalArgumentException("目标频道或来源频道 ID 无效。");

        String sourceMode = sourceRule.optString("mode", "default");
        JSONObject next = nextMessage(sourceId, lastPosition, lastMessageId, settings, sourceMode);
        if (next == null) {
            JSONObject probe = probeSource(sourceId, settings);
            JSONObject out = ok();
            out.put("status", "no_new");
            out.put("sourceId", source.optString("id"));
            out.put("lastMessageId", lastMessageId);
            out.put("position", lastPosition);
            out.put("probeCount", probe.optInt("count", 0));
            out.put("probeAllowed", probe.optInt("allowed", 0));
            out.put("probeLatestId", probe.optLong("latestId", 0L));
            out.put("probeText", probe.optString("text", ""));
            return out;
        }

        long sourceMessageId = next.optLong("id", 0L);
        long originChatId = next.optLong("_origin_chat_id", sourceId);
        String nextPosition = next.optString("_position", String.valueOf(sourceMessageId));
        String text = messageText(next);
        String filterAction = shouldStripCaption(text, filters) ? opt(settings, "filterMode", "strip_text_keep_media") : "keep";
        if ("media_clean".equals(sourceMode)) filterAction = "strip_text_keep_media";
        if ("discard".equals(filterAction)) {
            JSONObject out = ok();
            out.put("status", "no_new");
            out.put("sourceId", source.optString("id"));
            out.put("lastMessageId", sourceMessageId);
            out.put("position", nextPosition);
            out.put("skipped", "filtered");
            return out;
        }

        boolean removeCaption = "media_clean".equals(sourceMode)
                || "delete".equals(opt(settings, "captionMode", "keep"))
                || "strip_text_keep_media".equals(filterAction);
        boolean adEnabled = settings.optBoolean("adEnabled", true);
        if ("media_clean".equals(sourceMode) || "no_ads_text".equals(sourceMode)) adEnabled = false;
        String finalText = removeCaption ? "" : adEnabled ? applyAd(text, ads) : text;
        JSONObject sent;
        String mode;
        String pendingResendText = null;
        boolean albumPartial = false;
        JSONArray albumIds = albumMessageIds(originChatId, next);
        long lastSentSourceId = maxId(albumIds, sourceMessageId);
        if (albumIds.length() > 1) {
            sent = copyMessages(targetId, originChatId, albumIds, removeCaption || !finalText.equals(text));
            mode = "native_album";
            if (!isError(sent)) {
                JSONArray sentMessages = sent.optJSONArray("messages");
                if (sentMessages == null || sentMessages.length() != albumIds.length()) {
                    // 相册部分成功：整组重发会重复已送达消息，改为推进游标到相册末尾，不再重试
                    albumPartial = true;
                }
            }
            if (!finalText.trim().isEmpty() && !finalText.equals(text)) {
                JSONObject textResult = sendText(targetId, finalText);
                if (isError(textResult)) pendingResendText = finalText;
            }
        } else if (isPlainText(next)) {
            sent = sendText(targetId, finalText);
            mode = "native_text";
        } else if (!finalText.equals(text)) {
            sent = copyMessage(targetId, originChatId, sourceMessageId, true);
            mode = "native_media_text";
            if (!finalText.trim().isEmpty()) {
                JSONObject textResult = sendText(targetId, finalText);
                if (isError(textResult)) pendingResendText = finalText;
            }
        } else {
            sent = copyMessage(targetId, originChatId, sourceMessageId, removeCaption);
            mode = "native_copy";
        }
        if (sent == null) throw new IllegalStateException("发送接口无返回消息，已按失败处理。");
        if (isError(sent)) throw new IllegalStateException(sent.optString("message", "发送目标频道失败"));
        long sentMessageId = sentMessageId(sent);
        if (sentMessageId <= 0L) {
            throw new IllegalStateException("发送接口未返回目标消息 ID，已按失败处理。返回：" + sentSummary(sent));
        }
        JSONObject verified = verifyTargetMessage(targetId, sentMessageId);
        if (isError(verified) && !confirmMessageDelivered(targetId, sentMessageId)) {
            throw new IllegalStateException("目标频道未确认收到消息：" + verified.optString("message", "校验失败"));
        }

        JSONObject out = ok();
        out.put("status", "sent");
        out.put("sourceId", source.optString("id"));
        out.put("sourceName", source.optString("name"));
        out.put("targetId", target.optString("id"));
        out.put("targetName", target.optString("name"));
        out.put("sourceMessageId", sourceMessageId);
        out.put("lastMessageId", "native_album".equals(mode) ? lastSentSourceId : sourceMessageId);
        out.put("position", nextPosition.startsWith("c:") ? nextPosition : String.valueOf("native_album".equals(mode) ? lastSentSourceId : sourceMessageId));
        out.put("sentMessageId", sentMessageId);
        out.put("sentChatId", String.valueOf(targetId));
        out.put("mode", mode);
        if (albumPartial) out.put("albumPartial", true);
        if (pendingResendText != null) {
            out.put("textDelivered", false);
            out.put("resendText", pendingResendText);
            out.put("resendTargetId", String.valueOf(targetId));
        }
        return out;
    }

    private JSONObject nextMessage(long sourceId, String lastPosition, long lastMessageId, JSONObject settings, String sourceMode) throws Exception {
        if ("comments".equals(sourceMode)) {
            return nextCommentMessage(sourceId, lastPosition, settings);
        }
        JSONObject main = nextChannelMessage(sourceId, lastMessageId, settings);
        if (main != null || !"main_comments".equals(sourceMode)) return main;
        return nextCommentMessage(sourceId, lastPosition, settings);
    }

    private JSONObject nextChannelMessage(long sourceId, long lastMessageId, JSONObject settings) throws Exception {
        if (lastMessageId > 0L) {
            JSONObject history = chatHistory(sourceId, lastMessageId, -99, 100);
            JSONArray messages = history.optJSONArray("messages");
            if (messages == null) return null;
            JSONObject candidate = null;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                long id = message.optLong("id", 0L);
                if (id > lastMessageId
                        && contentAllowed(message, settings)
                        && (candidate == null || id < candidate.optLong("id", Long.MAX_VALUE))) {
                    candidate = message;
                }
            }
            return candidate;
        }

        String mode = opt(settings, "firstCollectMode", "first");
        if ("latest".equals(mode)) {
            JSONObject history = chatHistory(sourceId, 0L, 0, 20);
            JSONArray messages = history.optJSONArray("messages");
            if (messages == null) return null;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message != null && contentAllowed(message, settings)) return message;
            }
            return null;
        }

        int pageLimit = "recent500".equals(mode) ? 500 : "recent100".equals(mode) ? 100 : 100;
        int maxPages = "first".equals(mode) ? 30 : 1;
        JSONObject oldestAllowed = null;
        long fromId = 0L;
        for (int page = 0; page < maxPages; page++) {
            JSONObject history = chatHistory(sourceId, fromId, 0, pageLimit);
            JSONArray messages = history.optJSONArray("messages");
            if (messages == null || messages.length() == 0) break;
            long oldestSeen = 0L;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                long id = message.optLong("id", 0L);
                if (oldestSeen == 0L || (id > 0L && id < oldestSeen)) oldestSeen = id;
                if (contentAllowed(message, settings)
                        && (oldestAllowed == null || id < oldestAllowed.optLong("id", Long.MAX_VALUE))) {
                    oldestAllowed = message;
                }
            }
            if (!"first".equals(mode) || oldestSeen <= 0L || oldestSeen == fromId) break;
            fromId = oldestSeen;
        }
        if (oldestAllowed != null) return oldestAllowed;

        JSONObject fallback = chatHistory(sourceId, 0L, 0, 50);
        JSONArray fallbackMessages = fallback.optJSONArray("messages");
        if (fallbackMessages == null) return null;
        JSONObject fallbackAllowed = null;
        for (int i = 0; i < fallbackMessages.length(); i++) {
            JSONObject message = fallbackMessages.optJSONObject(i);
            if (message == null || !contentAllowed(message, settings)) continue;
            long id = message.optLong("id", 0L);
            if (fallbackAllowed == null || id < fallbackAllowed.optLong("id", Long.MAX_VALUE)) {
                fallbackAllowed = message;
            }
        }
        return fallbackAllowed;
    }

    private JSONObject probeSource(long sourceId, JSONObject settings) throws Exception {
        JSONObject out = new JSONObject();
        JSONObject history = chatHistory(sourceId, 0L, 0, 20);
        JSONArray messages = history.optJSONArray("messages");
        int count = messages == null ? 0 : messages.length();
        int allowed = 0;
        long latestId = 0L;
        String text = "";
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                long id = message.optLong("id", 0L);
                if (id > latestId) latestId = id;
                if (contentAllowed(message, settings)) {
                    allowed++;
                    if (text.isEmpty()) text = message.optJSONObject("content") == null ? "" : message.optJSONObject("content").optString("@type", "");
                }
            }
        }
        out.put("count", count);
        out.put("allowed", allowed);
        out.put("latestId", latestId);
        out.put("text", text);
        return out;
    }

    private JSONObject nextCommentMessage(long sourceId, String lastPosition, JSONObject settings) throws Exception {
        CommentCursor cursor = parseCommentCursor(lastPosition);
        if (cursor.postId > 0L) {
            JSONObject samePost = nextThreadMessage(sourceId, cursor.postId, cursor.commentId, settings);
            if (samePost != null) return samePost;
        }
        long postCursor = cursor.postId;
        for (int i = 0; i < 25; i++) {
            JSONObject post = nextChannelMessage(sourceId, postCursor, null);
            if (post == null) return null;
            postCursor = post.optLong("id", postCursor);
            JSONObject comment = nextThreadMessage(sourceId, postCursor, 0L, settings);
            if (comment != null) return comment;
        }
        return null;
    }

    private JSONObject nextThreadMessage(long sourceId, long postId, long lastCommentId, JSONObject settings) throws Exception {
        JSONObject history = messageThreadHistory(sourceId, postId, lastCommentId, lastCommentId > 0L ? -99 : 0, 100);
        JSONArray messages = history.optJSONArray("messages");
        if (messages == null || messages.length() == 0) return null;
        JSONObject candidate = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            long id = message.optLong("id", 0L);
            if (id <= lastCommentId || !contentAllowed(message, settings)) continue;
            if (candidate == null || id < candidate.optLong("id", Long.MAX_VALUE)) {
                candidate = message;
            }
        }
        if (candidate == null) return null;
        long originChatId = candidate.optLong("chat_id", sourceId);
        long commentId = candidate.optLong("id", 0L);
        candidate.put("_origin_chat_id", originChatId);
        candidate.put("_position", "c:" + postId + ":" + commentId);
        return candidate;
    }

    private JSONObject chatHistory(long chatId, long fromMessageId, int offset, int limit) throws Exception {
        JSONObject history = function("getChatHistory");
        history.put("chat_id", chatId);
        history.put("from_message_id", fromMessageId);
        history.put("offset", offset);
        history.put("limit", limit);
        history.put("only_local", false);
        JSONObject result = send(history);
        if (isError(result)) throw new IllegalStateException(result.optString("message", "读取来源频道失败"));
        return result;
    }

    private JSONObject messageThreadHistory(long chatId, long messageId, long fromMessageId, int offset, int limit) throws Exception {
        JSONObject history = function("getMessageThreadHistory");
        history.put("chat_id", chatId);
        history.put("message_id", messageId);
        history.put("from_message_id", fromMessageId);
        history.put("offset", offset);
        history.put("limit", limit);
        JSONObject result = send(history);
        if (isError(result)) return function("messages");
        return result;
    }

    private JSONObject latestMessageId(JSONObject input) throws Exception {
        if (!"authorizationStateReady".equals(authState.get())) throw new IllegalStateException("尚未登录 Telegram。");
        JSONObject source = input.optJSONObject("source");
        if (source == null) throw new IllegalArgumentException("来源频道为空。");
        long sourceId = parseChatId(source);
        if (sourceId == 0L) throw new IllegalArgumentException("来源频道 ID 无效。");
        JSONObject history = function("getChatHistory");
        history.put("chat_id", sourceId);
        history.put("from_message_id", 0L);
        history.put("offset", 0);
        history.put("limit", 1);
        history.put("only_local", false);
        JSONObject result = send(history);
        if (isError(result)) throw new IllegalStateException(result.optString("message", "读取来源频道失败"));
        JSONArray messages = result.optJSONArray("messages");
        long id = 0L;
        if (messages != null && messages.length() > 0) {
            JSONObject message = messages.optJSONObject(0);
            if (message != null) id = message.optLong("id", 0L);
        }
        JSONObject out = ok();
        out.put("sourceId", source.optString("id"));
        out.put("lastMessageId", id);
        return out;
    }

    private JSONObject runtimeService(JSONObject input) {
        String action = input.optString("action", "start");
        String status = input.optString("status", "正在采集");
        if ("stop".equals(action)) {
            CollectorService.stop(context);
        } else if ("pause".equals(action)) {
            CollectorService.pause(context);
        } else {
            CollectorService.start(context, status, input.optJSONObject("config"));
        }
        return ok();
    }

    private JSONObject runtimeHealth() throws Exception {
        JSONObject out = ok();
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean ignoring = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        out.put("batteryOptimized", !ignoring);
        out.put("foregroundService", true);
        out.put("wakeLock", true);
        return out;
    }

    private JSONObject exportSourcePackage() throws Exception {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = context.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, "BCollector-source.zip");
        try (InputStream in = context.getAssets().open("source.zip");
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        JSONObject out = ok();
        out.put("path", outFile.getAbsolutePath());
        out.put("name", outFile.getName());
        return out;
    }

    private JSONObject sendText(long targetId, String text) throws Exception {
        JSONObject sendMessage = function("sendMessage");
        sendMessage.put("chat_id", targetId);
        JSONObject content = function("inputMessageText");
        JSONObject formatted = function("formattedText");
        formatted.put("text", text);
        formatted.put("entities", new JSONArray());
        content.put("text", formatted);
        content.put("clear_draft", false);
        sendMessage.put("input_message_content", content);
        return send(sendMessage);
    }

    private JSONObject copyMessage(long targetId, long sourceId, long messageId, boolean removeCaption) throws Exception {
        JSONObject forward = function("forwardMessages");
        forward.put("chat_id", targetId);
        forward.put("from_chat_id", sourceId);
        JSONArray ids = new JSONArray();
        ids.put(messageId);
        forward.put("message_ids", ids);
        JSONObject options = function("messageSendOptions");
        options.put("disable_notification", false);
        options.put("from_background", false);
        options.put("protect_content", false);
        forward.put("options", options);
        forward.put("send_copy", true);
        forward.put("remove_caption", removeCaption);
        JSONObject result = send(forward);
        if (isError(result)) return result;
        JSONArray messages = result.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            JSONObject first = messages.optJSONObject(0);
            return first == null ? result : first;
        }
        return result;
    }

    private JSONObject copyMessages(long targetId, long sourceId, JSONArray messageIds, boolean removeCaption) throws Exception {
        JSONObject forward = function("forwardMessages");
        forward.put("chat_id", targetId);
        forward.put("from_chat_id", sourceId);
        forward.put("message_ids", messageIds);
        JSONObject options = function("messageSendOptions");
        options.put("disable_notification", false);
        options.put("from_background", false);
        options.put("protect_content", false);
        forward.put("options", options);
        forward.put("send_copy", true);
        forward.put("remove_caption", removeCaption);
        JSONObject result = send(forward);
        if (isError(result)) return result;
        JSONArray messages = result.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            JSONObject first = messages.optJSONObject(0);
            return first == null ? result : first;
        }
        return result;
    }

    private long sentMessageId(JSONObject sent) {
        if (sent == null) return 0L;
        long id = sent.optLong("id", 0L);
        if (id > 0L) return id;
        JSONArray messages = sent.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                id = message.optLong("id", 0L);
                if (id > 0L) return id;
            }
        }
        return 0L;
    }

    private String sentSummary(JSONObject sent) {
        if (sent == null) return "null";
        JSONArray messages = sent.optJSONArray("messages");
        String type = sent.optString("@type", "unknown");
        if (messages == null) return type;
        String firstType = "";
        JSONObject first = messages.length() == 0 ? null : messages.optJSONObject(0);
        if (first != null) firstType = first.optString("@type", "");
        return type + " messages=" + messages.length() + (firstType.isEmpty() ? "" : " first=" + firstType);
    }

    private JSONObject verifyTargetMessage(long targetId, long messageId) throws Exception {
        JSONObject last = null;
        for (int i = 0; i < 2; i++) {
            JSONObject req = function("getMessage");
            req.put("chat_id", targetId);
            req.put("message_id", messageId);
            last = send(req);
            if (!isError(last) && last.optLong("id", 0L) == messageId) return last;
            Thread.sleep(250);
        }
        return last == null ? error("目标消息校验无响应", 504) : last;
    }

    private boolean confirmMessageDelivered(long targetId, long messageId) throws Exception {
        JSONObject history = chatHistory(targetId, messageId, -99, 100);
        JSONArray messages = history.optJSONArray("messages");
        if (messages == null) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && message.optLong("id", 0L) == messageId) return true;
        }
        return false;
    }

    private JSONArray albumMessageIds(long chatId, JSONObject message) throws Exception {
        JSONArray ids = new JSONArray();
        long albumId = message.optLong("media_album_id", 0L);
        long messageId = message.optLong("id", 0L);
        if (albumId == 0L || messageId == 0L) {
            ids.put(messageId);
            return ids;
        }
        JSONObject history = chatHistory(chatId, messageId, -20, 40);
        JSONArray messages = history.optJSONArray("messages");
        ArrayList<Long> sorted = new ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                long id = item.optLong("id", 0L);
                if (id > 0L && item.optLong("media_album_id", 0L) == albumId) {
                    sorted.add(id);
                }
            }
        }
        if (sorted.isEmpty()) sorted.add(messageId);
        Collections.sort(sorted);
        for (Long id : sorted) ids.put(id.longValue());
        return ids;
    }

    private long maxId(JSONArray ids, long fallback) {
        long max = fallback;
        for (int i = 0; i < ids.length(); i++) {
            long id = ids.optLong(i, 0L);
            if (id > max) max = id;
        }
        return max;
    }

    private synchronized void restartTdlib() {
        // 销毁旧客户端
        if (client != null) {
            try {
                TdJson.tdJsonClientDestroy(client);
            } catch (Exception ignored) {}
            client = null;
        }
        // 清空待处理请求
        for (Pending p : pending.values()) {
            if (p.latch.getCount() > 0) p.latch.countDown();
        }
        pending.clear();
        // 重置状态
        authState.set("unknown");
        lastError.set("");
        extra.set(1000);
        // 创建新客户端
        client = TdJson.tdJsonClientCreate();
        android.util.Log.i("TDLib", "TDLib 客户端已重启");
    }

    private void ensureAuthProgress() {
        if ("unknown".equals(authState.get())) {
            sendAsync(function("getAuthorizationState"));
        }
    }

    private void waitForReady() throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end) {
            String state = authState.get();
            if ("authorizationStateReady".equals(state) || "authorizationStateWaitPassword".equals(state)) return;
            Thread.sleep(120);
        }
    }

    private void waitForAuthBootstrap() throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end) {
            String state = authState.get();
            if (!"unknown".equals(state)
                    && !"authorizationStateWaitTdlibParameters".equals(state)
                    && !"authorizationStateWaitEncryptionKey".equals(state)) {
                return;
            }
            Thread.sleep(120);
        }
    }

    private void waitForAnyState(String... states) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end) {
            String current = authState.get();
            for (String state : states) {
                if (state.equals(current)) return;
            }
            Thread.sleep(120);
        }
    }

    private JSONObject send(JSONObject request) throws Exception {
        long id = extra.incrementAndGet();
        request.put("@extra", id);
        Pending item = new Pending();
        pending.put(id, item);
        tdSend(request);
        if (!item.latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
            pending.remove(id);
            throw new IllegalStateException("Telegram 请求超时，发送状态未知。");
        }
        JSONObject result = item.result.get();
        return result == null ? error("Telegram 无响应。", 504) : result;
    }

    private JSONObject updateCredentials(JSONObject input) throws Exception {
        int apiId = input.optInt("api_id", 0);
        String apiHash = input.optString("api_hash", "").trim();
        if (apiId <= 0 && apiHash.isEmpty()) {
            return error("API ID 和 API Hash 不能为空。", 400);
        }
        // apiHash 留空表示保持已保存的值（前端不回显明文）
        int newApiId = apiId > 0 ? apiId : API_ID;
        String newApiHash = apiHash.isEmpty() ? API_HASH : apiHash;
        if (newApiId <= 0 || newApiHash.isEmpty()) {
            return error("API ID 和 API Hash 不能为空。", 400);
        }
        if (newApiHash.length() >= 40) {
            return error("API Hash 格式异常，疑似 Bot Token，请从 my.telegram.org/apps 获取。", 400);
        }
        // 保存到 SharedPreferences 供下次启动使用
        context.getSharedPreferences("bcollector_prefs", Context.MODE_PRIVATE)
            .edit().putInt("api_id", newApiId).putString("api_hash", newApiHash).apply();
        API_ID = newApiId;
        API_HASH = newApiHash;
        // TDLib 初始化后不允许重复调用 setTdlibParameters，统一重启客户端以应用新参数
        restartTdlib();
        JSONObject out = ok();
        out.put("api_id", newApiId);
        return out;
    }

    private JSONObject restart() throws Exception {
        restartTdlib();
        // 等待状态更新
        Thread.sleep(1000);
        JSONObject out = ok();
        out.put("authState", authState.get());
        return out;
    }

    private void sendAsync(JSONObject request) {
        tdSend(request);
    }

    private void tdSend(JSONObject request) {
        TdJson.tdJsonClientSend(client, request.toString());
    }

    private JSONObject function(String type) {
        JSONObject object = new JSONObject();
        try {
            object.put("@type", type);
        } catch (JSONException ignored) {
        }
        return object;
    }

    private JSONObject ok() {
        JSONObject object = new JSONObject();
        try {
            object.put("ok", true);
        } catch (JSONException ignored) {
        }
        return object;
    }

    private JSONObject error(String message, int status) {
        JSONObject object = new JSONObject();
        try {
            object.put("ok", false);
            object.put("status", status);
            object.put("error", message);
        } catch (JSONException ignored) {
        }
        return object;
    }

    private boolean isError(JSONObject object) {
        return object != null && "error".equals(object.optString("@type"));
    }

    private String normalizePhone(String value) {
        String phone = value == null ? "" : value.trim().replace(" ", "");
        if (phone.isEmpty()) return "";
        // 如果用户输入了本地格式（以0开头），去掉0并加+
        // 但无法自动判断国家区号，需要用户自己输入完整国际格式
        // 所以这里只做空格清理和 + 号补充
        if (!phone.startsWith("+")) {
            // 以0开头的号码去掉开头的0
            if (phone.startsWith("0")) {
                phone = phone.substring(1);
            }
            phone = "+" + phone;
        }
        return phone;
    }

    private boolean looksLikeBotToken() {
        // Bot Token 格式: 数字+冒号+字母数字, 如 "8945866696:AAFPte10XQkmb..."
        return String.valueOf(API_ID).contains(":") || 
               (API_HASH != null && API_HASH.length() >= 40) ||
               (API_HASH != null && API_HASH.contains(":"));
    }

    private String translateTdError(String message) {
        if (message == null) return "Telegram 请求失败。";
        if (message.contains("checkAuthenticationCode unexpected")) {
            return "请先点击获取验证码，并使用本次收到的验证码登录。";
        }
        if (message.contains("PHONE_CODE_INVALID")) return "验证码错误。";
        if (message.contains("PHONE_CODE_EXPIRED")) return "验证码已过期，请重新获取。";
        if (message.contains("PHONE_NUMBER_INVALID")) {
            // 检查是否因为 API 凭证错误导致的误报
            if (looksLikeBotToken()) {
                return "API 凭证错误！你填的是 Bot Token 格式，请从 my.telegram.org/apps 获取正确的 API ID 和 API Hash";
            }
            return "手机号格式不正确，请包含国家区号（如 +66812345678）。";
        }
        if (message.contains("PASSWORD_HASH_INVALID")) return "二步验证密码错误。";
        return message;
    }

    private String translateUsernameError(String message) {
        if (message == null) return "用户名设置失败。";
        if (message.contains("USERNAME_OCCUPIED") || message.contains("USERNAME_INVALID")) {
            return "公开用户名不可用，可能已被占用或格式不正确。";
        }
        if (message.contains("PUBLIC_CHANNELS_TOO_MUCH")) {
            return "当前账号创建的公开频道数量已达上限。";
        }
        return message;
    }

    private String stateLabel(String state) {
        if ("authorizationStateWaitPhoneNumber".equals(state)) return "等待手机号";
        if ("authorizationStateWaitCode".equals(state)) return "等待验证码";
        if ("authorizationStateWaitPassword".equals(state)) return "等待二步验证密码";
        if ("authorizationStateReady".equals(state)) return "已登录";
        if ("authorizationStateLoggingOut".equals(state)) return "退出中";
        return state == null ? "未知" : state;
    }

    private long parseChatId(JSONObject channel) {
        String value = channel.optString("chatId", "");
        if (value.isEmpty()) value = channel.optString("channelId", "");
        if (value.isEmpty()) value = channel.optString("id", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            long chatId = channel.optLong("chatId", 0L);
            if (chatId != 0L) return chatId;
            long channelId = channel.optLong("channelId", 0L);
            if (channelId != 0L) return channelId;
            return channel.optLong("id", 0L);
        }
    }

    private long supergroupIdFromChat(JSONObject chat) {
        if (chat == null) return 0L;
        JSONObject type = chat.optJSONObject("type");
        if (type == null || !"chatTypeSupergroup".equals(type.optString("@type"))) return 0L;
        return type.optLong("supergroup_id", 0L);
    }

    private String messageText(JSONObject message) {
        JSONObject content = message.optJSONObject("content");
        if (content == null) return message.optString("message", "");
        String type = content.optString("@type");
        if ("messageText".equals(type)) {
            JSONObject text = content.optJSONObject("text");
            return text == null ? "" : text.optString("text", "");
        }
        if ("messagePhoto".equals(type) || "messageVideo".equals(type) || "messageAnimation".equals(type)) {
            JSONObject caption = content.optJSONObject("caption");
            return caption == null ? "" : caption.optString("text", "");
        }
        return "";
    }

    private boolean isPlainText(JSONObject message) {
        JSONObject content = message.optJSONObject("content");
        return content != null && "messageText".equals(content.optString("@type"));
    }

    private boolean contentAllowed(JSONObject message, JSONObject settings) {
        String mode = opt(settings, "contentMode", "all");
        if ("all".equals(mode)) return true;
        boolean text = isPlainText(message);
        if ("text_only".equals(mode)) return text;
        if ("media_only".equals(mode)) return !text;
        return true;
    }

    private boolean matchedKeyword(String text, JSONObject filters) {
        if (text == null || text.isEmpty() || filters == null) return false;
        JSONArray keywords = filters.optJSONArray("keywords");
        if (keywords == null) return false;
        String lower = text.toLowerCase();
        for (int i = 0; i < keywords.length(); i++) {
            String keyword = keywords.optString(i, "").trim();
            if (!keyword.isEmpty() && lower.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }

    private String applyAd(String text, JSONObject ads) {
        if (ads == null) return text == null ? "" : text;
        JSONArray pool = ads.optJSONArray("pool");
        if (pool == null || pool.length() == 0) return text == null ? "" : text;
        int index = (int)(System.currentTimeMillis() % pool.length());
        JSONObject ad = pool.optJSONObject(index);
        if (ad == null) return text == null ? "" : text;
        String adText = renderAd(ad);
        if (adText.trim().isEmpty()) return text == null ? "" : text;
        String base = text == null ? "" : text.trim();
        String position = ads.optString("insertPosition", "after");
        if ("before".equals(position)) return adText + (base.isEmpty() ? "" : "\n\n" + base);
        return base + (base.isEmpty() ? "" : "\n\n") + adText;
    }

    private String renderAd(JSONObject ad) {
        String type = ad.optString("type", "text");
        String name = ad.optString("name", "");
        String content = ad.optString("content", "");
        String url = ad.optString("url", "");
        String anchor = ad.optString("anchorText", "");
        if ("anchor".equals(type)) {
            String label = anchor.isEmpty() ? (name.isEmpty() ? "广告" : name) : anchor;
            return url.isEmpty() ? label : label + "\n" + url;
        }
        if ("link".equals(type)) {
            String label = content.isEmpty() ? name : content;
            return url.isEmpty() ? label : label + "\n" + url;
        }
        return content.isEmpty() ? name : content;
    }

    private CommentCursor parseCommentCursor(String value) {
        if (value == null || !value.startsWith("c:")) return new CommentCursor(0L, 0L);
        String[] parts = value.split(":");
        if (parts.length < 3) return new CommentCursor(0L, 0L);
        try {
            return new CommentCursor(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException ignored) {
            return new CommentCursor(0L, 0L);
        }
    }

    private boolean hasUrlOrMention(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        if (lower.contains("http://") || lower.contains("https://")) return true;
        if (lower.contains("t.me") || lower.contains("telegram.me")) return true;
        if (java.util.regex.Pattern.compile("@\\w+").matcher(text).find()) return true;
        if (java.util.regex.Pattern.compile("\\[.+?\\]\\(.+?\\)").matcher(text).find()) return true;
        return false;
    }

    private boolean shouldStripCaption(String text, JSONObject filters) {
        if (text == null || text.isEmpty()) return false;
        if (hasUrlOrMention(text)) return true;
        return matchedKeyword(text, filters);
    }

    private String opt(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private JSONObject resendText(JSONObject input) throws Exception {
        if (!"authorizationStateReady".equals(authState.get())) throw new IllegalStateException("尚未登录 Telegram。");
        long targetId = parseLong(input.optString("targetId", input.optString("chatId", "0")));
        String text = input.optString("text", "");
        if (targetId <= 0L) throw new IllegalArgumentException("目标频道 ID 无效。");
        if (text.trim().isEmpty()) throw new IllegalArgumentException("补发文本为空。");
        JSONObject result = sendText(targetId, text);
        if (isError(result)) throw new IllegalStateException(result.optString("message", "补发文本失败"));
        return ok();
    }

    private long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String displayName(JSONObject user) {
        String first = user.optString("first_name", "");
        String last = user.optString("last_name", "");
        String value = (first + " " + last).trim();
        return value.isEmpty() ? "Telegram 用户" : value;
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONObject> result = new AtomicReference<>();
    }

    private static final class CommentCursor {
        final long postId;
        final long commentId;

        CommentCursor(long postId, long commentId) {
            this.postId = postId;
            this.commentId = commentId;
        }
    }
}
