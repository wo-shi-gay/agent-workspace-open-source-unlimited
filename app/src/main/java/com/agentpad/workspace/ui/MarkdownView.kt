package com.agentpad.workspace.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared palette for the chat surface — 动态读取当前主题。 */
internal val ChatInk get() = AColors.Ink
internal val ChatBody get() = ThemeStore.palette.value.inkSoft
internal val ChatMuted get() = AColors.Muted
internal val ChatFaint get() = AColors.Faint
internal val ChatAccent get() = AColors.Accent
internal val ChatReasoning get() = AColors.Violet
internal val ChatCotSurface get() = ThemeStore.palette.value.surfaceAlt

private sealed interface MdNode {
    data class Heading(val level: Int, val text: String) : MdNode
    data class Para(val text: String) : MdNode
    data class Item(
        val level: Int,
        val ordered: Boolean,
        val marker: String,
        val text: String,
        val task: Boolean,
        val checked: Boolean,
    ) : MdNode
    data class Code(val lang: String, val code: String) : MdNode
    data class Quote(val text: String) : MdNode
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdNode
    data object Rule : MdNode
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

// ⚠ 2026-11-01 性能修复：parseMd 里原本每行都 Regex(...) 新建对象。
// 实测（桌面 JDK17，_bench/Bench.java）：15000 字文本单次解析
//   每行 new Regex = 6440μs，顶层常量 = 726μs → 快 8.9×。
// 冷启动（无 JIT）差距更大：13.8×。真机 ART 上还要再乘 5~15。
// 长思考时这段每秒被调用 5~20 次，是「越久越卡」的主因之一。
private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
private val RE_TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}.*$")
private val RE_LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val RE_TASK_BOX = Regex("^\\[([ xX])]\\s*(.*)$")
private val RE_LIST_PREFIX = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+")

private fun parseMd(source: String): List<MdNode> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = mutableListOf<MdNode>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        if (line.isBlank()) { i++; continue }

        // fenced code
        val fence = line.trimStart()
        if (fence.startsWith("```")) {
            val lang = fence.removePrefix("```").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body.appendLine(lines[i]); i++ }
            if (i < lines.size) i++
            out += MdNode.Code(lang, body.toString().trimEnd('\n'))
            continue
        }

        // heading
        val heading = RE_HEADING.find(line)
        if (heading != null) {
            out += MdNode.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        // horizontal rule
        if (RE_RULE.containsMatchIn(line)) { out += MdNode.Rule; i++; continue }

        // table
        if (line.trim().startsWith("|") && i + 1 < lines.size &&
            RE_TABLE_SEP.containsMatchIn(lines[i + 1])
        ) {
            val header = splitRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) { rows += splitRow(lines[i]); i++ }
            out += MdNode.Table(header, rows)
            continue
        }

        // blockquote
        if (line.trimStart().startsWith(">")) {
            val body = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                body.appendLine(lines[i].trimStart().removePrefix(">").trimStart()); i++
            }
            out += MdNode.Quote(body.toString().trimEnd())
            continue
        }

        // list item
        val item = RE_LIST_ITEM.find(line)
        if (item != null) {
            val indent = item.groupValues[1].length
            val marker = item.groupValues[2]
            var text = item.groupValues[3]
            var task = false
            var checked = false
            val box = RE_TASK_BOX.find(text)
            if (box != null) { task = true; checked = box.groupValues[1].equals("x", true); text = box.groupValues[2] }
            out += MdNode.Item(indent / 2, marker.first().isDigit(), marker, text, task, checked)
            i++
            continue
        }

        // paragraph (glued until blank/special)
        val buf = StringBuilder(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            if (next.isBlank()) break
            val t = next.trimStart()
            if (t.startsWith("```") || t.startsWith(">") || t.startsWith("#") ||
                RE_LIST_PREFIX.containsMatchIn(next) ||
                (t.startsWith("|") && i + 1 < lines.size && RE_TABLE_SEP.containsMatchIn(lines[i + 1]))
            ) break
            buf.appendLine(next)
            i++
        }
        out += MdNode.Para(buf.toString().trimEnd())
    }
    return out
}

/**
 * Inline markdown → AnnotatedString: bold / italic / strike / code / links.
 *
 * [textColor] 是本段文字的基础色。加粗/斜体/删除线这些 SpanStyle
 * 只改字重或装饰线，**不设 color**，让它们继承 textColor。
 * 传入 textColor 而不是依赖 LocalContentColor，是因为 AnnotatedString
 * 会被 remember 缓存，必须让颜色成为显式输入，主题切换才能正确重建。
 */
