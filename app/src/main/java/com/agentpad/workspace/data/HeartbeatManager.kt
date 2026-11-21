package com.agentpad.workspace.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * 心跳管理器（在线检测）：
 * - APK 启动且已登录时开始心跳循环
 * - 发送心跳 → 等服务器确认（"已接收"）→ 收到确认后才启动 120 秒下一次计时
 * - 失败容错：网络失败不崩溃，10 秒后重试（最多连续 3 次失败后暂停，2 分钟后整体重试）
 * - 服务器负载考量：单用户 120 秒一次 = 每小时 30 次轻量请求（省 InfinityFree hits 配额；
 *   后台「实时在线」窗口为 5 分钟，120s 间隔不影响在线判定）
 */
object HeartbeatManager {
    private const val TAG = "HeartbeatManager"

    /** 心跳间隔（毫秒）：服务器确认后 120 秒发下一次 */
    private const val INTERVAL_MS = 120_000L

    /** 失败重试间隔（毫秒） */
    private const val RETRY_MS = 10_000L

    /** 连续失败 N 次后长冷却（避免无网时狂打服务器） */
    private const val MAX_FAIL_STREAK = 3
    private const val LONG_COOLDOWN_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var failStreak = 0
    @Volatile private var lastError: String? = null

    /** 最近一次心跳状态（UI 可读）：成功时间戳 / 活跃数 / 错误 */
    @Volatile var lastSuccessAt: Long = 0L
        private set
    @Volatile var lastActiveCount: String = ""
        private set

    /** 启动心跳循环（已登录才会真正发请求；重复调用安全） */
    fun start(context: Context) {
        if (running) return
        if (!UserStore.isLoggedIn(context)) return
        running = true
        failStreak = 0
        sendOnce(context.applicationContext)
    }

    /** 停止心跳循环（登出时调用） */
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    /** 登录成功后调用：立即开始心跳 */
    fun onLogin(context: Context) {
        stop()
        start(context)
    }

    private fun sendOnce(context: Context) {
        if (!running) return
        Thread {
            val (ok, info) = try {
                UserStore.heartbeat(context)
            } catch (e: Exception) {
                Pair(false, "网络异常")
            }
            if (!running) return@Thread
            if (ok) {
                failStreak = 0
                lastSuccessAt = System.currentTimeMillis()
                lastActiveCount = info
                // 服务器已确认（已接收），120 秒后发下一次
                handler.postDelayed({ sendOnce(context) }, INTERVAL_MS)
            } else {
                lastError = info
                failStreak++
                val delay = if (failStreak >= MAX_FAIL_STREAK) {
                    failStreak = 0
                    LONG_COOLDOWN_MS
                } else RETRY_MS
                handler.postDelayed({ sendOnce(context) }, delay)
            }
        }.start()
    }
}