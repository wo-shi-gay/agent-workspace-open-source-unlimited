// Ported from RikkaHub 3.1.1 (AGPL-3.0-only), 逐条对齐：
//   ui/components/richtext/MaterialMarkdown.kt  —— MaterialMarkdown / MaterialMarkdownDefaults
//   ui/components/richtext/Markdown.kt          —— MarkdownBlock / TableCard / ColorSwatch
//   ui/components/richtext/HighlightCodeBlock.kt —— 代码块（裁掉 WebView 预览/mermaid，见下）
//   ui/components/richtext/SelectableContent.kt  —— BetterSelectionContainer 包装
//   ui/components/richtext/MathBlock.kt          —— LatexBlock
//   ui/components/richtext/ZoomableAsyncImage.kt —— 适配成项目内的图片加载
//
// 与本项目的差异（都写在这里，避免以后困惑）：
//  1. 参考用 coil3 加载图片，本项目没有 coil，改用 ChatAttachment 的本地解码路径；
//  2. 参考的代码块带 HTML/SVG 预览与 Mermaid，需要 WebView + navController，
//     本项目走 BrowserSession 那套，不在这里塞，故省略（其余功能逐条保留）；
//  3. 参考的设置项（codeBlockAutoCollapse / showLineNumbers / enableLatexRendering 等）
//     本项目没有对应开关，一律取参考的默认值。

package com.agentpad.workspace.ui

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.bettertext.highlight.HighlightTextColorPalette
import me.rerere.bettertext.highlight.rememberHighlightedCode
import me.rerere.bettertext.latex.LatexBlock
import me.rerere.bettertext.markdown.MarkdownBlock
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.MarkdownInline
import me.rerere.bettertext.markdown.MarkdownInlineContent
import me.rerere.bettertext.markdown.MarkdownTheme
import me.rerere.bettertext.markdown.TableCell
import me.rerere.bettertext.markdown.plainText
import me.rerere.bettertext.markdown.Markdown as BetterMarkdown
import me.rerere.bettertext.text.BetterSelectionContainer
import me.rerere.bettertext.text.BetterText
import com.agentpad.workspace.ui.icons.HugeIconsLocal
import kotlin.math.max

// ============================================================
// SelectableContent（照抄 SelectableContent.kt）
// ============================================================

private val LocalInSelectionContainer = staticCompositionLocalOf { false }

/**
 * 让 [content] 里的 BetterText 可以长按选择。
 *
 * 已经在一个选择容器里时直接并入外层（例如消息里嵌的代码块），
 * 这样代码能和正文连着选；否则自己建一个。
 */
@Composable
fun SelectableContent(content: @Composable () -> Unit) {
    if (LocalInSelectionContainer.current) {
        content()
    } else {
        BetterSelectionContainer {
            CompositionLocalProvider(LocalInSelectionContainer provides true, content)
        }
    }
}

// ============================================================
// MaterialMarkdown（照抄 MaterialMarkdown.kt:68-94）
// ============================================================

/**
 * 用 Material 3 主题渲染 Markdown，解析和排版交给 BetterText。
 *
 * @param style 正文样式，合并到 `LocalTextStyle` 上；没有颜色时用 `LocalContentColor`
 * @param streaming 内容还在生成时为 true，末尾没写完的语法会被补全或隐藏
 * @param onLinkClick 链接点击回调，为 null 时交给系统的 UriHandler 打开
 */
@Composable
fun MaterialMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    theme: MarkdownTheme = agentMarkdownTheme(style),
    components: MarkdownComponents = AgentMarkdownDefaults.components,
    softBreakAsLineBreak: Boolean = true,
    streaming: Boolean = false,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val merged = LocalTextStyle.current.merge(style)
    val resolved = merged.copy(color = merged.color.takeOrElse { LocalContentColor.current })
    // 插槽里的组件（代码块、表格工具栏等）用 Material 的 Text，让它们也拿到正文样式
    ProvideTextStyle(resolved) {
        BetterMarkdown(
            content = content,
            modifier = modifier,
            style = resolved,
            theme = theme,
            components = components,
            softBreakAsLineBreak = softBreakAsLineBreak,
            streaming = streaming,
            onLinkClick = onLinkClick,
        )
    }
}

// ============================================================
// MarkdownBlock：接上代码高亮 / 图片 / LaTeX / 表格（照抄 Markdown.kt:138-216）
// ============================================================

