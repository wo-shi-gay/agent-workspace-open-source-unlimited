// Timeline step renderers adapted from RikkaHub 2.4.17 (AGPL-3.0-only),
// ChatMessageReasoning.kt / ChatMessageTools.kt, ported onto ChatStore parts.

package com.agentpad.workspace.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ChatStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** RikkaHub reasoning card states: preview while streaming, collapsed once done. */
internal enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

/**
 * 「深度思考 X.X 秒」文案。抽成顶层函数而不是内联 lambda，
 * 免得每次重组都分配一个新闭包。
 *
 * ⚠ 2026-11-01：调用点必须在 `label = { }` lambda **内部**。
 * Compose 的状态读取作用域决定谁失效 —— 在函数体顶层读 durationMs
 * 会让整个 ReasoningStep（含 MarkdownView 子树）每 50ms 重组一次。
 * RikkaHub 原版就是这么把读取关进 label lambda 的
 * （ChatMessageReasoning.kt:229）。
 */
private fun formatThinkingSeconds(durationMs: Long): String {
    val s = durationMs / 1000.0
    return if (s >= 0.05) "深度思考 %.1f 秒".format(s) else "深度思考"
}

/**
 * 一个思考步骤。逐行照抄 RikkaHub 3.1.1
 * ui/components/message/ChatMessageReasoning.kt 的 ChatMessageReasoningStep（194-255 行）
 * 与 rememberReasoningState（82-121 行）。
 *
 * 【与原版逐条对齐的关键点】
 *  1. 时长来源：原版用 reasoning.createdAt → finishedAt 两个真实时间戳相减
 *     （rememberReasoningState:91）。我们之前是从"开始收到流"那一刻计时，
 *     所以永远是 0.0 秒 —— 这就是"思考过程被吞"的根因。
 *     我们的 Part 没有 createdAt，改用 stepKey 首次出现时记时间 + 流结束时间。
 *  2. 时长刷新：原版 LaunchedEffect(loading) 里 while(isActive){ delay(50) }，
 *     每 50ms 重算一次（:111-118）。我们照做。
 *  3. 标题：原版 stringResource(R.string.deep_thinking_seconds, 秒数) →
 *     我们直接 "深度思考 %.1fs"。
 *  4. 图标：原版 HugeIcons.Idea01（灯泡）+ secondary 色。我们没有 HugeIcons，
 *     用 Icons.Outlined.Lightbulb 等价替换，尺寸 16dp 一致。
 *  5. 文字色：原版 MaterialTheme.colorScheme.secondary。我们改用同一个 token，
 *     不再用自定义 ChatReasoning，这样深浅主题都正确。
 *  6. 预览态：原版 heightIn(max = 100.dp)（:167），我们之前写 132dp，改回 100dp。
 *  7. 正文：原版用 MarkdownBlock（:175）渲染 markdown。我们用 MarkdownView，
 *     这样才能正确显示代码块/列表/加粗，而不是一坨纯文本。
 *  8. 展开内容缩进：由 ChainOfThought 的 padding(start=32.dp) 统一处理，一致。
 */
