package com.agentpad.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.AppLog
import com.agentpad.workspace.ui.*

/**
 * 日志查看页（设置 → 运行日志）。
 *
 * - 实时刷新（订阅 AppLog，无需手动刷新）
 * - 可按级别过滤
 * - 可复制全部 / 清空
 * - 低开销：只在页面可见时订阅
 */
class LogActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val activity = this@LogActivity

        // 全量快照（最新在最后）
        var entries by remember { mutableStateOf(AppLog.snapshot()) }
        var minLevel by remember { mutableStateOf(AppLog.LEVEL_DEBUG) }
        var autoScroll by remember { mutableStateOf(true) }
        var confirmClear by remember { mutableStateOf(false) }

        // 订阅实时日志（页面销毁时自动移除）
        DisposableEffect(Unit) {
            val listener: (AppLog.Entry) -> Unit = { e ->
                entries = (entries + e).takeLast(800)
            }
            AppLog.addListener(listener)
            onDispose { AppLog.removeListener(listener) }
        }

        val shown = remember(entries, minLevel) {
            entries.filter { it.level >= minLevel }
        }
        val listState = rememberLazyListState()

        // 自动滚动到底部（新日志）
        LaunchedEffect(shown.size, autoScroll) {
            if (autoScroll && shown.isNotEmpty()) {
                listState.animateScrollToItem(shown.size - 1)
            }
        }

        SubPage(
            "运行日志",
            "实时收集 · 崩溃 / 网络请求 / 异常",
            onBack = { finish() },
            scrollable = false,
            trailing = {
                Row {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(AColors.Surface)
                            .clickable { copyAll(activity) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制",
                            tint = AColors.InkSoft, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(AColors.Surface)
                            .clickable { confirmClear = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空",
                            tint = AColors.Danger.copy(alpha = .85f), modifier = Modifier.size(19.dp))
                    }
                }
            },
        ) {
            // 级别过滤条
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip("全部", minLevel == AppLog.LEVEL_DEBUG, AColors.InkSoft) {
                    minLevel = AppLog.LEVEL_DEBUG
                }
                FilterChip("信息+", minLevel == AppLog.LEVEL_INFO, AColors.Accent) {
                    minLevel = AppLog.LEVEL_INFO
                }
                FilterChip("警告+", minLevel == AppLog.LEVEL_WARN, AColors.Warning) {
                    minLevel = AppLog.LEVEL_WARN
                }
                FilterChip("仅错误", minLevel == AppLog.LEVEL_ERROR, AColors.Danger) {
                    minLevel = AppLog.LEVEL_ERROR
                }
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    if (autoScroll) "自动滚动:开" else "自动滚动:关",
                    autoScroll, AColors.Success,
                ) { autoScroll = !autoScroll }
            }

            // 统计
            Text(
                "${shown.size} 条 · 内存保留最近 800 条",
                fontSize = 11.sp, color = AColors.Faint,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.BugReport, "暂无日志", "应用运行时会自动收集")
                }
            } else {
                AgentCard(padding = 0.dp) {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        state = listState,
                    ) {
                        items(shown) { e ->
                            LogRow(e)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清空日志？") },
                text = { Text("将清空内存中的日志记录和已保存的日志文件。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        AppLog.clear()
                        entries = AppLog.snapshot()
                        Toast.makeText(activity, "已清空", Toast.LENGTH_SHORT).show()
                    }) { Text("清空", color = AColors.Danger, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            )
        }
    }

    @Composable
    private fun FilterChip(label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(if (active) tint else AColors.SurfaceAlt)
                .clickable(onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                label, fontSize = 12.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) Color.White else AColors.InkSoft,
            )
        }
    }

    @Composable
    private fun LogRow(e: AppLog.Entry) {
        val (color, bg) = when (e.level) {
            AppLog.LEVEL_ERROR -> AColors.Danger to AColors.DangerSoft
            AppLog.LEVEL_WARN -> AColors.Warning to AColors.WarningSoft
            AppLog.LEVEL_INFO -> AColors.Accent to Color.Transparent
            else -> AColors.Muted to Color.Transparent
        }
        val lv = when (e.level) {
            AppLog.LEVEL_ERROR -> "E"
            AppLog.LEVEL_WARN -> "W"
            AppLog.LEVEL_INFO -> "I"
            else -> "D"
        }
        val time = remember(e.timeMs) {
            java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(e.timeMs))
        }

        Column(
            Modifier.fillMaxWidth()
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, fontSize = 10.5.sp, color = AColors.Faint,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (e.level >= AppLog.LEVEL_WARN) color.copy(alpha = .18f) else AColors.SurfaceAlt)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(lv, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
                }
                Spacer(Modifier.width(6.dp))
                Text(e.tag, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = AColors.InkSoft, fontFamily = FontFamily.Monospace)
            }
            Text(
                e.message,
                fontSize = 11.5.sp,
                color = if (e.level == AppLog.LEVEL_ERROR) AColors.Danger else AColors.Ink,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(AColors.LineSoft))
        }
    }

    private fun copyAll(ctx: Context) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AgentPad Logs", AppLog.exportText()))
            Toast.makeText(ctx, "全部日志已复制", Toast.LENGTH_SHORT).show()
        }
    }
}