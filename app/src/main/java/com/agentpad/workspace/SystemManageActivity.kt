package com.agentpad.workspace

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.DistroManager
import com.agentpad.workspace.ui.*
import java.io.File
import java.util.concurrent.Executors

/**
 * 「设置 → 系统管理」：管理**终端执行器系统**（rootfs）。
 *
 * ## 能力
 *  - 列出全部系统（App 内置 + 用户自建），显示安装状态 / 是否生效
 *  - 已安装的 → 点一下「切换」即刻生效（AI 终端、工具链、部署服务全部跟着走）
 *  - 未安装的 → 下载安装（内置 Alpine 3.19 走阿里云源）
 *  - 外部链接 → 填 URL 新增一个系统，格式自动识别（tar.gz / tar）
 *  - 自建系统 → 可删除；内置系统 → 只可重装
 *
 * ## 为什么切换不需要重启 App
 * `sandbox/rootfs` 是一个**软链**，指向 `sandbox/systems/<当前id>`。
 * 所有既有调用点（SandboxRunner / TerminalService / DshService /
 * DownloadEnvironmentActivity / EnvCatalog）拿到的都是 `rootfsDir()` 这个路径，
 * 软链一改，下一条命令就走新系统，无需重建任何对象。
 */
class SystemManageActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    /** 每张卡的运行时状态（UI 层私有，不进数据层）。 */
    private class CardState {
        var running by mutableStateOf(false)
        var percent by mutableStateOf(-1)
        var status by mutableStateOf("")
        var failed by mutableStateOf(false)
    }

    private lateinit var stateMap: MutableMap<String, CardState>
    private val tick = mutableStateOf(0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        refreshStates()
        setContent { AgentTheme { Content() } }
    }

    private fun refreshStates() {
        val ids = DistroManager.all(this).map { it.id }
        if (!::stateMap.isInitialized) {
            stateMap = ids.associateWith { CardState() }.toMutableMap()
        } else {
            ids.forEach { stateMap.getOrPut(it) { CardState() } }
        }
        tick.value++
    }

    @Composable
    private fun Content() {
        // ⚠ 绝不能在这里再套 Column(Modifier.verticalScroll())：
        //   SubPage 自带一层 verticalScroll（scrollable 默认 true），
        //   两层可滚动嵌套 → infinity maximum height constraints → 直接崩。
        //   内容直接平铺进 SubPage 的 ColumnScope 即可。
        SubPage("系统管理", "切换终端执行器系统（rootfs）", onBack = { finish() }) {
            ActiveCard()
            Spacer(Modifier.height(16.dp))
            SectionLabel("可用系统")
            DistroList()
            Spacer(Modifier.height(16.dp))
            AddCustomCard()
            Spacer(Modifier.height(16.dp))
            HintCard()
        }
    }

    /** 顶部：当前生效系统 + 一句话说明切换是即时的。 */
    @Composable
    private fun ActiveCard() {
        val active = DistroManager.activeId(this)
        val distro = DistroManager.find(this, active)
        val installed = DistroManager.isInstalled(this, active)
        GradientCard(colors = listOf(AColors.Accent, AColors.Violet)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Text("当前生效", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .85f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                distro?.label ?: active,
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (installed) "已就绪 · 切换其他系统后立即生效，不用重启 App"
                else "尚未安装 · 请先在下面安装一个系统",
                fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .85f),
                lineHeight = 17.sp,
            )
        }
    }

    @Composable
    private fun DistroList() {
        val list = DistroManager.all(this)
        // ⚠ 外层 Column 已经 verticalScroll，这里**绝不能**再用 LazyColumn
        //   （可滚动嵌套可滚动会崩：infinity maximum height constraints）。
        //   系统数量是个位数，普通 Column 完全够。
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list.forEach { d -> key(d.id) { DistroCard(d) } }
        }
    }

    @Composable
    private fun DistroCard(distro: DistroManager.Distro) {
        tick.value   // 状态变化时重组
        val s = stateMap[distro.id]!!
        val installed = DistroManager.isInstalled(this, distro.id)
        val active = DistroManager.activeId(this) == distro.id
        val sizeText = dirSizeText(this, distro.id)

        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(
                    if (active) Icons.Outlined.CheckCircle else Icons.Outlined.Dns,
                    if (active) AColors.Success else AColors.Accent,
                )
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(distro.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Tag("生效中", AColors.Success, AColors.SuccessSoft)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (installed) {
                            Tag(if (sizeText.isBlank()) "已安装" else "已安装 · $sizeText",
                                AColors.InkSoft, AColors.AccentGhost)
                        } else {
                            Tag("未安装", AColors.Muted, AColors.LineSoft)
                        }
                        Spacer(Modifier.width(6.dp))
                        Tag(distro.pkgManager, AColors.Violet, AColors.AccentGhost)
                    }
                }
            }

            Text(distro.desc, fontSize = 12.sp, color = AColors.Muted,
                modifier = Modifier.padding(top = 10.dp), lineHeight = 17.sp)

            // 链接单独一行，方便长按复制
            Text(distro.url, fontSize = 10.sp, color = AColors.Faint,
                modifier = Modifier.padding(top = 6.dp), maxLines = 2)

            if (s.percent >= 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { s.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (s.failed) AColors.Danger else AColors.Accent,
                    trackColor = AColors.LineSoft,
                )
                if (s.status.isNotBlank()) {
                    Text(s.status, fontSize = 11.sp,
                        color = if (s.failed) AColors.Danger else AColors.Muted,
                        modifier = Modifier.padding(top = 7.dp), lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 主按钮：安装 / 切换
                Box(
                    Modifier.weight(1f).height(44.dp)
                        .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                        .background(
                            when {
                                s.running -> AColors.Accent.copy(alpha = .5f)
                                active -> AColors.SuccessSoft
                                else -> AColors.Accent
                            }
                        )
                        .clickable(enabled = !s.running) { primaryAction(distro, s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!s.running) {
                            Icon(
                                if (installed) Icons.Outlined.PlayArrow else Icons.Outlined.Download,
                                contentDescription = null,
                                tint = if (active) AColors.Success else androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(
                            when {
                                s.running -> "处理中…"
                                active -> "使用中"
                                installed -> "切换到此系统"
                                else -> "下载安装"
                            },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (active && !s.running) AColors.Success else androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                // 次按钮：重装 / 删除
                Box(
                    Modifier.size(44.dp)
                        .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                        .background(AColors.Surface)
                        .clickable(enabled = !s.running) { secondaryAction(distro, s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (distro.builtin) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                        contentDescription = if (distro.builtin) "重装" else "删除",
                        tint = if (distro.builtin) AColors.Muted else AColors.Danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    private fun primaryAction(distro: DistroManager.Distro, s: CardState) {
        val installed = DistroManager.isInstalled(this, distro.id)
        if (DistroManager.activeId(this) == distro.id) {
            Toast.makeText(this, "已经是当前生效的系统", Toast.LENGTH_SHORT).show()
            return
        }
        if (installed) {
            runCatching { DistroManager.setActive(this, distro.id) }
                .onSuccess {
                    Toast.makeText(this, "已切换到 ${distro.label}", Toast.LENGTH_SHORT).show()
                    refreshStates()
                }
                .onFailure { Toast.makeText(this, "切换失败：${it.message}", Toast.LENGTH_LONG).show() }
            return
        }
        confirmInstall(distro, s, reinstall = false)
    }

    private fun secondaryAction(distro: DistroManager.Distro, s: CardState) {
        if (distro.builtin) {
            if (!DistroManager.isInstalled(this, distro.id)) {
                Toast.makeText(this, "尚未安装，无需重装", Toast.LENGTH_SHORT).show()
                return
            }
            confirmInstall(distro, s, reinstall = true)
        } else {
            confirmDelete(distro)
        }
    }

    private fun confirmInstall(distro: DistroManager.Distro, s: CardState, reinstall: Boolean) {
        android.app.AlertDialog.Builder(this)
            .setTitle(if (reinstall) "重装 ${distro.label}？" else "安装 ${distro.label}？")
            .setMessage(
                if (reinstall) "会先删除已解压的内容再重新下载，期间该系统的终端命令会失败。"
                else "将下载并解压到沙箱。安装过程需要几分钟，请保持前台。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ -> runInstall(distro, s, reinstall) }
            .show()
    }

    private fun confirmDelete(distro: DistroManager.Distro) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除 ${distro.label}？")
            .setMessage("解压后的文件会全部删除，链接记录也会移除。此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runCatching { DistroManager.uninstall(this, distro.id) }
                    .onSuccess { Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show(); refreshStates() }
                    .onFailure { Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
            .show()
    }

    private fun runInstall(distro: DistroManager.Distro, s: CardState, reinstall: Boolean) {
        if (s.running) return
        s.running = true
        s.failed = false
        s.percent = 0
        s.status = "准备中…"
        executor.execute {
            fun ui(b: () -> Unit) = runOnUiThread(b)
            try {
                if (reinstall) {
                    DistroManager.reinstall(this, distro, { m -> ui { s.status = m } }) { p -> ui { if (p in 0..100) s.percent = p } }
                } else {
                    DistroManager.install(this, distro, { m -> ui { s.status = m } }) { p -> ui { if (p in 0..100) s.percent = p } }
                }
                ui {
                    s.running = false
                    s.percent = 100
                    s.status = "已安装 ✓"
                    Toast.makeText(this, "${distro.label} 安装完成", Toast.LENGTH_SHORT).show()
                    refreshStates()
                }
            } catch (e: Exception) {
                ui {
                    s.running = false
                    s.failed = true
                    s.status = "失败：${e.message}"
                }
            }
        }
    }

    // ---------------- 外部链接新增 ----------------

    @Composable
    private fun AddCustomCard() {
        var showDialog by remember { mutableStateOf(false) }
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Outlined.Add, AColors.Teal)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text("从外部链接安装", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    Text("贴一个 rootfs 的 tar.gz / tar 直链", fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(AColors.Teal)
                    .clickable { showDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("添加链接", fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        if (showDialog) AddCustomDialog { showDialog = false }
    }

    @Composable
    private fun AddCustomDialog(onDismiss: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("添加自定义系统") },
            text = {
                Column {
                    Text("填写 rootfs 压缩包直链。支持 .tar.gz / .tgz / .tar；" +
                        "小飞机分享链接（share.feijipan.com/s/xxx）会自动解析。",
                        fontSize = 12.sp, color = AColors.Muted, lineHeight = 17.sp)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("名称（可留空）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("下载链接") },
                        placeholder = { Text("https://…/rootfs.tar.gz") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        DistroManager.addCustom(this@SystemManageActivity, name.trim(), url.trim())
                    }.onSuccess {
                        Toast.makeText(this@SystemManageActivity, "已添加，点「下载安装」开始", Toast.LENGTH_SHORT).show()
                        refreshStates()
                        onDismiss()
                    }.onFailure {
                        Toast.makeText(this@SystemManageActivity, "链接无效：${it.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    @Composable
    private fun HintCard() {
        AgentCard {
            Text("几点说明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
            Spacer(Modifier.height(8.dp))
            listOf(
                "切换系统只影响之后执行的命令，正在跑的会话不受影响。",
                "工具链（Java / Kotlin / NDK / 逆向链）装在各系统里是**独立**的，切换后要重新部署。",
                "Python 3.12 / 3.16 同理；不同系统的 libc 不同（Debian=glibc，Alpine=musl），预编译二进制不一定通用。",
                "「部署服务」的 dsh 装在 Debian 的 /opt/dshenv，切到 Alpine 后需要重新下载部署。",
                "内置系统不可删除，只能重装；自建系统可随时删除。",
            ).forEach { line ->
                Row(Modifier.padding(top = 5.dp)) {
                    Text("· ", fontSize = 12.sp, color = AColors.Accent)
                    Text(line, fontSize = 12.sp, color = AColors.Muted, lineHeight = 17.sp)
                }
            }
        }
    }

    /** 目录体积（用真实字节数，不用 du -sh 的块对齐虚高）。 */
    private fun dirSizeText(context: android.content.Context, id: String): String {
        val dir = DistroManager.systemDir(context, id)
        if (!dir.isDirectory) return ""
        val bytes = runCatching {
            var total = 0L
            val stack = ArrayDeque<File>(); stack.add(dir)
            while (stack.isNotEmpty()) {
                val f = stack.removeFirst()
                if (f.isDirectory) { f.listFiles()?.forEach { stack.add(it) } }
                else total += f.length()
            }
            total
        }.getOrDefault(0L)
        return when {
            bytes <= 0 -> ""
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }
}