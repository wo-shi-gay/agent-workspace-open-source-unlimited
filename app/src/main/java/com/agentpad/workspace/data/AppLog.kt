package com.agentpad.workspace.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内日志中心（只收本 APK 自己的日志）。
 *
 * 设计目标：
 *  - 低负载：内存环形缓冲（默认 800 条）+ 异步批量落盘（单线程、每 3 秒最多一次）
 *  - 无阻塞：调用方（任意线程）只做入队 + 通知，绝不等待磁盘
 *  - 后台持续：由 Application 启动，不依赖任何界面
 *  - 覆盖范围：崩溃 / 未捕获异常 / 网络请求（含成功）/ 手动 info/warn/error
 *
 * 落盘位置：filesDir/logs/app.log（滚动，单文件上限 512KB，保留 2 份）
 */
object AppLog {

    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    private const val MAX_MEMORY = 800          // 内存保留条数
    private const val MAX_FILE_BYTES = 512 * 1024L  // 单文件 512KB
    private const val FLUSH_INTERVAL_MS = 3000L     // 最多 3 秒落盘一次

    data class Entry(
        val timeMs: Long,
        val level: Int,
        val tag: String,
        val message: String,
    ) {
        fun format(): String {
            val lv = when (level) {
                LEVEL_DEBUG -> "D"
                LEVEL_INFO -> "I"
                LEVEL_WARN -> "W"
                else -> "E"
            }
            return "${fmt(timeMs)} [$lv] $tag: $message"
        }
    }

    private val buffer = ArrayDeque<Entry>(MAX_MEMORY)
    private val dirty = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** 落盘线程（单线程，低优先级） */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog-io").apply { priority = Thread.MIN_PRIORITY }
    }

    /** 监听器（界面订阅实时刷新） */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Entry) -> Unit>()

    private lateinit var logDir: File
    private var lastFlush = 0L

    // ---------- 启动 ----------

    /** 由 Application.onCreate 调用，只执行一次。 */
    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        logDir = File(ctx.filesDir, "logs").apply { mkdirs() }
        // 启动时把上次的日志载入内存（供用户查看历史）
        runCatching { loadTailFromFile() }
        i("AppLog", "日志中心已启动 v${appVersion(ctx)}")
    }

    // ---------- 写入接口 ----------

    fun d(tag: String, msg: String) = log(LEVEL_DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LEVEL_INFO, tag, msg)
    fun w(tag: String, msg: String) = log(LEVEL_WARN, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val text = if (tr == null) msg else msg + "\n" + stackOf(tr)
        log(LEVEL_ERROR, tag, text)
    }

    fun log(level: Int, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            if (buffer.size >= MAX_MEMORY) buffer.pollFirst()
            buffer.addLast(entry)
        }
        // 同步镜像到 logcat（便于开发调试，不额外耗资源）
        runCatching {
            when (level) {
                LEVEL_ERROR -> Log.e("AW/$tag", message)
                LEVEL_WARN -> Log.w("AW/$tag", message)
                LEVEL_DEBUG -> Log.d("AW/$tag", message)
                else -> Log.i("AW/$tag", message)
            }
        }
        dirty.set(true)
        scheduleFlush()
        listeners.forEach { runCatching { it(entry) } }
    }

    /** 记录一次网络请求（无论成功失败）。 */
    fun net(method: String, url: String, code: Int, costMs: Long, note: String = "") {
        val shortUrl = url.take(200)
        val ok = code in 200..299
        val msg = "$method $shortUrl -> $code (${costMs}ms)" + if (note.isNotBlank()) " [$note]" else ""
        log(if (ok) LEVEL_INFO else LEVEL_WARN, "NET", msg)
    }

    /** 记录未捕获异常（崩溃）。 */
    fun crash(thread: Thread, tr: Throwable) {
        e("CRASH", "未捕获异常于线程 ${thread.name}", tr)
        flushNow()
    }

    // ---------- 读取 ----------

    /** 快照当前内存日志（新 → 旧或旧 → 新由调用方决定顺序）。 */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        runCatching { File(logDir, "app.log").writeText("") }
        i("AppLog", "日志已清空")
    }

    /** 导出为纯文本（用于分享 / 复制）。 */
    fun exportText(): String = snapshot().joinToString("\n") { it.format() }

    // ---------- 实时订阅 ----------

    fun addListener(l: (Entry) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Entry) -> Unit) { listeners.remove(l) }

    // ---------- 落盘（节流 + 异步） ----------

    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        if (now - lastFlush < FLUSH_INTERVAL_MS) return
        lastFlush = now
        ioExecutor.execute { runCatching { flushNow() } }
    }

    @Synchronized
    fun flushNow() {
        if (!dirty.compareAndSet(true, false)) return
        if (!::logDir.isInitialized) return
        val file = File(logDir, "app.log")
        runCatching {
            // 超过上限时滚动
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val bak = File(logDir, "app.log.1")
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            // 只追写新增部分（简化：取最后 200 条）
            val tail = synchronized(buffer) { buffer.toList().takeLast(200) }
            file.appendText(tail.joinToString("\n") { it.format() } + "\n", Charsets.UTF_8)
        }
    }

    private fun loadTailFromFile() {
        val f = File(logDir, "app.log")
        if (!f.exists()) return
        runCatching {
            f.readLines().takeLast(MAX_MEMORY).forEach { line ->
                // 简单解析：时间 [级别] tag: msg
                val m = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3}) \[(\w)] ([^:]+): (.*)$""").find(line)
                if (m != null) {
                    val lv = when (m.groupValues[2]) {
                        "D" -> LEVEL_DEBUG
                        "I" -> LEVEL_INFO
                        "W" -> LEVEL_WARN
                        else -> LEVEL_ERROR
                    }
                    synchronized(buffer) {
                        if (buffer.size >= MAX_MEMORY) buffer.pollFirst()
                        buffer.addLast(Entry(System.currentTimeMillis(), lv, m.groupValues[3], m.groupValues[4]))
                    }
                }
            }
        }
    }

    // ---------- 工具 ----------

    private fun fmt(ms: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun stackOf(tr: Throwable): String {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("?")
}