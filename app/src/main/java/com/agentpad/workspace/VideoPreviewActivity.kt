package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideocamOff
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
import com.agentpad.workspace.data.VideoProject
import com.agentpad.workspace.ui.*
import java.io.File

/**
 * 视频项目预览：
 *  - 顶部操作栏：刷新 / 用播放器打开 / 导出
 *  - 主体：VideoView 直接播放工作区里的 out.mp4
 *  - 「导出」把整个视频项目目录复制到 /sdcard/Download/<会话标题>/
 */
class VideoPreviewActivity : ComponentActivity() {

    private val reloadTick = mutableStateOf(0)
    private val filePath = mutableStateOf("")
    private lateinit var sessionId: String
    private lateinit var out: File

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sessionId = intent.getStringExtra("session_id") ?: run { finish(); return }
        // 确保基座存在（用户可能从历史页直接打开）
        runCatching { VideoProject.ensureTemplate(this, sessionId) }
        out = VideoProject.outFile(this, sessionId)
        filePath.value = out.absolutePath
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val session = remember { ChatStore.loadSession(this, sessionId) }
        val title = session?.title ?: "视频项目"
        Column(Modifier.fillMaxSize().background(AColors.Paper)) {

            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ADimens.PagePadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopBar(title, "视频项目预览", onBack = { finish() })
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ADimens.PagePadding).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(Icons.Outlined.Refresh, "刷新") { reloadTick.value++ }
                    ActionChip(Icons.Outlined.OpenInNew, "外部播放") { openExternal() }
                    ActionChip(Icons.Outlined.FileDownload, "导出视频", primary = true) { export() }
                }
            }

            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(horizontal = ADimens.PagePadding)
                    .clip(RoundedCornerShape(ADimens.CardRadius))
                    .background(Color.Black),
            ) {
                if (!out.exists()) {
                    EmptyState(Icons.Outlined.VideocamOff, "还没有 out.mp4",
                        "让 AI 改好 project.json 后运行 build.sh 渲染，或点上方刷新")
                } else {
                    key(reloadTick.value) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(Uri.fromFile(out))
                                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                    setOnPreparedListener { it.isLooping = false; start() }
                                    setOnErrorListener { _, _, _ ->
                                        Toast.makeText(ctx, "视频无法播放（文件可能损坏或未渲染完成）", Toast.LENGTH_LONG).show()
                                        true
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Text(
                out.absolutePath,
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

    private fun openExternal() {
        if (!out.exists()) { Toast.makeText(this, "还没有 out.mp4", Toast.LENGTH_SHORT).show(); return }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "用播放器打开"))
        }.onFailure {
            Toast.makeText(this, "没有可用的视频播放器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 导出整个视频项目到 /sdcard/Download/<标题>/ */
    private fun export() {
        val src = ChatStore.workspaceDir(this, sessionId)
        if (src == null || !src.exists() || src.listFiles().isNullOrEmpty()) {
            Toast.makeText(this, "工作区为空，没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = sanitize(ChatStore.loadSession(this, sessionId)?.title ?: "视频项目")
        var dest = File(download, base)
        var i = 1
        while (dest.exists()) { dest = File(download, "$base($i)"); i++ }
        Thread {
            val ok = runCatching { src.copyRecursively(dest, overwrite = false) }.isSuccess
            runOnUiThread {
                if (ok) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("导出成功")
                        .setMessage("视频项目已复制到：\n${dest.absolutePath}\n\n成片：${VideoProject.OUT}")
                        .setPositiveButton("好", null)
                        .show()
                } else {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "视频项目" }
}