/**
 * 应用内使用的 Markdown：在 [MaterialMarkdown] 上接入代码高亮、图片、LaTeX 渲染。
 *
 * @param streaming 内容还在生成时为 true，末尾没写完的语法（如 `**加粗`、`$\frac{`）会被补全或隐藏
 */
@Composable
fun AgentMarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    streaming: Boolean = false,
) {
    val theme = agentMarkdownTheme(style)
    val context = LocalContext.current

    val components = remember(theme) {
        MarkdownComponents(
            codeBlock = { block ->
                AgentCodeBlock(
                    code = block.code,
                    language = block.language ?: "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                    completeCodeBlock = block.closed,
                )
            },
            image = { image, _ ->
                AgentMarkdownImage(
                    url = image.url,
                    alt = image.alt,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .widthIn(min = 120.dp),
                )
            },
            // 公式：参考里由设置项控制，本项目没有该开关，默认开启
            mathBlock = { formula -> MathBlock(latex = formula, modifier = Modifier.fillMaxWidth()) },
            table = { table -> TableCard(table) },
            details = AgentMarkdownDefaults.components.details,
            inlineMath = { formula -> LatexInlineCached(formula, context) },
        )
    }

    MaterialMarkdown(
        content = content,
        modifier = modifier.padding(horizontal = 4.dp),
        style = style,
        theme = theme,
        components = components,
        streaming = streaming,
    )
}

// ============================================================
// 主题（照抄 MaterialMarkdown.kt:105-137）
// ============================================================

private val HeadingScales = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f)

/** 标题字号按正文等比放大，正文字号变化时标题跟着变。 */
@Composable
private fun agentMarkdownTheme(textStyle: TextStyle = LocalTextStyle.current): MarkdownTheme {
    val fontSize = LocalTextStyle.current.merge(textStyle).fontSize
    val colorScheme = MaterialTheme.colorScheme
    return remember(fontSize, colorScheme) {
        MarkdownTheme(
            headings = HeadingScales.map { scale ->
                if (fontSize.isSp) {
                    TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize * scale,
                        lineHeight = fontSize * (scale * 1.25f),
                    )
                } else {
                    TextStyle(fontWeight = FontWeight.Bold)
                }
            },
            link = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
            inlineCode = SpanStyle(
                color = colorScheme.primary,
                fontFamily = AgentMono,
                fontSize = 0.9.em,
            ),
            blockSpacing = 12.dp,
            markerColor = colorScheme.primary,
            quoteColor = colorScheme.outlineVariant,
            dividerColor = colorScheme.outlineVariant,
        )
    }
}

// ============================================================
// 默认组件（照抄 MaterialMarkdown.kt:96-275）
// ============================================================

object AgentMarkdownDefaults {

    val components = MarkdownComponents(
        codeBlock = { CodeBlock(it) },
        image = { image, link -> Image(image, link) },
        table = { Table(it) },
        details = { block, summary, content -> Details(block, summary, content) },
    )

    /** 折叠块：照抄 MaterialMarkdown.kt:140-174 */
    @Composable
    fun Details(
        block: MarkdownBlock.Details,
        summary: @Composable () -> Unit,
        content: @Composable () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var expanded by rememberSaveable(block.open) { mutableStateOf(block.open) }
        val rotation by animateFloatAsState(if (expanded) 90f else 0f)
        Column(modifier) {
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIconsLocal.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(rotation),
                )
                Column(Modifier.weight(1f, fill = false)) {
                    summary()
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 22.dp, top = 6.dp)) {
                    content()
                }
            }
        }
    }

    /** 不带语法高亮的代码块（照抄 MaterialMarkdown.kt:176-203） */
    @Composable
    fun CodeBlock(block: MarkdownBlock.CodeBlock, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.language?.let { language ->
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = block.code,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = AgentMono),
                    softWrap = false,
                )
            }
        }
    }

    /** 没有图片加载能力时只显示替代文本（照抄 MaterialMarkdown.kt:205-233） */
    @Composable
    fun Image(image: MarkdownInline.Image, link: String?, modifier: Modifier = Modifier) {
        AgentMarkdownImage(url = link ?: image.url, alt = image.alt, modifier = modifier)
    }

    /** 默认表格（照抄 MaterialMarkdown.kt:235-274） */
    @Composable
    fun Table(
        table: MarkdownBlock.Table,
        modifier: Modifier = Modifier,
        outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape: Shape = MaterialTheme.shapes.small,
    ) {
        val columnCount = max(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
        val headers: List<@Composable () -> Unit> = table.header.mapIndexed { index, cell ->
            @Composable {
                TableCellContent(cell, table.alignments.getOrNull(index), FontWeight.SemiBold)
            }
        }
        val rows: List<List<@Composable () -> Unit>> = table.rows.map { row ->
            row.mapIndexed { index, cell ->
                @Composable {
                    TableCellContent(cell, table.alignments.getOrNull(index), null)
                }
            }
        }
        DataTable(
            headers = headers,
            rows = rows,
            modifier = modifier,
            columnMinWidths = List(columnCount) { 80.dp },
            columnMaxWidths = List(columnCount) { 200.dp },
            columnAlignments = List(columnCount) { index ->
                table.alignments.getOrNull(index)?.toCellAlignment() ?: Alignment.CenterStart
            },
            bodyCellAlignment = { row, column ->
                table.rows.getOrNull(row)?.getOrNull(column)?.style?.textAlign?.toCellAlignment()
            },
            outerBorder = outerBorder,
            shape = shape,
        )
    }
}

