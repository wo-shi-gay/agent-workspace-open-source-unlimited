package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.SkillStore
import com.agentpad.workspace.data.skills.InvalidSkillDir
import com.agentpad.workspace.data.skills.SkillFrontmatterParser
import com.agentpad.workspace.data.skills.SkillMetadata
import com.agentpad.workspace.data.skills.SkillValidationException
import com.agentpad.workspace.data.skills.SkillValidator
import com.agentpad.workspace.data.skills.message
import com.agentpad.workspace.ui.*

/**
 * 技能（Skill）页 —— 照抄 RikkaHub 3.2.0 的
 * `ui/pages/extensions/skills/SkillsPage.kt` + `SkillsVM.kt` 的信息架构：
 *
 *   列表（name / description / 内置标记 / 开关 / 删除）
 *   + 无效目录分组（缺 SKILL.md、frontmatter 不全，单独列出供清理）
 *   + 三个入口：手动新建 / 从文件导入 / 从 GitHub 导入
 *   + 详情弹窗：SKILL.md 正文（去 frontmatter）+ 技能目录文件树
 *
 * 与原版的差别：原版是 Compose Navigation + Koin ViewModel，这里合成一个 Activity，
 * 状态用 mutableStateOf 直接持有；判定逻辑全部走 SkillManager（与源码同一套）。
 */
class SkillsActivity : ComponentActivity() {

    private var tick by mutableStateOf(0)

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 从文件导入：zip 或裸 SKILL.md 都走 SkillStore.importBytes。 */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                runOnUiThread { toast("读取文件失败") }
                return@Thread
            }
            val err = SkillStore.importBytes(this@SkillsActivity, bytes)
            runOnUiThread {
                toast(err ?: "技能已导入并启用")
                if (err == null) tick++
            }
        }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val skills = remember(tick) { SkillStore.all(this@SkillsActivity) }
        val invalid = remember(tick) { SkillStore.invalid(this@SkillsActivity) }

        var query by remember { mutableStateOf("") }
        var detail by remember { mutableStateOf<SkillMetadata?>(null) }
        var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }
        var invalidTarget by remember { mutableStateOf<InvalidSkillDir?>(null) }
        var showAdd by remember { mutableStateOf(false) }
        var showGithub by remember { mutableStateOf(false) }

        val filtered = if (query.isBlank()) skills else skills.filter {
            it.name.contains(query, true) || it.description.contains(query, true)
        }
        val enabledCount = skills.count { SkillStore.isEnabled(this@SkillsActivity, it.name) }

        SubPage(
            "技能",
            "SKILL.md 操作手册 · 已启用 $enabledCount / 共 ${skills.size} 个",
            onBack = { finish() }, scrollable = false,
        ) {

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EntryChip("手动新建", Icons.Outlined.Add) { showAdd = true }
                EntryChip("从文件导入", Icons.Outlined.Description) {
                    pickFile.launch(
                        arrayOf(
                            "text/*", "application/zip",
                            "application/x-zip-compressed", "application/octet-stream",
                        )
                    )
                }
                EntryChip("从 GitHub 导入", Icons.Outlined.Description) { showGithub = true }
            }

            Spacer(Modifier.height(12.dp))

            if (skills.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索技能名或描述", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            if (skills.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Icons.Outlined.Description,
                        "还没有技能",
                        "技能是一份 SKILL.md 操作手册（带 name / description frontmatter）。\n" +
                            "从文件或 GitHub 导入，或直接在对话里让 AI 做一个。",
                    )
                }
                return@SubPage
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.skillDir.absolutePath }) { s ->
                    SkillCard(
                        s = s,
                        enabled = SkillStore.isEnabled(this@SkillsActivity, s.name),
                        onOpen = { detail = s },
                        onDelete = { deleteTarget = s },
                        onToggle = { v ->
                            SkillStore.setEnabled(this@SkillsActivity, s.name, v)
                            tick++
                        },
                    )
                }
                if (invalid.isNotEmpty()) {
                    item { SectionLabel("无法识别的目录（${invalid.size}）") }
                    items(invalid, key = { "bad_" + it.dirName }) { d ->
                        InvalidCard(d) { invalidTarget = d }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        detail?.let { s -> SkillDetailDialog(s) { detail = null } }

        if (showAdd) {
            AddSkillDialog(
                onDismiss = { showAdd = false },
                onConfirm = { content ->
                    val name = SkillValidator.validate(content).getOrElse { err ->
                        toast((err as? SkillValidationException)?.error?.message() ?: "SKILL.md 校验失败")
                        return@AddSkillDialog
                    }
                    val saved = SkillStore.save(this@SkillsActivity, name, content)
                    showAdd = false
                    toast(if (saved != null) "已保存并启用「$name」" else "保存失败")
                    if (saved != null) tick++
                },
            )
        }

        if (showGithub) {
            GithubImportDialog(
                onDismiss = { showGithub = false },
                onConfirm = { url ->
                    showGithub = false
                    Thread {
                        val err = SkillStore.importGitHub(this@SkillsActivity, url)
                        runOnUiThread {
                            toast(err ?: "技能已导入并启用")
                            if (err == null) tick++
                        }
                    }.start()
                },
            )
        }

        deleteTarget?.let { s ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除技能？", fontWeight = FontWeight.Bold) },
                text = { Text("确定删除「${s.name}」？整个技能目录都会被删掉。", fontSize = 14.sp) },
                confirmButton = {
                    TextButton({
                        SkillStore.delete(this@SkillsActivity, s)
                        deleteTarget = null
                        toast("已删除")
                        tick++
                    }) { Text("删除", color = AColors.Danger) }
                },
                dismissButton = { TextButton({ deleteTarget = null }) { Text("取消") } },
            )
        }

        invalidTarget?.let { d ->
            AlertDialog(
                onDismissRequest = { invalidTarget = null },
                title = { Text("删除这个目录？", fontWeight = FontWeight.Bold) },
                text = { Text("「${d.dirName}」不是有效技能。删除会移除整个目录。", fontSize = 14.sp) },
                confirmButton = {
                    TextButton({
                        SkillStore.deleteInvalidDir(this@SkillsActivity, d.dirName)
                        invalidTarget = null
                        toast("已删除")
                        tick++
                    }) { Text("删除", color = AColors.Danger) }
                },
                dismissButton = { TextButton({ invalidTarget = null }) { Text("取消") } },
            )
        }
    }
}

