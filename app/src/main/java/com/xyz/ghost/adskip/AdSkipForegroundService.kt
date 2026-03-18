package com.xyz.ghost.adskip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xyz.ghost.R
import com.xyz.ghost.ui.MainActivity

/**
 * 轻量前台服务，唯一职责：保持进程存活，确保 AdSkipService 不被系统杀死。
 * 由 AdSkipService.onServiceConnected() 启动，onDestroy() 停止。
 */
class AdSkipForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ghost_adskip_channel"
        const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ghost 跳广告", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "自动跳广告运行中"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ad_skip)
            .setContentTitle("Ghost · 自动跳广告")
            .setContentText("正在监听 YouTube 广告")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}