// 照抄 MaterialMarkdown.kt:277-289
private fun TextAlign.toCellAlignment(): Alignment? = when (this) {
    TextAlign.Unspecified -> null
    TextAlign.Center -> Alignment.Center
    TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

@Composable
private fun TableCellContent(cell: TableCell, columnAlign: TextAlign?, fontWeight: FontWeight?) {
    val columnStyle = TextStyle(fontWeight = fontWeight, textAlign = columnAlign ?: TextAlign.Unspecified)
    MarkdownInlineContent(cell.content, style = columnStyle.merge(cell.style))
}

// ============================================================
// 代码块（照抄 HighlightCodeBlock.kt，裁掉 WebView 预览 / Mermaid）
// ============================================================

private const val COLLAPSE_LINES = 10

@Composable
fun AgentCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
) {
    val darkMode = AColors.IsDark
    val colorPalette: HighlightTextColorPalette =
        if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 参考有 codeBlockAutoCollapse 开关，默认 true；本项目没有开关，取默认
    var isExpanded by remember { mutableStateOf(false) }
    // 参考有 codeBlockAutoWrap / showLineNumbers 开关，默认均 false
    val autoWrap = false
    val showLineNumbers = false

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CodeBlockActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            val textStyle = LocalTextStyle.current.merge(style)
            val codeLines = remember(code) { code.lines() }
            val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
            val displayCode = if (isExpanded) code else collapsedCode
            val displayLines = remember(displayCode) { displayCode.lines() }

            CodeBlockDefault(
                displayCode = displayCode,
                displayLines = displayLines,
                language = language,
                textStyle = textStyle,
                colorPalette = colorPalette,
                autoWrap = autoWrap,
                showLineNumbers = showLineNumbers,
                scrollState = scrollState,
            )

            Spacer(Modifier.height(4.dp))
            // 代码折叠按钮（照抄 HighlightCodeBlock.kt:215-247）
            if (codeLines.size > COLLAPSE_LINES) {
                Box(
                    modifier = Modifier
                        .clickable { isExpanded = !isExpanded }
                        .fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isExpanded) HugeIconsLocal.ArrowUp01 else HugeIconsLocal.ArrowDown01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (isExpanded) "收起" else "展开全部 ${codeLines.size} 行",
                            fontSize = textStyle.fontSize,
                            lineHeight = textStyle.lineHeight,
                        )
                    }
                }
            }
        }
    }
}

/** 照抄 HighlightCodeBlock.kt:292-342 */
@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) Modifier else Modifier.horizontalScroll(scrollState),
        ),
    ) {
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) { displayLines.size.toString().length }
            Column(modifier = Modifier.padding(end = 8.dp)) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = AgentMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        SelectableContent {
            BetterText(
                text = rememberHighlightedCode(displayCode, language, colorPalette),
                modifier = Modifier.animateContentSize(),
                style = codeTextStyle(textStyle),
            )
        }
    }
}

/** 代码的文字样式（照抄 HighlightCodeBlock.kt:345-352） */
@Composable
private fun codeTextStyle(textStyle: TextStyle): TextStyle = textStyle.merge(
    TextStyle(
        color = textStyle.color.takeOrElse { LocalContentColor.current },
        fontFamily = AgentMono,
        fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0",
    ),
)