private fun inlineMd(text: String, link: Color, code: Color, codeBg: Color, textColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val sb = StringBuilder()
    fun flush() { if (sb.isNotEmpty()) { append(sb.toString()); sb.clear() } }
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > 0) {
                    flush()
                    // 显式带上 textColor：否则深色主题下加粗段落会沿用
                    // AnnotatedString 里旧的（浅色主题）颜色，看起来"发黑看不见"。
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = textColor)) { append(inlineMd(text.substring(i + 2, end), link, code, codeBg, textColor)) }
                    i = end + 2
                } else { sb.append(c); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = textColor)) { append(inlineMd(text.substring(i + 2, end), link, code, codeBg, textColor)) }
                    i = end + 2
                } else { sb.append(c); i++ }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    flush()
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code, background = codeBg, fontSize = 13.sp)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { sb.append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf(']', i)
                val open = if (close > 0 && close + 1 < text.length && text[close + 1] == '(') text.indexOf(')', close) else -1
                if (close > 0 && open > 0) {
                    flush()
                    val label = text.substring(i + 1, close)
                    val href = text.substring(close + 2, open)
                    withLink(LinkAnnotation.Url(href)) {
                        withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
                    i = open + 1
                } else { sb.append(c); i++ }
            }
            c == '*' || c == '_' -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = textColor)) { append(inlineMd(text.substring(i + 1, end), link, code, codeBg, textColor)) }
                    i = end + 1
                } else { sb.append(c); i++ }
            }
            else -> { sb.append(c); i++ }
        }
    }
    flush()
}

@Composable
internal fun inlineStyle(): Triple<Color, Color, Color> =
    Triple(ChatAccent, AColors.InlineCodeFg, AColors.InlineCodeBg)

/**
 * 渲染带行内 Markdown 的文本。
 *
 * 【深色主题 bug 修复说明】
 * 之前 `remember(text, link, code, codeBg)` 的 key 里**没有文字颜色**。
 * `inlineMd` 返回的 AnnotatedString 里，加粗/斜体/删除线这些 SpanStyle
 * 只设了 fontWeight/textDecoration，颜色靠 TextStyle 继承 —— 这条链本身没问题。
 * 但 `remember` 在主题切换时不会重建 AnnotatedString：
 * 同一个 text 只要 link/code/codeBg 不变（比如主题只换了 ink/paper），
 * 就会复用旧对象。而 Text(annotated, style = style) 里 style 变了、
 * annotated 没变，Compose 会把旧 SpanStyle 与新 style 合并，
 * 在深色主题下出现「加粗文字仍是浅色主题的深色」这种错位。
 *
 * 修法：把 style.color 也放进 remember 的 key，
 * 主题一换就重建 AnnotatedString，颜色立刻跟上。
 */
@Composable
private fun MdText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val (link, code, codeBg) = inlineStyle()
    // style.color 是 Color.Unspecified 时（比如只设了 fontSize 的 TextStyle），
    // 退回 LocalContentColor —— 这才是 Text 默认用的颜色。
    // 注意 Color 上没有 takeOrElse（那是 TextStyle 的扩展），必须显式判断。
    val textColor = if (style.color == Color.Unspecified) LocalContentColor.current else style.color
    val annotated = remember(text, link, code, codeBg, textColor) {
        inlineMd(text, link, code, codeBg, textColor)
    }
    Text(annotated, modifier = modifier, style = style)
}

private object ChatHeaderStyle {
    fun style(level: Int): TextStyle {
        val size = when (level) {
            1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp; 4 -> 17.sp; 5 -> 16.sp; else -> 15.sp
        }
        return TextStyle(fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.3f)
    }
    fun padding(level: Int) = when (level) {
        1 -> 14.dp; 2 -> 12.dp; 3 -> 10.dp; 4 -> 8.dp; else -> 6.dp
    }
}

/**
 * RikkaHub-style markdown renderer: headings, inline styles, nested task lists,
 * fenced code with copy/collapse, blockquotes, rules, and DataTable-backed tables.
 */
