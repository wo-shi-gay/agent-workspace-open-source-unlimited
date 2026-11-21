package com.agentpad.workspace.screencontrol

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 屏幕上一个可以操作或带文字的元素，[index] 在下一次 [ScreenController.captureScreen] 之前有效，坐标是屏幕像素 */
data class ScreenElement(
    val index: Int,
    val className: String,
    val label: String,
    /** 布局里的资源 ID 名（如 btn_send），应用没有设置或被系统隐藏时为空 */
    val resourceId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
)

data class ScreenSnapshot(
    val packageName: String?,
    val appLabel: String?,
    /** 这份元素来自哪些应用的窗口，[packageName] 只是最上层的那个 */
    val packages: Set<String>,
    val width: Int,
    val height: Int,
    val keyboardVisible: Boolean,
    val elements: List<ScreenElement>,
    /** 元素太多时只保留前面的一部分 */
    val truncated: Boolean,
)

data class ScreenApp(val packageName: String, val label: String)

enum class ScreenKey { BACK, HOME, RECENTS, NOTIFICATIONS, ENTER }

/**
 * 操作手机的底层能力，坐标都是屏幕像素。
 * 由本应用独立无障碍服务实现，用户在系统设置开启后连接。
 */
interface ScreenController {
    /**
     * 屏幕上所有可见窗口所属的应用，和 [captureScreen] 读取的窗口范围一致，不算本应用自己的悬浮窗。
     * 分屏或下拉通知栏时不止一个；只用来判断屏幕上有谁，不影响元素编号
     */
    suspend fun feedback(capture: Boolean = false) {}
    suspend fun clearFeedback() {}
    fun visiblePackages(): Set<String>

    /** 读取当前屏幕上的元素，不包含本应用自己的悬浮窗 */
    suspend fun captureScreen(): ScreenSnapshot

    /** JPEG 截图，长边不超过 [maxSide] */
    suspend fun screenshot(maxSide: Int): ByteArray

    /** 起点和终点相同时是点击，[durationMs] 决定是轻点还是长按 */
    suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean

    /** 把文字填进 [elementIndex] 指向的输入框；为空时填进当前有输入焦点的输入框 */
    suspend fun setText(elementIndex: Int?, text: String): Boolean

    suspend fun pressKey(key: ScreenKey): Boolean

    fun listApps(): List<ScreenApp>

    fun openApp(packageName: String): Boolean
}

/** 无障碍服务连接后把自己登记在这里，工具和设置页从这里拿 */
object ScreenControlManager {
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 26
    val allowed = MutableStateFlow(false)
    val stopNotificationReady = MutableStateFlow(false)
    val operationMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile var selfForeground = false
    @Volatile var revision = 0L
    @Volatile var activeJob: kotlinx.coroutines.Job? = null

    private val _controller = MutableStateFlow<ScreenController?>(null)
    val controller: StateFlow<ScreenController?> = _controller.asStateFlow()

    private val _acting = MutableStateFlow(false)

    /** 正在截图或执行手势，这期间悬浮窗要让出屏幕：不能出现在截图里，也不能挡住点击 */
    val acting: StateFlow<Boolean> = _acting.asStateFlow()

    fun attach(controller: ScreenController) {
        _controller.value = controller
    }

    fun detach(controller: ScreenController) {
        _controller.compareAndSet(controller, null)
    }

    fun setActing(acting: Boolean) {
        _acting.value = acting
    }
}
