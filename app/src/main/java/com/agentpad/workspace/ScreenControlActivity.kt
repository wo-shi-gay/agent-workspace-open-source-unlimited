package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.screencontrol.*
import com.agentpad.workspace.ui.*
import java.io.File
import kotlinx.coroutines.delay

class ScreenControlActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableControlFromPage()
    }

    private fun enableControlFromPage() {
        if (!ScreenControlManager.isSupported) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!ScreenControlNotificationService.notificationsAvailable(this)) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            android.widget.Toast.makeText(this, "开启屏幕控制通知后再启用，停止按钮放在通知栏", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        ScreenControlSettings.setEnabled(this, true)
        if (!ScreenControlSettings.serviceEnabled(this)) ScreenControlSettings.openSettings(this)
    }

    private val capturePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            runCatching { ScreenCaptureService.start(this, result.resultCode, data) }.onFailure {
                ScreenCaptureService.status.value = "屏幕捕获启动失败：${it.message}"
            }
        } else {
            ScreenCaptureService.status.value = "屏幕内容授权已取消"
        }
    }

    private fun requestScreenContent() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        capturePermission.launch(intent)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            AgentTheme {
                var revision by remember { mutableIntStateOf(0) }
                val controller by ScreenControlManager.controller.collectAsState()
                val ready by ScreenControlManager.stopNotificationReady.collectAsState()
                val captureReady by ScreenCaptureService.ready.collectAsState()
                val captureStatus by ScreenCaptureService.status.collectAsState()
                LaunchedEffect(Unit) { while (true) { delay(700); revision++ } }
                val enabled = remember(revision) { ScreenControlSettings.enabled(this) }
                val granted = remember(revision) { ScreenControlSettings.serviceEnabled(this) }
                AgentPage {
                    TopBar("屏幕控制", "无障碍 · 读取界面与操作手机", onBack = { finish() })
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        AgentCard {
                            Text(if (!ScreenControlManager.isSupported) "需要 Android 8 或更高版本"
                                else if (!granted) "无障碍服务待开启"
                                else if (controller == null) "已授权，等待系统连接"
                                else if (enabled && ready) "已就绪 · 屏幕控制开启" else "已连接 · 屏幕控制已停止",
                                color = AColors.Ink, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("由 AI 按你的任务读取当前界面、点击、长按、滑动、输入文字和打开应用。开启后通知栏显示运行状态，展开通知点击“停止控制”即可结束操作；屏幕上保留短暂光效反馈。",
                                color = AColors.Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(if (android.os.Build.VERSION.SDK_INT >= 30)
                                "当前系统：基础屏幕操作、无障碍直接截图、输入法回车均可用。"
                                else "当前系统：基础屏幕操作可用；截图请在下方申请屏幕内容权限。输入后点击界面上的搜索或发送按钮提交。",
                                color = AColors.Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton(if (granted) "打开无障碍服务设置" else "申请无障碍权限") {
                                if (ScreenControlManager.isSupported) ScreenControlSettings.openSettings(this@ScreenControlActivity)
                            }
                            Spacer(Modifier.height(8.dp))
                            if (enabled) {
                                PrimaryButton("停止屏幕控制") { ScreenControlSettings.setEnabled(this@ScreenControlActivity, false); revision++ }
                            } else {
                                PrimaryButton("开启屏幕控制") {
                                    enableControlFromPage()
                                    revision++
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AgentCard {
                            Text("屏幕内容权限（截图）", color = AColors.Ink, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(captureStatus, color = AColors.Muted, fontSize = 13.sp)
                            Text("使用系统录屏授权获取屏幕内容，专门供截图读取；每次由你在系统弹窗确认。此功能单独授权，和无障碍、ADB 分开；保持通知显示，系统或进程结束后重新申请。",
                                color = AColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                            Spacer(Modifier.height(12.dp))
                            if (captureReady) {
                                PrimaryButton("停止屏幕内容捕获") { ScreenCaptureService.stop(this@ScreenControlActivity) }
                            } else {
                                PrimaryButton("申请屏幕内容权限") { requestScreenContent() }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AgentCard {
                            Text("使用方式", color = AColors.Ink, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("1. 在系统设置中开启「Workspace 屏幕控制」。\n2. 回来点击「开启屏幕控制」，在通知栏看到“停止控制”按钮。\n3. 回对话告诉 AI 要在手机里做什么。\n\n工具名称：screen_control。与只做后台保活的无障碍服务分别管理。系统若显示受限设置，请在应用信息页允许受限设置后再进入授权。",
                                color = AColors.Muted, fontSize = 13.sp)
                            TextButton(onClick = {
                                File(filesDir, "screen-captures").listFiles()?.forEach { it.delete() }
                                android.widget.Toast.makeText(this@ScreenControlActivity, "截图缓存已清理", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("清理屏幕截图缓存") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}