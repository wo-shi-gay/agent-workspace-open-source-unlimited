package com.agentpad.workspace.terminal

/**
 * Terminal color palette + attribute bits shared by the emulator and the canvas view.
 */
object TermPalette {
    const val DEFAULT_FG = 0xFFE6EAF2.toInt()
    const val DEFAULT_BG = 0xFF0B0E13.toInt()
    const val CURSOR = 0xFF8B9CFF.toInt()
    const val CURSOR_DIM = 0x558B9CFF.toInt()
    const val SELECTION = 0x553D5AF5

    /** xterm 16-color base palette (dark, easy on the eyes). */
    val ANSI = intArrayOf(
        0xFF272C36.toInt(), // 0 black
        0xFFE06C75.toInt(), // 1 red
        0xFF98C379.toInt(), // 2 green
        0xFFE5C07B.toInt(), // 3 yellow
        0xFF61AFEF.toInt(), // 4 blue
        0xFFC678DD.toInt(), // 5 magenta
        0xFF56B6C2.toInt(), // 6 cyan
        0xFFD7DAE0.toInt(), // 7 white
        0xFF4B5364.toInt(), // 8 bright black
        0xFFFF7A85.toInt(), // 9 bright red
        0xFFB4E08A.toInt(), // 10 bright green
        0xFFFFD68A.toInt(), // 11 bright yellow
        0xFF82C7FF.toInt(), // 12 bright blue
        0xFFE3A6F5.toInt(), // 13 bright magenta
        0xFF70D5E0.toInt(), // 14 bright cyan
        0xFFFFFFFF.toInt()  // 15 bright white
    )

    /** xterm 256-color index -> ARGB. */
    fun xterm256(i: Int): Int = when {
        i < 0 -> DEFAULT_FG
        i < 16 -> ANSI[i]
        i in 16..231 -> {
            val n = i - 16
            fun v(x: Int) = if (x == 0) 0 else x * 40 + 55
            (0xFF shl 24) or (v(n / 36) shl 16) or (v((n % 36) / 6) shl 8) or v(n % 6)
        }
        i in 232..255 -> {
            val v = (i - 232) * 10 + 8
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        else -> DEFAULT_FG
    }
}

const val ATTR_BOLD = 1
const val ATTR_DIM = 2
const val ATTR_ITALIC = 4
const val ATTR_UNDERLINE = 8
const val ATTR_REVERSE = 16
const val ATTR_STRIKE = 32
const val ATTR_BLINK = 64
/** Second half of a double-width glyph; never rendered on its own. */
const val ATTR_WIDE_CONT = 0x80

/**
 * One physical terminal row: character cells plus per-cell colors/attributes.
 * Rows are reused when scrolling so allocation stays low.
 */
class TermLine(val cols: Int) {
    val chars = CharArray(cols) { ' ' }
    val fg = IntArray(cols) { TermPalette.DEFAULT_FG }
    val bg = IntArray(cols) { TermPalette.DEFAULT_BG }
    val attr = ByteArray(cols)
    /** True when this row is a soft-wrap continuation of the row above it. */
    var wrapped = false

    fun reset() {
        java.util.Arrays.fill(chars, ' ')
        java.util.Arrays.fill(fg, TermPalette.DEFAULT_FG)
        java.util.Arrays.fill(bg, TermPalette.DEFAULT_BG)
        java.util.Arrays.fill(attr, 0)
    }

    fun copyFrom(src: TermLine) {
        val keep = minOf(cols, src.cols)
        System.arraycopy(src.chars, 0, chars, 0, keep)
        System.arraycopy(src.fg, 0, fg, 0, keep)
        System.arraycopy(src.bg, 0, bg, 0, keep)
        System.arraycopy(src.attr, 0, attr, 0, keep)
        for (c in keep until cols) {
            chars[c] = ' '; fg[c] = TermPalette.DEFAULT_FG
            bg[c] = TermPalette.DEFAULT_BG; attr[c] = 0
        }
        wrapped = src.wrapped
    }

    /** Trim trailing blanks so a reflowed logical line does not carry padding. */
    fun trimTo(len: Int) {
        val n = len.coerceIn(0, cols)
        for (c in n until cols) {
            chars[c] = ' '; fg[c] = TermPalette.DEFAULT_FG
            bg[c] = TermPalette.DEFAULT_BG; attr[c] = 0
        }
    }

