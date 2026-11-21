package com.agentpad.workspace

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.data.ReverseProject
import com.agentpad.workspace.ui.*

/**
 * 逆向模式入口页：新建逆向会话后第一时间进入本页。
 *
 * 流程：
 *  1. 列出设备上所有已安装 APK（用户应用在前）
 *  2. 用户选一个 → 显示解压进度条
 *  3. 解压到该会话工作区 → 跳转 ChatActivity（mode=reverse）
 */
class ReverseImportActivity : ComponentActivity() {

    private val apks = mutableStateOf<List<ReverseProject.InstalledApk>>(emptyList())
    private val loading = mutableStateOf(true)
    private val query = mutableStateOf("")

    // 解压状态
    private val extracting = mutableStateOf(false)
    private val progress = mutableStateOf(0f)
    private val statusText = mutableStateOf("")

    private lateinit var sessionId: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // 会话在进入本页之前已创建（MainActivity 传 session_id）
        sessionId = intent.getStringExtra("session_id") ?: run {
            val s = ChatStore.createSession(this, "新对话", "reverse")
            s.id
        }
        setContent { AgentTheme { Content() } }
        loadApks()
    }

    private fun loadApks() {
        Thread {
            val list = runCatching { ReverseProject.installedApks(this) }.getOrDefault(emptyList())
            runOnUiThread { apks.value = list; loading.value = false }
        }.start()
    }

    @Composable
    private fun Content() {
        if (extracting.value) {
            ExtractingScreen()
        } else {
            PickerScreen()
        }
    }

    // ---------------- 选择 APK ----------------

    @Composable
    private fun PickerScreen() {
        val all = apks.value
        val filtered = remember(all, query.value) {
            val q = query.value.trim()
            if (q.isBlank()) all
            else all.filter {
                it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }
        }

        SubPage("选择要逆向的 APK", "选中后会解压到本会话工作区，供 AI 分析",
            onBack = { finish() }, scrollable = false) {

            AgentTextField(query.value, { query.value = it }, "搜索应用名或包名")

            Spacer(Modifier.height(12.dp))

            if (loading.value) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("正在扫描已安装应用…", fontSize = 13.sp, color = AColors.Muted)
                }
                return@SubPage
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.Inventory2, "没有找到 APK",
                        if (query.value.isBlank()) "设备上没有可选的已安装应用" else "没有匹配「${query.value}」的应用")
                }
                return@SubPage
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("共 ${filtered.size} 个应用（系统应用排在后面）",
                        fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(bottom = 2.dp))
                }
                items(filtered) { apk -> ApkRow(apk) }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    @Composable
    private fun ApkRow(apk: ReverseProject.InstalledApk) {
        AgentCard(onClick = { startExtract(apk) }, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (apk.isSystem) AColors.Muted.copy(alpha = .13f)
                                    else AColors.Accent.copy(alpha = .13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Android, contentDescription = null,
                        tint = if (apk.isSystem) AColors.Muted else AColors.Accent,
                        modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(apk.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(apk.packageName, fontSize = 11.sp, color = AColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatSize(apk.sizeBytes), fontSize = 11.sp, color = AColors.InkSoft)
                    if (apk.isSystem) {
                        Text("系统", fontSize = 10.sp, color = AColors.Muted, modifier = Modifier.padding(top = 2.dp))
                    } else if (apk.versionName.isNotBlank()) {
                        Text("v${apk.versionName}", fontSize = 10.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }

    private fun startExtract(apk: ReverseProject.InstalledApk) {
        extracting.value = true
        progress.value = 0f
        statusText.value = "准备中…"
        Thread {
            try {
                val result = ReverseProject.importApk(this, sessionId, apk) { done, total ->
                    val f = if (total > 0) done.toFloat() / total else 0f
                    runOnUiThread {
                        progress.value = f.coerceIn(0f, 1f)
                        statusText.value = "解压中 ${formatSize(done)} / ${formatSize(total)}"
                    }
                }
                runOnUiThread {
                    progress.value = 1f
                    statusText.value = "完成 · ${result.entryCount} 个文件"
                    android.widget.Toast.makeText(this, "已解压到工作区", android.widget.Toast.LENGTH_SHORT).show()
                }
                // Keep the content title independent of the imported APK name and project mode.
                // 进聊天页
                Thread.sleep(450)
                runOnUiThread {
                    startActivity(Intent(this, ChatActivity::class.java).putExtra("session_id", sessionId))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    extracting.value = false
                    android.widget.Toast.makeText(this, "解压失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------- 解压进度 ----------------

    @Composable
    private fun ExtractingScreen() {
        SubPage("正在解压 APK", "解压到本会话工作区，请稍候", onBack = {}, scrollable = false) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                AgentCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null,
                            tint = AColors.Accent, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(statusText.value, fontSize = 13.sp, color = AColors.InkSoft)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progress.value },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AColors.Accent,
                            trackColor = AColors.LineSoft,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("${(progress.value * 100).toInt()}%", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = AColors.Ink)
                    }
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}