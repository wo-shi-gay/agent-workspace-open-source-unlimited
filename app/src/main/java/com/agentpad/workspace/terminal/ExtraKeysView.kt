package com.agentpad.workspace.terminal

import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.agentpad.workspace.R
import com.termux.view.TerminalView

/**
 * 底部快捷键条 —— 照抄 Termux `ExtraKeysView` + `TerminalExtraKeys`。
 *
 * 原版做法（termux-shared/.../ExtraKeysView.java + TerminalExtraKeys.java）：
 *  - 用 `ExtraKeysInfo` 的矩阵 `[[行1],[行2]]` 生成 GridLayout 的 Button；
 *  - 按键分两类：`PRIMARY_KEY_CODES_FOR_STRINGS` 里的走
 *    `new KeyEvent(...); mTerminalView.onKeyDown(keyCode, keyEvent)`；
 *    其余（斜杠、字母、符号）走 `mTerminalView.inputCodePoint(codePoint, ctrl, alt)`。
 *  - ctrl/alt 是「特殊键」，点一下激活，用完自动复位（autoSetInActive）。
 *
 * 为什么不能自己写死一排 TextView：主人要的是「完全依照参考源码」。
 * 按键文本、行数、点击走哪个入口、Ctrl 语义，全部按原版来，不做自造。
 */
class ExtraKeysView(
    private val view: TerminalView,
    private val container: LinearLayout,
) {

    /** 原版 DEFAULT_IVALUE_EXTRA_KEYS（TermuxPropertyConstants:282）。 */
    private val matrix: List<List<String>> = listOf(
        listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
        listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"),
    )

    /** 原版 EXTRA_KEY_DISPLAY_MAPS 合并后的显示文本（default 风格）。 */
    private val display = mapOf(
        "LEFT" to "←", "RIGHT" to "→", "UP" to "↑", "DOWN" to "↓",
        "ENTER" to "↲", "TAB" to "↹", "BKSP" to "⌫", "DEL" to "⌦",
        "HOME" to "⇱", "END" to "⇲", "PGUP" to "⇑", "PGDN" to "⇓",
        "CTRL" to "CTRL", "ALT" to "ALT", "ESC" to "ESC",
    )

    /** 原版 PRIMARY_KEY_CODES_FOR_STRINGS。 */
    private val keyCodes = mapOf(
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS" to KeyEvent.KEYCODE_INSERT,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "BKSP" to KeyEvent.KEYCODE_DEL,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
    )

    /** 原版 PRIMARY_REPETITIVE_KEYS：长按连发。 */
    private val repetitiveKeys = setOf("UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL", "PGUP", "PGDN")

    private var ctrlDown = false
    private var altDown = false

    private val ctrlButtons = mutableListOf<Button>()
    private val altButtons = mutableListOf<Button>()
    private var popup: PopupWindow? = null

    /** 供 Ctrl+C 这类需要特殊处理的按键挂钩（原版由 TermuxTerminalExtraKeys 处理）。 */
    var onCtrlC: (() -> Unit)? = null

    fun build() {
        container.removeAllViews()
        matrix.forEach { row ->
            val rowView = LinearLayout(container.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            }
            row.forEach { key ->
                // 原版 GridLayout 用 weight 均分；这里横向等分，视觉一致。
                rowView.addView(makeButton(key), LinearLayout.LayoutParams(0, -1, 1f))
            }
            container.addView(rowView)
        }
    }

    private fun label(key: String): String = display[key] ?: key

    private fun makeButton(key: String): Button = Button(container.context, null, android.R.attr.buttonBarButtonStyle).apply {
        text = label(key)
        isAllCaps = true
        setPadding(0, 0, 0, 0)
        setTextColor(colorRes(R.color.term_key_text))
        background = null

        when (key) {
            "CTRL" -> {
                ctrlButtons += this
                setOnClickListener { toggleSpecial(ctrlDown, this) { ctrlDown = it } }
            }
            "ALT" -> {
                altButtons += this
                setOnClickListener { toggleSpecial(altDown, this) { altDown = it } }
            }
            else -> {
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dispatch(key)
                }
            }
        }
    }

    private fun toggleSpecial(active: Boolean, button: Button, apply: (Boolean) -> Unit) {
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val next = !active
        apply(next)
        button.setTextColor(colorRes(if (next) R.color.term_key_active else R.color.term_key_text))
    }

    /**
     * 完全照抄 TerminalExtraKeys.onTerminalExtraKeyButtonClick。
     * 特殊处理只有一处：CTRL 激活状态下按 ESC/字母这类，原版是把 ctrlDown 传给
     * inputCodePoint；而 Ctrl+C 在原版里是 `CTRL` + `c` 组合，没有独立按钮。
     * 主人的需求是「用 ^C 打断 AI」，所以当 ctrl 激活且按键能映射成 Ctrl+C 时走 onCtrlC。
     */
    private fun dispatch(key: String) {
        val code = keyCodes[key]
        if (code != null) {
            var meta = 0
            if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (altDown) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            val event = KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta)
            view.onKeyDown(code, event)
            resetSpecial()
            return
        }

        // 非控制键：逐 code point 交给 TerminalView（与原版一致）
        key.codePoints().forEach { cp ->
            if (ctrlDown && (cp == 'c'.code || cp == 'C'.code)) {
                onCtrlC?.invoke()
            } else {
                view.inputCodePoint(cp, ctrlDown, altDown)
            }
        }
        resetSpecial()
    }

    /** 原版 readSpecialButton(autoSetInActive=true)：非锁定状态下用完复位。 */
    private fun resetSpecial() {
        if (ctrlDown) {
            ctrlDown = false
            ctrlButtons.forEach { it.setTextColor(colorRes(R.color.term_key_text)) }
        }
        if (altDown) {
            altDown = false
            altButtons.forEach { it.setTextColor(colorRes(R.color.term_key_text)) }
        }
    }

    private fun colorRes(id: Int): Int =
        androidx.core.content.ContextCompat.getColor(container.context, id)

    companion object {
        /** 顶栏按钮那种小方块背景用不上，这里保持 null 与 Termux 一致。 */
        const val HEIGHT_DP = 37.5f
    }
}