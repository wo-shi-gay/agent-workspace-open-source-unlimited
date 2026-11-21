package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.FileHistoryStore
import com.agentpad.workspace.ui.*

/**
 * 「浏览 / 撤回 AI 操作」页面（仅文件工具）。
 *
 * 列出本会话里 AI 用 write_file / edit_file 改过的每个文件，
 * 每条显示路径 + 工具 + 时间 + 简略 diff；点「查看」看完整前后对比，
 * 点「撤回」把文件还原成该次改动前的状态。
 */
class FileHistoryActivity : ComponentActivity() {

    private lateinit var sessionId: String
    private val tick = mutableStateOf(0)
    private val viewing = mutableStateOf<FileHistoryStore.Entry?>(null)
    private val confirmRevert = mutableStateOf<FileHistoryStore.Entry?>(null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sessionId = intent.getStringExtra("session_id") ?: ""
        setContent { AgentTheme { Content() } }
    }

    private fun entries(): List<FileHistoryStore.Entry> =
        FileHistoryStore.load(this, sessionId).reversed()

    @Composable
    private fun Content() {
        val list = remember(tick.value) { entries() }

        SubPage("浏览 / 撤回 AI 操作", "仅记录 AI 通过文件工具的改动（${list.size} 条）",
            onBack = { finish() }, scrollable = false) {

            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.History, "还没有文件操作记录",
                        "AI 用文件工具创建或修改文件后，这里会出现可撤回的记录")
                }
                return@SubPage
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { e -> EntryCard(e) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        viewing.value?.let { ViewDialog(it) }
        confirmRevert.value?.let { RevertDialog(it) }
    }

    @Composable
    private fun EntryCard(e: FileHistoryStore.Entry) {
        AgentCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                        .background(AColors.Accent.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Code, contentDescription = null,
                        tint = AColors.Accent, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(fileName(e.path), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(parentPath(e.path), fontSize = 11.sp, color = AColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                if (e.reverted) {
                    Tag("已撤回", AColors.Muted, AColors.Muted.copy(alpha = .12f))
                } else {
                    Tag(if (e.tool == "write_file") "写入" else "编辑", AColors.Violet, AColors.Violet.copy(alpha = .12f))
                }
            }

            // diff 摘要
            Spacer(Modifier.height(10.dp))
            DiffPreview(e)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeText(e.at), fontSize = 11.sp, color = AColors.Faint, modifier = Modifier.weight(1f))
                Text("查看", fontSize = 13.sp, color = AColors.Accent, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { viewing.value = e }.padding(horizontal = 8.dp, vertical = 4.dp))
                if (!e.reverted) {
                    Text("撤回", fontSize = 13.sp, color = AColors.Danger, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { confirmRevert.value = e }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }

    @Composable
    private fun DiffPreview(e: FileHistoryStore.Entry) {
        val before = e.beforeText
        val after = e.afterText
        val text = remember(e.id) {
            if (!e.existedBefore) "＋ 新建文件（${after.lines().size} 行，${after.length} 字符）"
            else {
                val b = before?.lines()?.size ?: 0
                val a = after.lines().size
                "− 修改前 ${b} 行  →  ＋ 修改后 ${a} 行"
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(AColors.SurfaceAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text, fontSize = 12.sp, color = AColors.InkSoft, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun ViewDialog(e: FileHistoryStore.Entry) {
        AlertDialog(
            onDismissRequest = { viewing.value = null },
            title = { Text(fileName(e.path), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("路径：${e.path}", fontSize = 11.sp, color = AColors.Muted)
                    Spacer(Modifier.height(10.dp))

                    if (e.existedBefore) {
                        Text("改动前", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AColors.Danger)
                        CodeBlock(e.beforeText ?: "(空)")
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("改动后", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AColors.Success)
                    CodeBlock(e.afterText)
                }
            },
            confirmButton = { TextButton({ viewing.value = null }) { Text("关闭") } },
        )
    }

    @Composable
    private fun CodeBlock(text: String) {
        val shown = if (text.length > 4000) text.take(4000) + "\n…[已截断]" else text
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(AColors.SurfaceAlt)
                .padding(10.dp),
        ) {
            Text(shown, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AColors.InkSoft,
                lineHeight = 15.sp)
        }
    }

    @Composable
    private fun RevertDialog(e: FileHistoryStore.Entry) {
        AlertDialog(
            onDismissRequest = { confirmRevert.value = null },
            title = { Text("撤回这次改动？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (e.existedBefore) "文件「${fileName(e.path)}」将还原为 AI 修改前的内容。"
                    else "文件「${fileName(e.path)}」是 AI 新建的，撤回后会把它删除。",
                    fontSize = 14.sp, color = AColors.InkSoft, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton({
                    val res = FileHistoryStore.revert(this, sessionId, e.id)
                    confirmRevert.value = null
                    android.widget.Toast.makeText(this, res.message, android.widget.Toast.LENGTH_SHORT).show()
                    tick.value++
                }) { Text("撤回", color = AColors.Danger) }
            },
            dismissButton = { TextButton({ confirmRevert.value = null }) { Text("取消") } },
        )
    }

    private fun fileName(path: String) = path.substringAfterLast('/').ifBlank { path }
    private fun parentPath(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "工作区" else path.substring(0, idx)
    }

    private fun timeText(at: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(at))
}