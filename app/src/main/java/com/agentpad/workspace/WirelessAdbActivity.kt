package com.agentpad.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.AppLog
import com.agentpad.workspace.data.WirelessAdb
import com.agentpad.workspace.ui.*

/**
 * 无线调试（配对码直连）设置页——不依赖 Shizuku。
 *
 * 步骤：
 *   1. 打开开发者选项 → 无线调试
 *   2. 点「使用配对码配对设备」拿到 IP:端口 + 6 位码
 *   3. 填入 → 配对 → 连接
 */
class WirelessAdbActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val activity = this@WirelessAdbActivity

        var pairHost by remember { mutableStateOf("") }
        var pairPort by remember { mutableStateOf("") }
        var pairCode by remember { mutableStateOf("") }

        var connHost by remember { mutableStateOf("") }
        var connPort by remember { mutableStateOf("") }

        var busy by remember { mutableStateOf(false) }
        var busyText by remember { mutableStateOf("") }
        var log by remember { mutableStateOf("") }

        var enabled by remember { mutableStateOf(WirelessAdb.isEnabled(activity)) }
        var endpoint by remember { mutableStateOf(WirelessAdb.savedEndpoint(activity)) }

        fun appendLog(s: String) {
            log = if (log.isBlank()) s else "$log\n$s"
        }

        SubPage("无线调试", "配对码直连 · 不依赖 Shizuku", onBack = { finish() }) {

            // ---------- 状态 ----------
            SectionLabel("当前状态")
            AgentCard(
                background = if (endpoint != null && enabled) AColors.SuccessSoft else AColors.SurfaceAlt,
                border = if (endpoint != null && enabled) AColors.Success.copy(alpha = .35f) else AColors.LineSoft,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (endpoint != null && enabled) Icons.Outlined.CheckCircle else Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = if (endpoint != null && enabled) AColors.Success else AColors.Muted,
                        modifier = Modifier.size(21.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            WirelessAdb.statusText(activity),
                            fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink,
                        )
                        Text(
                            if (endpoint == null) "请按下面步骤配对" else "AI 将使用此 ADB 通道执行命令",
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (endpoint != null) {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v ->
                                enabled = v
                                WirelessAdb.setEnabled(activity, v)
                                Toast.makeText(activity, if (v) "已启用无线 ADB" else "已停用", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AColors.Success,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 步骤 1：打开无线调试 ----------
            SectionLabel("第 1 步：打开无线调试")
            AgentCard {
                Text(
                    "进入「开发者选项 → 无线调试」并打开开关（需连接 Wi-Fi）。\n" +
                        "然后点「使用配对码配对设备」，会显示 IP:端口 和 6 位配对码。",
                    fontSize = 12.5.sp, color = AColors.InkSoft, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    SmallButton("开发者选项") {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure {
                            Toast.makeText(activity, "无法打开，请手动去系统设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("无线调试") {
                        runCatching {
                            startActivity(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // ⚠ 2026-11-04 新增：通知栏直接配对（照抄 Shizuku 的做法）。
                // 点这里后不必回 App，通知栏会自动出现「输入配对码」输入框，
                // 因为端口是 mDNS 自动发现的，用户只需要填 6 位码。
                PrimaryButton(
                    text = "用通知栏配对（推荐）",
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                            Toast.makeText(activity, "需要 Android 11 及以上", Toast.LENGTH_SHORT).show()
                            return@PrimaryButton
                        }
                        Toast.makeText(
                            activity,
                            "已开始搜索，请打开「无线调试 → 使用配对码配对设备」，然后看通知栏",
                            Toast.LENGTH_LONG,
                        ).show()
                        AdbPairingService.start(activity)
                        activity.finish()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 步骤 2：填配对信息 ----------
            SectionLabel("第 2 步：输入配对信息")
            AgentCard {
                AgentTextField(value = pairHost, onValueChange = { pairHost = it },
                    placeholder = "配对 IP（如 192.168.1.5）")
                Spacer(Modifier.height(8.dp))
                Row {
                    Box(Modifier.weight(1f)) {
                        AgentTextField(value = pairPort, onValueChange = { pairPort = it.filter { c -> c.isDigit() } },
                            placeholder = "配对端口", keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1.2f)) {
                        AgentTextField(value = pairCode, onValueChange = { pairCode = it.filter { c -> c.isDigit() }.take(6) },
                            placeholder = "6 位配对码", keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword))
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = if (busy) busyText else "开始配对",
                    loading = busy,
                    onClick = {
                        val port = pairPort.toIntOrNull()
                        if (pairHost.isBlank() || port == null || pairCode.length != 6) {
                            Toast.makeText(activity, "请填写完整：IP / 端口 / 6 位配对码", Toast.LENGTH_SHORT).show()
                            return@PrimaryButton
                        }
                        busy = true; busyText = "配对中…"
                        Thread {
                            val r = WirelessAdb.pair(activity, pairHost.trim(), port, pairCode)
                            activity.runOnUiThread {
                                busy = false
                                appendLog(if (r.ok) "✓ ${r.message}" else "✗ ${r.message}")
                                Toast.makeText(activity, r.message, Toast.LENGTH_LONG).show()
                                if (r.ok) {
                                    // 配对成功后，连接端口通常与配对端口不同，提示用户填
                                    connHost = pairHost.trim()
                                }
                            }
                        }.start()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 步骤 3：连接 ----------
            SectionLabel("第 3 步：连接（配对成功后）")
            AgentCard {
                Text(
                    "回到「无线调试」主页，那里显示的 IP:端口 是连接用的（与配对端口不同）。",
                    fontSize = 12.sp, color = AColors.Muted, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                AgentTextField(value = connHost, onValueChange = { connHost = it },
                    placeholder = "连接 IP")
                Spacer(Modifier.height(8.dp))
                AgentTextField(value = connPort, onValueChange = { connPort = it.filter { c -> c.isDigit() } },
                    placeholder = "连接端口（无线调试主页显示）",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    if (busy) busyText else "连接设备",
                ) {
                    val port = connPort.toIntOrNull()
                    if (connHost.isBlank() || port == null) {
                        Toast.makeText(activity, "请填写连接 IP 和端口", Toast.LENGTH_SHORT).show()
                        return@SecondaryButton
                    }
                    busy = true; busyText = "连接中…"
                    Thread {
                        val r = WirelessAdb.connect(activity, connHost.trim(), port)
                        activity.runOnUiThread {
                            busy = false
                            appendLog(if (r.ok) "✓ ${r.message}" else "✗ ${r.message}")
                            Toast.makeText(activity, r.message, Toast.LENGTH_LONG).show()
                            if (r.ok) {
                                endpoint = WirelessAdb.savedEndpoint(activity)
                                enabled = true
                                WirelessAdb.setEnabled(activity, true)
                            }
                        }
                    }.start()
                }
            }

            // ---------- 测试 ----------
            if (endpoint != null) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("测试")
                AgentCard {
                    SecondaryButton(if (busy) busyText else "执行 id 测试连接") {
                        busy = true; busyText = "执行中…"
                        Thread {
                            val r = WirelessAdb.exec(activity, "id")
                            activity.runOnUiThread {
                                busy = false
                                appendLog("id → ${r.stdout.trim().ifBlank { r.stderr.trim() }}")
                            }
                        }.start()
                    }
                }
            }

            // ---------- 日志 ----------
            if (log.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("操作记录")
                AgentCard(background = AColors.SurfaceAlt) {
                    Text(log, fontSize = 11.5.sp, color = AColors.InkSoft,
                        fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "提示：无线调试要求手机与设备在同一 Wi-Fi；部分机型每次重启后需要重新配对。",
                fontSize = 11.5.sp, color = AColors.Faint,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    @Composable
    private fun SmallButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(AColors.AccentSoft)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(text, fontSize = 12.5.sp, color = AColors.Accent, fontWeight = FontWeight.Medium)
        }
    }
}