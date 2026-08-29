package com.example.aiphotoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class BatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 裂变批处理")
            .setContentText("批量生成进行中")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    companion object {
        private const val CHANNEL_ID = "batch_service"
        private const val NOTIF_ID = 1001

        fun ensureChannel(context: android.content.Context) {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "批处理进行中", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }
}