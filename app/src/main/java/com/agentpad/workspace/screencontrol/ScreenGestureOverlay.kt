package com.agentpad.workspace.screencontrol

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

private const val TAG = "ScreenGestureOverlay"

// 手势做完后再留一会儿淡出，短促的点击也看得清落点
private const val LINGER_MS = 650L
private const val TAP_RADIUS_DP = 34f
private const val DOT_RADIUS_DP = 9f
private const val STROKE_DP = 3f

// 起点和终点离得很近时按点击画
private const val TAP_SLOP_DP = 8f

/**
 * 在屏幕上画出 AI 这次点在哪、往哪滑，让用户看得出它在操作。
 * 用无障碍服务自己的覆盖层，不需要悬浮窗权限；窗口不接收触摸，只在动画期间存在。
 * 所有方法都要在主线程调用。
 */
class ScreenGestureOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: IndicatorView? = null

    /** 坐标是屏幕像素；[durationMs] 是手势本身的时长，滑动的轨迹跟着手势一起前进 */
    fun show(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        dismiss()
        val view = IndicatorView(service, fromX, fromY, toX, toY, durationMs, onEnd = ::dismiss)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        // 铺到刘海和系统栏下面，窗口原点才和屏幕原点一致
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager.addView(view, params)
            this.view = view
        } catch (e: Exception) {
            // 只是提示效果，加不上不影响手势本身
            Log.w(TAG, "show: failed to add overlay", e)
        }
    }

    fun dismiss() {
        val view = view ?: return
        this.view = null
        view.cancel()
        runCatching { windowManager.removeViewImmediate(view) }
    }
}

private class IndicatorView(
    context: Context,
    private val fromX: Float,
    private val fromY: Float,
    private val toX: Float,
    private val toY: Float,
    gestureMs: Long,
    private val onEnd: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val isTap = hypot(toX - fromX, toY - fromY) < TAP_SLOP_DP * density
    private val total = gestureMs + LINGER_MS

    // 手势进行到哪（0-1）和整体淡出（1-0）
    private var progress = 0f
    private var alphaScale = 1f
    private val location = IntArray(2)

    // 深色描边加浅色主体，亮暗背景上都看得见
    private val accent = accentColor(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = total
        interpolator = null
        addUpdateListener {
            val elapsed = it.animatedFraction * total
            progress = (elapsed / gestureMs.coerceAtLeast(1)).coerceAtMost(1f)
            alphaScale = (1f - (elapsed - gestureMs) / LINGER_MS).coerceIn(0f, 1f)
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            // 取消时由 dismiss 自己移除窗口
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onEnd()
            }
        })
    }

    private val ripple = DecelerateInterpolator()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    fun cancel() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        // 个别系统上窗口仍会被系统栏挤开，按实际位置换算
        getLocationOnScreen(location)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        if (isTap) drawTap(canvas) else drawSwipe(canvas)
    }

    // 点击：落点一个实心点，外面一圈向外扩散。长按时圆圈在按住期间一直留着
    private fun drawTap(canvas: Canvas) {
        val spread = ripple.getInterpolation(progress)
        fill.color = withAlpha(accent, 0.25f * alphaScale)
        canvas.drawCircle(fromX, fromY, TAP_RADIUS_DP * density * (0.4f + 0.6f * spread), fill)
        line.shader = android.graphics.LinearGradient(fromX - 40 * density, fromY - 40 * density, fromX + 40 * density, fromY + 40 * density,
            intArrayOf(0xFF5267E8.toInt(), 0xFF39C9EE.toInt(), 0xFF9B7CF6.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        line.strokeWidth = 2.5f * density
        line.alpha = (alphaScale * 240).toInt()
        canvas.drawCircle(fromX, fromY, TAP_RADIUS_DP * density * (0.45f + spread * 0.65f), line)
        line.strokeWidth = 1.4f * density
        line.alpha = (alphaScale * 155).toInt()
        canvas.drawCircle(fromX, fromY, TAP_RADIUS_DP * density * (0.65f + spread * 0.9f), line)
        line.shader = null
        line.alpha = 255
        drawDot(canvas, fromX, fromY)
    }

    // 滑动：从起点到当前位置的轨迹，头上一个点跟着手指走
    private fun drawSwipe(canvas: Canvas) {
        val x = fromX + (toX - fromX) * progress
        val y = fromY + (toY - fromY) * progress
        line.strokeWidth = (DOT_RADIUS_DP * 2 + STROKE_DP) * density
        line.color = withAlpha(Color.BLACK, 0.2f * alphaScale)
        canvas.drawLine(fromX, fromY, x, y, line)
        line.strokeWidth = DOT_RADIUS_DP * 2 * density
        line.shader = android.graphics.LinearGradient(fromX, fromY, toX + 1f, toY + 1f,
            0xFF5267E8.toInt(), 0xFF39C9EE.toInt(), android.graphics.Shader.TileMode.CLAMP)
        line.alpha = (alphaScale * 160).toInt()
        canvas.drawLine(fromX, fromY, x, y, line)
        line.shader = null
        line.alpha = 255
        drawDot(canvas, x, y)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float) {
        fill.color = withAlpha(Color.BLACK, 0.35f * alphaScale)
        canvas.drawCircle(x, y, (DOT_RADIUS_DP + STROKE_DP / 2) * density, fill)
        fill.color = withAlpha(accent, alphaScale)
        canvas.drawCircle(x, y, DOT_RADIUS_DP * density, fill)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

// 跟随系统的动态取色，Android 12 以下用固定的浅紫色
private fun accentColor(context: Context): Int = 0xFF7B8CFF.toInt()
