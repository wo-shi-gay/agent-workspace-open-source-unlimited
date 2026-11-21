package com.agentpad.workspace

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.McpClient
import com.agentpad.workspace.ai.McpServerManager
import com.agentpad.workspace.ai.McpStore
import com.agentpad.workspace.ui.*
import java.util.UUID

/**
 * MCP 服务（Compose）：
 *  - 上半部分：本机作为 **MCP 服务端**，监听端口，把内置工具暴露给其它 App / AI 客户端。
 *  - 下半部分：本机作为 **MCP 客户端**，添加别人的 MCP Server，把其工具挂进对话。
 */
class McpActivity : ComponentActivity() {

    private val tick = mutableStateOf(0)
    private fun refresh() { tick.value++ }

    // 服务端编辑态
    private lateinit var srv: McpStore.Server

    // 远端编辑弹窗
    private val editing = mutableStateOf<McpStore.Remote?>(null)
    private val adding = mutableStateOf(false)
    private val probingId = mutableStateOf<String?>(null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        srv = McpStore.server(this)
        McpServerManager.onStateChanged = { runOnUiThread { refresh() } }
        setContent {
            AgentTheme {
                Content()
                if (adding.value) RemoteEditDialog(null)
                editing.value?.let { RemoteEditDialog(it) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        McpServerManager.onStateChanged = null
    }

    @Composable
    private fun Content() {
        // 读 tick 触发刷新
        tick.value
        SubPage("MCP 服务", "让外部 AI 调用本机工具，或把别人的 MCP 接进来", onBack = { finish() }) {

            // ============ 一、服务端 ============
            SectionLabel("① 本机作为 MCP 服务端")
            AgentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Outlined.Dns, AColors.Accent)
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text("开放本机工具", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("监听本地端口，外部客户端可通过 MCP 协议调用 terminal / read_file 等工具",
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp),
                            lineHeight = 16.sp)
                    }
                    Switch(
                        checked = srv.enabled,
                        onCheckedChange = { on ->
                            srv = srv.copy(enabled = on)
                            McpServerManager.apply(this@McpActivity, srv)
                            refresh()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = AColors.Accent),
                    )
                }
            }

            if (srv.enabled) {
                Spacer(Modifier.height(10.dp))
                // 运行状态
                val running = McpServerManager.isRunning
                val err = McpServerManager.lastError
                AgentCard(
                    background = if (running) AColors.SuccessSoft else AColors.DangerSoft,
                    border = null,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(if (running) StatusState.Ok else StatusState.Fail,
                            if (running) "运行中" else "未运行")
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (running) "HTTP 端口已监听：${McpServerManager.boundPort}"
                            else (err.ifBlank { "启动失败，请检查端口是否被占用" }),
                            fontSize = 12.sp, color = AColors.InkSoft,
                            modifier = Modifier.weight(1f), lineHeight = 16.sp,
                        )
                    }
                    if (running) {
                        Spacer(Modifier.height(12.dp))
                        Text("MCP 端点 URL", fontSize = 11.sp, color = AColors.Muted)
                        CopyRow(endpointUrl())
                        if (srv.allowLan) {
                            Spacer(Modifier.height(8.dp))
                            Text("局域网地址（其它设备用）", fontSize = 11.sp, color = AColors.Muted)
                            CopyRow("http://${localIp()}:${McpServerManager.boundPort}/mcp")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("重启服务") {
                        McpServerManager.apply(this@McpActivity, srv)
                        Toast.makeText(this@McpActivity, "已重启", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }

                Spacer(Modifier.height(4.dp))
                // 端口 + 选项
                Spacer(Modifier.height(10.dp))
                AgentCard {
                    Text("监听端口", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    Spacer(Modifier.height(8.dp))
                    AgentTextField(
                        srv.port.toString(), { v ->
                            v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { p ->
                                if (p in 1024..65535) srv = srv.copy(port = p)
                            }
                        }, "1024 - 65535",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(14.dp))
                    ToggleRow("允许局域网访问", "关闭时仅本机 127.0.0.1 可连；开启后同 WiFi 的电脑/手机都能连",
                        srv.allowLan) { srv = srv.copy(allowLan = it) }
                    Divider(Modifier.padding(vertical = 12.dp))
                    Text("访问令牌（可选）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    Text("非空时，客户端必须带 Authorization: Bearer <token>", fontSize = 11.sp,
                        color = AColors.Muted, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                    AgentTextField(srv.token, { srv = srv.copy(token = it) }, "留空则不鉴权")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            SecondaryButton("随机生成令牌") {
                                srv = srv.copy(token = UUID.randomUUID().toString().replace("-", ""))
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            SecondaryButton("清空令牌") { srv = srv.copy(token = "") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("保存并重启") {
                        McpServerManager.apply(this@McpActivity, srv)
                        Toast.makeText(this@McpActivity, "已保存", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }

                // 开放哪些工具
                Spacer(Modifier.height(16.dp))
                SectionLabel("对外公开的工具")
                val allTools = listOf("terminal", "terminal_screen", "read_file", "write_file", "edit_file", "install_apk", "plan")
                AgentCard {
                    Text(
                        if (srv.exposedTools.isEmpty()) "当前：全部公开（点击下方可勾选限制）"
                        else "当前：已选 ${srv.exposedTools.size} 个",
                        fontSize = 12.sp, color = AColors.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    allTools.forEach { name ->
                        val checked = srv.exposedTools.isEmpty() || name in srv.exposedTools
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = checked,
                                onCheckedChange = { on ->
                                    val cur = if (srv.exposedTools.isEmpty()) allTools.toSet() else srv.exposedTools
                                    srv = srv.copy(exposedTools = if (on) cur + name else cur - name)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = AColors.Accent),
                            )
                            Text(name, fontSize = 14.sp, color = AColors.Ink,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (srv.exposedTools.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("恢复全部公开") { srv = srv.copy(exposedTools = emptySet()) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("保存工具范围") {
                    McpServerManager.apply(this@McpActivity, srv)
                    Toast.makeText(this@McpActivity, "已保存", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }

            // ============ 二、客户端（接入别人的 MCP） ============
            Spacer(Modifier.height(24.dp))
            SectionLabel("② 接入别人的 MCP Server") {
                Text("＋ 添加", fontSize = 13.sp, color = AColors.Accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { adding.value = true })
            }

            val remotes = McpStore.remotes(this@McpActivity)
            if (remotes.isEmpty()) {
                EmptyState(Icons.Outlined.Extension, "还没有接入任何 MCP",
                    "点右上「＋ 添加」，填入别人的 MCP 服务地址（Streamable HTTP 端点）") {
                    PrimaryButton("添加 MCP Server", Modifier.width(200.dp)) { adding.value = true }
                }
            } else {
                remotes.forEach { r ->
                    RemoteCard(r)
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            // 帮助
            AgentCard(background = AColors.SurfaceAlt, border = null) {
                Text("怎么用？", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                listOf(
                    "· 服务端 URL 格式：http://<手机IP>:<端口>/mcp（本机测试用 127.0.0.1）",
                    "· 在电脑的 Claude Desktop / Cursor 等 MCP 客户端里，添加一个 HTTP 类型的 MCP，填上面的 URL",
                    "· 客户端配置示例：{\"mcpServers\":{\"agentpad\":{\"url\":\"http://127.0.0.1:8765/mcp\"}}}",
                    "· 接入别人的 MCP 后，其工具会以 mcp__<服务>__<工具名> 出现在 AI 工具列表里",
                ).forEach {
                    Text(it, fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 7.dp), lineHeight = 17.sp)
                }
            }
        }
    }

    // ---------------- 组件 ----------------

    @Composable
    private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                Text(subtitle, fontSize = 11.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 3.dp), lineHeight = 15.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = AColors.Accent))
        }
    }

    @Composable
    private fun CopyRow(text: String) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp)
                .clip(RoundedCornerShape(ADimens.ChipRadius)).background(AColors.SurfaceAlt)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 12.sp, color = AColors.Accent,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = AColors.Accent,
                modifier = Modifier.size(18.dp).clickable {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MCP URL", text))
                    Toast.makeText(this@McpActivity, "已复制", Toast.LENGTH_SHORT).show()
                })
        }
    }

    @Composable
    private fun RemoteCard(r: McpStore.Remote) {
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Outlined.Extension, if (r.lastError.isBlank()) AColors.Violet else AColors.Danger)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.url, fontSize = 11.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { on ->
                        McpStore.upsertRemote(this@McpActivity, r.copy(enabled = on)); refresh()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = AColors.Accent),
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.lastError.isBlank()) {
                    StatusBadge(StatusState.Ok, "${r.toolCount} 个工具")
                } else {
                    StatusBadge(StatusState.Fail, "连接失败")
                }
                Spacer(Modifier.weight(1f))
            }
            if (r.lastError.isNotBlank()) {
                Text("错误：${r.lastError}", fontSize = 11.sp, color = AColors.Danger,
                    modifier = Modifier.padding(top = 8.dp), lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip(
                    if (probingId.value == r.id) "探测中…" else "测试 & 刷新工具",
                    AColors.Accent,
                    enabled = probingId.value == null,
                ) { probeRemote(r) }
                MiniChip("编辑", AColors.Muted) { editing.value = r }
                MiniChip("删除", AColors.Danger) {
                    McpStore.removeRemote(this@McpActivity, r.id); refresh()
                }
            }
        }
    }

    @Composable
    private fun MiniChip(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(ADimens.ChipRadius))
                .background(color.copy(alpha = if (enabled) 0.1f else 0.05f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text, fontSize = 12.sp,
                color = if (enabled) color else color.copy(alpha = 0.4f),
                fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun RemoteEditDialog(existing: McpStore.Remote?) {
        val isNew = existing == null
        var name by remember { mutableStateOf(existing?.name ?: "") }
        var url by remember { mutableStateOf(existing?.url ?: "") }
        var token by remember { mutableStateOf(existing?.token ?: "") }
        var expose by remember { mutableStateOf(existing?.exposeToAi ?: true) }

        AlertDialog(
            onDismissRequest = { adding.value = false; editing.value = null },
            title = { Text(if (isNew) "添加 MCP Server" else "编辑 MCP Server", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AgentTextField(name, { name = it }, "名称，如「高德地图 MCP」")
                    Spacer(Modifier.height(10.dp))
                    AgentTextField(url, { url = it }, "http://host:port/mcp",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(10.dp))
                    AgentTextField(token, { token = it }, "Bearer Token（可选）")
                    Spacer(Modifier.height(14.dp))
                    ToggleRow("把工具暴露给 AI", "关闭后仅保存配置，不注入到对话工具列表", expose) { expose = it }
                }
            },
            confirmButton = {
                TextButton({
                    val n = name.trim(); val u = url.trim()
                    if (n.isEmpty() || u.isEmpty()) {
                        Toast.makeText(this, "请填写名称和地址", Toast.LENGTH_SHORT).show(); return@TextButton
                    }
                    if (!u.startsWith("http")) {
                        Toast.makeText(this, "地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show(); return@TextButton
                    }
                    val rec = McpStore.Remote(
                        id = existing?.id ?: "mcp_${System.currentTimeMillis()}",
                        name = n, url = u, token = token.trim(),
                        enabled = existing?.enabled ?: true,
                        toolCount = existing?.toolCount ?: 0,
                        lastError = existing?.lastError ?: "",
                        exposeToAi = expose,
                    )
                    McpStore.upsertRemote(this, rec)
                    adding.value = false; editing.value = null
                    refresh()
                    // 新增后立即探测一次
                    if (isNew) probeRemote(rec)
                }) { Text(if (isNew) "添加" else "保存") }
            },
            dismissButton = {
                TextButton({ adding.value = false; editing.value = null }) { Text("取消") }
            },
        )
    }

    // ---------------- 逻辑 ----------------

    private fun probeRemote(r: McpStore.Remote) {
        probingId.value = r.id
        Thread {
            val (tools, err) = McpClient.probe(r.url, r.token)
            runOnUiThread {
                probingId.value = null
                if (err == null) {
                    val withId = tools.map { it.copy(serverId = r.id) }
                    McpStore.replaceToolsFor(this, r.id, withId)
                    McpStore.upsertRemote(this, r.copy(toolCount = withId.size, lastError = ""))
                    Toast.makeText(this, "连接成功，发现 ${withId.size} 个工具", Toast.LENGTH_LONG).show()
                } else {
                    McpStore.replaceToolsFor(this, r.id, emptyList())
                    McpStore.upsertRemote(this, r.copy(toolCount = 0, lastError = err))
                    Toast.makeText(this, "连接失败：$err", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }.start()
    }

    private fun endpointUrl(): String =
        "http://${if (srv.allowLan) localIp() else "127.0.0.1"}:${McpServerManager.boundPort}/mcp"

    /** 取本机 WiFi/局域网 IPv4 地址。 */
    private fun localIp(): String {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }
}