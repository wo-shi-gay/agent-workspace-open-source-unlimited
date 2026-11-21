package com.agentpad.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.agentpad.workspace.data.AdbPermission
import com.agentpad.workspace.data.AdbShell
import com.agentpad.workspace.data.AppLog

/**
 * ADB 级后台保活服务。
 *
 * 仅在「ADB 权限」开启且 Shizuku 可用时工作：
 *  1) 前台服务（防系统杀进程）
 *  2) 通过 ADB 定期执行保活命令（push 自身 / 清理后台限制）
 *  3) 进程被杀后由 AlarmManager 拉起（Android 12+ 有限制，尽力而为）
 *
 * 用户关闭 ADB 权限后自动停止。
 */
class AdbKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "adb_keepalive"
        private const val NOTIF_ID = 0x5A13
        private const val INTERVAL_MS = 5 * 60 * 1000L   // 5 分钟一次

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, AdbKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
                AppLog.i("KeepAlive", "ADB 保活服务已启动")
            }.onFailure { AppLog.e("KeepAlive", "启动失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.stopService(Intent(ctx, AdbKeepAliveService::class.java))
                AppLog.i("KeepAlive", "ADB 保活服务已停止")
            }
        }
    }

    private var thread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        running = true
        thread = Thread {
            while (running) {
                runCatching { keepAliveTick() }
                try { Thread.sleep(INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 权限被关掉 → 自杀
        if (!AdbPermission.isEnabled(this)) {
            AppLog.i("KeepAlive", "ADB 权限已关闭，停止保活")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun keepAliveTick() {
        if (!AdbPermission.isEnabled(this)) { stopSelf(); return }
        if (!AdbPermission.bootKeepAlive(this)) return

        // 用 ADB 权限做两件事：
        // 1) 把本应用加入电池优化白名单（如果尚未加入）
        // 2) 拉起自身主界面到后台栈（防止被系统彻底回收）
        runCatching {
            val pkg = packageName
            val r = AdbShell.exec(
                this,
                "dumpsys deviceidle whitelist | grep -q $pkg || cmd deviceidle whitelist +$pkg",
                8000,
            )
            if (r.exitCode != 0) {
                AppLog.w("KeepAlive", "加白名单失败: ${r.stderr.take(120)}")
            }
        }
        runCatching {
            // 用 am 拉起我们自己的 Activity（不抢焦点）
            AdbShell.exec(
                this,
                "am start -n $packageName/.BootActivity --activity-no-animation 2>/dev/null",
                5000,
            )
        }
        AppLog.d("KeepAlive", "保活心跳完成")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ADB 后台保活", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "维持 ADB 权限下的后台运行"
                        setShowBadge(false)
                    }
            )
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Workspace 正在运行")
            .setContentText("ADB 权限已开启 · 后台保活中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        AppLog.i("KeepAlive", "保活服务销毁")
        super.onDestroy()
    }
}