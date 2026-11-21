package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.MigrationExporter
import com.agentpad.workspace.ui.*
import java.io.File
import java.util.concurrent.Executors

/**
 * 数据迁移（4.6）—— 把用户数据打包成一个可带到 4.7 的 zip。
 *
 * 为什么需要：4.7 要换包名 + 换签名，Android 不允许覆盖安装，用户必须
 * 卸载重装。卸载会清空 /data/data/<包名>/，所以**必须在卸载前**把数据导出去。
 *
 * 本页只做「导出」。导入在 4.7 的新包里实现（读同一个 zip）。
 */
class MigrationActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val running = mutableStateOf(false)
    private val progress = mutableStateOf(-1)
    private val status = mutableStateOf("")
    private val lastResult = mutableStateOf<MigrationExporter.Result?>(null)
    /** 导出模式：默认压缩包。 */
    private val mode = mutableStateOf(MigrationExporter.Mode.ZIP)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        SubPage(
            title = "数据迁移",
            subtitle = "导出全部会话、工作区与登录状态，供 4.7 版导入",
            onBack = { if (!running.value) finish() },
        ) {
            // ⚠ 不要在这里再套 Column(Modifier.verticalScroll(...))。
            // SubPage 的 content 本身就是可滚动 Column（scrollable = true 时
            // 内部已经有 verticalScroll）。再嵌一层会让内层滚动组件收到
            // 无限高度约束，Compose 直接抛：
            //   IllegalStateException: Vertically scrollable component was
            //   measured with an infinity maximum height constraints.
            // 4.6 曾因此崩溃在 OPPO PCAM10 / Android 10 上。

            SectionLabel("将要打包的内容")
                AgentCard {
                    Column {
                        listOf(
                            "全部会话与消息（含项目类型：APK / 网页 / 视频 / 逆向）" to true,
                            "全部会话工作区文件（原样搬运，不筛选、不排除）" to true,
                            "登录状态与账号 token" to true,
                            "API 配置、模型选择、思考等级" to true,
                            "主题、对话背景、自定义提示词" to true,
                            "插件、MCP 配置、远程工作区" to true,
                            "系统镜像与工具链（Java/Python/Go/Node/NDK 等）" to false,
                        ).forEach { (text, included) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    if (included) "✓" else "✗",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = if (included) AColors.Success else AColors.Faint,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    text, fontSize = 12.sp, lineHeight = 17.sp,
                                    color = if (included) AColors.Ink else AColors.Muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "工具链不打包：它们在沙箱系统镜像里，4.7 首次启动会自动重新下载安装，包体因此小很多。",
                            fontSize = 11.sp, color = AColors.Muted, lineHeight = 16.sp,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("导出方式")

                AgentCard(
                    background = if (mode.value == MigrationExporter.Mode.ZIP) AColors.Accent.copy(alpha = 0.08f) else AColors.Surface,
                    border = if (mode.value == MigrationExporter.Mode.ZIP) AColors.Accent.copy(alpha = 0.35f) else AColors.LineSoft,
                    onClick = { if (!running.value) mode.value = MigrationExporter.Mode.ZIP },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (mode.value == MigrationExporter.Mode.ZIP) "●" else "○",
                            fontSize = 15.sp, color = AColors.Accent, modifier = Modifier.width(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("压缩包（.zip）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text(
                                "打成单个 zip。体积小，方便分享、传网盘、拷到电脑。必须解压才能看内容。",
                                fontSize = 11.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 3.dp), lineHeight = 16.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                AgentCard(
                    background = if (mode.value == MigrationExporter.Mode.FOLDER) AColors.Accent.copy(alpha = 0.08f) else AColors.Surface,
                    border = if (mode.value == MigrationExporter.Mode.FOLDER) AColors.Accent.copy(alpha = 0.35f) else AColors.LineSoft,
                    onClick = { if (!running.value) mode.value = MigrationExporter.Mode.FOLDER },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (mode.value == MigrationExporter.Mode.FOLDER) "●" else "○",
                            fontSize = 15.sp, color = AColors.Accent, modifier = Modifier.width(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("文件夹（直接铺开）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text(
                                "在下载目录建一个专属文件夹，把 manifest / prefs / files / workspaces 按原结构摆开。" +
                                    "不用解压就能用文件管理器逐个翻看、单独拷贝。文件多时复制较慢。",
                                fontSize = 11.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 3.dp), lineHeight = 16.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("操作")

                if (progress.value >= 0) {
                    AgentCard {
                        LinearProgressIndicator(
                            progress = { (progress.value.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AColors.Accent,
                            trackColor = AColors.LineSoft,
                        )
                        Text(
                            status.value.ifBlank { "准备中…" },
                            fontSize = 11.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 8.dp),
                            lineHeight = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!running.value) {
                    PrimaryButton("开始导出", Modifier.fillMaxWidth()) { startExport() }
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(AColors.Accent.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在导出…", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 导出成功：给出文件位置 + 分享/打开入口
                lastResult.value?.takeIf { it.ok }?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    AgentCard(background = AColors.Success.copy(alpha = 0.08f), border = AColors.Success.copy(alpha = 0.3f)) {
                        Text("导出成功", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AColors.Success)
                        r.zip?.let { zip ->
                            Text(
                                zip.absolutePath,
                                fontSize = 11.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 6.dp), lineHeight = 15.sp,
                            )
                            Text(
                                "大小 ${MigrationExporter.formatSize(r.bytes)} · ${r.sessions} 个会话",
                                fontSize = 11.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton("分享 / 另存到其他位置") { shareFile(zip) }
                        }
                    }
                }
                lastResult.value?.takeIf { !it.ok }?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    AgentCard(background = AColors.Danger.copy(alpha = 0.08f), border = AColors.Danger.copy(alpha = 0.3f)) {
                        Text("导出失败", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AColors.Danger)
                        Text(r.message, fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 6.dp), lineHeight = 17.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("迁移步骤")
                AgentCard {
                    Column {
                        listOf(
                            "1. 在这里点「开始导出」，等进度到 100%",
                            "2. 用「分享 / 另存」把 zip 存到网盘、电脑或另一台设备（别只留在手机里）",
                            "3. 安装 4.7 新版（包名不同，需要先卸载本版）",
                            "4. 在 4.7 的「数据迁移 → 导入」里选中这个 zip",
                        ).forEach {
                            Text("· $it", fontSize = 12.sp, color = AColors.Muted,
                                modifier = Modifier.padding(vertical = 3.dp), lineHeight = 17.sp)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("注意")
                AgentCard(background = AColors.Amber.copy(alpha = 0.08f), border = AColors.Amber.copy(alpha = 0.3f)) {
                    Column {
                        listOf(
                            "卸载本版会清空 App 私有数据，所以务必先导出再卸载。",
                            "工作区可能很大（GB 级），导出耗时取决于文件数量，请插着电、别锁屏。",
                            "导出完成后建议先用文件管理器确认 zip 已生成且大小合理。",
                        ).forEach {
                            Text("· $it", fontSize = 12.sp, color = AColors.Muted,
                                modifier = Modifier.padding(vertical = 3.dp), lineHeight = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
        }
    }

    private fun startExport() {
        if (running.value) return
        running.value = true
        progress.value = 0
        status.value = "准备中…"
        lastResult.value = null

        // 优先写 App 专属外部目录（无需任何存储权限）；
        // 若设备有公共 Download 且可写，则写到用户更容易找到的地方。
        val targetDir = pickTargetDir()

        executor.execute {
            val result = MigrationExporter.export(
                this,
                targetDir,
                mode = mode.value,
                progress = object : MigrationExporter.Progress {
                    override fun onMessage(text: String) {
                        runOnUiThread { status.value = text }
                    }

                    override fun onProgress(percent: Int) {
                        runOnUiThread { if (percent >= 0) progress.value = percent }
                    }
                },
            )
            runOnUiThread {
                running.value = false
                lastResult.value = result
                if (result.ok) {
                    progress.value = 100
                    status.value = "导出完成 · ${MigrationExporter.formatSize(result.bytes)}"
                    Toast.makeText(this, "导出完成", Toast.LENGTH_SHORT).show()
                } else {
                    progress.value = -1
                    status.value = ""
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 落盘目录：优先公共 `Download/WorkspaceMigration/`。
     *
     * 说明：App 已声明 MANAGE_EXTERNAL_STORAGE（Android 11+ 全盘访问）+
     * WRITE_EXTERNAL_STORAGE（≤API 29），所以在真机上可以直接写公共下载目录，
     * 用户在文件管理器/电脑上一眼就能看到并拷走。**这不是交给沙箱做**，
     * 是 APK 本体（Java IO）直接写。
     *
     * 写不进去时（个别厂商限制 / 用户没给全盘权限）退到 App 专属目录，
     * 保证导出这件事本身永远不失败。
     */
    private fun pickTargetDir(): File {
        val public = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WorkspaceMigration",
        )
        if (runCatching { public.mkdirs(); public.canWrite() }.getOrDefault(false)) return public
        val own = File(getExternalFilesDir(null) ?: filesDir, "migration")
        own.mkdirs()
        return own
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享迁移包"))
        }.onFailure {
            Toast.makeText(this, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}