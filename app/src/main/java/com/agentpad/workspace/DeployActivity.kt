package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.DeployStore
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*
import java.io.File

/**
 * 我的网站部署页。
 *
 * 规则：一人一站，服务器二选一，部署后锁定；删除后可重新选。
 * 访问优先用直连地址（performance 有独立 HTTPS 证书）。
 */
class DeployActivity : ComponentActivity() {

    private val pendingZip = mutableStateOf<Uri?>(null)

    private val zipPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingZip.value = uri }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val activity = this@DeployActivity

        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var busyText by remember { mutableStateOf("") }
        var ov by remember { mutableStateOf<DeployStore.Overview?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var pickedHost by remember { mutableStateOf(DeployStore.HOST_NORMAL) }
        var siteName by remember { mutableStateOf("") }
        var confirmDelete by remember { mutableStateOf(false) }

        fun refresh() {
            if (!UserStore.isLoggedIn(activity)) {
                loading = false; error = "请先登录后再部署网站"; return
            }
            loading = true; error = null
            Thread {
                val r = runCatching { DeployStore.status(UserStore.token(activity)) }
                activity.runOnUiThread {
                    loading = false
                    r.onSuccess { o ->
                        ov = o
                        o.site?.let { if (siteName.isBlank()) siteName = it.siteName }
                    }.onFailure { error = it.message ?: "加载失败" }
                }
            }.start()
        }

        LaunchedEffect(Unit) { refresh() }

        fun startPick(host: String) {
            if (!UserStore.isLoggedIn(activity)) {
                Toast.makeText(activity, "请先登录", Toast.LENGTH_SHORT).show(); return
            }
            pickedHost = host
            zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        LaunchedEffect(pendingZip.value) {
            val uri = pendingZip.value ?: return@LaunchedEffect
            pendingZip.value = null
            if (busy) return@LaunchedEffect
            val host = pickedHost
            busy = true; busyText = "读取压缩包…"
            Thread {
                val cache = File(activity.cacheDir, "deploy_${System.currentTimeMillis()}.zip")
                val ok = runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { i ->
                        cache.outputStream().use { o -> i.copyTo(o) }
                    }
                }.isSuccess && cache.isFile && cache.length() > 0
                if (!ok) {
                    activity.runOnUiThread { busy = false; Toast.makeText(activity, "读取文件失败", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                if (cache.length() > 20L * 1048576) {
                    cache.delete()
                    activity.runOnUiThread { busy = false; Toast.makeText(activity, "压缩包不能超过 20MB", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                activity.runOnUiThread { busyText = "上传部署中…" }
                val r = runCatching { DeployStore.deploy(UserStore.token(activity), host, siteName.trim(), cache) }
                cache.delete()
                activity.runOnUiThread {
                    busy = false
                    r.onSuccess { s ->
                        Toast.makeText(activity, "部署成功：${s.preferredUrl}", Toast.LENGTH_LONG).show()
                        refresh()
                    }.onFailure {
                        Toast.makeText(activity, it.message ?: "部署失败", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        fun doRenew() {
            busy = true; busyText = "续期中…"
            Thread {
                val r = runCatching { DeployStore.renew(UserStore.token(activity)) }
                activity.runOnUiThread {
                    busy = false
                    r.onSuccess { Toast.makeText(activity, "已续期，剩余 ${it.daysLeft} 天", Toast.LENGTH_LONG).show(); refresh() }
                        .onFailure { Toast.makeText(activity, it.message ?: "续期失败", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        fun doDelete() {
            busy = true; busyText = "删除中…"
            Thread {
                val r = runCatching { DeployStore.delete(UserStore.token(activity)) }
                activity.runOnUiThread {
                    busy = false
                    r.onSuccess {
                        Toast.makeText(activity, "站点已删除，可重新选择服务器", Toast.LENGTH_LONG).show()
                        refresh()
                    }.onFailure { Toast.makeText(activity, it.message ?: "删除失败", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        SubPage("我的网站", "一人一站 · 部署后档位锁定", onBack = { finish() }) {

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = AColors.Accent)
                }
                return@SubPage
            }

            error?.let {
                EmptyState(
                    icon = Icons.Outlined.Language,
                    title = it,
                    hint = if (!UserStore.isLoggedIn(activity)) "登录后即可创建你的第一个网站" else null,
                )
                return@SubPage
            }

            val o = ov ?: return@SubPage
            val site = o.site
            val normalSlot = o.slots[DeployStore.HOST_NORMAL]
            val perfSlot = o.slots[DeployStore.HOST_PERFORMANCE]

            // ================= 未部署：选服务器 =================
            if (site == null) {
                SectionLabel("选择服务器（只能选一个，部署后锁定）")
                Spacer(Modifier.height(2.dp))

                // 普通服务器
                ServerChoiceCard(
                    title = normalSlot?.label ?: "普通服务器",
                    sub = "每月 1GB 流量 · 1MB/s",
                    icon = Icons.Outlined.Storage,
                    tint = AColors.Accent,
                    selected = pickedHost == DeployStore.HOST_NORMAL,
                    onClick = { pickedHost = DeployStore.HOST_NORMAL },
                )
                Spacer(Modifier.height(10.dp))

                // 高性能服务器
                ServerChoiceCard(
                    title = perfSlot?.label ?: "高性能服务器",
                    sub = "每月 10GB 流量 · 5MB/s",
                    icon = Icons.Outlined.Speed,
                    tint = AColors.Violet,
                    selected = pickedHost == DeployStore.HOST_PERFORMANCE,
                    onClick = { pickedHost = DeployStore.HOST_PERFORMANCE },
                )

                // 高性能副作用提示
                if (pickedHost == DeployStore.HOST_PERFORMANCE) {
                    Spacer(Modifier.height(10.dp))
                    WarningCard()
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("部署")
                AgentCard {
                    AgentTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        placeholder = "站点名称（可选）",
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = if (busy) busyText else "选择 zip 并部署到${if (pickedHost == DeployStore.HOST_PERFORMANCE) "高性能" else "普通"}服务器",
                        loading = busy,
                        onClick = { startPick(pickedHost) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "zip 内需包含 index.html。部署后档位锁定，如需换服务器请先删除站点。",
                        fontSize = 12.sp, color = AColors.Faint, lineHeight = 17.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("档位说明")
                AgentCard {
                    RuleRow(Icons.Outlined.CloudUpload, AColors.Violet, "普通：1GB/月 · 1MB/s", "稳定运行，适合个人主页")
                    Spacer(Modifier.height(12.dp))
                    RuleRow(Icons.Outlined.Speed, AColors.Accent, "高性能：10GB/月 · 5MB/s", "流量更大，但主机可能不稳定")
                    Spacer(Modifier.height(12.dp))
                    RuleRow(Icons.Outlined.Update, AColors.Amber, "30 天有效期", "期间可续期，每次续满 30 天")
                }
            } else {
                // ================= 已部署：锁定档位 =================
                val isPerf = site.host == DeployStore.HOST_PERFORMANCE

                // 锁定提示
                AgentCard(background = AColors.SurfaceAlt, border = AColors.LineSoft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, contentDescription = null,
                            tint = AColors.Muted, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("当前档位：${site.hostLabel}", fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text("已锁定，要更换服务器需先删除站点",
                                fontSize = 11.5.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionLabel("我的站点")
                GradientCard(
                    colors = if (isPerf) listOf(Color(0xFF7C5CF0), Color(0xFF5267E8))
                             else listOf(AColors.Accent, AColors.Violet),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(22.dp))
                        Text(
                            site.siteName.ifBlank { "我的网站" },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(site.statusText, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 主访问地址（直连优先）
                    Text(
                        if (site.urlDirect.isNotBlank() && site.urlDirect != site.urlGateway) "访问地址（直连 · 推荐）" else "访问地址",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f),
                    )
                    Text(
                        site.preferredUrl, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    // 网关地址（备用）
                    if (site.urlDirect.isNotBlank() && site.urlDirect != site.urlGateway) {
                        Spacer(Modifier.height(8.dp))
                        Text("备用地址（网关）", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                        Text(site.urlGateway, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 3.dp))
                    }

                    Spacer(Modifier.height(14.dp))
                    Row {
                        CardActionButton("打开网站", Icons.Outlined.NorthEast) {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.preferredUrl))) }
                        }
                        if (site.urlDirect.isNotBlank() && site.urlDirect != site.urlGateway) {
                            Spacer(Modifier.width(10.dp))
                            CardActionButton("备用打开", Icons.Outlined.NorthEast) {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.urlGateway))) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("运行状态")
                AgentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("当前状态", fontSize = 14.sp, color = AColors.InkSoft, modifier = Modifier.weight(1f))
                        Tag(site.statusText,
                            if (site.status == "active") AColors.Success else AColors.Warning,
                            if (site.status == "active") AColors.SuccessSoft else AColors.WarningSoft)
                    }
                    Spacer(Modifier.height(14.dp)); Divider(); Spacer(Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本月流量", fontSize = 14.sp, color = AColors.InkSoft, modifier = Modifier.weight(1f))
                        Text(site.trafficText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (site.trafficPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (site.trafficPercent > 90) AColors.Danger else AColors.Accent,
                        trackColor = AColors.LineSoft,
                    )
                    Spacer(Modifier.height(14.dp)); Divider(); Spacer(Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("有效期至", fontSize = 14.sp, color = AColors.InkSoft, modifier = Modifier.weight(1f))
                        Text(site.expiresAt, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("剩余天数", fontSize = 14.sp, color = AColors.InkSoft, modifier = Modifier.weight(1f))
                        Text("${site.daysLeft} 天", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (site.daysLeft <= 3) AColors.Danger else AColors.Success)
                    }
                }

                if (isPerf) {
                    Spacer(Modifier.height(12.dp))
                    WarningCard()
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("操作")
                PrimaryButton(
                    text = if (busy) busyText else "续期（续满 30 天）",
                    loading = busy,
                    onClick = { doRenew() },
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(text = "更新网站内容（重新上传 zip）") {
                    if (!busy) startPick(site.host)
                }
                Spacer(Modifier.height(10.dp))
                SecondaryButton(text = "删除站点（删除后可重选服务器）") {
                    if (!busy) confirmDelete = true
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "站点到期自动删除；流量每月 1 日清零。",
                fontSize = 12.sp, color = AColors.Faint,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除站点？") },
                text = { Text("站点将被永久删除，网站无法访问，且不可恢复。删除后可以重新选择服务器档位。") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; doDelete() }) {
                        Text("确认删除", color = AColors.Danger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            )
        }
    }

    /** 服务器选择卡片 */
    @Composable
    private fun ServerChoiceCard(
        title: String, sub: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        tint: Color, selected: Boolean, onClick: () -> Unit,
    ) {
        AgentCard(
            background = if (selected) tint.copy(alpha = .08f) else AColors.Surface,
            border = if (selected) tint.copy(alpha = .5f) else AColors.LineSoft,
            onClick = onClick,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(icon, tint)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    Text(sub, fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                }
                if (selected) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(tint),
                        contentAlignment = Alignment.Center) {
                        Text("✓", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))
                        .border(1.5.dp, AColors.Line, RoundedCornerShape(11.dp)))
                }
            }
        }
    }

    /** 高性能档副作用提示 */
    @Composable
    private fun WarningCard() {
        AgentCard(background = AColors.WarningSoft, border = AColors.Warning.copy(alpha = .35f)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = AColors.Warning, modifier = Modifier.size(19.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("使用前请注意", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AColors.Warning)
                    Text(
                        "高性能服务器运行在独立主机上，可能存在不稳定情况（重启、短暂不可用）。重要数据请自行备份，站点不承担数据丢失责任。",
                        fontSize = 12.sp, color = AColors.InkSoft, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun RuleRow(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        tint: Color, title: String, subtitle: String,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
                Text(subtitle, fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    @Composable
    private fun CardActionButton(
        text: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}