    /** Append up to [len] cells from [src] starting at this row's [from] column. */
    fun appendFrom(src: TermLine, len: Int, from: Int) {
        for (i in 0 until len) {
            val c = from + i
            if (c >= cols) break
            chars[c] = src.chars[i]
            fg[c] = src.fg[i]
            bg[c] = src.bg[i]
            attr[c] = src.attr[i]
        }
    }

    /**
     * Reflow this row into a row of [newCols] cells, continuing on the returned
     * overflow rows. Only used for rows flagged `wrapped` (soft-wrapped lines).
     */
    fun reflow(newCols: Int): List<TermLine> {
        val contentEnd = lastNonBlank() + 1
        val out = ArrayList<TermLine>()
        if (newCols <= 0) return out
        var produced = TermLine(newCols)
        var col = 0
        var i = 0
        while (i < contentEnd) {
            val at = attr[i].toInt()
            if (at and ATTR_WIDE_CONT != 0) { i++; continue }
            val ch = chars[i]
            val span = if (AnsiScreen.isWide(ch)) 2 else 1
            if (col + span > newCols) {
                produced.wrapped = true
                out.add(produced)
                produced = TermLine(newCols)
                col = 0
            }
            produced.chars[col] = ch
            produced.fg[col] = fg[i]
            produced.bg[col] = bg[i]
            produced.attr[col] = attr[i]
            if (span == 2 && col + 1 < newCols) {
                produced.chars[col + 1] = '\u0000'
                produced.fg[col + 1] = fg[i]
                produced.bg[col + 1] = bg[i]
                produced.attr[col + 1] = (at or ATTR_WIDE_CONT).toByte()
            }
            col += span
            i += span
        }
        out.add(produced)
        return out
    }

    /** Trailing-blank scan so the renderer can skip empty suffixes. */
    fun lastNonBlank(): Int {
        for (c in cols - 1 downTo 0) {
            if (chars[c] != ' ' && chars[c] != '\u0000') return c
        }
        return -1
    }

    /** Independent copy of this row (used when reflowing). */
    fun copyLine(): TermLine = TermLine(cols).also { it.copyFrom(this) }

