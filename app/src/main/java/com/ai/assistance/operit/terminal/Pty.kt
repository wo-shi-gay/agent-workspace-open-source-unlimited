package com.ai.assistance.operit.terminal

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Shared liveness flags for the shell processes spawned by [Pty.start]. */
private class ProcessState {
    @Volatile var alive = true
    @Volatile var exitCode = 0
}

class Pty(
    val process: Process,
    val masterFd: FileDescriptor?,
    /** master fd 的整数值，Termux 视图层需要它来把 fd 交给 TerminalSession。 */
    val masterFdInt: Int,
    /** proot/shell 子进程 pid；0 表示未知。 */
    val pid: Int,
    private val ptyMaster: Int,
    val stdout: InputStream,
    val stdin: OutputStream
) {
    constructor(process: Process, fd: FileDescriptor, master: Int, pid: Int) : this(process, fd, master, pid, master, FileInputStream(fd), FileOutputStream(fd))
    fun destroy() { process.destroy(); runCatching { stdout.close() }; runCatching { stdin.close() } }
    companion object {
        init { System.loadLibrary("pty") }
        fun start(command: Array<String>, environment: Map<String, String>, workingDir: File): Pty {
            val info = createSubprocess(command, environment.map { "${it.key}=${it.value}" }.toTypedArray(), workingDir.absolutePath)
            val pid = info[0]; val fd = info[1]
            if (pid <= 0 || fd <= 0) error("PTY start failed pid=$pid fd=$fd")
            val descriptor = Reflect.getFileDescriptor(fd)
            val state = ProcessState()
            val process = object : Process() {
                override fun destroy() {
                    runCatching { android.os.Process.sendSignal(pid, 1) }
                    runCatching { android.os.Process.sendSignal(pid, 9) }
                    state.alive = false
                }
                // Process.isAlive() == "exitValue() does not throw". The old
                // override returned 0 unconditionally, so isAlive() was ALWAYS
                // false and the AI terminal tool aborted with "terminal session
                // exited" before sending any command. Track real state instead.
                override fun exitValue(): Int =
                    if (state.alive) throw IllegalThreadStateException("still running") else state.exitCode
                override fun getErrorStream(): InputStream = InputStream.nullInputStream()
                override fun getInputStream(): InputStream = InputStream.nullInputStream()
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun waitFor(): Int {
                    val code = waitFor(pid)
                    state.exitCode = code
                    state.alive = false
                    return code
                }
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    val deadline = System.nanoTime() + unit.toNanos(timeout)
                    while (System.nanoTime() < deadline) {
                        if (!state.alive) return true
                        // getTerminalFlags/availableBytes are cheap; poll the pid.
                        try { Thread.sleep(30) } catch (_: InterruptedException) { return false }
                    }
                    return !state.alive
                }
                override fun isAlive(): Boolean = state.alive
            }
            return Pty(process, descriptor, fd, pid)
        }
        private external fun createSubprocess(cmdArray: Array<String>, envArray: Array<String>, workingDir: String): IntArray
        private external fun waitFor(pid: Int): Int
        private external fun getTerminalFlags(fd: Int): Int
        private external fun getAvailableBytes(fd: Int): Int
    }
    fun setWindowSize(rows: Int, cols: Int): Boolean = setPtyWindowSize(ptyMaster, rows, cols) == 0
    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
}

object Reflect {
    fun getFileDescriptor(fd: Int): FileDescriptor {
        val result = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.set(result, fd)
        return result
    }
}