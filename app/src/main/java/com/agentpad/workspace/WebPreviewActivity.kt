package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.data.WebProject
import com.agentpad.workspace.ui.*
import java.io.File

/**
 * 网页项目预览：
 *  - 顶部操作栏：刷新 / 用浏览器打开 / 导出
 *  - 主体：WebView 直接渲染工作区里的 index.html（含其相对资源）
 *  - 「导出」把整个工作区目录复制到 /sdcard/Download/<标题>/
 */
class WebPreviewActivity : ComponentActivity() {

    private val reloadTick = mutableStateOf(0)
    private val filePath = mutableStateOf("")
    private lateinit var sessionId: String
    private lateinit var entry: File

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sessionId = intent.getStringExtra("session_id") ?: run { finish(); return }
        // 确保模板存在（用户可能从历史页直接打开）
        entry = WebProject.ensureTemplate(this, sessionId)
        filePath.value = entry.absolutePath
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val session = remember { ChatStore.loadSession(this, sessionId) }
        val title = session?.title ?: "网页项目"
        Column(Modifier.fillMaxSize().background(AColors.Paper)) {

            // 顶栏
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ADimens.PagePadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopBar(title, "网页项目预览", onBack = { finish() })
                }
                // 操作栏
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ADimens.PagePadding).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(Icons.Outlined.Refresh, "刷新") { reloadTick.value++ }
                    ActionChip(Icons.Outlined.OpenInBrowser, "浏览器打开") { openInBrowser() }
                    ActionChip(Icons.Outlined.FileDownload, "导出", primary = true) { export() }
                }
            }

            // WebView
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(horizontal = ADimens.PagePadding)
                    .clip(RoundedCornerShape(ADimens.CardRadius))
                    .background(Color.White),
            ) {
                if (!entry.exists()) {
                    EmptyState(Icons.Outlined.Code, "找不到 index.html",
                        "该会话工作区里没有网页文件，重新创建一个网页项目即可")
                } else {
                    key(reloadTick.value) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = false
                                }
                            },
                            update = { wv -> wv.loadUrl("file://${entry.absolutePath}") },
                        )
                    }
                }
            }

            // 路径提示
            Text(
                entry.absolutePath,
                fontSize = 11.sp, color = AColors.Faint,
                modifier = Modifier.padding(horizontal = ADimens.PagePadding, vertical = 10.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }

    @Composable
    private fun ActionChip(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        text: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(ADimens.ChipRadius))
                .background(if (primary) AColors.Accent else AColors.Surface)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null,
                tint = if (primary) Color.White else AColors.InkSoft,
                modifier = Modifier.size(16.dp))
            Text(text, fontSize = 13.sp,
                color = if (primary) Color.White else AColors.InkSoft,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp))
        }
    }

    private fun openInBrowser() {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", entry)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "用浏览器打开"))
        }.onFailure {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 导出整个工作区到 /sdcard/Download/<标题>/ */
    private fun export() {
        val src = ChatStore.workspaceDir(this, sessionId)
        if (src == null || !src.exists() || src.listFiles().isNullOrEmpty()) {
            Toast.makeText(this, "工作区为空，没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = sanitize(ChatStore.loadSession(this, sessionId)?.title ?: "网页项目")
        var dest = File(download, base)
        var i = 1
        while (dest.exists()) { dest = File(download, "$base($i)"); i++ }
        Thread {
            val ok = runCatching { src.copyRecursively(dest, overwrite = false) }.isSuccess
            runOnUiThread {
                if (ok) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("导出成功")
                        .setMessage("网页已复制到：\n${dest.absolutePath}\n\n入口文件：${WebProject.ENTRY}")
                        .setPositiveButton("好", null)
                        .show()
                } else {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "网页项目" }
}