    /** Crop/pad to a new width without trying to re-wrap (alt-screen resize). */
    fun withColsSimple(newCols: Int): TermLine {
        if (newCols == cols) return this
        val out = TermLine(newCols)
        val keep = minOf(cols, newCols)
        System.arraycopy(chars, 0, out.chars, 0, keep)
        System.arraycopy(fg, 0, out.fg, 0, keep)
        System.arraycopy(bg, 0, out.bg, 0, keep)
        System.arraycopy(attr, 0, out.attr, 0, keep)
        out.wrapped = wrapped
        return out
    }
}

/** Saved cursor + pen state (DECSC/DECRC). */
private class SavedState {
    var row = 0
    var col = 0
    var fg = TermPalette.DEFAULT_FG
    var bg = TermPalette.DEFAULT_BG
    var attr = 0
}

/**
 * A full-screen character-cell terminal model implementing the parts of the
 * VT100/xterm protocol that interactive Linux programs actually use:
 * cursor movement, erase, insert/delete, scroll regions, alternate screen,
 * SGR colors (16/256/truecolor), and a bounded scrollback ring.
 *
 * ⚠ 2026-11-17 历史缓冲照抄 Termux `TerminalBuffer` 的固定容量环形数组。
 *   原实现用 `ArrayList` + `historyHead` 头部空洞 + 惰性压缩，虽然摊还 O(1)，
 *   但有两处真实缺陷：
 *     ① `ArrayList` 在两次压缩之间会持续增长（空洞最大可到 size 的一半），
 *        刷屏场景下真实占用可超过 scrollbackLimit 的两倍；
 *     ② 每次 compact 都要 `subList` 复制一遍，几万行的历史会瞬间分配新数组，
 *        在手机上就是一次可见的卡顿。
 *   Termux 的做法更干净：数组一次性开满 `totalRows = scrollbackLimit + screenRows`，
 *   写满后**原地覆写最旧行**，永不扩容、永不复制。
 */
class AnsiScreen(
    rows: Int,
    cols: Int,
    /**
     * 可回看的行数。对齐 Termux：`DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000`，
     * 下限 100 / 上限 50000（TerminalEmulator.java:137-139）。
     * 我们取 2000，比原来的 800 更够用，同时数组一次性分配 2000 个引用 = 16KB，
     * 完全不是内存负担。
     */
    private val scrollbackLimit: Int = 2000,
) {

    var rows: Int = rows.coerceAtLeast(1); private set
    var cols: Int = cols.coerceAtLeast(1); private set

    private var screen: Array<TermLine> = Array(this.rows) { TermLine(this.cols) }

    /**
     * Scrollback 环形缓冲（照抄 Termux TerminalBuffer 的 transcript 机制）。
     *
     * [history] 容量固定为 `scrollbackLimit`，是**环形**的：
     *   · [historyHead] 指向「最旧那一行」的槽位
     *   · 追加时写到 `(historyHead + historySizeNow) % capacity`
     *   · 满了就把 [historyHead] 前移一格，等于丢弃最旧行（原地覆写，不扩容）
     *
     * 这样在任何刷屏强度下，历史区占用都是**恒定**的：
     *   capacity 个 TermLine 引用 = scrollbackLimit × 8 字节。
     * 2000 行 = 16KB，比原来 ArrayList 的峰值小一个数量级。
     */
    private var history: Array<TermLine?> = arrayOfNulls(scrollbackLimit.coerceAtLeast(1))
    /** 环形缓冲的「最旧行」槽位下标。 */
    private var historyHead = 0
    /** 当前实际存了多少行（<= capacity）。 */
    private var historyCount = 0

    private val capacity: Int get() = history.size
    private val historySizeNow: Int get() = historyCount

    /**
     * 追加一行到回看缓冲。
     *
     * 满了就覆写最旧行 —— 不新建数组、不搬移元素、不触发 GC 抖动。
     * 这就是「终端历史自动过滤」的落点：不是事后删，而是从源头就不让它长。
     */
    private fun pushHistory(line: TermLine) {
        if (capacity == 0) return
        if (historyCount < capacity) {
            history[(historyHead + historyCount) % capacity] = line
            historyCount++
        } else {
            // 满了：原地覆盖最旧行，头指针前移
            history[historyHead] = line
            historyHead = (historyHead + 1) % capacity
        }
    }

    private fun clearHistory() {
        history.fill(null)
        historyHead = 0
        historyCount = 0
    }

    /** 按逻辑序号取历史行（0 = 最旧）。环形下标换算，O(1)。 */
    private fun historyAt(index: Int): TermLine? {
        if (index < 0 || index >= historyCount) return null
        return history[(historyHead + index) % capacity]
    }

    /**
     * 标记「有变化」。视图绘制完必须调用 [consumeDirty] 复位，
     * 否则 `invalidateIfDirty()` 永远为 true → 90ms 轮询无条件重绘整屏
     * （每帧几百次 drawText），日志越多越卡。
     */
    var dirty = true

    /** 取走 dirty 标记（读后即清）。 */
    fun consumeDirty(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }

    var cursorRow = 0; private set
    var cursorCol = 0; private set
    var scrollTop = 0; private set
    var scrollBottom = this.rows - 1; private set

    /** 0 = live view; N = N lines scrolled up into history. */
    var scrollOffset = 0; private set

    var cursorVisible = true; private set
    var applicationCursorKeys = false; private set
    var bracketedPaste = false; private set
    var autoWrap = true; private set
    var insertMode = false; private set
    var windowTitle = ""; private set

    var penFg = TermPalette.DEFAULT_FG
    var penBg = TermPalette.DEFAULT_BG
    var penAttr = 0

    private val saved = SavedState()
    private var pendingWrap = false
    private var lastPrintable = ' '
    private var altScreen: Array<TermLine>? = null
    private var altSaved = SavedState()
    private var altActive = false

    val totalLines: Int get() = historySizeNow + rows
    val historySize: Int get() = historySizeNow

    /**
     * Logical line by absolute index (0 = oldest history line).
     *
     * ⚠ 环形缓冲下**不能**写 `history[historyHead + index]`：
     * historyHead 可能是后半段的任何位置，historyHead + index 会直接越界。
     * 必须走 historyAt() 做取模换算。
     */
    fun lineAt(index: Int): TermLine {
        val h = historySizeNow
        if (index < h) {
            historyAt(index)?.let { return it }
        }
        return screen[(index - h).coerceIn(0, rows - 1)]
    }

    fun scrollBy(delta: Int) {
        if (delta == 0) return
        val max = historySizeNow
        scrollOffset = (scrollOffset + delta).coerceIn(0, max)
        dirty = true
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) { scrollOffset = 0; dirty = true }
    }

