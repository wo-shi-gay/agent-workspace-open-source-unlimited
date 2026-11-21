package com.agentpad.workspace.screencontrol

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.agentpad.workspace.ScreenControlActivity
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-granted MediaProjection session. Frames are discarded except for an explicit screenshot request. */
class ScreenCaptureService : Service() {
    companion object {
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION = 0x5A31
        private const val ACTION_START = "com.agentpad.workspace.CAPTURE_START"
        private const val ACTION_STOP = "com.agentpad.workspace.CAPTURE_STOP"
        private const val GRANT = "projection_grant"
        @Volatile private var instance: ScreenCaptureService? = null
        val ready = MutableStateFlow(false)
        val status = MutableStateFlow("屏幕内容授权待开启")

        fun start(context: Context, resultCode: Int, data: Intent) {
            if (resultCode != Activity.RESULT_OK) return
            status.value = "正在连接屏幕捕获服务"
            context.startForegroundService(Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START).putExtra("result_code", resultCode).putExtra(GRANT, data))
        }

        fun stop(context: Context) {
            ready.value = false
            status.value = "屏幕捕获已停止"
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        suspend fun screenshot(maxSide: Int = 1568): ByteArray = withTimeout(6500L) {
            val service = instance?.takeIf { ready.value } ?: error("请在设置→屏幕控制中申请屏幕内容权限")
            service.requestFrame(maxSide.coerceIn(256, 2048))
        }
    }

    private val thread = HandlerThread("screen-capture")
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var density = 0
    private var pending: CancellableContinuation<ByteArray>? = null
    private var pendingMaxSide = 1568
    private var callback: MediaProjection.Callback? = null
    @Volatile private var closing = false

    override fun onCreate() {
        super.onCreate()
        thread.start()
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ready.value = false
            status.value = "屏幕捕获已停止"
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (instance === this && ready.value) return START_NOT_STICKY
        try {
            showNotification()
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(GRANT) ?: error("屏幕内容授权已失效，请重新申请")
            val code = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
            intent.removeExtra(GRANT)
            check(code == Activity.RESULT_OK) { "屏幕内容授权未完成" }
            worker.post {
                if (closing || projection != null) return@post
                try {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    val session = manager.getMediaProjection(code, data) ?: error("屏幕内容会话创建失败")
                    projection = session
                    val listener = object : MediaProjection.Callback() {
                        override fun onStop() {
                            ready.value = false
                            status.value = "系统已停止屏幕捕获，请重新授权"
                            cleanup()
                            stopSelf()
                        }
                    }
                    callback = listener
                    session.registerCallback(listener, worker)
                    val metrics = currentMetrics()
                    width = metrics.widthPixels
                    height = metrics.heightPixels
                    density = metrics.densityDpi
                    val target = createReader(width, height)
                    reader = target
                    display = session.createVirtualDisplay("Workspace Screen Capture", width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target.surface, null, worker)
                    instance = this
                    ready.value = true
                    status.value = "屏幕内容已授权 · 截图可用"
                } catch (e: Exception) {
                    ready.value = false
                    status.value = "屏幕捕获启动失败：${e.message}"
                    cleanup()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            status.value = "屏幕捕获启动失败：${e.message}"
            stopSelf()
        }
        // A projection token is never persisted or silently replayed after service/process restart.
        return START_NOT_STICKY
    }

    private fun currentMetrics(): DisplayMetrics {
        val manager = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        return DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }
    }

    private fun createReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { target ->
            target.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val request = pending ?: return@use
                    if (!request.isActive) { pending = null; return@use }
                    pending = null
                    try {
                        val bytes = encode(image, pendingMaxSide)
                        if (request.isActive) request.resume(bytes)
                    } catch (e: Exception) {
                        if (request.isActive) request.resumeWithException(e)
                    }
                }
            }, worker)
        }

    private suspend fun requestFrame(maxSide: Int): ByteArray = suspendCancellableCoroutine { request ->
        request.invokeOnCancellation { worker.post { if (pending === request) pending = null } }
        worker.post {
            if (!request.isActive) return@post
            try {
                check(!closing && projection != null && ready.value) { "屏幕捕获会话已停止，请重新授权" }
                check(pending == null) { "已有截图正在获取，请稍后重试" }
                val metrics = currentMetrics()
                if (metrics.widthPixels != width || metrics.heightPixels != height || metrics.densityDpi != density) {
                    // Reuse the same VirtualDisplay: modern Android projection grants are single-use.
                    val old = reader
                    val next = createReader(metrics.widthPixels, metrics.heightPixels)
                    display?.surface = null
                    display?.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                    display?.surface = next.surface
                    reader = next
                    old?.setOnImageAvailableListener(null, null)
                    old?.close()
                    width = metrics.widthPixels
                    height = metrics.heightPixels
                    density = metrics.densityDpi
                }
                // Drop queued frames; wait for a fresh frame after the overlays have yielded the screen.
                reader?.acquireLatestImage()?.close()
                pendingMaxSide = maxSide
                pending = request
                display?.surface = null
                display?.surface = reader?.surface
            } catch (e: Exception) {
                if (request.isActive) request.resumeWithException(e)
            }
        }
    }

    private fun encode(image: Image, maxSide: Int): ByteArray {
        val plane = image.planes[0]
        val stride = plane.pixelStride
        check(stride == 4) { "屏幕图像像素格式异常" }
        val paddedWidth = plane.rowStride / stride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val crop = image.cropRect
            val source = Bitmap.createBitmap(padded, crop.left, crop.top, crop.width(), crop.height())
            cropped = source
            val ratio = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
            val outputBitmap = if (ratio < 1f) Bitmap.createScaledBitmap(source,
                (source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1), true)
                else source
            scaled = outputBitmap
            return ByteArrayOutputStream().use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) { "截图编码失败" }
                output.toByteArray()
            }
        } finally {
            if (scaled !== cropped && scaled !== padded) scaled?.recycle()
            if (cropped !== padded) cropped?.recycle()
            padded.recycle()
        }
    }

    private fun showNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕内容捕获", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, NOTIFICATION,
            Intent(this, ScreenControlActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTIFICATION + 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Workspace 屏幕内容授权中")
            .setContentText("供 AI 按任务截图 · 点击停止可结束本次授权")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止捕获", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION, notification)
    }

    private fun cleanup() {
        val request = pending
        pending = null
        if (request?.isActive == true) request.resumeWithException(IllegalStateException("屏幕捕获已停止"))
        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        val session = projection
        projection = null
        callback?.let { listener -> runCatching { session?.unregisterCallback(listener) } }
        callback = null
        runCatching { session?.stop() }
    }

    override fun onDestroy() {
        closing = true
        if (instance === this) { instance = null; ready.value = false }
        worker.post { cleanup(); thread.quitSafely() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}