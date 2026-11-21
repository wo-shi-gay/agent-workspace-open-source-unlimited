package com.agentpad.workspace

import android.content.Intent
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.AdbPermission
import com.agentpad.workspace.data.AdbShell
import com.agentpad.workspace.data.AppLog
import com.agentpad.workspace.ui.*

/**
 * ADB 权限设置页。
 *
 * - 检测 Shizuku 安装/运行/授权状态
 * - 一键请求 Shizuku 授权
 * - 开关 ADB 权限（开启后 AI 权限升级）
 * - 开机保活开关
 * - 随时可切回普通权限
 */
class AdbPermissionActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val activity = this@AdbPermissionActivity

        var installed by remember { mutableStateOf(AdbPermission.isShizukuInstalled(activity)) }
        var alive by remember { mutableStateOf(AdbPermission.isShizukuAlive()) }
        var granted by remember { mutableStateOf(AdbPermission.isGranted()) }
        var enabled by remember { mutableStateOf(AdbPermission.prefEnabled(activity)) }
        var bootKeep by remember { mutableStateOf(AdbPermission.bootKeepAlive(activity)) }
        var identity by remember { mutableStateOf("") }

        fun refresh() {
            installed = AdbPermission.isShizukuInstalled(activity)
            alive = AdbPermission.isShizukuAlive()
            granted = AdbPermission.isGranted()
            enabled = AdbPermission.prefEnabled(activity)
            if (granted && alive) {
                Thread {
                    val id = AdbShell.identity(activity)
                    activity.runOnUiThread { identity = id }
                }.start()
            }
        }

        LaunchedEffect(Unit) { refresh() }

        SubPage("ADB 权限", "通过 Shizuku 获取系统级权限", onBack = { finish() }) {

            // ---------- 状态卡 ----------
            SectionLabel("状态")
            AgentCard(
                background = when {
                    !installed -> AColors.DangerSoft
                    !alive -> AColors.WarningSoft
                    !granted -> AColors.WarningSoft
                    else -> AColors.SuccessSoft
                },
                border = when {
                    !installed -> AColors.Danger.copy(alpha = .35f)
                    !alive || !granted -> AColors.Warning.copy(alpha = .35f)
                    else -> AColors.Success.copy(alpha = .35f)
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            !installed || !alive || !granted -> Icons.Outlined.ErrorOutline
                            else -> Icons.Outlined.CheckCircle
                        },
                        contentDescription = null,
                        tint = when {
                            !installed -> AColors.Danger
                            !alive || !granted -> AColors.Warning
                            else -> AColors.Success
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            AdbPermission.statusText(activity),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink,
                        )
                        if (identity.isNotBlank()) {
                            Text("当前身份：$identity", fontSize = 11.5.sp, color = AColors.Muted,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 分步引导 ----------
            when {
                !installed -> {
                    SectionLabel("需要先安装 Shizuku")
                    AgentCard {
                        Text("Shizuku 是开源的权限代理工具，通过 ADB 或 root 授权后，应用即可获得系统级权限。",
                            fontSize = 13.sp, color = AColors.InkSoft, lineHeight = 19.sp)
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("去安装 Shizuku") {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                            }
                        }
                    }
                }
                !alive -> {
                    SectionLabel("Shizuku 服务未运行")
                    AgentCard {
                        Text("已安装 Shizuku，但服务没有启动。请打开 Shizuku 应用，按提示通过「无线调试」或「连接电脑」启动服务。",
                            fontSize = 13.sp, color = AColors.InkSoft, lineHeight = 19.sp)
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton("打开 Shizuku") {
                            runCatching {
                                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    ?.let { startActivity(it) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("重新检测") { refresh() }
                    }
                }
                !granted -> {
                    SectionLabel("等待授权")
                    AgentCard {
                        Text("Shizuku 已运行，但本应用还没有获得授权。点击下方按钮，在弹出框中选「允许」。",
                            fontSize = 13.sp, color = AColors.InkSoft, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("请求 Shizuku 授权") {
                            AdbPermission.requestPermission()
                            Toast.makeText(activity, "请在弹出框中选择「允许」", Toast.LENGTH_LONG).show()
                        }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton("已授权，刷新状态") { refresh() }
                    }
                }
                else -> {
                    // ---------- 已授权：权限开关 ----------
                    SectionLabel("权限级别")
                    AgentCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Security, contentDescription = null,
                                tint = if (enabled) AColors.Success else AColors.Muted,
                                modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    if (enabled) "ADB 权限（已开启）" else "普通权限（默认）",
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink,
                                )
                                Text(
                                    if (enabled) "AI 可以执行 pm/am/settings 等系统命令"
                                    else "AI 只能在沙箱内执行命令",
                                    fontSize = 12.sp, color = AColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp), lineHeight = 17.sp,
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { v ->
                                    enabled = v
                                    AdbPermission.setPrefEnabled(activity, v)
                                    if (v) {
                                        AdbKeepAliveService.start(activity)
                                        Toast.makeText(activity, "ADB 权限已开启", Toast.LENGTH_SHORT).show()
                                    } else {
                                        AdbKeepAliveService.stop(activity)
                                        Toast.makeText(activity, "已切回普通权限", Toast.LENGTH_SHORT).show()
                                    }
                                    AppLog.i("AdbPermission", "权限切换 -> ${if (v) "ADB" else "普通"}")
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AColors.Success,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SectionLabel("后台保活（ADB 级）")
                    AgentCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("开机后自动保活", fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                                Text(
                                    "通过 ADB 定期把应用加入电池白名单并拉活，降低被系统清理的概率",
                                    fontSize = 12.sp, color = AColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp), lineHeight = 17.sp,
                                )
                            }
                            Switch(
                                checked = bootKeep,
                                enabled = enabled,
                                onCheckedChange = { v ->
                                    bootKeep = v
                                    AdbPermission.setBootKeepAlive(activity, v)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AColors.Accent,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SectionLabel("说明")
                    AgentCard {
                        RuleLine(Icons.Outlined.Security, AColors.Accent,
                            "权限切换即时生效",
                            "关掉开关后 AI 立刻回落到沙箱权限，无需重启")
                        Spacer(Modifier.height(12.dp))
                        RuleLine(Icons.Outlined.WarningAmber, AColors.Warning,
                            "ADB 权限风险",
                            "AI 将能执行系统级命令（如卸载应用、修改设置），请只在信任的场景开启")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Shizuku 授权信息由系统保管，本应用不会保存你的授权凭证。",
                fontSize = 11.5.sp, color = AColors.Faint,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    @Composable
    private fun RuleLine(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        tint: Color, title: String, sub: String,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
                Text(sub, fontSize = 11.5.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 3.dp), lineHeight = 16.sp)
            }
        }
    }
}