package com.agentpad.workspace.terminal

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.agentpad.workspace.ai.TerminalService
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 把 Termux 渲染层接到我们自己的 pty 上（路线一）。
 *
 * 为什么需要这一层：Termux 的 `TerminalView` 只认 `TerminalSession`，
 * 而本项目每一条会话的 pty 由 `TerminalService`（libpty.so）创建并托管，
 * 同一条流还要供 AI 命令通道镜像。两者不能各开一条，否则就是两套终端。
 *
 * 这里做的事：
 *  1. 建一个 `TerminalSession`，用 `attachExternal(fd, pid, ...)` 挂到已有 pty，
 *     让它**不**去 createSubprocess、**不**起自己的读线程；
 *  2. 订 `TerminalService.addRawSink`，把原始字节 post 到主线程 `feedBytes()`；
 *  3. 用户按键 → `setOutputSink` → `TerminalService.sendInput`；
 *  4. 视图 resize → `setSizeSink` → `TerminalService.resize`（真正 ioctl）。
 *
 * 一条会话一个实例，随 Activity 销毁时 `detach()`。
 */
class TermuxHostView(private val view: TerminalView) {

    private val ui = Handler(Looper.getMainLooper())

    private var session: TerminalSession? = null
    private var currentId: String? = null
    private var unsubscribeRaw: (() -> Unit)? = null

    /** Ctrl+C 之外的中断出口（AI 命令真正的打断走 TerminalService.interrupt）。 */
    var onInterruptRequest: (() -> Unit)? = null

    /** 由 Activity 提供真正弹键盘的动作（TerminalView 自己是 TYPE_NULL，弹不出来）。 */
    var requestShowKeyboard: (() -> Unit)? = null

