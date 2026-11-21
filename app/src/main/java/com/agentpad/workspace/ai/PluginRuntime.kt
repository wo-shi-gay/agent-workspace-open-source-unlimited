package com.agentpad.workspace.ai

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.widget.Toast
import com.agentpad.workspace.data.PluginStore
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory as RhinoContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.Base64

/**
 * 插件 JS 运行时（路由 B）。
 *
 * 用 Rhino 在宿主进程内跑插件的 main.js，注入一个受限的 `host` API 对象，
 * 让插件能调用**系统级能力**（文件 / 网络 / 终端 / 通知 / 剪贴板 / 短信 / 联系人 / 定位 …），
 * 而不是只能跑终端脚本。
 *
 * 安全边界：
 *  - 插件代码在 Rhino 沙箱里跑（ClassShutter 禁一切 Java 类），拿不到 Android 类；
 *  - **硬超时中断**：自定义 ContextFactory 的 observeInstructionCount 每 N 条指令检查一次，
 *    超时抛 Error 强制中断，死循环也能杀掉；阻塞型 host 调用（http/shell/sleep）也各自检查 deadline；
 *  - `host` 里的每个能力都按插件声明的 permissions 门控；设备能力还要宿主运行时权限。
 *
 * 插件 JS 约定：
 *   function run(args, host) { ... return "结果字符串或对象"; }
 */
object PluginRuntime {

    /** 单次执行硬超时（毫秒）。 */
    private const val EXEC_TIMEOUT_MS = 30_000L

    /** 每多少条 Rhino 指令检查一次超时。 */
    private const val INSTRUCTION_CHECK_INTERVAL = 10_000

    /** 内部：超时信号（Rhino 要求 observeInstructionCount 抛 Error）。 */
    private class PluginTimeoutError : Error("插件执行超时")

    /** 执行结果。 */
    data class RunResult(val ok: Boolean, val output: String)

    private val hostLogs = java.util.Collections.synchronizedList(mutableListOf<String>())

    /**
     * 带硬超时的 Rhino ContextFactory。
     * observeInstructionCount 会被 Rhino 按 instructionObserverThreshold 的间隔回调，
     * 一旦超过 deadline 就抛 Error，直接中断正在执行的 JS。
     */
    private class TimeoutContextFactory : RhinoContextFactory() {
        @Volatile var deadlineMs: Long = Long.MAX_VALUE
        override fun makeContext(): RhinoContext {
            val cx = super.makeContext()
            cx.optimizationLevel = -1                       // 解释模式
            cx.languageVersion = RhinoContext.VERSION_ES6
            return cx
        }
        override fun observeInstructionCount(cx: RhinoContext, instructionCount: Int) {
            if (System.currentTimeMillis() > deadlineMs) throw PluginTimeoutError()
        }
        override fun hasFeature(cx: RhinoContext, featureIndex: Int): Boolean {
            if (featureIndex == RhinoContext.FEATURE_ENHANCED_JAVA_ACCESS) return false
            return super.hasFeature(cx, featureIndex)
        }
    }

    /**
     * 执行一个 JS 插件。
     * @param args AI 传入的参数（JSONObject）
     * @param workspace 当前会话工作区（host.fs 的根）
     * @param timeoutMs 可选超时覆盖
     */
    fun runJsPlugin(
        context: Context,
        plugin: PluginStore.Installed,
        entrySource: String,
        args: JSONObject,
        workspace: File,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
    ): RunResult {
        val manifest = plugin.manifest
        val permSet = manifest.permissions.toSet()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 300_000L)

