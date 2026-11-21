package com.agentpad.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「工作中」悬浮窗 + 前台服务。
 *
 * 自动继续开关打开后，每轮对话开始显示一个小胶囊「工作中」；点击它回到对应的
 * AI 对话界面。之所以做成前台服务而不是普通 View：Activity 切到后台后，普通
 * Activity 持有的 WindowManager 悬浮窗会随 Activity 一起停止绘制/被回收，只有
 * Service（TYPE_APPLICATION_OVERLAY）才能跨 App 常驻显示。
 *
 * 没有悬浮窗权限（Settings.canDrawOverlays）时不显示胶囊，但服务照常运行，
 * 只作为「这一轮在跑」的状态标记，不影响对话逻辑。
 */
class WorkingBubble : Service() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val ACTION_START = "com.agentpad.workspace.WORKING_BUBBLE_START"
        private const val ACTION_STOP = "com.agentpad.workspace.WORKING_BUBBLE_STOP"
        private const val CHANNEL_ID = "agent_working"
        /** 当前正在显示胶囊的会话 id；null = 没有显示。 */
        @Volatile
        var activeSessionId: String? = null
            private set

        fun isShowing(sessionId: String): Boolean = activeSessionId == sessionId

        /** 开始「工作中」：切到别的 App 也一直在，直到 stop()。 */
        fun start(context: Context, sessionId: String) {
            if (activeSessionId == sessionId) return
            activeSessionId = sessionId
            val i = Intent(context, WorkingBubble::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
        }

        /** 结束「工作中」：隐藏胶囊并停掉服务。 */
        fun stop(context: Context) {
            activeSessionId = null
            val i = Intent(context, WorkingBubble::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }

        /**
         * 只停掉指定会话的悬浮窗（其他会话的「工作中」不受影响）。
         * 用于「会话 A 收尾，但会话 B 还在后台跑」的场景。
         */
        fun stopIfShowing(context: Context, sessionId: String) {
            if (activeSessionId != sessionId) return
            stop(context)
        }
    }

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var sessionId: String = ""
    private val overlayScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        overlayScope.launch {
            com.agentpad.workspace.screencontrol.ScreenControlManager.acting.collect { acting ->
                bubble?.visibility = if (acting || com.agentpad.workspace.floating.AgentFloatingService.active) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                hideBubble()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val sid = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                if (sid.isNotBlank()) sessionId = sid
                if (sid.isNotBlank() && activeSessionId != sid) {
                    activeSessionId = sid
                }
                ensureForeground()
                showBubble()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlayScope.cancel()
        hideBubble()
        super.onDestroy()
    }

    // ---- 前台服务（否则 8.0+ 后台 Service 会被很快杀掉） ----
    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "运行状态", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                        enableLights(false)
                        enableVibration(false)
                    },
                )
            }
            val notif: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Workspace")
                .setContentText("AI 工作中…")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        this, 0,
                        chatIntent(),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0,
                    ),
                )
                .build()
            startForeground(0x5A17, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun chatIntent(): Intent =
        Intent(this, ChatActivity::class.java)
            .putExtra("session_id", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // ---- 悬浮胶囊 ----
    private fun canOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun showBubble() {
        if (bubble != null) return
        if (com.agentpad.workspace.floating.AgentFloatingService.active) return
        if (!canOverlay()) return   // 无权限：静默跳过，只保留前台服务状态
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val tv = TextView(this).apply {
            text = "工作中"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(13), dp(6), dp(13), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#E65267E8"))
            }
            elevation = dp(6).toFloat()
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(120)
        }

        // 拖拽 + 点击：位移小于阈值才算点击（回到该 AI 对话界面）。
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX - dx      // END gravity：x 向右为正、往左拖增大
                    lp.y = startY + dy
                    runCatching { wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) openChat()
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(tv, lp)
            bubble = tv
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        runCatching { windowManager?.removeView(v) }
    }

    private fun openChat() {
        // 只把对应的 AI 对话界面拉到前台，绝不取消悬浮窗——
        // 这一轮还在后台跑，胶囊要一直留着，直到本轮真正结束（completeTurn/停止/报错）才消失。
        runCatching {
            startActivity(chatIntent())
        }
    }
}
