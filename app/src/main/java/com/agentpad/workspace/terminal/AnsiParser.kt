package com.agentpad.workspace.terminal

/**
 * Streaming VT100/xterm escape-sequence parser.
 *
 * It consumes raw bytes coming out of the PTY (already decoded as a String in
 * "UTF-8 with surrogate escapes" form via [decodePtyBytes]) and drives an
 * [AnsiScreen]. The parser is byte-oriented in spirit but works on Char values
 * so it can also process lone 8-bit control codes emitted by legacy tools.
 *
 * Handled:
 *  - C0 controls: BEL BS HT LF VT FF CR SO SI ESC
 *  - ESC sequences: 7 8 = > D E H M ( ) # c
 *  - CSI: cursor movement, erase, insert/delete, SGR, modes, scroll region,
 *         device status / window reports (answered through [onReply])
 *  - OSC: window title (0/1/2), ignored others; terminated by BEL or ST
 *  - DCS/SOS/PM/APC: consumed and discarded until ST
 */
class AnsiParser(private val screen: AnsiScreen) {

    /** Bytes to write back to the PTY (e.g. cursor-position report). */
    var onReply: ((String) -> Unit)? = null

    private enum class State { GROUND, ESC, CSI, OSC, STRING }

    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private val stringBuffer = StringBuilder()
    private val csiIntermediate = StringBuilder()
    private var savedDecState = false

    fun feed(text: String) {
        for (ch in text) process(ch)
    }

    fun reset() {
        state = State.GROUND
        csiParams.setLength(0)
        stringBuffer.setLength(0)
        csiIntermediate.setLength(0)
    }