        val factory = TimeoutContextFactory().apply { deadlineMs = deadline }
        return try {
            val cx = factory.enterContext()
            try {
                cx.instructionObserverThreshold = INSTRUCTION_CHECK_INTERVAL
                cx.setClassShutter(ClassShutter { false })   // 任何 Java 类都不可见

                val scope = cx.initStandardObjects()

                hostLogs.clear()
                val host = buildHost(context, plugin, permSet, workspace, args, deadline)
                ScriptableObject.putProperty(scope, "host", host)

                val console = cx.newObject(scope)
                val logFn = object : org.mozilla.javascript.BaseFunction() {
                    override fun call(c: RhinoContext, sc: Scriptable, t: Scriptable, a: Array<Any?>): Any? {
                        hostLogs.add(a.joinToString(" ") { RhinoContext.toString(it) })
                        return RhinoContext.getUndefinedValue()
                    }
                }
                ScriptableObject.putProperty(console, "log", logFn)
                ScriptableObject.putProperty(console, "error", logFn)
                ScriptableObject.putProperty(scope, "console", console)

                // 执行入口源码（定义 run 函数）
                cx.evaluateString(scope, entrySource, manifest.entry, 1, null)

                val runFn = ScriptableObject.getProperty(scope, "run")
                if (runFn !is Function) return RunResult(false, "插件未定义 run(args, host) 函数")

                val argsObj = jsonToNative(scope, args)
                if (System.currentTimeMillis() > deadline) throw PluginTimeoutError()
                val resultValue = runFn.call(cx, scope, scope, arrayOf(argsObj, host))

                val out = renderResult(resultValue)
                val logs = hostLogs.joinToString("\n")
                val combined = buildString {
                    if (logs.isNotBlank()) append(logs).append("\n")
                    append(out)
                }.trim()
                RunResult(true, combined.ifBlank { "(插件无返回值)" })
            } finally {
                RhinoContext.exit()
            }
        } catch (e: PluginTimeoutError) {
            RunResult(false, "插件执行超时（>${timeoutMs / 1000} 秒），已强制中断")
        } catch (e: Error) {
            if (e.cause is PluginTimeoutError || e.message?.contains("超时") == true) {
                RunResult(false, "插件执行超时，已强制中断")
            } else RunResult(false, "插件执行失败：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            RunResult(false, "插件执行失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            hostLogs.clear()
        }
    }

