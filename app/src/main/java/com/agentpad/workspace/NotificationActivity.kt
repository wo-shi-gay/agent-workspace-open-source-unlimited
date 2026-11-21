package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.MarketStore
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*
import org.json.JSONObject

/**
 * 通知中心（Compose 重构版）：只显示当前账号的个人通知，进入自动已读。
 */
class NotificationActivity : ComponentActivity() {

    private val loading = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)
    private val items = mutableStateOf<List<JSONObject>>(emptyList())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
        if (!UserStore.isLoggedIn(this)) { loading.value = false; return }
        Thread {
            val (err, list) = MarketStore.notifications(UserStore.token(this), markRead = true)
            runOnUiThread { error.value = err; items.value = list; loading.value = false }
        }.start()
    }

    @Composable
    private fun Content() {
        val loggedIn = remember { UserStore.isLoggedIn(this) }
        SubPage("通知中心", "审核结果、下架通知等个人消息", onBack = { finish() }, scrollable = false) {
            when {
                !loggedIn -> EmptyState(Icons.Outlined.NotificationsNone, "请先登录", "登录后可查看个人通知")
                loading.value -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = AColors.Accent)
                }
                error.value != null -> EmptyState(Icons.Outlined.NotificationsNone, "加载失败", error.value)
                items.value.isEmpty() -> EmptyState(Icons.Outlined.NotificationsNone, "暂无通知", "有新的审核结果或消息时会出现在这里")
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items.value) { n -> NotificationCard(n) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    @Composable
    private fun NotificationCard(n: JSONObject) {
        AgentCard {
            Text(n.optString("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
            Text(n.optString("body"), fontSize = 13.sp, color = AColors.InkSoft,
                modifier = Modifier.padding(top = 7.dp), lineHeight = 19.sp)
            Row(Modifier.padding(top = 9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(n.optString("created_at", "").take(16), fontSize = 11.sp, color = AColors.Faint)
                if (n.optInt("is_read") != 1) {
                    Spacer(Modifier.width(8.dp))
                    Tag("新", AColors.Danger, AColors.DangerSoft)
                }
            }
        }
    }
}