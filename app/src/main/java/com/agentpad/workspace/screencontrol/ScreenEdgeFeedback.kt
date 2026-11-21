package com.agentpad.workspace.screencontrol

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/** Workspace blue/violet aurora, separate from touch targets and never captured in screenshots. */
class ScreenEdgeFeedback(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var current: AuraView? = null
    fun pulse(capture: Boolean = false) {
        dismiss()
        val next = AuraView(service, capture) { dismiss() }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { windows.addView(next, params) }.onSuccess { current = next }
    }
    fun dismiss() {
        val old = current ?: return
        current = null
        old.cancel()
        runCatching { windows.removeViewImmediate(old) }
    }
}

private class AuraView(service: AccessibilityService, private val capture: Boolean, onEnd: () -> Unit) : View(service) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var progress = 0f
    private var gradient: SweepGradient? = null
    private val matrix = Matrix()
    private val rect = RectF()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = if (capture) 1100L else 800L
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
        })
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    fun cancel() { animator.cancel() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradient = SweepGradient(w / 2f, h / 2f, intArrayOf(0xFF5267E8.toInt(), 0xFF7C5CF0.toInt(),
            0xFF39C9EE.toInt(), 0xFF5267E8.toInt()), null)
    }
    override fun onDraw(canvas: Canvas) {
        val strength = sin(progress * Math.PI).toFloat().coerceIn(0f, 1f)
        paint.shader = gradient
        matrix.setRotate(progress * 100f, width / 2f, height / 2f)
        gradient?.setLocalMatrix(matrix)
        // Multiple translucent strokes create an inward feather without a full-screen bitmap blur.
        for (layer in 18 downTo 1) {
            val spread = layer / 18f
            val inset = 2 * density
            rect.set(inset, inset, width - inset, height - inset)
            paint.strokeWidth = (3 + layer * 2) * density
            paint.alpha = (strength * (1f - spread * 0.75f) * 14).toInt()
            canvas.drawRoundRect(rect, 30 * density, 30 * density, paint)
        }
        paint.strokeWidth = 2.5f * density
        paint.alpha = (strength * 235).toInt()
        rect.set(2 * density, 2 * density, width - 2 * density, height - 2 * density)
        canvas.drawRoundRect(rect, 30 * density, 30 * density, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = (strength * 150).toInt()
        paint.strokeWidth = density
        rect.inset(3 * density, 3 * density)
        canvas.drawRoundRect(rect, 27 * density, 27 * density, paint)
    }
}