package com.bcollector.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class CollectorService extends Service {
    static final String ACTION_START = "com.bcollector.app.START";
    static final String ACTION_PAUSE = "com.bcollector.app.PAUSE";
    static final String ACTION_STOP = "com.bcollector.app.STOP";
    private static final String CHANNEL_ID = "collector_runtime";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "collector_service";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_FAILURE_QUEUE = "failure_queue";
    private static final String KEY_TARGET_INDEX = "target_index";
    private static final String KEY_SOURCE_INDEX = "source_index";
    private static final String KEY_STATUS = "status";
    private static final String KEY_COLLECTED = "collected";
    private static final String KEY_SENT = "sent";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_EVENT_ID = "event_id";
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private JSONObject config;
    private JSONArray failureQueue;
    private int currentTargetIndex = 0;
    private int currentSourceIndex = 0;
    private long eventId = 0L;
    private long collected = 0L;
    private long sent = 0L;
    private long failed = 0L;

    static JSONObject snapshot(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject out = new JSONObject();
        JSONObject status = new JSONObject(prefs.getString(KEY_STATUS, "{}"));
        out.put("ok", true);
        out.put("status", status);
        out.put("collected", prefs.getLong(KEY_COLLECTED, 0L));
        out.put("sent", prefs.getLong(KEY_SENT, 0L));
        out.put("failed", prefs.getLong(KEY_FAILED, 0L));
        out.put("targetIndex", prefs.getInt(KEY_TARGET_INDEX, 0));
        out.put("sourceIndex", prefs.getInt(KEY_SOURCE_INDEX, 0));
        out.put("failureQueueLength", new JSONArray(prefs.getString(KEY_FAILURE_QUEUE, "[]")).length());
        String rawConfig = prefs.getString(KEY_CONFIG, "{}");
        JSONObject config = new JSONObject(rawConfig == null || rawConfig.trim().isEmpty() ? "{}" : rawConfig);
        out.put("positions", config.optJSONObject("positions") == null ? new JSONObject() : config.optJSONObject("positions"));
        return out;
    }

    static void start(Context context, String status, JSONObject config) {
        Intent intent = new Intent(context, CollectorService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("status", status);
        if (config != null) intent.putExtra("config", config.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void pause(Context context) {
        Intent intent = new Intent(context, CollectorService.class);
        intent.setAction(ACTION_PAUSE);
        context.startService(intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, CollectorService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLoop();
            releaseWakeLock();
            writeStatus("info", "采集停止", "后台采集服务已停止。", null, null, null);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            stopLoop();
            releaseWakeLock();
            String status = "Ⅱ";
            startForeground(NOTIFICATION_ID, notification(status));
            writeStatus("info", "采集暂停", "后台采集服务已暂停。", null, null, null);
            return START_STICKY;
        }
        acquireWakeLock();
        String status = ACTION_PAUSE.equals(action) ? "Ⅱ" : intent == null ? "●" : intent.getStringExtra("status");
        if (status == null || status.trim().isEmpty()) status = "●";
        if (ACTION_START.equals(action)) {
            loadConfig(intent == null ? null : intent.getStringExtra("config"));
            startLoop();
            writeStatus("info", "采集启动", "后台采集服务已接收最新队列配置。", null, null, null);
        }
        startForeground(NOTIFICATION_ID, notification(status));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopLoop();
        if (executor != null) executor.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "B Collector 运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示采集器后台运行状态");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("B Collector")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_status_b)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BCollector:CollectorWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void loadConfig(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) {
                raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CONFIG, "");
            }
            if (raw != null && !raw.trim().isEmpty()) {
                config = new JSONObject(raw);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CONFIG, config.toString()).apply();
            }
            String queue = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FAILURE_QUEUE, "[]");
            failureQueue = new JSONArray(queue == null ? "[]" : queue);
            sanitizeFailureQueue();
            currentTargetIndex = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_TARGET_INDEX, 0);
            currentSourceIndex = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SOURCE_INDEX, 0);
            eventId = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_EVENT_ID, 0L);
            collected = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_COLLECTED, 0L);
            sent = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_SENT, 0L);
            failed = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_FAILED, 0L);
        } catch (Exception ignored) {
            config = null;
            failureQueue = new JSONArray();
        }
    }

    private void startLoop() {
        stopLoop();
        if (config == null || executor == null) {
            writeStatus("error", "服务未启动", "运行队列配置为空。", null, null, null);
            return;
        }
        scheduleNext(0);
    }

    private void scheduleNext(long delaySeconds) {
        stopLoop();
        if (config == null || executor == null || executor.isShutdown()) return;
        task = executor.schedule(this::collectSafely, delaySeconds, TimeUnit.SECONDS);
    }

    private long calcNextDelay() {
        if (config == null) return 3;
        JSONObject settings = config.optJSONObject("settings");
        if (settings == null) return 3;
        String mode = settings.optString("sendMode", "fixed");
        if ("random".equals(mode)) {
            long min = Math.max(1, settings.optLong("sendRandomMin", 30));
            long max = Math.max(min + 1, settings.optLong("sendRandomMax", 300));
            return min + (long)(Math.random() * (max - min));
        }
        return Math.max(1, settings.optLong("sendFixedSeconds", settings.optLong("collectInterval", 3)));
    }

    private void stopLoop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void collectSafely() {
        try {
            if (!isNetworkAvailable()) {
                writeStatus("info", "网络等待", "当前无可用网络，后台采集暂停等待。", null, null, null);
                scheduleNext(5);
                return;
            }
            if (retryFailureQueue()) {
                scheduleNext(calcNextDelay());
                return;
            }
            boolean sent = collectOnceFromConfig();
            if (sent) {
                saveRuntimeCursor();
            }
            scheduleNext(calcNextDelay());
        } catch (Exception error) {
            failed++;
            saveCounters();
            writeStatus("error", "后台异常", error.getMessage() == null ? "采集线程异常。" : error.getMessage(), null, null, null);
            scheduleNext(5);
        }
    }

    private boolean retryFailureQueue() throws Exception {
        if (failureQueue == null || failureQueue.length() == 0) return false;
        JSONObject body = failureQueue.optJSONObject(0);
        if (body == null) {
            removeFailureAt(0);
            return false;
        }
        String resendText = body.optString("_resendText", "");
        if (!resendText.isEmpty()) {
            JSONObject resendBody = new JSONObject();
            resendBody.put("targetId", body.optString("_resendTargetId", ""));
            resendBody.put("text", resendText);
            JSONObject resendResult = new JSONObject(TelegramCore.get(this).handle("/api/telegram/resend-text", resendBody.toString()));
            if (resendResult.optBoolean("ok", false)) {
                removeFailureAt(0);
                writeStatus("info", "文本补发成功", "已补发缺失文本到目标频道。", null, null, null);
            } else {
                int retries = body.optInt("_retries", 0) + 1;
                body.put("_retries", retries);
                removeFailureAt(0);
                if (retries < 20) {
                    failureQueue.put(body);
                    saveFailureQueue();
                }
                writeStatus("error", "文本补发失败", resendResult.optString("error", "补发失败"), null, null, null);
            }
            return true;
        }
        sanitizeRetryBody(body);
        JSONObject result = new JSONObject(TelegramCore.get(this).handle("/api/telegram/collect-once", body.toString()));
        if (result.optBoolean("ok", false)) {
            removeFailureAt(0);
            updatePositionFromResult(body, result);
            if (!"no_new".equals(result.optString("status"))) {
                collected++;
                sent++;
                saveCounters();
            }
            writeStatusFromResult("重试成功", body, result);
        } else {
            if (!shouldRetry(result)) {
                removeFailureAt(0);
            } else {
                // 避免队头阻塞：轮转到队尾，超过 20 次放弃
                int retries = body.optInt("_retries", 0) + 1;
                body.put("_retries", retries);
                removeFailureAt(0);
                if (retries < 20) {
                    failureQueue.put(body);
                    saveFailureQueue();
                }
            }
            writeStatusFromResult("重试失败", body, result);
        }
        return true;
    }

    private boolean collectOnceFromConfig() throws Exception {
        if (config == null) return false;
        JSONArray targets = config.optJSONArray("targets");
        JSONArray channels = config.optJSONArray("channels");
        JSONObject bindings = config.optJSONObject("bindings");
        JSONObject sourceRules = config.optJSONObject("sourceRules");
        JSONObject positions = config.optJSONObject("positions");
        if (targets == null || channels == null || bindings == null) {
            writeStatus("error", "配置不完整", "目标、频道池或绑定关系为空。", null, null, null);
            return false;
        }
        if (positions == null) {
            positions = new JSONObject();
            config.put("positions", positions);
        }
        int targetCount = targets.length();
        if (targetCount == 0) {
            writeStatus("error", "队列为空", "运行队列没有目标频道。", null, null, null);
            return false;
        }
        for (int attempts = 0; attempts < targetCount; attempts++) {
            if (currentTargetIndex >= targetCount) currentTargetIndex = 0;
            JSONObject target = targets.optJSONObject(currentTargetIndex);
            if (target == null || target.optBoolean("enabled", true) == false) {
                currentTargetIndex++;
                currentSourceIndex = 0;
                continue;
            }
            JSONArray sourceIds = bindings.optJSONArray(target.optString("id"));
            if (sourceIds == null || sourceIds.length() == 0) {
                writeStatus("info", "队列跳过", target.optString("name") + " 没有绑定来源频道。", target, null, null);
                currentTargetIndex++;
                currentSourceIndex = 0;
                continue;
            }
            boolean allNoNew = true;
            int sourceCount = sourceIds.length();
            if (currentSourceIndex >= sourceCount) currentSourceIndex = 0;
            for (int checked = 0; checked < sourceCount; checked++) {
                String sourceId = sourceIds.optString(currentSourceIndex);
                JSONObject source = findChannel(channels, sourceId);
                currentSourceIndex = (currentSourceIndex + 1) % sourceCount;
                if (source == null) {
                    writeStatus("error", "来源失效", "绑定来源不在频道池：" + sourceId, target, null, null);
                    continue;
                }
                String key = target.optString("id") + ":" + source.optString("id");
                String lastPosition = positions.optString(key, "0");
                long lastMessageId = parseLongPosition(lastPosition);
                JSONObject body = new JSONObject();
                body.put("target", target);
                body.put("source", source);
                body.put("lastMessageId", lastMessageId);
                body.put("lastPosition", lastPosition);
                body.put("settings", config.optJSONObject("settings"));
                body.put("filters", config.optJSONObject("filters"));
                body.put("ads", config.optJSONObject("ads"));
                JSONObject sourceRule = sourceRules == null ? null : sourceRules.optJSONObject(source.optString("id"));
                body.put("sourceRule", sourceRule == null ? new JSONObject() : sourceRule);
                JSONObject result = new JSONObject(TelegramCore.get(this).handle("/api/telegram/collect-once", body.toString()));
                if (!result.optBoolean("ok", false)) {
                    if (shouldRetry(result)) enqueueFailure(body);
                    failed++;
                    saveCounters();
                    writeStatusFromResult("发送失败", body, result);
                    saveRuntimeCursor();
                    return false;
                }
                updatePositionFromResult(body, result);
                if (!"no_new".equals(result.optString("status"))) {
                    allNoNew = false;
                    collected++;
                    sent++;
                    saveCounters();
                    String resendText = result.optString("resendText", "");
                    if (!resendText.isEmpty()) {
                        JSONObject resendBody = new JSONObject();
                        resendBody.put("_resendText", resendText);
                        resendBody.put("_resendTargetId", result.optString("resendTargetId", target.optString("id")));
                        resendBody.put("_retries", 0);
                        enqueueFailure(resendBody);
                        writeStatusFromResult("文本待补发", body, result);
                    } else {
                        writeStatusFromResult("发送成功", body, result);
                    }
                    saveRuntimeCursor();
                    return true;
                }
                writeStatusFromResult(result.has("skipped") ? "过滤跳过" : "暂无内容", body, result);
            }
            if (allNoNew) {
                currentTargetIndex = (currentTargetIndex + 1) % targetCount;
                currentSourceIndex = 0;
                writeStatus("info", "切换目标", target.optString("name") + " 全部来源暂无可采内容。", target, null, null);
                saveRuntimeCursor();
            }
            return false;
        }
        return false;
    }

    private void updatePositionFromResult(JSONObject body, JSONObject result) throws Exception {
        if (config == null) return;
        JSONObject positions = config.optJSONObject("positions");
        if (positions == null) {
            positions = new JSONObject();
            config.put("positions", positions);
        }
        JSONObject target = body.optJSONObject("target");
        JSONObject source = body.optJSONObject("source");
        if (target == null || source == null) return;
        String lastPosition = body.optString("lastPosition", String.valueOf(body.optLong("lastMessageId", 0L)));
        long last = parseLongPosition(lastPosition);
        long nextId = result.optLong("lastMessageId", last);
        String nextPosition = result.optString("position", nextId > 0L ? String.valueOf(nextId) : lastPosition);
        if (!nextPosition.equals(lastPosition) || nextId > last) {
            String key = target.optString("id") + ":" + source.optString("id");
            positions.put(key, nextPosition);
            saveConfig();
        }
    }

    private void enqueueFailure(JSONObject body) {
        sanitizeRetryBody(body);
        if (failureQueue == null) failureQueue = new JSONArray();
        if (failureQueue.length() >= 200) removeFailureAt(0);
        failureQueue.put(body);
        saveFailureQueue();
    }

    private boolean shouldRetry(JSONObject result) {
        if (result == null) return true;
        String error = result.optString("error", "");
        if (error.contains("发送接口未返回目标消息 ID")) return false;
        if (error.contains("发送接口无返回消息")) return false;
        if (error.contains("目标频道未确认收到消息")) return false;
        // 超时意味着发送状态未知：消息可能已送达，重发会造成重复，按不重试处理（游标不推进，由人工确认）
        if (error.contains("Telegram 请求超时")) return false;
        return true;
    }

    private void sanitizeRetryBody(JSONObject body) {
        if (body == null) return;
        try {
            if (body.optJSONObject("sourceRule") == null) body.put("sourceRule", new JSONObject());
            if (body.optJSONObject("settings") == null && config != null) {
                JSONObject settings = config.optJSONObject("settings");
                body.put("settings", settings == null ? new JSONObject() : settings);
            }
            if (body.optJSONObject("filters") == null && config != null) {
                JSONObject filters = config.optJSONObject("filters");
                body.put("filters", filters == null ? new JSONObject() : filters);
            }
            if (body.optJSONObject("ads") == null && config != null) {
                JSONObject ads = config.optJSONObject("ads");
                body.put("ads", ads == null ? new JSONObject() : ads);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeFailureAt(int index) {
        if (failureQueue == null) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < failureQueue.length(); i++) {
            if (i != index) next.put(failureQueue.opt(i));
        }
        failureQueue = next;
        saveFailureQueue();
    }

    private void saveFailureQueue() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FAILURE_QUEUE, failureQueue == null ? "[]" : failureQueue.toString()).apply();
    }

    private void sanitizeFailureQueue() {
        if (failureQueue == null) return;
        for (int i = 0; i < failureQueue.length(); i++) {
            sanitizeRetryBody(failureQueue.optJSONObject(i));
        }
        saveFailureQueue();
    }

    private void saveRuntimeCursor() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_TARGET_INDEX, currentTargetIndex)
                .putInt(KEY_SOURCE_INDEX, currentSourceIndex)
                .apply();
    }

    private void saveCounters() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putLong(KEY_COLLECTED, collected)
                .putLong(KEY_SENT, sent)
                .putLong(KEY_FAILED, failed)
                .apply();
    }

    private void writeStatusFromResult(String title, JSONObject body, JSONObject result) {
        JSONObject target = body == null ? null : body.optJSONObject("target");
        JSONObject source = body == null ? null : body.optJSONObject("source");
        String detail = result == null
                ? title
                : result.optString("error", result.optString("sourceName", source == null ? "" : source.optString("name")) + " · " + result.optString("status", ""));
        if (result != null && "no_new".equals(result.optString("status")) && result.has("probeCount")) {
            detail = detail + "；最近读取 " + result.optInt("probeCount", 0)
                    + " 条，符合规则 " + result.optInt("probeAllowed", 0)
                    + " 条，最新ID " + result.optLong("probeLatestId", 0L)
                    + (result.optString("probeText", "").isEmpty() ? "" : "，类型 " + result.optString("probeText"));
        }
        if (result != null && result.optLong("sentMessageId", 0L) > 0L) {
            detail = detail + "；目标消息ID " + result.optLong("sentMessageId", 0L);
        }
        writeStatus(result != null && result.optBoolean("ok", false) ? "info" : "error", title, detail, target, source, result);
    }

    private void writeStatus(String level, String title, String detail, JSONObject target, JSONObject source, JSONObject result) {
        try {
            eventId++;
            JSONObject status = new JSONObject();
            status.put("eventId", eventId);
            status.put("time", System.currentTimeMillis());
            status.put("level", level);
            status.put("title", title);
            status.put("detail", detail == null ? "" : detail);
            status.put("targetName", target == null ? "" : target.optString("name"));
            status.put("sourceName", source == null ? "" : source.optString("name"));
            status.put("sourceRule", sourceRuleMode(source));
            status.put("position", result == null ? "" : result.optString("position", result.optString("lastMessageId", "")));
            status.put("status", result == null ? "" : result.optString("status", ""));
            status.put("mode", result == null ? "" : result.optString("mode", ""));
            status.put("collected", collected);
            status.put("sent", sent);
            status.put("failed", failed);
            status.put("targetIndex", currentTargetIndex);
            status.put("sourceIndex", currentSourceIndex);
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            editor.putString(KEY_STATUS, status.toString());
            editor.putLong(KEY_EVENT_ID, eventId);
            editor.putLong(KEY_COLLECTED, collected);
            editor.putLong(KEY_SENT, sent);
            editor.putLong(KEY_FAILED, failed);
            editor.apply();
        } catch (Exception ignored) {
            // Status reporting must never stop collection.
        }
    }

    private String sourceRuleMode(JSONObject source) {
        if (source == null || config == null) return "default";
        JSONObject sourceRules = config.optJSONObject("sourceRules");
        if (sourceRules == null) return "default";
        JSONObject rule = sourceRules.optJSONObject(source.optString("id"));
        if (rule == null) return "default";
        return rule.optString("mode", "default");
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            if (manager.getActiveNetwork() == null) return false;
            NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
            return caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private JSONObject findChannel(JSONArray channels, String id) {
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel != null && id.equals(channel.optString("id"))) return channel;
        }
        return null;
    }

    private long parseLongPosition(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void saveConfig() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CONFIG, config.toString()).apply();
    }
}
