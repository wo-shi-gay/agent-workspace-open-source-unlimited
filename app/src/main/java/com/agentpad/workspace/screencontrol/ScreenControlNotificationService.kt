package com.agentpad.workspace.screencontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.agentpad.workspace.ScreenControlActivity

/** Persistent, user-visible control switch in the notification shade, without a screen overlay. */
class ScreenControlNotificationService : Service() {
    companion object {
        private const val CHANNEL = "screen_control_status"
        private const val NOTICE = 0x5A43
        private const val STOP = "com.agentpad.workspace.STOP_SCREEN_CONTROL"
        fun start(context: Context) {
            if (!ScreenControlSettings.enabled(context)) return
            runCatching { context.startForegroundService(Intent(context, ScreenControlNotificationService::class.java)) }
                .onFailure {
                    ScreenControlManager.stopNotificationReady.value = false
                    ScreenControlSettings.setEnabled(context, false)
                    android.util.Log.w("ScreenControl", "notification service start failed", it)
                }
        }
        fun stop(context: Context) {
            ScreenControlManager.stopNotificationReady.value = false
            context.stopService(Intent(context, ScreenControlNotificationService::class.java))
        }
        fun notificationsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            return manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            ScreenControlSettings.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ScreenControlSettings.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕控制状态", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false); enableVibration(false) })
        val open = PendingIntent.getActivity(this, NOTICE, Intent(this, ScreenControlActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTICE + 1,
            Intent(this, ScreenControlNotificationService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Workspace · 屏幕控制运行中")
            .setContentText("AI 可按任务操作手机 · 展开通知可停止控制")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "停止控制", stop)
            .build()
        try {
            startForeground(NOTICE, notification)
            if (notificationsAvailable(this)) ScreenControlManager.stopNotificationReady.value = true
            else {
                ScreenControlSettings.setEnabled(this, false)
                stopSelf()
            }
        } catch (e: Exception) {
            ScreenControlSettings.setEnabled(this, false)
            android.util.Log.w("ScreenControl", "control notification failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenControlManager.stopNotificationReady.value = false
        if (ScreenControlSettings.enabled(this)) ScreenControlSettings.setEnabled(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}