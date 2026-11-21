package com.agentpad.workspace

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.agentpad.workspace.data.AdbMdns
import com.agentpad.workspace.data.AppLog
import com.agentpad.workspace.data.WirelessAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 无线调试「通知栏直接配对」服务。
 *
 * ⚠ 2026-11-04 新增。**照抄 Shizuku `AdbPairingService.kt`**
 * （RikkaApps/Shizuku, Apache-2.0）的核心流程：
 *
 *   1. 启动后挂一条「正在搜索无线调试服务」的通知，同时用 [AdbMdns] 监听
 *      `_adb-tls-pairing._tcp`（系统 adbd 广播的配对端口）
 *   2. 发现端口 → 把通知换成带 **RemoteInput 输入框** 的「输入配对码」
 *      —— 用户直接在通知栏里填 6 位码，不用切回 App
 *   3. 提交后立刻进入「正在配对」通知，后台完成配对
 *   4. 成功/失败各推一条结果通知
 *
 * 两个必须照抄的关键点：
 *  - **端口要放进 Intent extra**（Shizuku 原注释：服务可能在用户输入期间被杀，
 *    重建时靠 extra 里的端口才知道要连哪）
 *  - RemoteInput 的 PendingIntent **必须 FLAG_MUTABLE**，否则系统无法回填输入内容
 */
@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "AdbPairing"
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIF_ID = 0x5A30
        private const val REPLY_REQ = 1
        private const val STOP_REQ = 2

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"

        private const val KEY_CODE = "pairing_code"
        private const val KEY_PORT = "pairing_port"

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, AdbPairingService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }.onFailure { AppLog.e(TAG, "启动配对服务失败", it) }
        }

        private fun stopIntent(ctx: Context) =
            Intent(ctx, AdbPairingService::class.java).setAction(ACTION_STOP)

        private fun replyIntent(ctx: Context, port: Int) =
            Intent(ctx, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(KEY_PORT, port)
    }

    private var mdns: AdbMdns? = null
    private var searching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // mDNS 无线调试配对是 Android 11+ 才有，低版本直接退出
            stopSelf()
            return
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return START_NOT_STICKY

        val notification = when (intent?.action) {
            ACTION_START -> startSearching()

            ACTION_REPLY -> {
                // ⚠ 从 PendingIntent 里取用户输入（RemoteInput 的标准取法）
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                val port = intent.getIntExtra(KEY_PORT, -1)
                if (port > 0 && code.isNotBlank()) {
                    doPair(code, port)
                } else {
                    startSearching()
                }
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSearching()
                stopSelf()
                null
            }

            else -> return START_NOT_STICKY
        }

        if (notification != null) {
            runCatching {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure {
                // Android 12+ 后台启动前台服务受限时，退回普通通知
                runCatching {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    // ---------- 搜索 ----------

    private fun startSearching(): Notification {
        if (!searching) {
            searching = true
            mdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
                if (port > 0) {
                    AppLog.i(TAG, "发现配对端口：$port")
                    // 换一条带输入框的通知
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIF_ID, inputNotification(port))
                    }
                }
            }.also { it.start() }
        }
        return searchingNotification()
    }

    private fun stopSearching() {
        if (!searching) return
        searching = false
        runCatching { mdns?.stop() }
        mdns = null
    }

    override fun onDestroy() {
        stopSearching()
        super.onDestroy()
    }

    // ---------- 配对 ----------

    private fun doPair(code: String, port: Int): Notification {
        CoroutineScope(Dispatchers.IO).launch {
            val host = "127.0.0.1"
            // 本机自连（无线调试配对端口只监听 loopback）
            val r = WirelessAdb.pairDirect(this@AdbPairingService, host, port, code)
            stopSearching()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NotificationManager::class.java)
            val n = if (r.ok) {
                AppLog.i(TAG, "配对成功 $host:$port")
                // 配对成功后立刻尝试连接（连接端口同样用 mDNS 重新发现）
                WirelessAdb.autoConnectAfterPair(this@AdbPairingService)
                resultNotification("配对成功", "已开始连接，可返回 App 使用")
            } else {
                AppLog.w(TAG, "配对失败：${r.message}")
                resultNotification("配对失败", r.message)
            }
            runCatching { nm?.notify(NOTIF_ID, n) }
            stopSelf()
        }
        return workingNotification()
    }

    // ---------- 通知构造 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "无线调试配对", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        setSound(null, null)
                        setShowBadge(false)
                        setAllowBubbles(false)
                    }
            )
        }
    }

    private fun baseBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

    private fun flagImmutable() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /** RemoteInput 的 PendingIntent 必须 MUTABLE（系统要往里塞输入内容）。 */
    private fun flagMutable() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(this, STOP_REQ, stopIntent(this), flagImmutable())
        return Notification.Action.Builder(null, "停止搜索", pi).build()
    }

    /** 搜索中：一条通知 + 停止按钮。 */
    private fun searchingNotification(): Notification =
        baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("正在搜索无线调试")
            .setContentText("请在系统设置里打开「无线调试」")
            .setOngoing(true)
            .addAction(stopAction())
            .build()

    /**
     * 已发现端口：带 RemoteInput 输入框的动作。
     *
     * ⚠ 端口写进 PendingIntent 的 extra —— 服务被杀后重建还能知道端口
     * （照抄 Shizuku AdbPairingService.kt:58-60 的注释）。
     */
    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("输入 6 位配对码")
            .build()

        val pi = PendingIntent.getForegroundService(
            this,
            REPLY_REQ,
            replyIntent(this, port),
            flagMutable(),
        )

        val action = Notification.Action.Builder(null, "输入配对码", pi)
            .addRemoteInput(remoteInput)
            .build()

        return baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("已找到配对服务")
            .setContentText("端口 $port · 点「输入配对码」填入手机显示的 6 位码")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .addAction(stopAction())
            .build()
    }

    private fun workingNotification(): Notification =
        baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("正在配对…")
            .setOngoing(true)
            .build()

    private fun resultNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WirelessAdbActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flagImmutable(),
        )
        return baseBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}