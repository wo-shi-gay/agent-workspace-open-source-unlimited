package com.agentpad.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.agentpad.workspace.data.AppLog

/**
 * 通知常驻保活服务（2026-11-04 新增）。
 *
 * 为什么这是「不申请无障碍」时最好的保活手段：
 *   前台服务（startForeground）会在状态栏挂一条常驻通知，系统据此把进程
 *   视为「用户可见的前台任务」，oom_adj 显著低于普通后台进程。
 *   这是 Android 官方认可的合法保活方式，**不需要任何特殊权限**
 *   （Android 13+ 只需 POST_NOTIFICATIONS，已在 Manifest 声明）。
 *
 * 与另外两层保活的关系（三层互相兜底，可任意组合）：
 *   1. 通知常驻（本服务）—— 零门槛，最通用
 *   2. 无障碍保活（KeepAliveAccessibilityService）—— 需用户手动开启，更抗杀
 *   3. ADB 保活（AdbKeepAliveService）—— 需 Shizuku/ADB，可主动拉起
 *
 * 行为：
 *   - 常驻通知不可划掉（setOngoing），点击回到主界面
 *   - 系统回收后自动重建（START_STICKY）
 *   - 用户可在设置里关闭，关闭后不再自启
 */
class KeepAliveNotificationService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive_foreground"
        private const val NOTIF_ID = 0x5A20
        private const val PREF = "keepalive"
        private const val KEY_NOTIFY_ENABLED = "notify_enabled"

        /** 用户是否开启了「通知常驻保活」（默认开启）。 */
        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFY_ENABLED, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NOTIFY_ENABLED, on).apply()
            if (on) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, KeepAliveNotificationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
                AppLog.i("KeepAlive", "通知常驻保活已启动")
            }.onFailure { AppLog.e("KeepAlive", "通知保活启动失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.stopService(Intent(ctx, KeepAliveNotificationService::class.java))
                AppLog.i("KeepAlive", "通知常驻保活已停止")
            }
        }

        /** 进程启动时调用：只在用户开启时拉起，避免打扰。 */
        fun startIfEnabled(ctx: Context) {
            if (isEnabled(ctx)) start(ctx)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户关掉了就不复活
        if (!isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
        // START_STICKY：被系统回收后自动重建，这是前台服务保活的关键
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "后台常驻", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "保持 Workspace 后台运行，AI 任务不中断"
                        setShowBadge(false)
                        // 静音、不亮屏，尽量不打扰
                        enableLights(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        val builder = b.setContentTitle("Workspace 正在运行")
            .setContentText("AI 任务保持连接中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)          // 不可划掉
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setPriority(Notification.PRIORITY_MIN)
        }
        return builder.build()
    }

    override fun onDestroy() {
        AppLog.i("KeepAlive", "通知常驻保活销毁")
        super.onDestroy()
    }
}