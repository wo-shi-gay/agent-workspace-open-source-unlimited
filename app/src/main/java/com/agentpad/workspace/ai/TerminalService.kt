package com.agentpad.workspace.ai

import java.io.File
import com.ai.assistance.operit.terminal.Pty
import com.agentpad.workspace.terminal.AnsiParser
import com.agentpad.workspace.terminal.AnsiScreen
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 持久共享 PTY：命令直接进入用户终端，保留 cwd/export 与手动交互。
 * 2026-11-18：提示符回归收尾，工具等待窗口上限 20 秒；无需退出码标记。
 * 等待到期只返回已有输出，保持前台任务，绝不自动发送 Ctrl+C。
 * success 表示工具交付输出；命令成败由输出与实际产物判断。
 */
object TerminalService {

    private class Session(
        val id: String,
        val title: String,
        val cwd: File,
        val pty: Pty,
        val screen: AnsiScreen,
        val parser: AnsiParser,
        val lock: Any = Any()
    ) {
        /** 有界的纯文本镜像（给 terminal_screen / 完成检测用）。 */
        val textBuf = StringBuilder(8192)

        /** 当前前台命令；工具提前返回后仍保留，直到提示符回归。 */
        @Volatile var pendingExec: ExecWaiter? = null

    }

    private class ExecWaiter {
        val latch = CountDownLatch(1)
        val out = StringBuilder()
        val startedNs = System.nanoTime()
        @Volatile var lastOutputNs = startedNs
        @Volatile var promptSeen = false
        @Volatile var interrupted = false
        @Volatile var reason = "shell_prompt"
    }

    /** shell 存活与命令运行分开；读屏时顺便收尾已回归的提示符。 */
    fun commandRunning(id: String): Boolean {
        val session = sessions[id] ?: return false
        val waiter = session.pendingExec ?: return false
        return !finishAtPrompt(session, waiter)
    }

