package com.example.aiphotoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.sync.SyncEngine
import com.example.aiphotoapp.telegram.TelegramManager
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

class CollectorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent?.action == ACTION_STOP) {
            CollectorRuntime.engine?.stop()
            CollectorRuntime.telegram?.close()
            CollectorRuntime.telegram = null
            CollectorRuntime.engine = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            resumeCollectionIfIdle()
        }
        return START_STICKY
    }

    // 进程被杀后重启：从 DB 读启用的规则，自动续采集
    private fun resumeCollectionIfIdle() {
        if (CollectorRuntime.engine != null) return
        thread(name = "collector-resume") {
            val db = CollectorRuntime.db
                ?: Room.databaseBuilder(this, CollectorDatabase::class.java, "collector.db")
                    .build()
                    .also { CollectorRuntime.db = it }
            val rule = runCatching { runBlocking { db.collectorDao().getEnabledRules().firstOrNull() } }.getOrNull()
                ?: return@thread
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val apiId = prefs.getString(KEY_ID, "")?.toIntOrNull()
            val apiHash = prefs.getString(KEY_HASH, "")
            if (apiId == null || apiHash.isNullOrEmpty()) {
                stopSelf()
                return@thread
            }
            if (CollectorRuntime.telegram == null) {
                val client = TelegramManager(this, apiId, apiHash)
                client.bindUiListener { }
                CollectorRuntime.telegram = client
                client.start()
            }
            val telegram = CollectorRuntime.telegram ?: return@thread
            CollectorRuntime.activeRuleId.set(rule.id)
            val engine = SyncEngine(telegram, db.collectorDao())
            CollectorRuntime.engine = engine
            engine.start(rule, onUpdate = { msg ->
                updateNotification(msg)
            })
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (CollectorRuntime.engine != null || hasPersistedRule()) {
            ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction(ACTION_START))
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun hasPersistedRule(): Boolean {
        return runCatching {
            val db = CollectorRuntime.db
                ?: Room.databaseBuilder(this, CollectorDatabase::class.java, "collector.db")
                    .build()
                    .also { CollectorRuntime.db = it }
            runBlocking { db.collectorDao().getEnabledRules().isNotEmpty() }
        }.getOrDefault(false)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "采集服务", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("频道采集器运行中")
            .setContentText(CollectorRuntime.engineMsg.ifBlank { "正在后台复制频道媒体" })
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(msg: String) {
        CollectorRuntime.engineMsg = msg
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        const val CHANNEL_ID = "collector_service"
        const val PREFS = "collector_login"
        const val KEY_ID = "api_id"
        const val KEY_HASH = "api_hash"
        private const val NOTIFICATION_ID = 1001
        val ACTION_START = "com.example.aiphotoapp.action.START"
        val ACTION_STOP = "com.example.aiphotoapp.action.STOP"
    }
}