@Composable
fun ChainOfThoughtScope.ReasoningStep(
    part: ChatStore.Part,
    stepKey: String,
    streaming: Boolean,
    isLastStep: Boolean = false,
) {
    val scrollState = rememberScrollState()
    var state by remember(stepKey) { mutableStateOf(ReasoningCardState.Collapsed) }
    // 原版时长来自 createdAt→finishedAt 时间戳。我们没有这两个字段，
    // 所以用 stepKey 首次组合时刻作为起点，流结束时把终点定下来。
    val startAt = remember(stepKey) { System.currentTimeMillis() }
    var endAt by remember(stepKey) { mutableLongStateOf(0L) }
    var durationMs by remember(stepKey) { mutableLongStateOf(0L) }

    // 原版 rememberReasoningState:111-118：每 50ms 重算时长。
    LaunchedEffect(streaming, stepKey) {
        if (streaming) {
            while (isActive) {
                durationMs = System.currentTimeMillis() - startAt
                delay(50)
            }
        } else {
            if (endAt == 0L) endAt = System.currentTimeMillis()
            durationMs = (endAt - startAt).coerceAtLeast(0L)
        }
    }

    // ⚠ 2026-11-04 修复「思考中的滚动气泡不再跟随」。
    //
    // 原版写法（ChatMessageReasoning.kt:97-110）：
    //     LaunchedEffect(reasoning.reasoning, loading) {
    //         if (loading) {
    //             if (!expanded && showThinkingContent) expandState = Preview
    //             scrollState.animateScrollTo(scrollState.maxValue)
    //         } else { ...收尾... }
    //     }
    // 注意 key 是 **(正文文本, loading) 两个**。思考每流出新内容 → 文本变了 →
    // effect 重启 → 重新滚到底。这就是「气泡一直跟着内容走」的来源。
    //
    // 之前这里被拆成两个 LaunchedEffect，而且 key 只留了 streaming：
    //     LaunchedEffect(streaming) {
    //         if (streaming && state != Collapsed) scrollState.animateScrollTo(scrollState.maxValue)
    //     }
    // streaming 在整个思考期间**恒为 true、从不变化** → effect 只在开始跑一次，
    // 之后正文再长也不再滚，气泡就卡在原地不动了。
    // （交接文档 2026-11-04 第 1 节里写的「顺手把 animateScrollTo 的 key 从
    //   part.text 改成 streaming」，就是这次回归的来源。）
    //
    // 现在照抄原版：两个 effect 合并，key 补回正文内容。
    LaunchedEffect(part.text, streaming) {
        if (streaming) {
            // 用户手动展开过就不动他的展开态（原版同样只在 !expanded 时进 Preview）
            if (state != ReasoningCardState.Expanded) {
                state = ReasoningCardState.Preview
            }
            // 原版这里不带任何条件，直接在 loading 期间滚到底
            scrollState.animateScrollTo(scrollState.maxValue)
        } else if (state == ReasoningCardState.Preview) {
            state = ReasoningCardState.Collapsed
        }
    }

    // 原版 :221-230：标题恒为「深度思考 X 秒」，流式中也显示实时秒数。
    //
    // ⚠ 2026-11-01 性能修复（照 RikkaHub ChatMessageReasoning.kt:229 抄）：
    //   旧写法把 `durationMs` 在函数体顶层读出来算 title，于是每 50ms
    //   durationMs 一变，整个 ReasoningStep 就失效重组 —— 包括下面 content{}
    //   里的 MarkdownView，等于 3 分钟思考白跑 3600 次全量 Markdown 解析。
    //   原版把 state.duration 的读取关在 `label = { }` 这个 lambda 里，
    //   Compose 的作用域失效就只到那一个 Text。这里照做。

    ControlledChainOfThoughtStep(
        expanded = state == ReasoningCardState.Expanded,
        onExpandedChange = { next ->
            state = if (streaming) {
                if (next) ReasoningCardState.Expanded else ReasoningCardState.Preview
            } else {
                if (next) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
            }
        },
        // 原版 :209-216：Idea01 灯泡，16dp，secondary 色
        icon = {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            // ⚠ durationMs 只在 lambda 内读：50ms 刷新只失效这一个 Text。
            Text(
                text = formatThinkingSeconds(durationMs),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                modifier = Modifier.shimmer(isLoading = streaming),
            )
        },
        extra = if (streaming && isLastStep) {
            { DotLoading(size = 8.dp, color = MaterialTheme.colorScheme.secondary) }
        } else null,
        contentVisible = state != ReasoningCardState.Collapsed,
        content = {
            val preview = state == ReasoningCardState.Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (preview) {
                            Modifier
                                .graphicsLayer { alpha = 0.99f }
                                .drawWithCache {
                                    val fade = 64f
                                    val brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            (fade / size.height.coerceAtLeast(1f)) to Color.Black,
                                            (1f - fade / size.height.coerceAtLeast(1f)) to Color.Black,
                                            1f to Color.Transparent,
                                        ),
                                    )
                                    onDrawWithContent {
                                        drawContent()
                                        drawRect(brush = brush, size = Size(size.width, size.height), blendMode = BlendMode.DstIn)
                                    }
                                }
                                // 原版 :167 heightIn(max = 100.dp)
                                .heightIn(max = 100.dp)
                                .verticalScroll(scrollState)
                        } else Modifier
                    ),
            ) {
                val body = @Composable {
                    // ⚠ 2026-11-04 性能修复（这是「思考中切后台回来就卡死」的根因）：
                    //
                    // 这里原来用自研 MarkdownView。它的实现是
                    //     val nodes = remember(text) { parseMd(text) }
                    // 也就是**每来一个 token 就把整篇思考文本全量重解析一遍**。
                    // 思考文本是逐步变长的，流式期间每个刷新周期都触发一次 O(n) 解析，
                    // 主线程被打满 → 连系统返回键都排不上队（表面看是"无响应"）。
                    // 工具输出走的是纯 Text（ToolPayloadBox），不解析 Markdown，
                    // 所以「输出工具读写代码时不卡」—— 这个现象正好印证了上述判断。
                    //
                    // 原版 RikkaHub 的对应实现是 MarkdownBlock(streaming = loading)
                    // （ChatMessageReasoning.kt:174-185），内部用 BetterText 的
                    // MarkdownStreamParser 做**增量解析**：只有未闭合的尾块需要重算，
                    // 已完成的块直接复用。这里换成同一套（AgentMarkdownBlock）。
                    AgentMarkdownBlock(
                        content = part.text.trim(),
                        streaming = streaming,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // ⚠ 2026-11-04 长按复制修复。
                //
                // 原来写的是 `if (streaming) body() else SelectionContainer { body() }`，两个错：
                //   1. 用原生 SelectionContainer —— 它只认 Text 的 TextLayoutResult；
                //      而 body() 里是 AgentMarkdownBlock → BetterText，走自己的选择链路，
                //      套原生容器等于没套，长按完全无反应。
                //   2. streaming 期间直接不包容器 —— 生成中根本没法选。
                //
                // 参考源码 ChatMessageReasoning.kt:186-189 的写法是：
                //     // Markdown 由 BetterText 渲染，要用它自己的选择容器；生成中也可以选择
                //     SelectableContent { reasoningContent() }
                // 无分支、无 streaming 判断。这里原样照抄。
                SelectableContent { body() }
            }
        },
    )
}

