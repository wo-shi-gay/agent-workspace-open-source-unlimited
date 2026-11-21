package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
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
import com.agentpad.workspace.data.MigrationImporter
import com.agentpad.workspace.ui.*
import java.io.File
import java.util.concurrent.Executors

/**
 * 4.7 数据导入页 —— 接收 4.6 导出的迁移包。
 *
 * 两个入口：
 *   ① 选择 .zip 迁移包
 *   ② 选择迁移文件夹（4.6 的「文件夹铺开」模式）
 *
 * 流程：选包 → 预览（显示会话数/项目类型分布/来源版本）→ 确认 → 导入 → 结果。
 * 先预览再导入，是为了让用户能确认「这确实是我的包」，避免选错文件就把
 * 现有数据覆盖掉。
 */
class DataImportActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    // ---- 状态 ----
    private val running = mutableStateOf(false)
    private val progress = mutableStateOf(-1)
    private val status = mutableStateOf("")
    private val preview = mutableStateOf<MigrationImporter.Preview?>(null)
    private val result = mutableStateOf<MigrationImporter.Result?>(null)
    private val pickedLabel = mutableStateOf("")
    private var pickedFile: File? = null

    /** 自动扫描到的候选包（启动时扫一次，也可手动刷新）。 */
    private val found = mutableStateOf<List<MigrationImporter.Found>>(emptyList())
    private val scanning = mutableStateOf(false)
    private val scanNote = mutableStateOf("")

    /** 选择 zip 文件。用 SAF，避免手工路径出错。 */
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        handlePicked(uri, expectDirectory = false)
    }

    /** 选择文件夹（迁移文件夹模式）。 */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        handlePicked(uri, expectDirectory = true)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
        // 进页就扫一次：用户刚导出完通常会立刻来导入，直接列出来最省事。
        scan()
    }

    /**
     * 扫描本机的迁移包。
     *
     * 放在后台线程 —— 要遍历 Download 目录、还要打开每个 zip 校验 manifest，
     * 目录里文件多时会有明显 IO。
     */
    private fun scan() {
        if (scanning.value) return
        scanning.value = true
        scanNote.value = ""
        Thread {
            val list = runCatching { MigrationImporter.scanForPackages(this) }.getOrElse { emptyList() }
            runOnUiThread {
                found.value = list
                scanning.value = false
                if (list.isEmpty()) {
                    scanNote.value = "没有找到迁移包。\n" +
                        "请确认：\n" +
                        "· 4.6 导出的 zip（或迁移文件夹）已放在「下载」目录\n" +
                        "· 若导出时提示写进了 App 专属目录，那也在扫描范围内\n" +
                        "· 存储权限已授予（设置 → 应用 → 所有文件访问）"
                }
            }
        }.start()
    }

    /** 点选一个扫描到的候选包 → 立刻预览。 */
    private fun selectFound(item: MigrationImporter.Found) {
        if (running.value) return
        preview.value = null
        result.value = null
        progress.value = -1
        status.value = ""
        pickedFile = item.file
        pickedLabel.value = item.label

        Thread {
            val p = MigrationImporter.preview(this, item.file)
            runOnUiThread { preview.value = p }
        }.start()
    }

    @Composable
    private fun Content() {
        SubPage(
            title = "导入数据",
            subtitle = "从 4.6 导出的迁移包恢复会话、工作区与登录状态",
            onBack = { if (!running.value) finish() },
        ) {
            SectionLabel("① 选择迁移包")

            AgentCard {
                Text(
                    "自动扫描本机「下载」目录及常见的迁移包存放位置。若没扫到，可以手动选文件。",
                    fontSize = 12.sp, color = AColors.Muted, lineHeight = 17.sp,
                )

                Spacer(Modifier.height(10.dp))

                // 扫描结果列表
                if (scanning.value) {
                    Text("正在扫描…", fontSize = 12.sp, color = AColors.Accent)
                } else if (found.value.isEmpty()) {
                    Text(
                        scanNote.value.ifBlank { "没有找到迁移包。请确认已把 4.6 导出的 zip 或文件夹放到「下载」目录。" },
                        fontSize = 12.sp, color = AColors.Amber, lineHeight = 17.sp,
                    )
                } else {
                    Text("找到 ${found.value.size} 个迁移包：", fontSize = 11.sp, color = AColors.Faint)
                    Spacer(Modifier.height(6.dp))
                    found.value.forEach { item ->
                        val selected = pickedFile?.absolutePath == item.file.absolutePath
                        AgentCard(
                            modifier = Modifier.padding(bottom = 8.dp),
                            background = if (selected) AColors.Accent.copy(alpha = 0.08f) else AColors.SurfaceAlt,
                            border = if (selected) AColors.Accent.copy(alpha = 0.4f) else AColors.LineSoft,
                            onClick = { if (!running.value) selectFound(item) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                    if (item.isFolder) Icons.Outlined.FolderOpen else Icons.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (selected) AColors.Accent else AColors.Muted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(
                                        item.label, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) AColors.Accent else AColors.Ink,
                                        maxLines = 1,
                                    )
                                    Text(item.hint, fontSize = 10.sp, color = AColors.Muted,
                                        modifier = Modifier.padding(top = 2.dp), lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("重新扫描", Modifier.weight(1f)) { scan() }
                    SecondaryButton("手动选 zip", Modifier.weight(1f)) {
                        pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }

                if (pickedLabel.value.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已选择：${pickedLabel.value}",
                        fontSize = 11.sp, color = AColors.Accent, lineHeight = 16.sp,
                    )
                }
            }

            // ---- 预览 ----
            preview.value?.let { p ->
                Spacer(Modifier.height(18.dp))
                SectionLabel("② 确认内容")

                if (!p.ok) {
                    AgentCard(
                        background = AColors.Danger.copy(alpha = 0.08f),
                        border = AColors.Danger.copy(alpha = 0.3f),
                    ) {
                        Text("无法导入", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AColors.Danger)
                        Text(p.message, fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 6.dp), lineHeight = 17.sp)
                    }
                    return@SubPage
                }

                AgentCard {
                    Column {
                        InfoRow("来源版本", "${p.fromVersionName}（${p.fromPackage}）")
                        InfoRow("导出时间", p.exportedAtText.ifBlank { "—" })
                        InfoRow("包形态", if (p.kind == "zip") "压缩包" else "文件夹")
                        InfoRow("总大小", formatSize(p.totalBytes))
                        InfoRow("会话数", "${p.sessionCount} 个")
                        InfoRow("工作区", "${p.workspaceDirCount} 个")

                        // 项目类型分布 —— 让用户确认「APK / 网页 / 视频 / 逆向」都在
                        val modes = p.sessions.groupingBy { it.mode }.eachCount()
                        if (modes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("项目类型", fontSize = 11.sp, color = AColors.Faint)
                            Spacer(Modifier.height(4.dp))
                            modes.entries.sortedByDescending { it.value }.forEach { (mode, count) ->
                                Text(
                                    "· ${modeLabel(mode)}　$count 个",
                                    fontSize = 12.sp, color = AColors.InkSoft,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("③ 开始导入")

                if (progress.value >= 0) {
                    AgentCard {
                        LinearProgressIndicator(
                            progress = { (progress.value.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AColors.Accent,
                            trackColor = AColors.LineSoft,
                        )
                        Text(status.value.ifBlank { "准备中…" },
                            fontSize = 11.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 8.dp), lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!running.value) {
                    PrimaryButton("确认导入", Modifier.fillMaxWidth()) { startImport() }
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(AColors.Accent.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在导入…", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ---- 结果 ----
            result.value?.let { r ->
                Spacer(Modifier.height(12.dp))
                if (r.ok) {
                    AgentCard(
                        background = AColors.Success.copy(alpha = 0.08f),
                        border = AColors.Success.copy(alpha = 0.3f),
                    ) {
                        Text("导入完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AColors.Success)
                        Text(r.message, fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 6.dp), lineHeight = 17.sp)
                        if (r.warnings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("警告 ${r.warnings.size} 条", fontSize = 11.sp, color = AColors.Amber)
                            r.warnings.take(5).forEach {
                                Text("· $it", fontSize = 11.sp, color = AColors.Muted,
                                    modifier = Modifier.padding(vertical = 1.dp), lineHeight = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("重启 App 生效") { restartApp() }
                    }
                } else {
                    AgentCard(
                        background = AColors.Danger.copy(alpha = 0.08f),
                        border = AColors.Danger.copy(alpha = 0.3f),
                    ) {
                        Text("导入失败", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AColors.Danger)
                        Text(r.message, fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 6.dp), lineHeight = 17.sp)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("注意")
            AgentCard(background = AColors.Amber.copy(alpha = 0.08f), border = AColors.Amber.copy(alpha = 0.3f)) {
                Column {
                    listOf(
                        "导入会覆盖同名会话与文件，建议先确认包里内容正确。",
                        "工作区可能有 GB 级数据，导入耗时取决于文件数量，请插电、别锁屏。",
                        "导入完成后建议重启一次 App，确保所有页面读到新数据。",
                        "工具链与系统镜像不在包里，会在首次使用时自动重新下载。",
                    ).forEach {
                        Text("· $it", fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(vertical = 3.dp), lineHeight = 17.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.width(72.dp))
            Text(value, fontSize = 12.sp, color = AColors.Ink, lineHeight = 17.sp)
        }
    }

    @Composable
    private fun ChoiceButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        subtitle: String,
        enabled: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        AgentCard(
            modifier = modifier,
            background = if (enabled) AColors.Surface else AColors.SurfaceAlt,
            border = AColors.LineSoft,
            onClick = if (enabled) onClick else null,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Icon(
                    icon, contentDescription = null,
                    tint = if (enabled) AColors.Accent else AColors.Faint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) AColors.Ink else AColors.Faint)
                Text(subtitle, fontSize = 10.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "apk" -> "项目模式（APK）"
        "web" -> "网页项目"
        "video" -> "视频项目"
        "reverse" -> "逆向模式"
        else -> "普通对话"
    }

    // ================= 选包 =================

    /**
     * 把 SAF 返回的 Uri 变成一个本地可读的 File。
     *
     * zip：拷到 cacheDir/import/ 下（ZipFile 需要真实路径）。
     * 文件夹：直接解析 document id 拿真实路径 —— 只有能从路径推断时才可行，
     *        否则提示用户改用 zip，或把文件夹放到 App 专属目录。
     */
    private fun handlePicked(uri: Uri, expectDirectory: Boolean) {
        if (running.value) return
        preview.value = null
        result.value = null
        progress.value = -1
        status.value = ""

        Thread {
            val resolved = runCatching { resolveToFile(uri, expectDirectory) }.getOrNull()
            if (resolved == null) {
                runOnUiThread {
                    pickedLabel.value = ""
                    preview.value = MigrationImporter.Preview(
                        false,
                        "无法访问所选位置。请把迁移包放到「下载」目录后再选；文件夹模式请把整个迁移文件夹放到 App 可访问的位置。",
                    )
                }
                return@Thread
            }
            pickedFile = resolved
            runOnUiThread { pickedLabel.value = resolved.name }
            val p = MigrationImporter.preview(this, resolved)
            runOnUiThread { preview.value = p }
        }.start()
    }

    private fun resolveToFile(uri: Uri, expectDirectory: Boolean): File? {
        // 情况 1：能直接拿到真实路径（大多数本地文件管理器会给 file:// 或可解析的 document id）
        val direct = runCatching { uriToFile(uri) }.getOrNull()
        if (direct != null && direct.exists()) {
            if (expectDirectory && !direct.isDirectory) return null
            if (!expectDirectory && direct.isDirectory) return null
            return direct
        }

        // 情况 2：content:// —— 需要把内容复制出来
        if (expectDirectory) {
            // 文件夹不能靠拷单个文件解决，只能要求用户放到可访问位置
            return null
        }
        val cache = File(cacheDir, "import").apply { mkdirs() }
        val out = File(cache, "picked-${System.currentTimeMillis()}.zip")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
        }.onFailure { return null }
        return out.takeIf { it.isFile && it.length() > 0 }
    }

    /** 尝试从 Uri 解析真实路径（仅对 file:// 与外部存储 document id 有效）。 */
    private fun uriToFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        if (uri.scheme != "content") return null
        // document id 形如 primary:Download/WorkspaceMigration/xxx
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            return docIdToFile(docId)
        }
        runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            return docIdToFile(docId)
        }
        return null
    }

    private fun docIdToFile(docId: String): File? {
        // "primary:Download/xxx" → /storage/emulated/0/Download/xxx
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        val root = when (parts[0]) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> File("/storage/${parts[0]}")
        }
        val f = File(root, parts[1])
        return if (f.exists()) f else null
    }

    // ================= 导入 =================

    private fun startImport() {
        val f = pickedFile ?: return
        if (running.value) return
        running.value = true
        progress.value = 0
        status.value = "准备导入…"
        result.value = null

        executor.execute {
            val r = MigrationImporter.import(
                this, f, overwrite = true,
                progress = object : MigrationImporter.Progress {
                    override fun onMessage(text: String) = runOnUiThread { status.value = text }
                    override fun onProgress(percent: Int) = runOnUiThread {
                        if (percent >= 0) progress.value = percent
                    }
                },
            )
            runOnUiThread {
                running.value = false
                result.value = r
                if (r.ok) {
                    progress.value = 100
                    status.value = "导入完成"
                    Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show()
                } else {
                    progress.value = -1
                    Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 重启到启动页，让所有数据重新加载。 */
    private fun restartApp() {
        runCatching {
            val intent = Intent(this, BootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            Runtime.getRuntime().exit(0)
        }.onFailure {
            Toast.makeText(this, "请手动重启 App", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}