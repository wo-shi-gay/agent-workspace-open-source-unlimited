package com.agentpad.workspace

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.HeartbeatManager
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*

/**
 * 个人中心（Compose 重构版）：头像 + 用户名 + 功能入口 + 退出登录。
 */
class ProfileActivity : ComponentActivity() {

    private val confirmLogout = mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            AgentTheme {
                Content()
                if (confirmLogout.value) LogoutDialog()
            }
        }
    }

    @Composable
    private fun Content() {
        val username = remember { UserStore.username(this).ifBlank { "已登录" } }
        SubPage("个人中心", "账号信息与快捷入口", onBack = { finish() }) {
            // 头像区
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(AColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PersonOutline, contentDescription = null,
                        tint = AColors.Accent, modifier = Modifier.size(40.dp))
                }
                Text(username, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AColors.Ink,
                    modifier = Modifier.padding(top = 14.dp))
                Text("Workspace 账号", fontSize = 12.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 4.dp))
            }

            SectionLabel("我的")
            ActionRow(Icons.Outlined.Wallet, AColors.Success, "钱包与卡密",
                "余额查询、卡密兑换与购买") {
                startActivity(Intent(this@ProfileActivity, WalletActivity::class.java))
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(Icons.Outlined.Storefront, AColors.Accent, "应用市场",
                "浏览下载 AI 构建的应用，或发布你的作品") {
                startActivity(Intent(this@ProfileActivity, MarketActivity::class.java))
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(Icons.Outlined.NotificationsNone, AColors.Amber, "通知中心",
                "审核结果、下架通知等个人消息") {
                startActivity(Intent(this@ProfileActivity, NotificationActivity::class.java))
            }

            Spacer(Modifier.height(22.dp))
            SecondaryButton("退出登录") { confirmLogout.value = true }
        }
    }

    @Composable
    private fun LogoutDialog() {
        AlertDialog(
            onDismissRequest = { confirmLogout.value = false },
            title = { Text("退出登录", fontWeight = FontWeight.Bold) },
            text = { Text("确定退出当前账号？", fontSize = 14.sp, color = AColors.InkSoft) },
            confirmButton = {
                TextButton({
                    UserStore.logout(this)
                    HeartbeatManager.stop()
                    android.widget.Toast.makeText(this, "已退出登录", android.widget.Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }) { Text("退出", color = AColors.Danger) }
            },
            dismissButton = { TextButton({ confirmLogout.value = false }) { Text("取消") } },
        )
    }
}