    /** 把 JSONObject 递归转成 Rhino NativeObject。 */
    private fun jsonToNative(scope: Scriptable, o: JSONObject): NativeObject {
        val obj = NativeObject()
        for (k in o.keys()) {
            val v = o.get(k)
            val value: Any? = when (v) {
                is JSONObject -> jsonToNative(scope, v)
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until v.length()) list.add(v.get(i))
                    RhinoContext.javaToJS(list, scope)
                }
                else -> v
            }
            ScriptableObject.putProperty(obj, k, value)
        }
        return obj
    }

    private fun renderResult(value: Any?): String = when (value) {
        null, org.mozilla.javascript.Undefined.instance -> ""
        is NativeObject -> runCatching { RhinoContext.toString(value) }.getOrDefault("")
        else -> runCatching { RhinoContext.toString(value) }.getOrDefault(value?.toString() ?: "")
    }

    // ============================================================
    //                     host API 构建
    // ============================================================

    private fun buildHost(
        context: Context,
        plugin: PluginStore.Installed,
        perms: Set<String>,
        workspace: File,
        args: JSONObject,
        deadline: Long,
    ): NativeObject {
        val host = NativeObject()

        fun checkDeadline() {
            if (System.currentTimeMillis() > deadline) throw PluginTimeoutError()
        }
        fun require(perm: String) {
            if (!perms.contains(perm)) throw IllegalStateException("插件未声明权限：$perm")
        }
        fun put(name: String, fn: (Array<Any?>) -> Any?) {
            ScriptableObject.putProperty(host, name, object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: RhinoContext, sc: Scriptable, thisObj: Scriptable, a: Array<Any?>): Any? {
                    return runCatching { checkDeadline(); fn(a) }
                        .getOrElse { err(it.message ?: "调用失败") }
                }
            })
        }
        fun putOn(target: NativeObject, name: String, fn: (Array<Any?>) -> Any?) {
            ScriptableObject.putProperty(target, name, object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: RhinoContext, sc: Scriptable, thisObj: Scriptable, a: Array<Any?>): Any? {
                    return runCatching { checkDeadline(); fn(a) }
                        .getOrElse { err(it.message ?: "调用失败") }
                }
            })
        }

        // ---- host.fs：工作区文件（根 = 当前会话工作区）----
        val fs = NativeObject()
        putOn(fs, "read") { a ->
            require("fs.read")
            val rel = RhinoContext.toString(a.getOrNull(0))
            val f = resolveInWorkspace(workspace, rel) ?: throw IllegalStateException("非法路径：$rel")
            if (!f.isFile) throw IllegalStateException("文件不存在：$rel")
            f.readText()
        }
        putOn(fs, "write") { a ->
            require("fs.write")
            val rel = RhinoContext.toString(a.getOrNull(0))
            val content = RhinoContext.toString(a.getOrNull(1))
            val f = resolveInWorkspace(workspace, rel) ?: throw IllegalStateException("非法路径：$rel")
            f.parentFile?.mkdirs(); f.writeText(content); "OK"
        }
        putOn(fs, "exists") { a ->
            require("fs.read")
            val rel = RhinoContext.toString(a.getOrNull(0))
            (resolveInWorkspace(workspace, rel)?.exists()) ?: false
        }
        putOn(fs, "list") { a ->
            require("fs.read")
            val rel = RhinoContext.toString(a.getOrNull(0)).ifBlank { "." }
            val dir = resolveInWorkspace(workspace, rel) ?: throw IllegalStateException("非法路径：$rel")
            (dir.listFiles() ?: emptyArray()).joinToString("\n") {
                (if (it.isDirectory) "[D] " else "[F] ") + it.name
            }
        }
        putOn(fs, "mkdir") { a ->
            require("fs.write")
            val rel = RhinoContext.toString(a.getOrNull(0))
            val f = resolveInWorkspace(workspace, rel) ?: throw IllegalStateException("非法路径：$rel")
            f.mkdirs(); "OK"
        }
        putOn(fs, "delete") { a ->
            require("fs.write")
            val rel = RhinoContext.toString(a.getOrNull(0))
            val f = resolveInWorkspace(workspace, rel) ?: throw IllegalStateException("非法路径：$rel")
            if (f.isDirectory) f.deleteRecursively() else f.delete(); "OK"
        }
        ScriptableObject.putProperty(host, "fs", fs)

        // ---- host.http：宿主网络层 ----
        val http = NativeObject()
        putOn(http, "get") { a ->
            require("net.http")
            httpRequest(RhinoContext.toString(a.getOrNull(0)), "GET", null, null)
        }
        putOn(http, "post") { a ->
            require("net.http")
            val body = if (a.size > 1) RhinoContext.toString(a[1]) else ""
            httpRequest(RhinoContext.toString(a.getOrNull(0)), "POST", body, null)
        }
        putOn(http, "request") { a ->
            require("net.http")
            val opts = a.getOrNull(0)
            val json = if (opts is NativeObject) nativeToJson(opts) else JSONObject()
            httpRequestFull(
                json.optString("url"),
                json.optString("method", "GET").uppercase(),
                json.optString("body", "").ifBlank { null },
                json.optJSONObject("headers"),
            )
        }
        ScriptableObject.putProperty(host, "http", http)

        // ---- host.shellExec：沙箱终端命令 ----
        put("shellExec") { a ->
            require("shell")
            val cmd = RhinoContext.toString(a.getOrNull(0))
            SandboxRunner.runOnceBlocking(context, cmd, workspace) ?: "(无输出)"
        }

        // ---- host.notify：系统通知 ----
        put("notify") { a ->
            require("notify")
            postNotification(context, RhinoContext.toString(a.getOrNull(0)),
                if (a.size > 1) RhinoContext.toString(a[1]) else "")
            "OK"
        }

        // ---- host.toast：弹提示（无需权限）----
        put("toast") { a ->
            val msg = RhinoContext.toString(a.getOrNull(0))
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            }
            "OK"
        }

        // ---- host.clipboard ----
        put("clipboardRead") {
            require("clipboard")
            clipboard(context).getText()?.toString() ?: ""
        }
        put("clipboardWrite") { a ->
            require("clipboard")
            val txt = RhinoContext.toString(a.getOrNull(0))
            Handler(Looper.getMainLooper()).post { runCatching { clipboard(context).setText(txt) } }
            "OK"
        }

        // ---- host.device：设备能力（需运行时权限）----
        val device = NativeObject()
        putOn(device, "sms") { a ->
            require("device.sms")
            requirePerm(context, Manifest.permission.SEND_SMS, "发送短信")
            val number = RhinoContext.toString(a.getOrNull(0))
            val text = if (a.size > 1) RhinoContext.toString(a[1]) else ""
            @Suppress("DEPRECATION")
            val mgr = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            mgr?.sendTextMessage(number, null, text, null, null)
                ?: throw IllegalStateException("短信服务不可用")
            "SMS_SENT"
        }
        putOn(device, "contacts") { a ->
            require("device.contacts")
            requirePerm(context, Manifest.permission.READ_CONTACTS, "读取联系人")
            readContacts(context, if (a.isNotEmpty()) RhinoContext.toString(a[0]) else "")
        }
        putOn(device, "location") {
            require("device.location")
            requirePerm(context, Manifest.permission.ACCESS_FINE_LOCATION, "获取定位")
            lastLocation(context)
        }
        putOn(device, "info") {
            JSONObject().put("model", Build.MODEL).put("brand", Build.BRAND)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).toString()
        }
        ScriptableObject.putProperty(host, "device", device)

        // ---- 工具函数（无需权限）----
        put("time") { System.currentTimeMillis() }
        put("random") { Math.random() }
        put("sleep") { a ->
            val ms = RhinoContext.toString(a.getOrNull(0)).toDoubleOrNull()?.toLong() ?: 0L
            val end = System.currentTimeMillis() + ms.coerceIn(0L, 30_000L)
            while (System.currentTimeMillis() < end) { checkDeadline(); Thread.sleep(50) }
            "OK"
        }
        put("base64Encode") { a ->
            Base64.getEncoder().encodeToString(RhinoContext.toString(a.getOrNull(0)).toByteArray(Charsets.UTF_8))
        }
        put("base64Decode") { a ->
            String(Base64.getDecoder().decode(RhinoContext.toString(a.getOrNull(0))), Charsets.UTF_8)
        }
        put("jsonParse") { a ->
            jsonToNative(ScriptableObject.getTopLevelScope(host), JSONObject(RhinoContext.toString(a.getOrNull(0))))
        }
        put("jsonStringify") { a ->
            val v = a.getOrNull(0)
            if (v is NativeObject) nativeToJson(v).toString() else RhinoContext.toString(v)
        }

        return host
    }

    // ============================================================
    //                     工具实现
    // ============================================================

    private fun clipboard(context: Context): android.content.ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    private fun err(msg: String?): String = "ERROR: ${msg ?: "unknown"}"

    private fun requirePerm(context: Context, perm: String, label: String) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) throw IllegalStateException("缺少「$label」权限，请在系统设置中授予本应用该权限后再试")
    }

    private fun postNotification(context: Context, title: String, text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val channelId = "plugin_channel"
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(channelId, "插件通知", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val builder = if (Build.VERSION.SDK_INT >= 26)
                    android.app.Notification.Builder(context, channelId)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
                builder.setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                nm.notify(System.currentTimeMillis().toInt(), builder.build())
            }
        }
    }

    private fun readContacts(context: Context, keyword: String): String {
        val out = StringBuilder()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                if (out.length > 8000) break
                val name = c.getString(nameIdx) ?: ""
                val number = c.getString(numIdx) ?: ""
                if (keyword.isBlank() || name.contains(keyword) || number.contains(keyword)) {
                    out.append(name).append(": ").append(number).append("\n")
                }
            }
        }
        return out.toString().trim().ifBlank { "(无匹配联系人)" }
    }

    private fun lastLocation(context: Context): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = runCatching { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) }.getOrNull()
        return if (loc != null) "${loc.latitude},${loc.longitude}" else "(暂无已知位置)"
    }

    /** 把相对路径限制在工作区内，禁止越界。 */
    private fun resolveInWorkspace(workspace: File, rel: String): File? {
        return runCatching {
            val root = workspace.canonicalFile
            val target = File(root, rel).canonicalFile
            if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
        }.getOrNull()
    }

    /** 简单 HTTP 请求（阻塞）→ 返回响应文本。 */
    private fun httpRequest(urlStr: String, method: String, body: String?, headers: JSONObject?): String {
        val res = httpRequestFull(urlStr, method, body, headers)
        val o = runCatching { JSONObject(res) }.getOrNull() ?: return res
        return o.optString("body")
    }

    /** 完整 HTTP 请求 → JSON 字符串 {status, body}。 */
    private fun httpRequestFull(urlStr: String, method: String, body: String?, headers: JSONObject?): String {
        return runCatching {
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000; readTimeout = 30_000
                if (method != "GET" && method != "HEAD") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                headers?.let { h -> h.keys().forEach { k -> setRequestProperty(k, h.optString(k)) } }
            }
            if (method != "GET" && method != "HEAD" && body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            JSONObject().put("status", code).put("body", text).toString()
        }.getOrElse { JSONObject().put("status", 0).put("body", "请求失败：${it.message}").toString() }
    }

    /** Rhino NativeObject → org.json.JSONObject（浅层）。 */
    private fun nativeToJson(o: NativeObject): JSONObject {
        val json = JSONObject()
        for (id in o.ids) {
            val key = id.toString()
            val v = ScriptableObject.getProperty(o, key)
            json.put(key, when (v) {
                null, org.mozilla.javascript.Undefined.instance -> JSONObject.NULL
                is NativeObject -> nativeToJson(v)
                else -> v
            })
        }
        return json
    }
}
