package com.agentpad.workspace.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agentpad.workspace.ChatActivity
import com.agentpad.workspace.screencontrol.ScreenControlManager
import com.agentpad.workspace.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

val LocalFloatingToolDetails = staticCompositionLocalOf { true }

/** Agent Workspace floating chat: same chat engine and components, separate movable window. */
class AgentFloatingService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    companion object {
        private const val CHANNEL = "agent_floating_chat"
        private const val NOTICE = 0x5A42
        private const val OPEN = "agent.floating.OPEN"
        private const val CLOSE = "agent.floating.CLOSE"
        @Volatile var active = false
            private set
        fun open(context: Context, id: String): Boolean {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "开启悬浮窗权限后，再点一次对话小窗", Toast.LENGTH_LONG).show()
                return false
            }
            context.startForegroundService(Intent(context, AgentFloatingService::class.java).setAction(OPEN).putExtra("session_id", id))
            return true
        }
        fun close(context: Context) { context.stopService(Intent(context, AgentFloatingService::class.java)) }
    }
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private var view: ComposeView? = null
    private var container: android.widget.FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var id by mutableStateOf("")
    private var expanded by mutableStateOf(true)
    private var draft by mutableStateOf("")
    private var error by mutableStateOf("")
    private var yielding = false
    private var frameWidth = 0
    private var frameHeight = 0
    // Physical-pixel hit rect is sampled only on DOWN. Compose density changes cannot cancel ownership.
    private val resizeHitRect = RectF()
    private var resizePointerId = MotionEvent.INVALID_POINTER_ID
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private val chromeHitRects = mutableMapOf<String, RectF>()
    private var chromePointerId = MotionEvent.INVALID_POINTER_ID
    private var chromeAction: String? = null
    private val chromeDownRect = RectF()
    private var chromeStartRawX = 0f
    private var chromeStartRawY = 0f
    private var chromeStartX = 0
    private var chromeStartY = 0
    private var chromeMoved = false
    private val chromeTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var wasBusy = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windows = getSystemService(WindowManager::class.java)
        val prefs = getSharedPreferences("agent_floating", MODE_PRIVATE)
        val bounds = resources.displayMetrics
        // Migrate the oversized first-generation window once; preserve later user resizing.
        val compactSaved = prefs.getBoolean("compact_v2", false)
        val savedWidth = prefs.getInt("width", dp(260)).let { if (compactSaved) it else minOf(it, dp(260)) }
        val savedHeight = prefs.getInt("height", dp(360)).let { if (compactSaved) it else minOf(it, dp(360)) }
        frameWidth = savedWidth.coerceIn(dp(150).coerceAtMost(bounds.widthPixels), bounds.widthPixels)
        frameHeight = savedHeight.coerceIn(dp(180).coerceAtMost(bounds.heightPixels), (bounds.heightPixels * 0.85f).toInt().coerceAtLeast(dp(180).coerceAtMost(bounds.heightPixels)))
        params = WindowManager.LayoutParams(frameWidth, frameHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt("x", dp(12)); y = prefs.getInt("y", dp(100))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        scope.launch {
            ScreenControlManager.acting.collect { acting ->
                yielding = acting
                if (acting) {
                    cancelChromeTouch()
                    if (resizePointerId != MotionEvent.INVALID_POINTER_ID) finishResizeTouch()
                    hideKeyboard()
                    setWindowFocusable(false)
                    container?.visibility = View.INVISIBLE
                } else {
                    setWindowFocusable(expanded)
                    container?.visibility = View.VISIBLE
                }
            }
        }
        scope.launch {
            FloatingSessionHub.state.collect { state ->
                if (state.sessionId == id) {
                    if (resizePointerId == MotionEvent.INVALID_POINTER_ID && chromePointerId == MotionEvent.INVALID_POINTER_ID && wasBusy && !state.busy && state.messages.lastOrNull()?.parts?.any { it.toolName == "screen_control" } == true) {
                        applyExpandedState(true)
                    }
                    wasBusy = state.busy
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE) { stopSelf(); return START_NOT_STICKY }
        val target = intent?.getStringExtra("session_id").orEmpty()
        if (target.isBlank() || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (id != target) { draft = ""; error = "" }
        id = target
        FloatingSessionHub.select(this, id)
        ensureNotification()
        if (view == null) installWindow()
        applyExpandedState(true)
        return START_NOT_STICKY
    }

    private fun installWindow() {
        val root = ComposeView(this)
        val host = object : android.widget.FrameLayout(this@AgentFloatingService) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // Chrome and resize own complete native streams, unaffected by Compose density or focus.
                if (dispatchChromeTouch(event)) return true
                if (dispatchResizeTouch(event)) return true
                return super.dispatchTouchEvent(event)
            }
        }
        host.setViewTreeLifecycleOwner(this)
        host.setViewTreeViewModelStoreOwner(this)
        host.setViewTreeSavedStateRegistryOwner(this)
        host.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        container = host
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        root.setContent {
            val deviceDensity = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Change real dp/sp conversion, not just clip or squeeze a full-size layout.
                val uiScale = if (expanded) minOf(maxWidth.value / 340f, maxHeight.value / 440f).coerceIn(0.48f, 1f) else 0.85f
                val floatingDensity = Density(deviceDensity.density * uiScale, deviceDensity.fontScale.coerceIn(0.85f, 1.15f))
                CompositionLocalProvider(LocalDensity provides floatingDensity,
                    LocalMinimumInteractiveComponentSize provides 0.dp) {
            AgentTheme {
                val snapshot by FloatingSessionHub.state.collectAsState()
                val current = if (snapshot.sessionId == id) snapshot else FloatingSessionHub.Snapshot(sessionId = id)
                if (!expanded) {
                    Surface(onClick = { applyExpandedState(true) }, shape = RoundedCornerShape(22.dp), color = AColors.Accent,
                        modifier = Modifier.fillMaxSize().recordChromeBounds("bubble")) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("AG", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(if (current.busy) "  工作中" else "  对话", color = Color.White, fontSize = 12.sp)
                        }
                    }
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = AColors.Paper,
                        border = BorderStroke(1.dp, AColors.Accent.copy(alpha = 0.4f)), shadowElevation = 12.dp) {
                        Column {
                            Row(Modifier.fillMaxWidth().recordChromeBounds("header").background(Brush.horizontalGradient(listOf(AColors.Accent, AColors.Violet)))
                                .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).height(36.dp).recordChromeBounds("drag"), verticalAlignment = Alignment.CenterVertically) {
                                    Text("AG", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    Text(current.title, color = Color.White, fontSize = 13.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                                }
                                HeaderButton("↗", "打开完整对话", "open") { openFullChat() }
                                HeaderButton("−", "收起小窗", "collapse") { applyExpandedState(false) }
                                HeaderButton("×", "关闭小窗", "close") { stopSelf() }
                            }
                            CompositionLocalProvider(LocalFloatingToolDetails provides false) {
                                key(id) {
                                    AgentChatMessages(messages = current.messages,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        streaming = current.busy,
                                        hasOlderHistory = current.hasOlder,
                                        onLoadOlder = { FloatingSessionHub.loadOlder(this@AgentFloatingService) },
                                        listBackground = AColors.Paper)
                                }
                            }
                            if (error.isNotBlank() || !current.connected) {
                                Text(error.ifBlank { "原对话已释放，点 ↗ 回到对话重新连接" }, color = AColors.Danger,
                                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
                            }
                            CompactFloatingComposer(value = draft, onValueChange = { draft = it }, busy = current.busy,
                                onSend = {
                                    val text = draft.trim()
                                    if (text.isNotEmpty()) {
                                        if (FloatingSessionHub.send(id, text)) { draft = ""; error = ""; hideKeyboard() }
                                        else error = "请先打开完整对话检查当前任务"
                                    }
                                },
                                onStop = { FloatingSessionHub.stop(id) })
                            Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (current.busy) "生成中" else "拖动右下角缩放",
                                    color = AColors.Muted, fontSize = 8.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                                Box(Modifier.width(40.dp).fillMaxHeight().onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInRoot()
                                    resizeHitRect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
                                }, contentAlignment = Alignment.Center) { Text("◢", color = AColors.Accent, fontSize = 16.sp) }
                            }
                        }
                    }
                }
            }
                }
            }
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        runCatching { windows.addView(host, params) }.onSuccess {
            view = root
            active = true
            FloatingSessionHub.showing = true
            host.visibility = if (yielding) View.INVISIBLE else View.VISIBLE
        }.onFailure { Toast.makeText(this, "小窗开启失败：${it.message}", Toast.LENGTH_LONG).show(); stopSelf() }
    }

    @Composable private fun HeaderButton(text: String, description: String, hitKey: String, action: () -> Unit) {
        // onClick remains for accessibility actions; physical touch is routed by the native host.
        TextButton(onClick = action, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(28.dp).recordChromeBounds(hitKey).semantics { contentDescription = description }) {
            Text(text, color = Color.White, fontSize = 17.sp)
        }
    }
    private fun Modifier.recordChromeBounds(key: String): Modifier = onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        chromeHitRects.getOrPut(key) { RectF() }.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    private fun dispatchChromeTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelChromeTouch()
            if (yielding || resizePointerId != MotionEvent.INVALID_POINTER_ID) return false
            val key = if (expanded) {
                listOf("close", "collapse", "open", "drag", "header").firstOrNull {
                    chromeHitRects[it]?.contains(event.x, event.y) == true
                }
            } else "bubble".takeIf { chromeHitRects[it]?.contains(event.x, event.y) == true }
            if (key == null) return false
            chromeAction = key
            chromePointerId = event.getPointerId(0)
            chromeStartRawX = event.rawX
            chromeStartRawY = event.rawY
            chromeStartX = params.x
            chromeStartY = params.y
            chromeMoved = false
            chromeDownRect.set(chromeHitRects.getValue(key))
            return true
        }
        if (chromePointerId == MotionEvent.INVALID_POINTER_ID) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(chromePointerId)
                if (index < 0) { cancelChromeTouch(); return true }
                val rawX = event.rawX + event.getX(index) - event.x
                val rawY = event.rawY + event.getY(index) - event.y
                updateChromeDrag(rawX, rawY)
            }
            MotionEvent.ACTION_UP -> {
                val key = chromeAction
                updateChromeDrag(event.rawX, event.rawY)
                val clicked = !chromeMoved && chromeDownRect.contains(event.x, event.y)
                // Clear ownership before a click resizes/removes the window.
                cancelChromeTouch()
                saveBounds()
                if (clicked) when (key) {
                    "close" -> stopSelf()
                    "collapse" -> applyExpandedState(false)
                    "open" -> openFullChat()
                    "bubble" -> applyExpandedState(true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == chromePointerId) {
                    cancelChromeTouch(); saveBounds()
                }
            }
            MotionEvent.ACTION_CANCEL -> { cancelChromeTouch(); saveBounds() }
        }
        return true
    }
    private fun updateChromeDrag(rawX: Float, rawY: Float) {
        val dx = rawX - chromeStartRawX
        val dy = rawY - chromeStartRawY
        if (kotlin.math.abs(dx) > chromeTouchSlop || kotlin.math.abs(dy) > chromeTouchSlop) chromeMoved = true
        if (chromeMoved && chromeAction in setOf("drag", "header", "bubble")) {
            params.x = chromeStartX + dx.roundToInt()
            params.y = chromeStartY + dy.roundToInt()
            clampBounds(); updateWindow()
        }
    }
    private fun cancelChromeTouch() {
        chromePointerId = MotionEvent.INVALID_POINTER_ID
        chromeAction = null
        chromeMoved = false
    }
    private fun dispatchResizeTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resizePointerId = MotionEvent.INVALID_POINTER_ID
            if (!expanded || yielding || resizeHitRect.isEmpty) return false
            // Fixed device-density hit slop remains usable even at the smallest UI scale.
            val hit = RectF(resizeHitRect).apply { inset(-dp(8).toFloat(), -dp(8).toFloat()) }
            if (!hit.contains(event.x, event.y)) return false
            resizePointerId = event.getPointerId(0)
            resizeStartRawX = event.rawX
            resizeStartRawY = event.rawY
            resizeStartWidth = frameWidth
            resizeStartHeight = frameHeight
            return true
        }
        if (resizePointerId == MotionEvent.INVALID_POINTER_ID) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(resizePointerId)
                if (index >= 0) {
                    // Works before API 29 too; avoids getRawX(index)'s newer API requirement.
                    val rawX = event.rawX + event.getX(index) - event.x
                    val rawY = event.rawY + event.getY(index) - event.y
                    resizeTo(resizeStartWidth + (rawX - resizeStartRawX).roundToInt(),
                        resizeStartHeight + (rawY - resizeStartRawY).roundToInt())
                } else finishResizeTouch()
            }
            MotionEvent.ACTION_UP -> {
                resizeTo(resizeStartWidth + (event.rawX - resizeStartRawX).roundToInt(),
                    resizeStartHeight + (event.rawY - resizeStartRawY).roundToInt())
                finishResizeTouch()
            }
            MotionEvent.ACTION_CANCEL -> finishResizeTouch()
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == resizePointerId) finishResizeTouch()
            }
        }
        // Never re-hit-test moving handle bounds during an owned gesture.
        return true
    }
    private fun finishResizeTouch() {
        resizePointerId = MotionEvent.INVALID_POINTER_ID
        saveBounds()
    }
    private fun resizeTo(width: Int, height: Int) {
        val metrics = resources.displayMetrics
        val nextWidth = width.coerceIn(dp(150).coerceAtMost(metrics.widthPixels), metrics.widthPixels)
        val nextHeight = height.coerceIn(dp(180).coerceAtMost(metrics.heightPixels),
            (metrics.heightPixels * 0.9f).toInt().coerceAtLeast(dp(180).coerceAtMost(metrics.heightPixels)))
        if (frameWidth == nextWidth && frameHeight == nextHeight) return
        frameWidth = nextWidth
        frameHeight = nextHeight
        params.width = frameWidth; params.height = frameHeight
        clampBounds(); updateWindow()
    }
    private fun applyExpandedState(value: Boolean) {
        expanded = value
        if (!value) hideKeyboard()
        setWindowFocusable(value && !yielding)
        params.width = if (value) frameWidth else dp(140)
        params.height = if (value) frameHeight else dp(48)
        clampBounds(); updateWindow()
    }
    private fun clampBounds() {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24), (metrics.heightPixels - dp(60)).coerceAtLeast(dp(24)))
    }
    private fun updateWindow() { container?.let { runCatching { windows.updateViewLayout(it, params) } } }
    private fun setWindowFocusable(focusable: Boolean) {
        val next = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (params.flags == next) return
        params.flags = next
        updateWindow()
    }
    private fun hideKeyboard() {
        view?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        // Never change window flags in the middle of a pointer stream.
    }
    private fun saveBounds() {
        getSharedPreferences("agent_floating", MODE_PRIVATE).edit()
            .putBoolean("compact_v2", true).putInt("x", params.x).putInt("y", params.y).putInt("width", frameWidth).putInt("height", frameHeight).apply()
    }
    private fun openFullChat() {
        hideKeyboard()
        runCatching {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("session_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onSuccess { stopSelf() }.onFailure {
            error = "打开完整对话失败：${it.message}"
        }
    }
    private fun ensureNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "对话小窗", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, NOTICE, Intent(this, AgentFloatingService::class.java).setAction(CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTICE, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Workspace 对话小窗").setContentText("可发送消息、停止生成和调整窗口大小")
            .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭小窗", stop).build())
    }
    override fun onDestroy() {
        active = false
        FloatingSessionHub.showing = false
        scope.cancel()
        if (::params.isInitialized) saveBounds()
        container?.let { runCatching { windows.removeViewImmediate(it) } }
        view?.disposeComposition()
        container = null
        view = null
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}