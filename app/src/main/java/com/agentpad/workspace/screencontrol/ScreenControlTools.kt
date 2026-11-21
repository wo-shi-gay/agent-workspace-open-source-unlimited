package com.agentpad.workspace.screencontrol

// Action protocol adapted from RikkaHub 3.2.0 PhoneUseTool.kt.
import android.content.Context
import android.os.Looper
import android.util.Base64
import com.agentpad.workspace.ai.ToolRegistry
import com.agentpad.workspace.ai.ToolSpec
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object ScreenControlTools {
    fun register(registry: ToolRegistry, context: Context) {
        val session = ScreenActionSession(context)
        registry.registerRemote("screen_control", spec(context)) { args ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                JSONObject().put("success", false).put("error", "屏幕控制需要后台工具线程").toString()
            } else runCatching {
                runBlocking {
                    withTimeout(25000L) {
                        ScreenControlManager.activeJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                        try { session.execute(args) }
                        finally { ScreenControlManager.activeJob = null }
                    }
                }.toString()
            }.getOrElse {
                JSONObject().put("success", false).put("error", it.message ?: "屏幕控制执行失败").toString()
            }
        }
    }

    private fun spec(context: Context): ToolSpec {
        val advanced = android.os.Build.VERSION.SDK_INT >= 30
        val actions = mutableListOf("observe", "tap", "long_press", "swipe", "scroll", "type", "press", "open_app", "list_apps", "wait")
        actions.add("screenshot")
        val keys = mutableListOf("back", "home", "recents", "notifications")
        if (advanced) keys.add("enter")
        fun field(type: String) = JSONObject().put("type", type)
        fun coordinate() = field("number").put("minimum", 0).put("maximum", 1000)
        val properties = JSONObject()
            .put("action", field("string").put("enum", JSONArray(actions)))
            .put("element", field("integer").put("description", "Index from latest screen; valid until screen changes"))
            .put("x", coordinate()).put("y", coordinate()).put("to_x", coordinate()).put("to_y", coordinate())
            .put("direction", field("string").put("enum", JSONArray(listOf("up", "down", "left", "right"))))
            .put("text", field("string")).put("submit", field("boolean"))
            .put("key", field("string").put("enum", JSONArray(keys)))
            .put("app", field("string").put("description", "Launcher app name or package name"))
            .put("seconds", field("number").put("minimum", 0.5).put("maximum", 10))
        if (!advanced) properties.remove("submit")
        return ToolSpec("screen_control",
            "Operate the user's Android screen through the explicitly enabled accessibility service. " +
                "Start with open_app or press home, then observe. Act using indexes from the latest element list. " +
                "Each action returns the updated screen. Coordinates use a 1000 x 1000 grid, not pixels. " +
                "tap/long_press: element or x,y; swipe: x,y,to_x,to_y; scroll: direction and optional element; " +
                "type: replace text of element or focused input. " +
                "screenshot returns an image via user-granted screen content capture (MediaProjection), available on Android 8+. Ask the user to grant screen content permission in Settings > Screen Control when needed. " +
                (if (advanced) "submit=true presses enter. " else
                    "Android 8-10: after typing, tap the on-screen search/send button. ") +
                "This app itself and payment/banking apps are excluded. The user can stop control from the persistent notification. " +
                "Ask before irreversible actions unless the user already requested that exact action.",
            JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(listOf("action"))).toString())
    }
}

// Preserved from reference: protected apps stay outside screen reading and automation.
private val excludedApps = setOf("com.eg.android.AlipayGphone", "com.unionpay", "com.paypal.android.p2pmobile",
    "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.icbc", "com.chinamworld.main",
    "com.android.bankabc", "com.chinamworld.bocmbci", "com.bankcomm.Bankcomm", "cmb.pb", "com.yitong.mbank.psbc")

private class ScreenActionSession(private val context: Context) {
    private var lastScreen: ScreenSnapshot? = null
    private var lastRevision = -1L