    /** 选中模式开关回调：Activity 据此显示/隐藏自己的复制胶囊条。 */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            ui.post { view.onScreenUpdated() }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            ui.post { view.onScreenUpdated() }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            // Termux 自己复制时也通知宿主，保证胶囊条状态一致
            onSelectionChanged?.invoke(false)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val text = clipboardText() ?: return
            currentId?.let { TerminalService.sendInput(it, text) }
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}

        /** 光标样式：实心块，和老终端一致。 */
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    private val viewClient = object : TerminalViewClient {
        /**
         * 双指缩放。
         *
         * 原版 Termux 的做法是 `mScaleFactor *= scale` 然后交给客户端换算字号；
         * 之前我写成「每帧对当前字号取整」，缩小时因为 toInt() 截断，小幅度变化
         * 一直被抹平 → 看起来「缩放不动」。这里改成累计浮点字号，只在显示时取整。
         *
         * 同时 setTextSize() 内部会 updateSize() → 重算行列 → 告诉 PTY 新尺寸，
         * 所以文字会正常重排、不会溢出屏幕。
         */
        override fun onScale(scale: Float): Float {
            exactTextSize = (exactTextSize * scale).coerceIn(
                MIN_TEXT_SIZE.toFloat(), MAX_TEXT_SIZE.toFloat()
            )
            val rounded = exactTextSize.toInt()
            if (rounded != currentTextSize) {
                currentTextSize = rounded
                view.setTextSize(rounded)
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent) {
            view.requestFocus()
            // Termux 的 TerminalView 用 InputType.TYPE_NULL（见 TerminalView.onCreateInputConnection），
            // 系统不会因为获得焦点就自动弹键盘。必须由宿主显式 showSoftInput，
            // 否则就是「只有进页面那次弹一下，之后怎么点都不出来」。
            requestShowKeyboard?.invoke()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true

        /**
         * 选中模式开关。Termux 的 ActionMode 是系统浮条，样式没法改；
         * 我们改成自己的胶囊条，所以这里只要把状态通知给 Activity。
         */
        override fun copyModeChanged(copyMode: Boolean) {
            onSelectionChanged?.invoke(copyMode)
        }

        override fun onKeyDown(keyCode: Int, e: KeyEvent, ts: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        /**
         * Ctrl 组合键：Ctrl+C 直接走 `TerminalService.interrupt`，
         * 这样 AI 正在跑的命令也能被终端打断（这是路线一的核心诉求）。
         * 其余 Ctrl 组合照常交给 emulator 发控制字符。
         */
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (ctrlDown && (codePoint == 'c'.code || codePoint == 'C'.code)) {
                onInterruptRequest?.invoke()
                return true
            }
            return false
        }

        /** 视图算出行列后，把真正的 ioctl 交给 TerminalService。已在 SizeSink 里做，这里空。 */
        override fun onEmulatorSet() {}

        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    private var currentTextSize = DEFAULT_TEXT_SIZE

    /** 缩放用的浮点字号：按浮点累计，避免 toInt() 把小幅度变化反复抹平。 */
    private var exactTextSize = DEFAULT_TEXT_SIZE.toFloat()

    init {
        view.setTerminalViewClient(viewClient)
        view.setTextSize(currentTextSize)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
    }

    /**
     * 挂到指定会话。每次切换会话都要调：先把旧的 sink 拆干净，
     * 否则字节会被两个 emulator 同时吃掉，画面互相污染。
     */
    fun attach(id: String) {
        if (currentId == id && session != null) return
        detach()
        val info = TerminalService.ptyInfo(id) ?: return

        val s = TerminalSession("", "", emptyArray<String>(), emptyArray<String>(), null, sessionClient)
        s.setOutputSink { data, offset, count ->
            TerminalService.sendInputBytes(id, data, offset, count)
        }
        s.setSizeSink { rows, cols -> TerminalService.resize(id, rows, cols) }

        // 初始化时用当前行列，避免先闪一下 80x24 再重排
        s.attachExternal(info.fd, info.pid, info.cols, info.rows, 0, 0)
        currentId = id
        session = s

        view.attachSession(s)
        view.setTextSize(currentTextSize)

        unsubscribeRaw = TerminalService.addRawSink { sid, data, length ->
            if (sid != id) return@addRawSink
            // 原始字节必须回主线程喂：TerminalEmulator 不是线程安全的
            ui.post {
                if (currentId == id) session?.feedBytes(data, length)
            }
        }

        // 回灌历史：会话可能在我们打开界面之前就已经跑起来了（AI 早就在用这条 pty），
        // 它吐过的 banner / 提示符 / 命令输出全在 rawHistory 里。不回灌就是一片黑屏。
        // 顺序必须严格按到达顺序逐段 feed，否则 UTF-8 多字节序列和 ANSI 状态机会错乱。
        val history = TerminalService.rawHistory(id)
        if (history.isNotEmpty()) {
            ui.post {
                if (currentId != id) return@post
                history.forEach { chunk -> s.feedBytes(chunk, chunk.size) }
                view.onScreenUpdated()
                view.setTopRow(0)
            }
        }
    }

    /** 解绑当前会话（不关闭 pty，pty 归 TerminalService）。 */
    fun detach() {
        unsubscribeRaw?.invoke()
        unsubscribeRaw = null
        session = null
        currentId = null
    }

    fun setTextSize(size: Int) {
        currentTextSize = size.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        exactTextSize = currentTextSize.toFloat()
        view.setTextSize(currentTextSize)
    }

    fun textSize(): Int = currentTextSize

    fun scrollToBottom() {
        view.setTopRow(0)
        view.invalidate()
    }

    fun lineUp() {
        val top = view.topRow
        view.topRow = top - 1
        view.invalidate()
    }

    fun lineDown() {
        val top = view.topRow
        view.topRow = top + 1
        view.invalidate()
    }

    fun pageUp() {
        view.topRow = view.topRow - 10
        view.invalidate()
    }

    fun pageDown() {
        view.topRow = view.topRow + 10
        view.invalidate()
    }

    private fun clipboardText(): String? {
        val cm = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(view.context)?.toString()
    }

    companion object {
        const val DEFAULT_TEXT_SIZE = 12
        private const val MIN_TEXT_SIZE = 8
        private const val MAX_TEXT_SIZE = 30

        /**
         * 挂载界面时最多回灌多少原始字节。
         *
         * 2026-11-18 加：把 512KB 全量历史一次喂进 ANSI 状态机，
         * 主线程要跑几十万次 feedBytes，界面卡死几秒；而最终能显示的
         * 只有 AnsiScreen 的 2000 行 scrollback，前面 99% 解析完就被丢掉。
         *
         * 64KB 足以铺满 2000 行留给用户翻，同时把回灌压到毫秒级。
         * 调大不会让用户看到更多（环形缓冲会丢），只会让挂载更慢 —— 别调。
         */
        private const val RECAP_BYTES = 64 * 1024
    }
}