    // ------------------------------------------------------------------ output

    fun putChar(ch: Char) {
        if (pendingWrap && autoWrap) {
            cursorCol = 0
            lineFeed()
            // This new row continues the previous one, so remember it for reflow.
            screen[cursorRow].wrapped = true
            pendingWrap = false
        }
        val wide = isWide(ch)
        if (wide && cursorCol == cols - 1) {
            if (autoWrap) { cursorCol = 0; lineFeed(); screen[cursorRow].wrapped = true } else cursorCol = cols - 2
        }
        val cell = screen[cursorRow]
        val span = if (wide) 2 else 1
        if (insertMode) insertChars(span)
        cell.chars[cursorCol] = ch
        cell.fg[cursorCol] = penFg
        cell.bg[cursorCol] = penBg
        cell.attr[cursorCol] = penAttr.toByte()
        if (wide && cursorCol + 1 < cols) {
            cell.chars[cursorCol + 1] = '\u0000'
            cell.fg[cursorCol + 1] = penFg
            cell.bg[cursorCol + 1] = penBg
            cell.attr[cursorCol + 1] = (penAttr or ATTR_WIDE_CONT).toByte()
        }
        cursorCol += span
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            pendingWrap = true
        }
        lastPrintable = ch
        dirty = true
    }

    fun carriageReturn() {
        cursorCol = 0
        pendingWrap = false
        dirty = true
    }

    fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
        dirty = true
    }

    fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
        dirty = true
    }

    fun backspace() {
        if (pendingWrap) { pendingWrap = false; return }
        if (cursorCol > 0) cursorCol--
        dirty = true
    }

    fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = if (next >= cols) cols - 1 else next
        pendingWrap = false
        dirty = true
    }

    fun bell() { /* no audible bell; the view may flash */ }

    // ------------------------------------------------------------- scrolling

    private fun scrollUp(n: Int) {
        if (!altActive && scrollTop == 0 && scrollBottom == rows - 1) {
            repeat(n) {
                pushHistory(screen[0])
                for (r in 1 until rows) screen[r - 1] = screen[r]
                screen[rows - 1] = TermLine(cols)
            }
        } else {
            repeat(n) {
                for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
                screen[scrollBottom] = TermLine(cols)
            }
        }
        dirty = true
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = TermLine(cols)
        }
        dirty = true
    }

    // ------------------------------------------------------------ cursor ops

    fun setCursor(row: Int, col: Int) {
        cursorRow = (row - 1).coerceIn(0, rows - 1)
        cursorCol = (col - 1).coerceIn(0, cols - 1)
        pendingWrap = false
        dirty = true
    }

    fun setColumn(col: Int) { cursorCol = (col - 1).coerceIn(0, cols - 1); pendingWrap = false; dirty = true }
    fun setRow(row: Int) { cursorRow = (row - 1).coerceIn(0, rows - 1); pendingWrap = false; dirty = true }

    fun cursorUp(n: Int) { cursorRow = (cursorRow - n).coerceAtLeast(scrollTop); pendingWrap = false; dirty = true }
    fun cursorDown(n: Int) { cursorRow = (cursorRow + n).coerceAtMost(scrollBottom); pendingWrap = false; dirty = true }
    fun cursorForward(n: Int) { cursorCol = (cursorCol + n).coerceAtMost(cols - 1); pendingWrap = false; dirty = true }
    fun cursorBack(n: Int) { cursorCol = (cursorCol - n).coerceAtLeast(0); pendingWrap = false; dirty = true }
    fun cursorNextLine(n: Int) { cursorDown(n); cursorCol = 0 }
    fun cursorPrevLine(n: Int) { cursorUp(n); cursorCol = 0 }

    fun tabForward(n: Int) { repeat(n) { tab() } }

    fun saveCursor() {
        saved.row = cursorRow; saved.col = cursorCol
        saved.fg = penFg; saved.bg = penBg; saved.attr = penAttr
    }

    fun restoreCursor() {
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        penFg = saved.fg; penBg = saved.bg; penAttr = saved.attr
        pendingWrap = false
        dirty = true
    }

    // ------------------------------------------------------------- erase ops

    fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cursorRow + 1 until rows) screen[r].reset() }
            1 -> { eraseLine(1); for (r in 0 until cursorRow) screen[r].reset() }
            2, 3 -> { screen.forEach { it.reset() }; if (mode == 3) clearHistory() }
        }
        dirty = true
    }

    fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) clearCell(line, c)
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) clearCell(line, c)
            2 -> line.reset()
        }
        dirty = true
    }

    fun eraseChars(n: Int) {
        val line = screen[cursorRow]
        for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) clearCell(line, c)
        dirty = true
    }

    private fun clearCell(line: TermLine, c: Int) {
        line.chars[c] = ' '
        line.fg[c] = penFg
        line.bg[c] = penBg
        line.attr[c] = 0
    }

    fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (r in scrollBottom downTo cursorRow + 1) screen[r] = screen[r - 1]
            screen[cursorRow] = TermLine(cols)
        }
        dirty = true
    }

    fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(n.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (r in cursorRow until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = TermLine(cols)
        }
        dirty = true
    }

    fun insertChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + count) {
            line.chars[c] = line.chars[c - count]; line.fg[c] = line.fg[c - count]
            line.bg[c] = line.bg[c - count]; line.attr[c] = line.attr[c - count]
        }
        for (c in cursorCol until cursorCol + count) clearCell(line, c)
        dirty = true
    }

    fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val count = n.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - count) {
            line.chars[c] = line.chars[c + count]; line.fg[c] = line.fg[c + count]
            line.bg[c] = line.bg[c + count]; line.attr[c] = line.attr[c + count]
        }
        for (c in cols - count until cols) clearCell(line, c)
        dirty = true
    }

    fun setScrollingRegion(top: Int, bottom: Int) {
        val t = (top - 1).coerceIn(0, rows - 1)
        val b = (bottom - 1).coerceIn(0, rows - 1)
        if (t < b) { scrollTop = t; scrollBottom = b }
        else { scrollTop = 0; scrollBottom = rows - 1 }
        cursorRow = scrollTop
        cursorCol = 0
        dirty = true
    }

    // --------------------------------------------------------------- modes

    fun setMode(enabled: Boolean, code: Int) {
        when (code) {
            4 -> insertMode = enabled
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            1 -> applicationCursorKeys = enabled
            47, 1047, 1049 -> if (enabled) enterAltScreen(code == 1049) else leaveAltScreen(code == 1049)
            2004 -> bracketedPaste = enabled
        }
        dirty = true
    }

    private fun enterAltScreen(saveCursor: Boolean) {
        if (altActive) return
        if (saveCursor) { altSaved.row = cursorRow; altSaved.col = cursorCol }
        altScreen = screen
        screen = Array(rows) { TermLine(cols) }
        altActive = true
        cursorRow = 0; cursorCol = 0
        pendingWrap = false
    }

    private fun leaveAltScreen(restoreCursor: Boolean) {
        if (!altActive) return
        screen = altScreen ?: Array(rows) { TermLine(cols) }
        altScreen = null
        altActive = false
        if (restoreCursor) { cursorRow = altSaved.row; cursorCol = altSaved.col }
        pendingWrap = false
    }

    fun setWindowTitle(t: String) { windowTitle = t }

    fun reset() {
        screen.forEach { it.reset() }
        clearHistory()
        cursorRow = 0; cursorCol = 0; scrollTop = 0; scrollBottom = rows - 1
        scrollOffset = 0
        penFg = TermPalette.DEFAULT_FG; penBg = TermPalette.DEFAULT_BG; penAttr = 0
        cursorVisible = true; autoWrap = true; insertMode = false
        applicationCursorKeys = false; bracketedPaste = false
        pendingWrap = false
        dirty = true
    }

    // ------------------------------------------------------------------ SGR

    fun setGraphicsRendition(params: IntArray) {
        if (params.isEmpty()) { resetPen(); return }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> resetPen()
                p == 1 -> penAttr = penAttr or ATTR_BOLD
                p == 2 -> penAttr = penAttr or ATTR_DIM
                p == 3 -> penAttr = penAttr or ATTR_ITALIC
                p == 4 || p == 21 -> penAttr = penAttr or ATTR_UNDERLINE
                p == 5 || p == 6 -> penAttr = penAttr or ATTR_BLINK
                p == 7 -> penAttr = penAttr or ATTR_REVERSE
                p == 9 -> penAttr = penAttr or ATTR_STRIKE
                p == 22 -> penAttr = penAttr and (ATTR_BOLD or ATTR_DIM).inv()
                p == 23 -> penAttr = penAttr and ATTR_ITALIC.inv()
                p == 24 -> penAttr = penAttr and ATTR_UNDERLINE.inv()
                p == 25 -> penAttr = penAttr and ATTR_BLINK.inv()
                p == 27 -> penAttr = penAttr and ATTR_REVERSE.inv()
                p == 29 -> penAttr = penAttr and ATTR_STRIKE.inv()
                p in 30..37 -> penFg = TermPalette.ANSI[p - 30]
                p == 39 -> penFg = TermPalette.DEFAULT_FG
                p in 40..47 -> penBg = TermPalette.ANSI[p - 40]
                p == 49 -> penBg = TermPalette.DEFAULT_BG
                p in 90..97 -> penFg = TermPalette.ANSI[p - 90 + 8]
                p in 100..107 -> penBg = TermPalette.ANSI[p - 100 + 8]
                p == 38 || p == 48 -> {
                    // extended color: 5;n  or  2;r;g;b
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> if (i + 2 < params.size) {
                                val c = TermPalette.xterm256(params[i + 2])
                                if (p == 38) penFg = c else penBg = c
                                i += 2
                            }
                            2 -> if (i + 4 < params.size) {
                                val c = (0xFF shl 24) or (params[i + 2].coerceIn(0, 255) shl 16) or
                                        (params[i + 3].coerceIn(0, 255) shl 8) or params[i + 4].coerceIn(0, 255)
                                if (p == 38) penFg = c else penBg = c
                                i += 4
                            }
                        }
                    }
                }
            }
            i++
        }
        dirty = true
    }

    private fun resetPen() {
        penFg = TermPalette.DEFAULT_FG
        penBg = TermPalette.DEFAULT_BG
        penAttr = 0
        dirty = true
    }

    // --------------------------------------------------------------- resize

    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceAtLeast(1)
        val c = newCols.coerceAtLeast(1)
        if (r == rows && c == cols) return

        if (altActive) {
            // Full-screen programs redraw themselves on SIGWINCH; only crop/pad.
            val next = Array(r) { i -> if (i < rows) screen[i].withColsSimple(c) else TermLine(c) }
            screen = next
            rows = r; cols = c
            scrollTop = 0; scrollBottom = rows - 1
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            pendingWrap = false
            dirty = true
            return
        }

        // Re-wrap soft-wrapped logical lines (this is what makes the layout
        // reflow when the font size or the keyboard changes the column count).
        val logical = ArrayList<TermLine>(totalLines + 8)
        if (c != cols) {
            var group = ArrayList<TermLine>()
            for (i in 0 until totalLines) {
                val line = lineAt(i)
                if (i > 0 && !line.wrapped && group.isNotEmpty()) {
                    logical.addAll(reflowGroup(group, c)); group = ArrayList()
                }
                group.add(line)
            }
            if (group.isNotEmpty()) logical.addAll(reflowGroup(group, c))
        } else {
            for (i in 0 until totalLines) logical.add(lineAt(i).copyLine())
        }

        val keepVisible = minOf(r, logical.size)
        val historyCount = logical.size - keepVisible
        clearHistory()
        // 只保留最后 scrollbackLimit 行，避免一次性灌满后又要逐行搬移
        val from = (historyCount - scrollbackLimit).coerceAtLeast(0)
        for (i in from until historyCount) pushHistory(logical[i])
        val nextScreen = Array(r) { TermLine(c) }
        val offset = r - keepVisible
        for (i in 0 until keepVisible) nextScreen[offset + i] = logical[historyCount + i]
        screen = nextScreen

        rows = r; cols = c
        scrollTop = 0; scrollBottom = rows - 1
        scrollOffset = 0
        pendingWrap = false
        // Put the cursor back at the end of the last visible content (the prompt).
        var rr = rows - 1
        while (rr > 0 && screen[rr].lastNonBlank() < 0) rr--
        cursorRow = rr
        cursorCol = (screen[rr].lastNonBlank() + 1).coerceIn(0, cols - 1)
        dirty = true
    }

    /** Flatten a logical (soft-wrapped) line and re-wrap it to [newCols]. */
    private fun reflowGroup(group: List<TermLine>, newCols: Int): List<TermLine> {
        val chars = ArrayList<Char>(group.size * 40)
        val fgs = ArrayList<Int>(group.size * 40)
        val bgs = ArrayList<Int>(group.size * 40)
        val ats = ArrayList<Byte>(group.size * 40)
        group.forEach { line ->
            val end = line.lastNonBlank() + 1
            var i = 0
            while (i < end) {
                val at = line.attr[i].toInt()
                if (at and ATTR_WIDE_CONT != 0) { i++; continue }
                chars.add(line.chars[i]); fgs.add(line.fg[i]); bgs.add(line.bg[i]); ats.add(line.attr[i])
                i += if (isWide(line.chars[i])) 2 else 1
            }
        }
        val out = ArrayList<TermLine>(group.size + 2)
        var row = TermLine(newCols)
        var col = 0
        for (k in chars.indices) {
            val ch = chars[k]
            val span = if (isWide(ch)) 2 else 1
            if (col + span > newCols) {
                row.wrapped = true
                out.add(row); row = TermLine(newCols); col = 0
            }
            row.chars[col] = ch; row.fg[col] = fgs[k]; row.bg[col] = bgs[k]; row.attr[col] = ats[k]
            if (span == 2 && col + 1 < newCols) {
                row.chars[col + 1] = '\u0000'
                row.fg[col + 1] = fgs[k]; row.bg[col + 1] = bgs[k]
                row.attr[col + 1] = (ats[k].toInt() or ATTR_WIDE_CONT).toByte()
            }
            col += span
        }
        out.add(row)
        return out
    }

    // ------------------------------------------------------------- selection

    /** Plain text of the whole scrollback (used by "copy all"). */
    fun fullText(): String {
        val sb = StringBuilder()
        for (i in 0 until totalLines) {
            val l = lineAt(i)
            val end = l.lastNonBlank()
            for (c in 0..end) {
                val a = l.attr[c].toInt()
                if (a and ATTR_WIDE_CONT != 0) continue
                sb.append(l.chars[c])
            }
            if (i != totalLines - 1) sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** Text between two (row,col) points in logical line coordinates. */
    fun textBetween(r1: Int, c1: Int, r2: Int, c2: Int): String {
        var a = r1 to c1
        var b = r2 to c2
        if (a.first > b.first || (a.first == b.first && a.second > b.second)) { val t = a; a = b; b = t }
        val sb = StringBuilder()
        for (r in a.first..b.first) {
            val l = lineAt(r.coerceIn(0, totalLines - 1))
            val from = if (r == a.first) a.second.coerceIn(0, cols - 1) else 0
            val to = if (r == b.first) b.second.coerceIn(0, cols - 1) else cols - 1
            for (c in from..to) {
                val at = l.attr[c].toInt()
                if (at and ATTR_WIDE_CONT != 0) continue
                sb.append(l.chars[c])
            }
            if (r != b.first) sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    companion object {
        /** East-Asian wide detection (good enough for CJK + emoji planes). */
        fun isWide(ch: Char): Boolean {
            val c = ch.code
            return (c in 0x1100..0x115F) || (c in 0x2E80..0x303E) || (c in 0x3041..0x33FF) ||
                (c in 0x3400..0x4DBF) || (c in 0x4E00..0x9FFF) || (c in 0xA000..0xA4CF) ||
                (c in 0xAC00..0xD7A3) || (c in 0xF900..0xFAFF) || (c in 0xFE30..0xFE4F) ||
                (c in 0xFF00..0xFF60) || (c in 0xFFE0..0xFFE6)
        }
    }
}