    suspend fun execute(args: JSONObject): JSONObject = ScreenControlManager.operationMutex.withLock {
        ensureEnabled()
        check(ScreenControlManager.stopNotificationReady.value) { "请开启屏幕控制通知，通知栏需保留停止控制入口" }
        val controller = ScreenControlManager.controller.value ?: error("请在系统无障碍设置开启 Workspace 屏幕控制")
        val action = args.getString("action")
        when (action) {
            "list_apps" -> result(controller.listApps().filterNot { it.packageName in excludedApps }
                .joinToString("\n") { "${it.label} (${it.packageName})" })
            "open_app" -> {
                val query = args.getString("app")
                val apps = controller.listApps()
                val app = apps.find { it.packageName == query } ?: apps.find { it.label.equals(query, true) }
                    ?: apps.find { it.label.contains(query, true) } ?: error("找不到应用，请先用 list_apps")
                check(app.packageName != context.packageName && app.packageName !in excludedApps) { "此应用由用户手动操作" }
                ensureEnabled()
                check(controller.openApp(app.packageName)) { "应用启动失败" }
                delay(1500)
                describeCurrent(controller, "Opened ${app.label}")
            }
            "observe" -> { ensureOperable(controller); describeCurrent(controller) }
            "wait" -> {
                delay((args.optDouble("seconds", 2.0).coerceIn(0.5, 10.0) * 1000).toLong())
                ensureOperable(controller); describeCurrent(controller)
            }
            "screenshot" -> {
                ensureOperable(controller)
                val screen = capture(controller)
                controller.feedback(true)
                delay(220)
                val projectionReady = ScreenCaptureService.ready.value
                val bytes = hidingOverlays {
                    if (projectionReady) ScreenCaptureService.screenshot(1568)
                    else if (android.os.Build.VERSION.SDK_INT >= 30) controller.screenshot(1568)
                    else error("请到设置→屏幕控制→申请屏幕内容权限，确认系统获取屏幕内容授权后再截图")
                }
                ensureOperable(controller)
                controller.feedback(true)
                val directory = File(context.filesDir, "screen-captures").apply { mkdirs() }
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
                val file = File(directory, "screen-${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
                result(describe(screen)).put("image_path", file.absolutePath)
                    .put("_image_data_url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("capture_backend", if (projectionReady) "media_projection" else "accessibility")
                    .put("note", "Screenshot uses a 1000 x 1000 coordinate grid.")
            }
            "press" -> {
                val key = ScreenKey.entries.find { it.name.equals(args.getString("key"), true) } ?: error("key 参数错误")
                check(key != ScreenKey.ENTER || android.os.Build.VERSION.SDK_INT >= 30) { "当前系统请点击界面上的搜索或发送按钮，其他屏幕操作可正常使用" }
                when (key) {
                    ScreenKey.HOME -> Unit
                    ScreenKey.BACK -> check(!ScreenControlManager.selfForeground) { "请先用 open_app 或 home 离开本应用" }
                    else -> ensureOperable(controller)
                }
                act(controller, "press ${key.name.lowercase()}") { controller.pressKey(key) }
            }
            "tap", "long_press" -> {
                ensureOperable(controller)
                val (x, y) = targetPoint(controller, args)
                act(controller, action) { controller.gesture(x, y, x, y, if (action == "tap") 60L else 700L) }
            }
            "swipe" -> {
                ensureOperable(controller)
                val (x, y) = targetPoint(controller, args)
                val screen = lastScreen ?: capture(controller)
                val endX = grid(args.getDouble("to_x"), screen.width)
                val endY = grid(args.getDouble("to_y"), screen.height)
                act(controller, action) { controller.gesture(x, y, endX, endY, 350L) }
            }
            "scroll" -> {
                ensureOperable(controller)
                val screen = currentScreen(controller, args.has("element"))
                val element = if (args.has("element")) screen.element(args.getInt("element")) else null
                val width = element?.let { it.right - it.left } ?: screen.width
                val height = element?.let { it.bottom - it.top } ?: screen.height
                val x = (element?.left ?: 0) + width / 2f
                val y = (element?.top ?: 0) + height / 2f
                val dx = width * 0.3f; val dy = height * 0.3f
                val points = when (args.optString("direction", "down")) {
                    "down" -> listOf(x, y + dy, x, y - dy)
                    "up" -> listOf(x, y - dy, x, y + dy)
                    "right" -> listOf(x + dx, y, x - dx, y)
                    "left" -> listOf(x - dx, y, x + dx, y)
                    else -> error("direction 参数错误")
                }
                act(controller, action) { controller.gesture(points[0], points[1], points[2], points[3], 350L) }
            }
            "type" -> {
                ensureOperable(controller)
                val index = if (args.has("element")) args.getInt("element").also { currentScreen(controller, true).element(it) } else null
                val text = args.getString("text")
                val legacySubmit = args.optBoolean("submit", false) && android.os.Build.VERSION.SDK_INT < 30
                val response = act(controller, action) {
                    controller.setText(index, text) &&
                        (!args.optBoolean("submit", false) || legacySubmit || controller.pressKey(ScreenKey.ENTER))
                }
                if (legacySubmit) response.put("submitted", false).put("note", "文字已输入，请根据返回界面点击搜索或发送按钮完成提交")
                response
            }
            else -> error("未知屏幕控制动作：$action")
        }
    }

    private fun ensureEnabled() {
        check(ScreenControlNotificationService.notificationsAvailable(context)) { "请在系统设置开启屏幕控制通知" }
        check(ScreenControlSettings.enabled(context) && ScreenControlManager.allowed.value) { "屏幕控制已停止，请由用户在设置中重新开启" }
    }
    private fun ensureOperable(controller: ScreenController) {
        ensureEnabled()
        check(!ScreenControlManager.selfForeground) { "请先用 open_app 或 press home 离开本应用" }
        check(controller.visiblePackages().none { it in excludedApps }) { "当前界面由用户手动操作；可用 back 或 home 返回" }
    }
    private suspend fun capture(controller: ScreenController): ScreenSnapshot {
        val screen = controller.captureScreen()
        if (!ScreenControlManager.acting.value) controller.feedback()
        lastScreen = screen
        lastRevision = ScreenControlManager.revision
        return screen
    }
    private suspend fun currentScreen(controller: ScreenController, indexed: Boolean): ScreenSnapshot {
        if (indexed) check(lastScreen != null && lastRevision == ScreenControlManager.revision) { "元素编号已过期，请先 observe" }
        return lastScreen ?: capture(controller)
    }
    private suspend fun targetPoint(controller: ScreenController, args: JSONObject): Pair<Float, Float> {
        val screen = currentScreen(controller, args.has("element"))
        if (args.has("element")) {
            val item = screen.element(args.getInt("element"))
            return (item.left + item.right) / 2f to (item.top + item.bottom) / 2f
        }
        return grid(args.getDouble("x"), screen.width) to grid(args.getDouble("y"), screen.height)
    }
    private suspend fun act(controller: ScreenController, name: String, block: suspend () -> Boolean): JSONObject {
        val done = hidingOverlays(block)
        if (!done) return JSONObject().put("success", false).put("error", "$name 执行失败，请重新 observe")
        delay(800)
        return describeCurrent(controller, "$name done")
    }
    private suspend fun <T> hidingOverlays(block: suspend () -> T): T {
        ensureEnabled()
        ScreenControlManager.setActing(true)
        try {
            ScreenControlManager.controller.value?.clearFeedback()
            delay(160)
            ensureEnabled()
            return block()
        }
        finally { ScreenControlManager.setActing(false) }
    }
    private suspend fun describeCurrent(controller: ScreenController, note: String = ""): JSONObject {
        ensureEnabled()
        if (ScreenControlManager.selfForeground || controller.visiblePackages().any { it in excludedApps }) {
            lastScreen = null
            return result("$note\n界面已切换，请用 open_app 或 home 进入目标应用")
        }
        return result(note + "\n" + describe(capture(controller)))
    }
}

private fun result(text: String) = JSONObject().put("success", true).put("content", text.trim())
private fun grid(value: Double, pixels: Int): Float {
    require(value.isFinite() && value in 0.0..1000.0) { "坐标范围为0到1000" }
    return (value / 1000 * (pixels - 1).coerceAtLeast(0)).toFloat()
}
private fun ScreenSnapshot.element(index: Int): ScreenElement = elements.getOrNull(index) ?: error("元素编号已过期，请先 observe")
private fun describe(screen: ScreenSnapshot): String = buildString {
    appendLine("App: ${screen.appLabel.orEmpty()} (${screen.packageName.orEmpty()})")
    appendLine("Screen: ${screen.width}x${screen.height}; coordinates: 1000x1000 grid")
    if (screen.keyboardVisible) appendLine("Keyboard: visible")
    screen.elements.forEach { element ->
        append("[${element.index}] ${element.className} \"${element.label}\"")
        if (element.label.isEmpty()) element.resourceId?.let { append(" #$it") }
        if (element.clickable) append(" clickable")
        if (element.editable) append(" editable")
        if (element.scrollable) append(" scrollable")
        element.checked?.let { append(if (it) " checked" else " unchecked") }
        if (element.focused) append(" focused")
        appendLine(" @(${(element.left + element.right) / 2 * 1000 / screen.width.coerceAtLeast(1)},${(element.top + element.bottom) / 2 * 1000 / screen.height.coerceAtLeast(1)})")
    }
    if (screen.elements.isEmpty()) appendLine("No elements. Wait or use screenshot.")
    if (screen.truncated) appendLine("More elements omitted; scroll to see more.")
}.trimEnd()