    private fun process(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> escape(ch)
            State.CSI -> csi(ch)
            State.OSC -> osc(ch)
            State.STRING -> stringState(ch)
        }
    }

    // ------------------------------------------------------------------ ground

    private fun ground(ch: Char) {
        when (ch) {
            '\u001b' -> { state = State.ESC }
            '\u0007' -> screen.bell()
            '\u0008' -> screen.backspace()
            '\u0009' -> screen.tab()
            '\n', '\u000b', '\u000c' -> screen.lineFeed()
            '\r' -> screen.carriageReturn()
            '\u000e', '\u000f' -> { /* SO/SI charset shift: ignore */ }
            else -> if (ch.code < 0x20) {
                /* other C0 controls: drop */
            } else {
                screen.putChar(ch)
            }
        }
    }

    // --------------------------------------------------------------------- ESC

    private fun escape(ch: Char) {
        state = State.GROUND
        when (ch) {
            '[' -> { csiParams.setLength(0); csiIntermediate.setLength(0); state = State.CSI }
            ']' -> { stringBuffer.setLength(0); state = State.OSC }
            'P', 'X', '^', '_' -> { stringBuffer.setLength(0); state = State.STRING } // DCS/SOS/PM/APC
            '7' -> screen.saveCursor()
            '8' -> screen.restoreCursor()
            'D' -> screen.lineFeed()
            'E' -> { screen.carriageReturn(); screen.lineFeed() }
            'M' -> screen.reverseIndex()
            'H' -> { /* HTS: horizontal tab set — tabs are fixed-width here */ }
            'c' -> screen.reset()
            '=' -> { /* DECKPAM: keypad application mode */ }
            '>' -> { /* DECKPNM */ }
            '#', '(', ')', '*', '+', '%', ' ' -> { /* charset / alignment selectors: consume designator */ }
        }
    }

    // --------------------------------------------------------------------- CSI

    private fun csi(ch: Char) {
        when {
            // Parameter bytes: digits, ';', ':' and the private prefixes ? > ! <
            // (the '?' of e.g. ESC[?25l / ESC[?1049h MUST be kept, otherwise the
            // whole sequence is dropped and its digits leak as literal text).
            ch in '0'..'9' || ch == ';' || ch == ':' || ch == '?' || ch == '>' || ch == '!' || ch == '<' ->
                csiParams.append(ch)
            ch in ' '..'/' -> csiIntermediate.append(ch)
            ch in '@'..'~' -> {
                state = State.GROUND
                dispatchCsi(ch)
                csiParams.setLength(0)
                csiIntermediate.setLength(0)
            }
            ch == '\u001b' -> { state = State.ESC }
            else -> state = State.GROUND
        }
    }

    private fun params(): IntArray {
        val raw = csiParams.toString().trimStart('?', '>', '!')
        if (raw.isEmpty()) return IntArray(0)
        // colon is used as a sub-parameter separator (e.g. SGR 38:5:n) — treat like ';'
        return raw.replace(':', ';').split(';').map { part ->
            if (part.isEmpty()) 0 else part.toIntOrNull() ?: 0
        }.toIntArray()
    }

    private fun arg(p: IntArray, index: Int, def: Int = 1): Int {
        val v = p.getOrNull(index) ?: 0
        return if (v == 0) def else v
    }

    private fun dispatchCsi(final: Char) {
        if (csiIntermediate.isNotEmpty()) {
            // DEC private "?" prefix is stored in csiParams as leading '?', so
            // intermediates here are rare; only handle a few explicitly.
            val interm = csiIntermediate.toString()
            if (interm == "!" && final == 'p') { screen.reset(); return } // DECSTR soft reset
            return
        }
        val p = params()
        when (final) {
            'A' -> screen.cursorUp(arg(p, 0))
            'B' -> screen.cursorDown(arg(p, 0))
            'C' -> screen.cursorForward(arg(p, 0))
            'D' -> screen.cursorBack(arg(p, 0))
            'E' -> screen.cursorNextLine(arg(p, 0))
            'F' -> screen.cursorPrevLine(arg(p, 0))
            'G', '`' -> screen.setColumn(arg(p, 0))
            'd' -> screen.setRow(arg(p, 0))
            'H', 'f' -> screen.setCursor(arg(p, 0), arg(p, 1))
            'I' -> screen.tabForward(arg(p, 0))
            'Z' -> repeat(arg(p, 0)) { screen.cursorBack(8) }
            'J' -> screen.eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> screen.eraseLine(p.getOrNull(0) ?: 0)
            'X' -> screen.eraseChars(arg(p, 0))
            'L' -> screen.insertLines(arg(p, 0))
            'M' -> screen.deleteLines(arg(p, 0))
            '@' -> screen.insertChars(arg(p, 0))
            'P' -> screen.deleteChars(arg(p, 0))
            'S' -> repeat(arg(p, 0)) { screen.lineFeed() }
            'T' -> repeat(arg(p, 0)) { screen.reverseIndex() }
            'r' -> screen.setScrollingRegion(arg(p, 0), arg(p, 1, screen.rows))
            'm' -> screen.setGraphicsRendition(p)
            'h' -> p.forEach { screen.setMode(true, it) }
            'l' -> p.forEach { screen.setMode(false, it) }
            's' -> screen.saveCursor()
            'u' -> screen.restoreCursor()
            'c' -> onReply?.invoke("\u001b[?1;2c")           // primary device attributes
            'n' -> when (p.getOrNull(0)) {
                5 -> onReply?.invoke("\u001b[0n")            // device status OK
                6 -> onReply?.invoke("\u001b[${screen.cursorRow + 1};${screen.cursorCol + 1}R")
            }
            't' -> { /* window manipulation: ignore */ }
            'g' -> { /* tab clear: tabs fixed here */ }
        }
    }

    // --------------------------------------------------------------------- OSC

    private fun osc(ch: Char) {
        when (ch) {
            '\u0007' -> { finishOsc(); state = State.GROUND }
            '\u001b' -> { finishOsc(); state = State.ESC }
            else -> if (stringBuffer.length < 2048) stringBuffer.append(ch)
        }
    }

    private fun finishOsc() {
        val s = stringBuffer.toString()
        // 0/1/2 => set window title; format "<code>;<title>"
        val semi = s.indexOf(';')
        if (semi > 0) {
            val code = s.substring(0, semi)
            if (code == "0" || code == "1" || code == "2") {
                screen.setWindowTitle(s.substring(semi + 1))
            }
        }
        stringBuffer.setLength(0)
    }

    private fun stringState(ch: Char) {
        // Consume DCS/SOS/PM/APC payload until ST (ESC \) or BEL.
        if (ch == '\u0007') { state = State.GROUND; return }
        if (ch == '\u001b') { savedDecState = true; return }
        if (savedDecState) {
            savedDecState = false
            state = State.GROUND
            if (ch == '\\') return
            state = State.STRING
        }
    }

    companion object {
        /**
         * Decode raw PTY bytes into a String the screen can consume.
         *
         * PTY output is normally UTF-8, but interactive programs (readline, vim)
         * keep multi-byte input in a line editor and re-emit it byte by byte.
         * Decoding each read() independently would corrupt split sequences, so
         * we carry the partial tail across calls via [carry].
         */
        fun decodePtyBytes(bytes: ByteArray, length: Int, carry: ByteArray?): Pair<String, ByteArray?> {
            var data = bytes
            var len = length
            if (carry != null && carry.isNotEmpty()) {
                val merged = ByteArray(carry.size + length)
                System.arraycopy(carry, 0, merged, 0, carry.size)
                System.arraycopy(bytes, 0, merged, carry.size, length)
                data = merged
                len = merged.size
            }
            var end = len
            // Find the longest trailing prefix that is an incomplete UTF-8 char.
            var cut = end
            var back = 0
            while (cut > 0 && back < 4) {
                val b = data[cut - 1].toInt() and 0xFF
                if (b and 0x80 == 0) break            // ASCII: complete
                if (b and 0xC0 == 0xC0) {             // leading byte
                    val need = when {
                        b and 0xE0 == 0xC0 -> 2
                        b and 0xF0 == 0xE0 -> 3
                        b and 0xF8 == 0xF0 -> 4
                        else -> 1
                    }
                    if (back + 1 < need) { cut -= 1; break }
                    cut -= 1
                    break
                }
                back++
                cut--
            }
            if (cut >= end) cut = end
            val text = decodeUtf8(data, 0, cut)
            val rest = if (cut < end) data.copyOfRange(cut, end) else null
            return text to rest
        }

        private fun decodeUtf8(buf: ByteArray, from: Int, to: Int): String {
            val sb = StringBuilder(to - from)
            var i = from
            while (i < to) {
                val b0 = buf[i].toInt() and 0xFF
                when {
                    b0 < 0x80 -> { sb.append(b0.toChar()); i++ }
                    b0 and 0xE0 == 0xC0 && i + 1 < to -> {
                        sb.append((((b0 and 0x1F) shl 6) or (buf[i + 1].toInt() and 0x3F)).toChar()); i += 2
                    }
                    b0 and 0xF0 == 0xE0 && i + 2 < to -> {
                        sb.append((((b0 and 0x0F) shl 12) or ((buf[i + 1].toInt() and 0x3F) shl 6) or
                                (buf[i + 2].toInt() and 0x3F)).toChar()); i += 3
                    }
                    b0 and 0xF8 == 0xF0 && i + 3 < to -> {
                        val cp = ((b0 and 0x07) shl 18) or ((buf[i + 1].toInt() and 0x3F) shl 12) or
                                ((buf[i + 2].toInt() and 0x3F) shl 6) or (buf[i + 3].toInt() and 0x3F)
                        val c = cp - 0x10000
                        sb.append(((c shr 10) + 0xD800).toChar())
                        sb.append(((c and 0x3FF) + 0xDC00).toChar())
                        i += 4
                    }
                    else -> { sb.append('\uFFFD'); i++ }
                }
            }
            return sb.toString()
        }
    }
}