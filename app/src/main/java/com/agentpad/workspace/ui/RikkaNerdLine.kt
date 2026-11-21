// Nerd line ported from RikkaHub 3.1.1
//   ui/components/message/ChatMessageNerdLine.kt
//
// 规格对齐点：
//   · 行内 FlowRow 三段：步数（Repeat）/ 速度（Zap）/ 上下文（Layers）
//   · 点击弹出 RichTooltip，内含上下文占用条 + 明细行
//   · 上下文占用条用 Canvas 画分段圆角矩形：缓存(主色) / 未命中输入(主色45%) / 输出(第三色) / 空余(轨道色)
//   · 文本样式 labelSmall，整体半透明
//
// 与上游的差异（本项目适配）：
//   · 无 HugeIcons 依赖 → 改用 Material Icons（Repeat / Bolt / Layers）
//   · 无 stringResource → 直接中文常量（本项目单语言）
//   · 无 TokenUsage 数据类 → 解析 message.stats 里的 JSON
//   · 无模型 contextLength 字段 → 传 null，占用条按「已用铺满」绘制
//   · 颜色全部走 AColors 主题色板，跟随用户的主题切换
package com.agentpad.workspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat

/**
 * 一轮对话的技术统计，对应 RikkaHub 的 NerdStats。
 *
 * @param totalPrompt 本轮所有请求累计的输入 token
 * @param totalCompletion 本轮所有请求累计的输出 token
 * @param totalCached 本轮累计的缓存命中 token
 * @param lastPrompt 最后一次请求的输入 token（上下文占用用）
 * @param lastCompletion 最后一次请求的输出 token
 * @param lastCached 最后一次请求的缓存命中 token
 * @param steps 工具调用步数，1 表示没调工具
 * @param durationMillis 生成耗时
 * @param tps 生成速度
 * @param running 是否还在流式输出中
 */
internal data class NerdStats(
    val totalPrompt: Int,
    val totalCompletion: Int,
    val totalCached: Int,
    val lastPrompt: Int,
    val lastCompletion: Int,
    val lastCached: Int,
    val steps: Int,
    val durationMillis: Long,
    val tps: Float,
    val running: Boolean,
) {
    val contextTokens: Int get() = lastPrompt + lastCompletion
}

/** 把 message.stats 里的 JSON 解析成 NerdStats。解析失败返回 null（不显示这一行）。 */
internal fun parseNerdStats(raw: String): NerdStats? {
    if (raw.isBlank() || !raw.startsWith("{")) return null
    return runCatching {
        val o = JSONObject(raw)
        NerdStats(
            totalPrompt = o.optInt("prompt"),
            totalCompletion = o.optInt("completion"),
            totalCached = o.optInt("cached"),
            lastPrompt = o.optInt("lastPrompt"),
            lastCompletion = o.optInt("lastCompletion"),
            lastCached = o.optInt("lastCached"),
            steps = o.optInt("steps", 1),
            durationMillis = (o.optDouble("secs", 0.0) * 1000).toLong(),
            tps = o.optDouble("tps", 0.0).toFloat(),
            running = o.optBoolean("running", false),
        )
    }.getOrNull()
}