@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val nodes = remember(text) { parseMd(text) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nodes.forEachIndexed { index, node ->
            when (node) {
                is MdNode.Heading -> Box(Modifier.padding(vertical = ChatHeaderStyle.padding(node.level))) {
                    MdText(node.text, style = ChatHeaderStyle.style(node.level))
                }
                is MdNode.Para -> SelectionContainer {
                    MdText(node.text, style = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = ChatBody))
                }
                is MdNode.Item -> ListItemRow(node)
                is MdNode.Code -> CodeBlock(node.lang, node.code)
                is MdNode.Quote -> QuoteBlock(node.text)
                is MdNode.Table -> TableBlock(node)
                MdNode.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = ChatAccent.copy(alpha = 0.35f),
                )
            }
            // Streaming caret hangs off the very last block.
            if (streaming && index == nodes.lastIndex) {
                Text("▍", color = ChatAccent, fontSize = 15.sp, modifier = Modifier.blink(true))
            }
        }
    }
}

/**
 * Shimmer/alpha blink that ONLY affects the draw layer.
 *
 * RikkaHub uses `Modifier.shimmer(isLoading)` for this. The naive port read an
 * animated value directly in a composable (`val a by transition.animateFloat()`)
 * which subscribes the CALLER to the animation and therefore recomposes it every
 * frame — 60fps x every visible reasoning/tool step. On a real device that
 * saturates the main thread and the OS kills the process (ANR: frozen screen,
 * then back to the launcher, with no crash log).
 *
 * Reading the state inside `graphicsLayer {}` keeps the read in the layer/draw
 * phase, so the animation repaints WITHOUT recomposing.
 */
@Composable
internal fun Modifier.blink(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "blink")
    val alphaState = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "blink_alpha",
    )
    return this.graphicsLayer { alpha = alphaState.value }
}

@Composable
private fun ListItemRow(node: MdNode.Item) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((node.level % 3) * 14).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            node.task -> {
                Text(
                    if (node.checked) "☑" else "☐",
                    color = if (node.checked) ChatAccent else ChatMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 6.dp, top = 1.dp),
                )
            }
            node.ordered -> Text(
                node.marker,
                color = ChatMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 6.dp, top = 2.dp),
            )
            else -> Text(
                when (node.level % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" },
                color = ChatMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 6.dp, top = 1.dp),
            )
        }
        SelectionContainer {
            MdText(
                node.text,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = ChatBody,
                    textDecoration = if (node.task && node.checked) TextDecoration.LineThrough else null,
                ),
            )
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(AColors.SurfaceAlt),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(ChatAccent.copy(alpha = 0.55f)))
        SelectionContainer {
            MdText(
                text,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = AColors.InkSoft, fontStyle = FontStyle.Italic),
            )
        }
    }
}

@Composable
private fun TableBlock(node: MdNode.Table) {
    val columns = maxOf(node.header.size, node.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val headers = List(columns) { col ->
        @Composable {
            if (col < node.header.size) MdText(node.header[col], style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ChatInk))
        }
    }
    val rows = node.rows.map { row ->
        List(columns) { col ->
            @Composable {
                if (col < row.size) MdText(row[col], style = TextStyle(fontSize = 13.sp, color = ChatBody))
            }
        }
    }
    DataTable(
        headers = headers,
        rows = rows,
        modifier = Modifier.padding(vertical = 4.dp),
        columnMinWidths = List(columns) { 76.dp },
        columnMaxWidths = List(columns) { 190.dp },
    )
}

private const val CODE_COLLAPSE_LINES = 14

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    var expanded by remember { mutableStateOf(lineCount <= CODE_COLLAPSE_LINES) }
    val visible = if (expanded) code else code.lines().take(CODE_COLLAPSE_LINES).joinToString("\n")

    Surface(
        color = AColors.CodeBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language.ifBlank { "code" },
                    color = AColors.CodeMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (lineCount > CODE_COLLAPSE_LINES) {
                    Text(
                        if (expanded) "收起" else "$lineCount 行",
                        color = AColors.CodeMuted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制代码", tint = AColors.CodeMuted, modifier = Modifier.size(15.dp))
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        visible,
                        color = AColors.CodeFg,
                        fontSize = 12.5f.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Minimal remote image loader so markdown images render without extra deps. */
@Composable
internal fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = runCatching {
            java.net.URL(url).openConnection().let { conn ->
                (conn as java.net.HttpURLConnection).connectTimeout = 12000
                conn.readTimeout = 15000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = modifier.clip(RoundedCornerShape(10.dp)))
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(AColors.SurfaceAlt)
                .blink(true),
        )
    }
}
