package com.agentpad.workspace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.agentpad.workspace.ai.SandboxInstaller
import com.agentpad.workspace.ai.TerminalMonitor
import com.agentpad.workspace.ai.TerminalService
import com.agentpad.workspace.databinding.ActivityTerminalBinding
import com.agentpad.workspace.terminal.ExtraKeysView
import com.agentpad.workspace.terminal.TermuxHostView

/**
 * 终端界面 —— 布局与交互**照抄 Termux 0.118.3**，只加主人点名的两样。
 *
 * 照抄的部分：
 *  - 画布是 `com.termux.view.TerminalView`，配置抄 activity_termux.xml（vertical scrollbars
 *    / focusableInTouchMode / defaultFocusHighlightEnabled=false）；
 *  - 底部是 37.5dp 高的两行 ExtraKeys（ESC / - HOME ↑ END ⇑ + TAB CTRL ALT ← ↓ → ⇓），
 *    点击语义完全照 TerminalExtraKeys：控制键走 onKeyDown，其余走 inputCodePoint；
 *  - **没有输入框**（原版就是直接在画布上打字，IME 由 TerminalView 自己接管）；
 *  - IME 用 ADJUST_RESIZE 走系统避让（原版也是让系统算，不自造 margin hack）。
 *
 * 额外加的两样（主人原话：「在上面显示一个终端的会话切换窗口就行，加个返回按钮就差不多」）：
 *  1. 顶栏：返回 + 标题/状态 + 新建/关闭；
 *  2. 会话切换条。
 */
class TerminalActivity : Activity() {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var chatSessionId: String
    private lateinit var host: TermuxHostView
    private lateinit var extraKeys: ExtraKeysView
    private var selectedId: String? = null
    private var unsubscribe: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private var lastSessionCount = -1

    /** 只用来感知会话数量变化（会话是别人（AI）建的，需要刷新标签栏）。 */
    private val refresh = object : Runnable {
        override fun run() {
            val count = TerminalMonitor.snapshot().count { TerminalService.hasSession(it.id) }
            if (count != lastSessionCount) { lastSessionCount = count; rebuildTabs() }
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        chatSessionId = intent.getStringExtra("session_id") ?: "terminal"

        // 原版用 adjustResize 让系统把画布顶上去了；Termux 的 updateSize() 会跟着重算行列。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        host = TermuxHostView(binding.output)
        host.onInterruptRequest = { doInterrupt() }
        // TerminalView 是 TYPE_NULL，自己弹不出键盘，必须由 Activity 调 IME。
        host.requestShowKeyboard = { showSoftKeyboard() }
        // 选中文本时弹出我们自己的胶囊复制条（Termux 原版 ActionMode 太朴素）
        host.onSelectionChanged = { has ->
            binding.copyBar.visibility = if (has) View.VISIBLE else View.GONE
        }

        binding.btnCopy.setOnClickListener { copySelection() }
        binding.btnCopyCancel.setOnClickListener {
            binding.output.stopTextSelectionMode()
            binding.copyBar.visibility = View.GONE
        }

        extraKeys = ExtraKeysView(binding.output, binding.extraKeys)
        extraKeys.onCtrlC = { doInterrupt() }
        extraKeys.build()

        binding.back.setOnClickListener { finish() }
        binding.newSession.setOnClickListener { newSession() }
        binding.closeSession.setOnClickListener { closeCurrent() }
        binding.btnInterrupt.setOnClickListener { doInterrupt() }

        ensureSession()
        unsubscribe = TerminalMonitor.observe { updated ->
            if (updated.id != selectedId) return@observe
            ui.post { updateStatus(updated.running, updated.cwd) }
        }
        ui.post(refresh)
    }

    // ------------------------------------------------------------- sessions

    private fun ensureSession() {
        val base = TerminalService.aiSessionId(chatSessionId)
        // 三种情况都要能接上：
        //  ① 已经由 UI/AI 建过会话 → 直接挂；
        //  ② TerminalMonitor 里有记录但 pty 没登记（AI 的临时会话）→ 建一条真会话；
        //  ③ 都没有 → 新建。
        if (TerminalService.hasSession(base)) { select(base); return }
        val created = createSession(base, "AI 终端")
        if (!created && !TerminalService.hasSession(base)) {
            // 极少数竞态：别人刚刚建完又立刻关掉，重试一次。
            createSession(base, "AI 终端")
        }
        select(base)
    }

    private fun newSession() {
        val base = TerminalService.aiSessionId(chatSessionId)
        val id = "${base}_${System.currentTimeMillis()}"
        createSession(id, "终端 ${id.takeLast(4)}")
        select(id)
    }

    private fun createSession(id: String, title: String): Boolean {
        val cwd = SandboxInstaller.projectDir(this)
        cwd.mkdirs()
        return TerminalService.createSession(
            id, title,
            SandboxInstaller.sandboxDir(this),
            SandboxInstaller.rootfsDir(this),
            SandboxInstaller.nativeDir(this),
            SandboxInstaller.initScript(this), cwd
        )
    }

    private fun select(id: String) {
        selectedId = id
        if (!TerminalService.hasSession(id)) return
        host.attach(id)
        val session = TerminalMonitor.snapshot().firstOrNull { it.id == id }
        binding.title.text = session?.title ?: "终端"
        updateStatus(session?.running ?: false, session?.cwd ?: "")
        rebuildTabs()
    }

    private fun closeCurrent() {
        val id = selectedId ?: return
        host.detach()
        TerminalService.closeSession(id)
        selectedId = null
        val next = TerminalMonitor.snapshot().lastOrNull { it.id != id && TerminalService.hasSession(it.id) }
        if (next != null) select(next.id) else ensureSession()
    }

    private fun updateStatus(running: Boolean, cwd: String) {
        binding.status.text = shortenCwd(cwd)
        binding.statusDot.setTextColor(colorRes(if (running) R.color.term_ok else R.color.term_muted))
    }

    private fun shortenCwd(cwd: String): String {
        val parts = cwd.trimEnd('/').split('/').filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "/"
            parts.size <= 2 -> "/" + parts.joinToString("/")
            else -> "…/" + parts.takeLast(2).joinToString("/")
        }
    }