/**
 * 消息下方的技术统计行。对齐 ChatMessageNerdLine：
 * 点击整行弹出用量详情，行内只显示最关键的三个数字。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RikkaNerdLine(
    rawStats: String,
    modifier: Modifier = Modifier,
    color: Color = AColors.Faint,
) {
    val stats = remember(rawStats) { parseNerdStats(rawStats) } ?: return
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            RichTooltip(title = { Text("用量") }) {
                NerdUsageDetails(stats)
            }
        },
        state = tooltipState,
        enableUserInput = false,
        modifier = modifier,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
            CompositionLocalProvider(LocalContentColor provides color) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { tooltipState.show() } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    // 步数：只在真的调过工具时显示，普通对话不占位
                    if (stats.steps > 1) {
                        NerdStatsItem(
                            icon = { NerdStatsIcon(Icons.Outlined.Repeat, "步数") },
                            content = { Text("${stats.steps} 步") },
                        )
                    }
                    // 速度：有耗时才显示
                    if (stats.tps > 0f) {
                        NerdStatsItem(
                            icon = { NerdStatsIcon(Icons.Outlined.Bolt, "速度") },
                            content = { Text("${fmtTps(stats.tps)} tok/s") },
                        )
                    }
                    // 上下文：始终显示，附带缓存命中量
                    NerdStatsItem(
                        icon = { NerdStatsIcon(Icons.Outlined.Layers, "上下文") },
                        content = {
                            Text("${fmtInt(stats.contextTokens)} 上下文")
                            if (stats.lastCached > 0) {
                                Text("（已缓存 ${fmtInt(stats.lastCached)}）")
                            }
                            if (stats.running) Text(" …")
                        },
                    )
                }
            }
        }
    }
}

/** 详情面板：顶部占用条 + 下面逐项明细。对齐 UsageDetails。 */
@Composable
private fun NerdUsageDetails(stats: NerdStats) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        NerdContextBar(
            stats = stats,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (stats.steps > 1) {
            NerdDetailRow("步数", stats.steps.toString())
            NerdDetailRow("总输入", fmtInt(stats.totalPrompt))
            if (stats.totalCached > 0) NerdDetailRow("缓存总量", fmtInt(stats.totalCached))
            NerdDetailRow("总输出", fmtInt(stats.totalCompletion))
        } else {
            NerdDetailRow("输入", fmtInt(stats.totalPrompt))
            if (stats.totalCached > 0) NerdDetailRow("缓存", fmtInt(stats.totalCached))
            NerdDetailRow("输出", fmtInt(stats.totalCompletion))
        }
        if (stats.tps > 0f) NerdDetailRow("速度", "${fmtTps(stats.tps)} tok/s")
        if (stats.durationMillis > 0) {
            NerdDetailRow("时长", String.format(java.util.Locale.US, "%.1fs", stats.durationMillis / 1000f))
        }
    }
}

private data class NerdSegment(val label: String, val tokens: Int, val color: Color)

/**
 * 最后一次请求的上下文占用条。
 *
 * 本项目没有模型上下文窗口长度（contextLength），所以 capacity 取「已用量」，
 * 整条铺满，不画空余段——RikkaHub 在 contextLength 为 null 时也是这个行为。
 */
@Composable
private fun NerdContextBar(stats: NerdStats, modifier: Modifier = Modifier) {
    val accent = AColors.Accent
    val segments = listOf(
        NerdSegment("缓存", stats.lastCached, accent),
        NerdSegment(
            "输入",
            (stats.lastPrompt - stats.lastCached).coerceAtLeast(0),
            accent.copy(alpha = 0.45f),
        ),
        NerdSegment("输出", stats.lastCompletion, AColors.Teal),
    ).filter { it.tokens > 0 }
    val contextTokens = stats.contextTokens
    val capacity = contextTokens.coerceAtLeast(1)
    val trackColor = AColors.LineSoft

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "上下文",
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            Text(fmtInt(contextTokens))
        }
        if (segments.isEmpty()) {
            // 没有任何用量（例如网关没回 usage），只画一条空轨道
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            ) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            ) {
                val gap = 2.dp.toPx()
                val radius = CornerRadius(size.height / 2)
                val available = size.width - gap * (segments.size - 1)
                var x = 0f
                segments.forEach { segment ->
                    if (x >= size.width) return@forEach
                    // 太小的段至少画成一个圆点，不然看不出来
                    val width = (segment.tokens.toFloat() / capacity * available)
                        .coerceAtLeast(size.height)
                    drawRoundRect(
                        color = segment.color,
                        topLeft = Offset(x, 0f),
                        size = Size(width.coerceAtMost(size.width - x), size.height),
                        cornerRadius = radius,
                    )
                    x += width + gap
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                segments.forEach { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(segment.color, CircleShape)
                        )
                        Text(
                            text = "${segment.label} ${fmtInt(segment.tokens)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NerdDetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value)
    }
}

@Composable
private fun NerdStatsIcon(imageVector: ImageVector, contentDescription: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(12.dp),
    )
}

@Composable
private fun NerdStatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

/** 千分位整数，对齐 RikkaHub 的 formatNumber。 */
private fun fmtInt(v: Int): String = NumberFormat.getIntegerInstance().format(v)

/** 一位小数，对齐 RikkaHub 的 toFixed(1)。 */
private fun fmtTps(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