/** 代码块右上角动作条（照抄 HighlightCodeBlock.kt:354-437，裁掉下载与预览） */
@Composable
private fun CodeBlockActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = AgentMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Icon(
                imageVector = HugeIconsLocal.Copy01,
                contentDescription = "复制代码",
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        scope.launch {
                            clipboardManager.setClipEntry(
                                ClipEntry(ClipData.newPlainText("code", code))
                            )
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize),
            )
        }
    }
}

// ============================================================
// 表格卡片（照抄 Markdown.kt:337-430，裁掉 CSV 导出）
// ============================================================

@Composable
private fun TableCard(table: MarkdownBlock.Table) {
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // HTML 写出的表格没有 Markdown 源码，复制时用 CSV 兜底
    val tableText = remember(table) { table.source ?: buildTableCsv(table) }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "表格",
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = HugeIconsLocal.Copy01,
                contentDescription = "复制表格",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        scope.launch {
                            clipboardManager.setClipEntry(
                                ClipEntry(ClipData.newPlainText("table", tableText))
                            )
                        }
                    }
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
        AgentMarkdownDefaults.Table(
            table = table,
            outerBorder = null,
            shape = androidx.compose.ui.graphics.RectangleShape,
        )
    }
}

/** 照抄 Markdown.kt:433-447 */
private fun buildTableCsv(table: MarkdownBlock.Table): String {
    fun escape(field: String): String {
        return if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    return buildString {
        appendLine(table.header.joinToString(",") { escape(it.content.plainText()) })
        table.rows.forEach { row ->
            appendLine(row.joinToString(",") { escape(it.content.plainText()) })
        }
    }
}

// ============================================================
// 公式（照抄 MathBlock.kt）
// ============================================================

/** 块级公式，居中显示，太宽时横向滚动；字号和颜色跟随当前文字样式。 */
@Composable
fun MathBlock(latex: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    LatexBlock(
        latex = latex,
        modifier = modifier.padding(8.dp),
        fontSize = style.fontSize.takeOrElse { 15.sp },
        color = style.color.takeOrElse { LocalContentColor.current },
    )
}

/**
 * 行内公式（照抄 Markdown.kt:194 `LatexInline.cached(formula, context)`）。
 *
 * ⚠ `LatexInline.cached` 是普通函数，不是 @Composable，所以这里也不能标 @Composable。
 */
private fun LatexInlineCached(
    formula: String,
    context: android.content.Context,
) = me.rerere.bettertext.latex.LatexInline.cached(formula, context)

// ============================================================
// 图片（参考用 coil3，这里用 Android 原生解码）
// ============================================================

/**
 * Markdown 图片：本地文件 / content:// / http(s) 都能显示。
 *
 * 参考用 coil3 + 点击放大，本项目没有 coil，改为：
 *  - 本地路径直接用 BitmapFactory 解码（走 ChatAttachment 已有的能力）
 *  - 网络地址先用 ImageIO 下载再解码，失败则退化成替代文本
 *  - 点击用系统 UriHandler 打开
 */
@Composable
private fun AgentMarkdownImage(url: String, alt: String?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val bitmap = rememberChatImage(url)
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = alt,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { runCatching { uriHandler.openUri(url) } },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    } else {
        Surface(
            onClick = { runCatching { uriHandler.openUri(url) } },
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIconsLocal.Image02,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = alt ?: url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 拉取并解码 Markdown 里的图片。
 *
 * 参考用 coil3（带内存/磁盘缓存），本项目没有 coil，走已有能力：
 *  - `http(s)://` → 直接 URL 连接下载后解码（与 MarkdownView.RemoteImage 同款做法）
 *  - `content://` → ContentResolver 打开输入流解码
 *  - 其余按本地路径 → BitmapFactory.decodeFile
 * 失败返回 null，调用方退化成替代文本胶囊。
 */
@Composable
private fun rememberChatImage(url: String): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(url) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 12_000
                        conn.readTimeout = 15_000
                        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                    url.startsWith("content://") -> {
                        context.contentResolver.openInputStream(android.net.Uri.parse(url))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                    else -> android.graphics.BitmapFactory.decodeFile(url.removePrefix("file://"))
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}