private fun prettyToolPayload(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    return runCatching {
        when {
            value.startsWith("{") -> org.json.JSONObject(value).toString(2)
            value.startsWith("[") -> org.json.JSONArray(value).toString(2)
            else -> value
        }
    }.getOrDefault(raw)
}

/** A bounded, independently scrollable code surface for long tool payloads. */
@Composable
private fun ToolPayloadBox(
    raw: String,
    foreground: Color,
    background: Color,
    maxHeight: androidx.compose.ui.unit.Dp = 160.dp,
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp, max = maxHeight),
    ) {
        SelectionContainer {
            Box(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    prettyToolPayload(raw).ifBlank { "（无）" },
                    color = foreground,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * One tool step: dot loader while running, expandable argument/result preview,
 * and a dialog with the full payload.
 */
@Composable
fun ChainOfThoughtScope.ToolStep(part: ChatStore.Part, stepKey: String, streaming: Boolean) {
    var expanded by remember(stepKey) { mutableStateOf(false) }
    var showDetail by remember(stepKey) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val running = part.running && streaming
    val hasDetail = com.agentpad.workspace.floating.LocalFloatingToolDetails.current && (part.toolArguments.isNotBlank() || part.toolResult.isNotBlank())
    val lineStats = buildString {
        if (part.addedLines > 0) append("+${part.addedLines}")
        if (part.removedLines > 0) {
            if (isNotEmpty()) append(" ")
            append("-${part.removedLines}")
        }
    }
    val fileTool = part.toolName == "write_file" || part.toolName == "edit_file"

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (running) {
                DotLoading(size = 9.dp, color = ChatAccent)
            } else {
                Icon(Icons.Outlined.Build, null, modifier = Modifier.size(14.dp), tint = ChatMuted)
            }
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.blink(running),
            ) {
                Text(
                    "调用工具 ${part.toolName.ifBlank { "tool" }}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (running) ChatAccent else ChatMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (fileTool && lineStats.isNotBlank()) {
                    Text(
                        if (running) "生成中 · $lineStats" else lineStats,
                        color = if (part.removedLines > 0) ChatAccent else ChatReasoning,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        },
        extra = if (hasDetail) {
            {
                Text(
                    "详情",
                    color = ChatAccent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showDetail = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else null,
        contentVisible = expanded && hasDetail,
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (part.toolArguments.isNotBlank()) {
                    Text("参数", color = ChatFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        part.toolArguments,
                        color = ChatMuted,
                        fontSize = 11.5f.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (part.toolResult.isNotBlank()) {
                    Text(
                        if (running) "运行中…" else "结果",
                        color = ChatFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        part.toolResult.trim(),
                        color = ChatMuted,
                        fontSize = 11.5f.sp,
                        lineHeight = 17.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row {
                    Text(
                        "查看完整结果",
                        color = ChatAccent,
                        fontSize = 11.5f.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showDetail = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
    )

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("工具调用 · ${part.toolName.ifBlank { "tool" }}") },
            text = {
                // The dialog itself stays compact. Each payload owns its vertical
                // scroll position, so a large JSON result never expands the sheet
                // to the full screen or turns into one clipped horizontal line.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("参数", color = ChatFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    ToolPayloadBox(
                        raw = part.toolArguments,
                        foreground = ChatBody,
                        background = AColors.SurfaceAlt,
                        maxHeight = 150.dp,
                    )
                    Text("结果", color = ChatFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    ToolPayloadBox(
                        raw = part.toolResult.ifBlank { "（尚无结果）" },
                        foreground = AColors.CodeFg,
                        background = AColors.CodeBg,
                        maxHeight = 180.dp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(part.toolResult.ifBlank { part.toolArguments }))
                    showDetail = false
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showDetail = false }) { Text("关闭") } },
        )
    }
}

// 旧的纯文本 NerdLine 已被 ui/RikkaNerdLine.kt 取代（RikkaHub 源码同版）。
// 保留这段注释是为了让后来的人知道这里曾经有什么、为什么被删。
