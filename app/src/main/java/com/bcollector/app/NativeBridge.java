package com.bcollector.app;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public final class NativeBridge {
    private final Activity activity;
    private final WebView webView;
    private volatile TelegramCore telegram;
    private CancellationSignal biometricCancel;

    NativeBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.telegram = null;
    }

    void setTelegramCore(TelegramCore core) {
        this.telegram = core;
    }

    @JavascriptInterface
    public String request(String path, String body) {
        if (telegram == null) {
            return "{\"ok\":false,\"error\":\"TDLib 初始化中，请稍后再试\"}";
        }
        return telegram.handle(path, body == null ? "{}" : body);
    }

    @JavascriptInterface
    public String biometricAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? "true" : "false";
    }

    @JavascriptInterface
    public void authenticateBiometric() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            emitBiometric(false, "当前系统不支持指纹解锁。");
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                biometricCancel = new CancellationSignal();
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("B Collector")
                        .setSubtitle("使用指纹解锁")
                        .setDescription("验证通过后进入 APP")
                        .setNegativeButton("使用启动密码", activity.getMainExecutor(), (dialog, which) -> emitBiometric(false, "已取消指纹验证。"))
                        .build();
                prompt.authenticate(biometricCancel, activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        emitBiometric(true, "");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        emitBiometric(false, errString == null ? "指纹验证失败。" : errString.toString());
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        emitBiometric(false, "指纹不匹配。");
                    }
                });
            } catch (Exception error) {
                emitBiometric(false, error.getMessage() == null ? "无法启动指纹验证。" : error.getMessage());
            }
        });
    }

    private void emitBiometric(boolean ok, String message) {
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("'", "\\'");
        activity.runOnUiThread(() -> webView.evaluateJavascript("window.onNativeBiometricResult && window.onNativeBiometricResult(" + ok + ", '" + safe + "')", null));
    }
}
