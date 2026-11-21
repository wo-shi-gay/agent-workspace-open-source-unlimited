package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.MarketStore
import com.agentpad.workspace.ui.*
import org.json.JSONObject

/**
 * 平台公告（Compose 重构版）：全站公开，未登录可看。
 */
class AnnouncementActivity : ComponentActivity() {

    private val loading = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)
    private val items = mutableStateOf<List<JSONObject>>(emptyList())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
        Thread {
            // 传真实 versionCode，服务端才会按 `target_version > version_code` 过滤
            val vc = runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
            }.getOrDefault(0)
            val (err, list) = MarketStore.announcements(vc, all = true)
            runOnUiThread { error.value = err; items.value = list; loading.value = false }
        }.start()
    }

    @Composable
    private fun Content() {
        SubPage("平台公告", "官方发布的最新动态", onBack = { finish() }, scrollable = false) {
            when {
                loading.value -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = AColors.Accent)
                }
                error.value != null -> EmptyState(Icons.Outlined.Campaign, "加载失败", error.value)
                items.value.isEmpty() -> EmptyState(Icons.Outlined.Campaign, "暂无公告", "有新的平台动态时会显示在这里")
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items.value) { a -> AnnouncementCard(a) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    @Composable
    private fun AnnouncementCard(a: JSONObject) {
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Outlined.Campaign, AColors.Accent, size = 32.dp)
                Text("平台公告", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = AColors.Accent, modifier = Modifier.padding(start = 10.dp))
            }
            Text(a.optString("body"), fontSize = 14.sp, color = AColors.Ink,
                modifier = Modifier.padding(top = 11.dp), lineHeight = 21.sp)
            Text(a.optString("created_at", "").take(16), fontSize = 11.sp, color = AColors.Faint,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}