/** 入口 chip。 */
@Composable
private fun EntryChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(AColors.Accent.copy(alpha = .12f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(15.dp))
        Text("  $label", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
    }
}

@Composable
private fun SkillCard(
    s: SkillMetadata,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    var on by remember(s.name, enabled) { mutableStateOf(enabled) }
    AgentCard(padding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(AColors.Violet.copy(alpha = .13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null,
                    tint = AColors.Violet, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.builtin) {
                        Text("内置", fontSize = 10.sp, color = AColors.Muted,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text(s.description, fontSize = 12.sp, color = AColors.InkSoft,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
            }
            if (!s.builtin) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除",
                    tint = AColors.Danger, modifier = Modifier.size(19.dp).clickable { onDelete() })
                Spacer(Modifier.width(6.dp))
            }
            Switch(
                checked = on,
                onCheckedChange = { v -> on = v; onToggle(v) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AColors.Accent),
            )
        }
    }
}

@Composable
private fun InvalidCard(d: InvalidSkillDir, onClean: () -> Unit) {
    AgentCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null,
                tint = AColors.Warning, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(d.dirName, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.reason.name, fontSize = 11.sp, color = AColors.Muted)
            }
            Text("清理", fontSize = 13.sp, color = AColors.Danger, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onClean() }.padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

/** 详情：SKILL.md 正文（去掉 frontmatter）+ 目录文件树。 */
@Composable
private fun SkillDetailDialog(s: SkillMetadata, onClose: () -> Unit) {
    val raw = remember(s.name) { runCatching { s.skillFile.readText() }.getOrDefault("") }
    val body = remember(raw) { SkillFrontmatterParser.extractBody(raw) }
    val files = remember(s.name) {
        s.skillDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(s.skillDir).path }.sorted().take(200).toList()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 440.dp)) {
                Text(s.description, fontSize = 12.sp, color = AColors.Muted)
                if (!s.compatibility.isNullOrBlank()) {
                    Text("兼容：${s.compatibility}", fontSize = 11.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(10.dp))
                if (files.isNotEmpty()) {
                    Text("目录内容（${files.size}）", fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = AColors.InkSoft)
                    Text(files.take(20).joinToString("\n"),
                        fontSize = 11.sp, color = AColors.Muted, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                }
                Text("SKILL.md 正文", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AColors.InkSoft)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Text(body, fontSize = 12.sp, color = AColors.Ink, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("关闭") } },
    )
}

/** 手动新建：照 RikkaHub 的 AddSkillDialog —— 边输边校验，name 从 frontmatter 里取。 */
@Composable
private fun AddSkillDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var content by remember { mutableStateOf("") }
    val validation = remember(content) { SkillValidator.validate(content) }
    val name = validation.getOrNull()
    val error = (validation.exceptionOrNull() as? SkillValidationException)?.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建技能", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("SKILL.md 全文") },
                placeholder = {
                    Text(
                        "---\nname: my-skill\ndescription: \"一句话说明这个技能做什么\"\n---\n\n" +
                            "## 适用场景\n…\n## 执行步骤\n…\n## 约束边界\n…\n## 期望输出\n…",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    when {
                        error != null -> Text(error.message(), color = AColors.Danger, fontSize = 11.sp)
                        name != null -> Text("技能名：$name", fontSize = 11.sp)
                        else -> Text("必须包含 --- frontmatter（name + description）", fontSize = 11.sp)
                    }
                },
                isError = content.isNotBlank() && name == null,
                minLines = 8,
                maxLines = 14,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton({ name?.let(onConfirm) }, enabled = name != null) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 从 GitHub 仓库导入（仓库根 SKILL.md，或整包 zip 里找）。 */
@Composable
private fun GithubImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 GitHub 导入技能", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("粘贴仓库地址，会自动取仓库根的 SKILL.md；找不到则下载 zip 再翻。",
                    fontSize = 11.sp, color = AColors.Muted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("仓库 URL") },
                    placeholder = {
                        Text("https://github.com/owner/repo",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton({ onConfirm(url) }, enabled = url.isNotBlank()) { Text("导入") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}