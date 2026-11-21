package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.PluginStore
import com.agentpad.workspace.ui.*
import java.io.File

/**
 * 插件管理页（路由 B：JS 沙箱插件）。
 *
 * 插件 = 用户/市场提供的 .apk-plugin（zip：manifest.json + main.js + 可选 icon.png）。
 * 安装后注册为「系统级工具」，AI 可在对话里像内置工具一样调用；
 * 插件 JS 能通过 host API 调用文件 / 网络 / 终端 / 剪贴板等原生能力。
 */
class PluginManagerActivity : ComponentActivity() {

    private val tick = mutableStateOf(0)
    private val confirmUninstall = mutableStateOf<PluginStore.Installed?>(null)

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val tmp = File(cacheDir, "plugin_pick_${System.currentTimeMillis()}.zip")
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (!ok) {
                runOnUiThread { toast("读取文件失败") }
                return@Thread
            }
            val (success, msg, _) = PluginStore.installFromZip(this, tmp)
            tmp.delete()
            runOnUiThread {
                toast(msg)
                tick.value++
            }
        }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    @Composable
    private fun Content() {
        val list = remember(tick.value) { PluginStore.installed(this) }
        // 分类筛选：默认「全部」；分类值来自 manifest.category（内置/导入技能归「技能」）。
        val selectedCategory = remember { mutableStateOf("全部") }

        SubPage("插件与技能", "技能 = 手动导入的 SKILL.md 包，或由 AI 用 create_skill 制作；插件 = JS 沙箱工具",
            onBack = { finish() }, scrollable = false) {

            AgentCard(padding = 14.dp, onClick = { pickLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(AColors.Accent.copy(alpha = .13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Extension, contentDescription = null,
                            tint = AColors.Accent, modifier = Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("安装插件", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("选择一个 .apk-plugin 包（zip）", fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 技能（Skill）：照抄 RikkaHub 3.2.0 的技能体系 ----
            AgentCard(padding = 14.dp, onClick = {
                startActivity(android.content.Intent(this@PluginManagerActivity, SkillsActivity::class.java))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(AColors.Violet.copy(alpha = .13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Extension, contentDescription = null,
                            tint = AColors.Violet, modifier = Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("技能", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("SKILL.md 操作手册：手动导入 zip / GitHub 仓库，或让 AI 直接制作",
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.Extension, "还没有安装插件",
                        "插件是用户自由实现的系统级工具：manifest.json 声明 + main.js 实现")
                }
                return@SubPage
            }

val cats = list.map { it.manifest.category.ifBlank { "其他" } }.distinct()
            val selected = selectedCategory.value
            val filtered = if (selected == "全部") list
            else list.filter { it.manifest.category.ifBlank { "其他" } == selected }

            // 分类 chips（可横向滚动；分类多了也不挤）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf("全部") + cats).forEach { c ->
                    val on = c == selected
                    Text(
                        c,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Color.White else AColors.InkSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) AColors.Accent else AColors.Accent.copy(alpha = .12f))
                            .clickable { selectedCategory.value = c }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            SectionLabel("已安装（${filtered.size}）")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.groupBy { it.manifest.category.ifBlank { "其他" } }.forEach { (cat, group) ->
                    item(key = "cat_$cat") {
                        Text(cat, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = AColors.Muted, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                    }
                    items(group, key = { it.manifest.id }) { p -> PluginCard(p) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        confirmUninstall.value?.let { p ->
            AlertDialog(
                onDismissRequest = { confirmUninstall.value = null },
                title = { Text("卸载插件？", fontWeight = FontWeight.Bold) },
                text = { Text("确定卸载「${p.manifest.name}」？卸载后 AI 将不再看到这个工具。",
                    fontSize = 14.sp, color = AColors.InkSoft) },
                confirmButton = {
                    TextButton({
                        PluginStore.uninstall(this, p.manifest.id)
                        confirmUninstall.value = null
                        tick.value++
                        toast("已卸载")
                    }) { Text("卸载", color = AColors.Danger) }
                },
                dismissButton = { TextButton({ confirmUninstall.value = null }) { Text("取消") } },
            )
        }
    }

    @Composable
    private fun PluginCard(p: PluginStore.Installed) {
        AgentCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(AColors.Violet.copy(alpha = .13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null,
                        tint = AColors.Violet, modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p.manifest.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(p.manifest.category)
                            if (p.manifest.author.isNotBlank()) append(" · ").append(p.manifest.author)
                            append(" · ").append(if (p.manifest.type == "skill") "技能" else p.manifest.type)
                        },
                        fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = p.enabled,
                    onCheckedChange = { v ->
                        PluginStore.setEnabled(this@PluginManagerActivity, p.manifest.id, v)
                        tick.value++
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AColors.Accent),
                )
            }

            if (p.manifest.description.isNotBlank()) {
                Text(p.manifest.description, fontSize = 12.sp, color = AColors.InkSoft,
                    modifier = Modifier.padding(top = 10.dp), lineHeight = 17.sp)
            }

            if (p.manifest.params.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("参数：" + p.manifest.params.joinToString("、") {
                    "${it.name}:${it.type}${if (it.required) "*" else ""}"
                }, fontSize = 11.sp, color = AColors.Muted)
            }

            if (p.manifest.permissions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    Icon(Icons.Outlined.Terminal, contentDescription = null,
                        tint = AColors.Warning, modifier = Modifier.size(13.dp))
                    Text(" 需权限：" + p.manifest.permissions.joinToString("、"),
                        fontSize = 11.sp, color = AColors.Warning)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("卸载", fontSize = 13.sp, color = AColors.Danger, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { confirmUninstall.value = p }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}