    // 只匹配本 App 的 shell 提示符；单独的 #/$、BUILD SUCCESSFUL 和静默均非完成证据。
    private val promptPattern = Regex("(?:^|\\n)[^\\n]*[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+(?::[^\\n]*)?[#$] *$")
    private val ansiPattern = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\))")

    private fun finishAtPrompt(session: Session, waiter: ExecWaiter): Boolean = synchronized(session.lock) {
        val now = System.nanoTime()
        if (session.pendingExec === waiter && waiter.promptSeen &&
            now - waiter.lastOutputNs >= 750_000_000L && now - waiter.startedNs >= 1_000_000_000L) {
            session.pendingExec = null
            waiter.latch.countDown()
        }
        waiter.latch.count == 0L
    }

    private fun outputSnapshot(waiter: ExecWaiter, completed: Boolean, reason: String): TerminalOutput =
        TerminalOutput(
            if (waiter.interrupted) 130 else -1,
            synchronized(waiter.out) { waiter.out.toString() },
            if (waiter.interrupted) "interrupted by user (SIGINT)" else "",
            exitCodeKnown = waiter.interrupted,
            completed = completed,
            completionReason = reason,
        )

    /**
     * 文本镜像上限（字符）。超过就裁掉头部。
     * 以前用 `old.output + chunk` 累加，是 O(n²) 且无上限——
     * 几十 MB 的日志会让每次 publish 都整串拷贝，GC 抖动直接卡死 UI。
     */
    private const val TEXT_BUF_LIMIT = 256 * 1024

    /** 每条会话保留的原始字节上限。一个满屏 80x24 约 2KB，512KB 够翻很久。 */
    private const val RAW_HISTORY_LIMIT = 512 * 1024

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 旧的一次性通道在跑的命令（保留兜底用）。 */
    private val activeExec = ConcurrentHashMap<String, Pty>()

    /** 被用户 Ctrl+C 打断过的会话，用于给模型报 130。 */
    private val interruptedSessions = ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------- raw byte 旁路

    /** UI 订阅原始字节的出口。 */
    fun interface RawSink {
        fun onRawBytes(id: String, data: ByteArray, length: Int)
    }

    private val rawSinks = CopyOnWriteArrayList<RawSink>()

    /**
     * 原始字节要**无条件**记账，不能只在有订阅者时广播：
     * 订阅者（例如一分钟后才打开的终端界面）来晚了就再也看不到 shell 的 banner
     * 和提示符，屏幕上就是一片空白。所以始终落进 history，订阅时先回灌。
     */
    private val rawHistory = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val rawHistoryBytes = ConcurrentHashMap<String, Int>()

    /** One shared interactive shell per chat session. */
    fun aiSessionId(chatSessionId: String): String =
        if (chatSessionId.isBlank()) "ai-terminal" else "ai-terminal-$chatSessionId"

    fun screen(id: String): AnsiScreen? = sessions[id]?.screen

    /** 供 UI 侧挂 Termux 渲染层用的 pty 元信息（fd / pid / 当前行列）。 */
    data class PtyInfo(val fd: Int, val pid: Int, val rows: Int, val cols: Int)

    fun ptyInfo(id: String): PtyInfo? {
        val s = sessions[id] ?: return null
        return PtyInfo(s.pty.masterFdInt, s.pty.pid, s.screen.rows, s.screen.cols)
    }

    /** 该会话是否由本服务托管（UI 据此决定能否挂渲染层）。 */
    fun hasSession(id: String): Boolean = sessions.containsKey(id)

    /** 返回取消订阅函数。 */
    fun addRawSink(sink: RawSink): () -> Unit {
        rawSinks += sink
        return { rawSinks.remove(sink) }
    }

    /** 取某会话至今为止的全部原始字节（按到达顺序），供 UI 挂载时回灌。 */
    fun rawHistory(id: String): List<ByteArray> =
        synchronized(rawHistory) { rawHistory[id]?.toList() ?: emptyList() }

    private fun recordRaw(id: String, bytes: ByteArray) {
        val dq = rawHistory.getOrPut(id) { ArrayDeque() }
        dq.addLast(bytes)
        var total = (rawHistoryBytes[id] ?: 0) + bytes.size
        while (total > RAW_HISTORY_LIMIT && dq.isNotEmpty()) {
            total -= dq.removeFirst().size
        }
        rawHistoryBytes[id] = total
    }

    private fun publishRaw(id: String, data: ByteArray, length: Int) {
        if (length <= 0) return
        val copy = data.copyOf(length)
        synchronized(rawHistory) { recordRaw(id, copy) }
        val sinks = rawSinks
        if (sinks.isEmpty()) return
        for (i in 0 until sinks.size) {
            runCatching { sinks[i].onRawBytes(id, copy, length) }
        }
    }

    /** 把一段「装饰性」文本（超时/退出提示）送进 UI。 */
    private fun publishRawText(id: String, text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        publishRaw(id, bytes, bytes.size)
    }

    /** 装饰行 + 镜像一起走：终端画布与旧 AnsiScreen 都要看到。 */
    private fun feedBoth(session: Session, text: String) {
        feedMirror(session, text)
        publishRawText(session.id, text)
    }

    // ------------------------------------------------- 真共享执行（主通道）

    /** 只在登记/读写时持锁，等待发生在锁外；提示符或 EOF 即可收尾。 */
    fun executeOnSharedPty(
        id: String, title: String,
        sandbox: File, rootfs: File, native: File, script: File,
        initialCwd: File, command: String, timeoutMs: Long,
    ): TerminalOutput {
        if (command.isBlank()) return TerminalOutput(-1, "", "empty command")

        val session = sessions[id] ?: synchronized(sessions) {
            sessions[id] ?: start(id, title, sandbox, rootfs, native, script, initialCwd).also { sessions[id] = it }
        }

        val waiter = ExecWaiter()
        synchronized(session.lock) {
            session.pendingExec?.let { previous ->
                if (!finishAtPrompt(session, previous)) {
                    return outputSnapshot(previous, false, "previous_command_running")
                }
                session.pendingExec = null
            }
            session.pendingExec = waiter
            interruptedSessions.remove(id)
            // 同一条持久 shell，保留 cd/export；省去尾随 printf/$? 结束标记。
            val payload = "{ " + command + "\n} 2>&1\n"
            try {
                session.pty.stdin.write(payload.toByteArray(Charsets.UTF_8))
                session.pty.stdin.flush()
            } catch (e: Exception) {
                session.pendingExec = null
                return TerminalOutput(-1, "", "failed to write to shared pty: ${e.message}")
            }
        }

        val deadline = System.nanoTime() + timeoutMs.coerceIn(1_000L, 20_000L) * 1_000_000L
        try {
            while (System.nanoTime() < deadline) {
                if (waiter.latch.await(100, TimeUnit.MILLISECONDS) || finishAtPrompt(session, waiter)) {
                    synchronized(session.lock) {
                        if (session.pendingExec === waiter) session.pendingExec = null
                    }
                    return outputSnapshot(waiter, true, if (waiter.interrupted) "user_interrupt" else waiter.reason)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 只交回本次输出；命令继续执行，保持 pending，避免下一条命令混进前台程序。
        return outputSnapshot(waiter, false, "wait_window_elapsed")
    }

    // ------------------------------------------------- 旧一次性通道（仅兜底）

    /**
     * 每条命令单开一个 proot 进程。
     *
     * ⚠ 已不是 `terminal` 工具的主路径（主路径是 `executeOnSharedPty`）。
     * 保留它是因为「非交互、要真 stdout/stderr 分离」的场景仍然需要。
     */
    fun execute(id: String, title: String, sandbox: File, rootfs: File, native: File, script: File, cwd: File, command: String, timeoutMs: Long): TerminalOutput {
        if (command.isBlank()) return TerminalOutput(-1, "", "empty command")
        val session = sessions[id] ?: synchronized(sessions) {
            sessions[id] ?: start(id, title, sandbox, rootfs, native, script, cwd).also { sessions[id] = it }
        }

        val safeCwd = cwd.absolutePath.replace("'", "'\\''")
        val runCommand = "cd '$safeCwd' 2>/dev/null || { echo \"cwd not found: $safeCwd\" >&2; exit 2; }\n$command"
        feedBoth(session, "\u001b[38;5;244m› $command\u001b[0m\n")

        // KEY: pass the command as EXTRA argv so init-sandbox.sh takes its
        // non-interactive branch (`exec "$@"`). With no extra args it starts the
        // interactive shell (`exec "$0"`), which never exits — that is exactly
        // why every AI command used to time out with exit_code=124.
        val argv = arrayOf(
            "/system/bin/sh", script.absolutePath,
            sandbox.absolutePath, rootfs.absolutePath, native.absolutePath, cwd.absolutePath,
            "sh", "-c", runCommand
        )
        val pty = try {
            Pty.start(argv, buildEnv(), sandbox)
        } catch (e: Exception) {
            return TerminalOutput(-1, "", "failed to start command: ${e.message}")
        }

        val out = StringBuilder()
        activeExec[id] = pty
        val reader = Thread {
            val buf = ByteArray(8192)
            var carry: ByteArray? = null
            try {
                while (true) {
                    val n = try { pty.stdout.read(buf) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    publishRaw(id, buf, n)
                    val decoded = AnsiParser.decodePtyBytes(buf, n, carry)
                    carry = decoded.second
                    val chunk = decoded.first
                    if (chunk.isEmpty()) continue
                    synchronized(out) { out.append(chunk) }
                    feedMirror(session, chunk)
                }
            } catch (_: Exception) { }
        }.apply { name = "ai-exec-$id"; isDaemon = true; start() }

        val exited = CountDownLatch(1)
        Thread {
            runCatching { pty.process.waitFor() }
            exited.countDown()
        }.apply { name = "ai-exec-wait-$id"; isDaemon = true; start() }

        val done = try { exited.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done) {
            activeExec.remove(id)
            runCatching { pty.destroy() }
            reader.join(400)
            val text = synchronized(out) { out.toString() }
            feedBoth(session, "\u001b[38;5;203m(command timed out)\u001b[0m\n")
            return TerminalOutput(124, text, "command timed out after ${timeoutMs}ms")
        }

        reader.join(800)
        val text = synchronized(out) { out.toString() }
        val rawExit = runCatching { pty.process.exitValue() }.getOrDefault(-1)
        activeExec.remove(id)
        runCatching { pty.destroy() }
        val wasInterrupted = interruptedSessions.remove(id) == true
        val exit = if (wasInterrupted) 130 else rawExit
        val note = if (wasInterrupted) "interrupted by user (SIGINT)" else ""
        feedBoth(session, if (exit == 0) "\u001b[38;5;114m(exit 0)\u001b[0m\n"
            else if (wasInterrupted) "\u001b[38;5;214m(interrupted by user, exit 130)\u001b[0m\n"
            else "\u001b[38;5;203m(exit $exit)\u001b[0m\n")
        return TerminalOutput(exit, text, note)
    }

    // ------------------------------------------------------------- interactive

    private fun start(id: String, title: String, sandbox: File, rootfs: File, native: File, script: File, cwd: File): Session {
        installColorRc(sandbox)
        val pty = Pty.start(arrayOf("/system/bin/sh", script.absolutePath, sandbox.absolutePath, rootfs.absolutePath, native.absolutePath, cwd.absolutePath), buildEnv(), sandbox)

        val screen = AnsiScreen(30, 96)
        val parser = AnsiParser(screen)
        parser.onReply = { reply -> runCatching { pty.stdin.write(reply.toByteArray(Charsets.UTF_8)); pty.stdin.flush() } }

        // 先建 Session 再起读线程：读线程要引用它（剥离标记 / 收集输出都靠它）。
        val session = Session(id, title, cwd, pty, screen, parser)

        Thread {
            val buffer = ByteArray(16384)
            var carry: ByteArray? = null
            val text = StringBuilder()
            var lastPublish = 0L
            fun publish(running: Boolean, exitCode: Int? = null, force: Boolean = true) {
                val now = System.currentTimeMillis()
                if (!force && now - lastPublish < 150) return
                lastPublish = now
                val old = TerminalMonitor.snapshot().firstOrNull { it.id == id }
                TerminalMonitor.update(TerminalMonitor.Session(
                    id, title, cwd.absolutePath, old?.command.orEmpty(),
                    text.toString(), running, exitCode, screen
                ))
            }
            try {
                while (true) {
                    val n = try { pty.stdout.read(buffer) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    // 输出直接交给 UI 与 AI；共享通道已移除 RS/US 退出标记。
                    val cleaned = buffer.copyOf(n)
                    // 有共享命令在等结果 → 把输出顺便收一份给 AI
                    synchronized(session.lock) {
                        session.pendingExec?.let { w ->
                            synchronized(w.out) {
                                w.out.append(String(cleaned, Charsets.UTF_8))
                                if (w.out.length > TEXT_BUF_LIMIT) w.out.delete(0, w.out.length - TEXT_BUF_LIMIT)
                                val tail = ansiPattern.replace(w.out.takeLast(4096), "")
                                    .replace("\r", "")
                                w.promptSeen = promptPattern.containsMatchIn(tail)
                                w.lastOutputNs = System.nanoTime()
                            }
                        }
                    }
                    publishRaw(id, cleaned, cleaned.size)
                    val decoded = AnsiParser.decodePtyBytes(cleaned, cleaned.size, carry)
                    carry = decoded.second
                    val chunk = decoded.first
                    if (chunk.isEmpty()) continue
                    text.append(chunk)
                    if (text.length > TEXT_BUF_LIMIT) text.delete(0, text.length - TEXT_BUF_LIMIT)
                    parser.feed(chunk)
                    publish(running = true, force = false)
                }
            } catch (_: Exception) { }
            synchronized(session.lock) {
                session.pendingExec?.let { w ->
                    w.reason = "pty_closed"
                    w.latch.countDown()
                    session.pendingExec = null
                }
            }
            // EOF 已足够结束工具等待，避免再被 process.waitFor 卡住。
            publish(running = false, exitCode = runCatching { pty.process.exitValue() }.getOrNull())
        }.apply { name = "agent-terminal-$id"; isDaemon = true; start() }

        TerminalMonitor.update(TerminalMonitor.Session(id, title, cwd.absolutePath, running = true, screen = screen))
        return session
    }

    fun createSession(id: String, title: String, sandbox: File, rootfs: File, native: File, script: File, cwd: File): Boolean {
        synchronized(sessions) {
            if (sessions.containsKey(id)) return false
            sessions[id] = start(id, title, sandbox, rootfs, native, script, cwd)
        }
        return true
    }

    fun resize(id: String, rows: Int, cols: Int): Boolean {
        val s = sessions[id] ?: return false
        s.screen.resize(rows, cols)
        return runCatching { s.pty.setWindowSize(rows, cols) }.getOrDefault(false)
    }

    fun sendInput(id: String, input: String): Boolean {
        val s = sessions[id] ?: return false
        return synchronized(s.lock) {
            runCatching {
                s.pty.stdin.write(input.toByteArray(Charsets.UTF_8))
                s.pty.stdin.flush()
                true
            }.getOrElse {
                sessions.remove(id, s)
                TerminalMonitor.snapshot().firstOrNull { it.id == id }?.let { TerminalMonitor.update(it.copy(running = false)) }
                false
            }
        }
    }

    /** 字节级写入（Termux write() 交来的已是 UTF-8，避免 String 往返丢字符）。 */
    fun sendInputBytes(id: String, data: ByteArray, offset: Int, count: Int): Boolean {
        val s = sessions[id] ?: return false
        if (count <= 0) return true
        return synchronized(s.lock) {
            runCatching {
                s.pty.stdin.write(data, offset, count)
                s.pty.stdin.flush()
                true
            }.getOrElse {
                sessions.remove(id, s)
                TerminalMonitor.snapshot().firstOrNull { it.id == id }
                    ?.let { TerminalMonitor.update(it.copy(running = false)) }
                false
            }
        }
    }

    /** 用户主动 Ctrl+C 时同时唤醒工具等待，保留明确的中断状态。 */
    fun interrupt(id: String): Boolean {
        val session = sessions[id]
        if (session != null) {
            synchronized(session.lock) {
                session.pendingExec?.let { w ->
                    w.interrupted = true
                    w.latch.countDown()
                }
                val ok = runCatching {
                    session.pty.stdin.write(byteArrayOf(3))
                    session.pty.stdin.flush()
                    true
                }.getOrDefault(false)
                return ok
            }
        }
        // 兜底：旧的一次性通道
        activeExec[id]?.let { pty ->
            interruptedSessions[id] = true
            return runCatching {
                pty.stdin.write(byteArrayOf(3))
                pty.stdin.flush()
                true
            }.getOrDefault(false)
        }
        return false
    }

    fun closeSession(id: String) {
        sessions.remove(id)?.let { session ->
            synchronized(session.lock) {
                session.pendingExec?.let { w ->
                    w.reason = "session_closed"
                    w.latch.countDown()
                    session.pendingExec = null
                }
            }
            runCatching { session.pty.destroy() }
        }
        synchronized(rawHistory) {
            rawHistory.remove(id)
            rawHistoryBytes.remove(id)
        }
        TerminalMonitor.snapshot().firstOrNull { it.id == id }?.let { TerminalMonitor.update(it.copy(running = false)) }
    }

    fun isRunning(id: String): Boolean = sessions[id]?.pty?.process?.isAlive == true

    /** Mirror a chunk into the session's emulator + text buffer (screen only). */
    private fun feedMirror(session: Session, chunk: String) {
        runCatching { session.parser.feed(chunk) }
        synchronized(session.textBuf) {
            session.textBuf.append(chunk)
            // 文本缓冲上限：超过就裁掉头部。以前是无界 StringBuilder，
            // 长日志（编译 / npm install）能涨到几十 MB，每次 publish 都
            // 整串 toString() → GC 抖动 → 卡顿。
            if (session.textBuf.length > TEXT_BUF_LIMIT) {
                session.textBuf.delete(0, session.textBuf.length - TEXT_BUF_LIMIT)
            }
        }
        val old = TerminalMonitor.snapshot().firstOrNull { it.id == session.id } ?: return
        TerminalMonitor.update(TerminalMonitor.Session(
            session.id, old.title, old.cwd, old.command,
            synchronized(session.textBuf) { session.textBuf.toString() },
            old.running, old.exitCode, session.screen
        ))
    }

    private fun buildEnv(): Map<String, String> = mapOf(
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "COLORTERM" to "truecolor",
        "CLICOLOR" to "1",
        "CLICOLOR_FORCE" to "1",
        "TERM_PROGRAM" to "AgentPad",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "LC_CTYPE" to "C.UTF-8",
        "PYTHONIOENCODING" to "utf-8",
        "PYTHONUTF8" to "1"
    )

    // ------------------------------------------------------------------- rc

    private fun installColorRc(sandbox: File) {
        runCatching {
            val publicDir = File(sandbox, "public")
            publicDir.mkdirs()
            File(publicDir, ".agentpad_rc").writeText(shRc)
            File(sandbox, ".agentpad_rc").writeText(shRc)
            File(publicDir, ".bashrc").writeText(bashRc)
            File(sandbox, ".bashrc").writeText(bashRc)
        }
    }

    private val shRc: String = """
        export TERM=xterm-256color
        export COLORTERM=truecolor
        export CLICOLOR=1
        export CLICOLOR_FORCE=1
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
        export LC_CTYPE=C.UTF-8
        export PYTHONIOENCODING=utf-8
        export PYTHONUTF8=1
        export LS_COLORS='di=01;34:ln=01;36:so=01;35:pi=33:ex=01;32:bd=01;33:cd=01;33:or=01;31:mi=01;31:*.tar=01;31:*.tgz=01;31:*.zip=01;31:*.gz=01;31:*.jpg=01;35:*.png=01;35:*.mp4=01;35:*.mp3=01;35:*.c=01;36:*.h=01;36:*.py=01;33:*.js=01;33:*.json=01;33:*.kt=01;33:*.java=01;33'
        alias ls='ls --color=auto'
        alias ll='ls -alF --color=auto'
        alias la='ls -A --color=auto'
        alias grep='grep --color=auto'
        alias fgrep='fgrep --color=auto'
        alias egrep='egrep --color=auto'
        alias diff='diff --color=auto'
        _AP_ESC=${'$'}(printf '\033')
        PS1="${'$'}{_AP_ESC}[1;32mroot@agentpad${'$'}{_AP_ESC}[0m# "
        export PS1
        unset _AP_ESC
    """.trimIndent() + "\n"

    private val bashRc: String = """
        export TERM=xterm-256color
        export COLORTERM=truecolor
        export CLICOLOR=1
        export CLICOLOR_FORCE=1
        export LANG=C.UTF-8
        export LC_ALL=C.UTF-8
        export LC_CTYPE=C.UTF-8
        export PYTHONIOENCODING=utf-8
        export PYTHONUTF8=1
        export LS_COLORS='di=01;34:ln=01;36:so=01;35:pi=33:ex=01;32:bd=01;33:cd=01;33:or=01;31:mi=01;31:*.tar=01;31:*.tgz=01;31:*.zip=01;31:*.gz=01;31:*.jpg=01;35:*.png=01;35:*.mp4=01;35:*.mp3=01;35:*.c=01;36:*.h=01;36:*.py=01;33:*.js=01;33:*.json=01;33:*.kt=01;33:*.java=01;33'
        alias ls='ls --color=auto'
        alias ll='ls -alF --color=auto'
        alias la='ls -A --color=auto'
        alias grep='grep --color=auto'
        alias fgrep='fgrep --color=auto'
        alias egrep='egrep --color=auto'
        alias diff='diff --color=auto'
        bind 'set convert-meta off' 2>/dev/null
        bind 'set input-meta on' 2>/dev/null
        bind 'set output-meta on' 2>/dev/null
        bind 'set enable-bracketed-paste off' 2>/dev/null
        PS1='\[\033[1;32m\]root@agentpad\[\033[0m\]:\[\033[1;34m\]\w\[\033[0m\]# '
    """.trimIndent() + "\n"
}