    private fun rebuildTabs() {
        binding.sessions.removeAllViews()
        val sessions = TerminalMonitor.snapshot().filter { TerminalService.hasSession(it.id) }
        binding.sessionsScroll.visibility = if (sessions.size <= 1) View.GONE else View.VISIBLE
        sessions.forEach { item ->
            val active = item.id == selectedId
            binding.sessions.addView(TextView(this).apply {
                text = item.title
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(colorRes(if (active) R.color.term_fg else R.color.term_muted))
                background = GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(colorRes(if (active) R.color.term_key_active else R.color.term_key))
                }
                setOnClickListener { select(item.id) }
            }, LinearLayout.LayoutParams(-2, dp(26)).apply { rightMargin = dp(6) })
        }
    }

    // ------------------------------------------------------------- 中断 / 键盘

    /**
     * 中断当前命令（等价于终端里按 Ctrl+C）。
     *
     * 走 TerminalService.interrupt：它会往那条 pty 打 ETX，并主动给等待中的
     * AI 命令收尾（因为 Ctrl+C 会让 shell 丢弃输入缓冲，标记行不会执行）。
     */
    private fun doInterrupt() {
        val id = selectedId ?: return
        val ok = TerminalService.interrupt(id)
        // 给个触感反馈，让用户知道点到了（没命中就不提示，避免误导）
        if (ok) binding.output.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** TerminalView 的 inputType 是 TYPE_NULL，必须由宿主显式唤起 IME。 */
    private fun showSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return
        binding.output.requestFocus()
        imm.showSoftInput(binding.output, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    // ------------------------------------------------------------- 复制

    private fun copySelection() {
        val text = binding.output.selectedText
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "没有选中文本", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
        binding.output.stopTextSelectionMode()
    }

    /** 原版在 onContextMenuClosed 里清理选中的暂存文本，必须转发。 */
    override fun onContextMenuClosed(menu: android.view.Menu) {
        super.onContextMenuClosed(menu)
        binding.output.onContextMenuClosed(menu)
    }

    // -------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        binding.output.requestFocus()
        ui.removeCallbacks(refresh)
        ui.post(refresh)
        selectedId?.let { if (TerminalService.hasSession(it)) host.attach(it) }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        unsubscribe = null
        ui.removeCallbacks(refresh)
        host.detach()
        super.onDestroy()
    }

    private fun colorRes(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_MS = 400L
    }
}