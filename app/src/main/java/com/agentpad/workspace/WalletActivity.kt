package com.agentpad.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.data.WalletStore
import com.agentpad.workspace.ui.*
import java.util.Locale

/**
 * 钱包与卡密（Compose 重构版）：
 * 余额展示（kami_wallet）+ 卡密兑换 + 购买入口。
 */
class WalletActivity : ComponentActivity() {

    private val balance = mutableStateOf<String?>(null)
    private val status = mutableStateOf("")
    private val loading = mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
        loadBalance()
    }

    @Composable
    private fun Content() {
        val token = remember { UserStore.token(this) }
        SubPage("钱包与卡密", "官方模型按用量计费，余额实时扣减", onBack = { finish() }) {
            // 余额渐变卡
            GradientCard(colors = listOf(AColors.Accent, AColors.Violet)) {
                Text("钱包余额（元）", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                Text(balance.value ?: "…", fontSize = 36.sp, color = Color.White,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text("新注册用户赠送 ¥0.25 体验金 · 官方模型计费从此余额扣除",
                    fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp), lineHeight = 15.sp)
            }

            // Token 卡
            Spacer(Modifier.height(14.dp))
            AgentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Outlined.Key, AColors.Teal)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("我的 API Token", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text(maskToken(token), fontSize = 12.sp, color = AColors.Accent,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (token.isNotBlank()) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = AColors.Accent,
                            modifier = Modifier.size(20.dp).clickable {
                                (this@WalletActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("API Token", token))
                                Toast.makeText(this@WalletActivity, "Token 已复制", Toast.LENGTH_SHORT).show()
                            })
                    }
                }
                Text(
                    if (token.isBlank()) "请先登录" else "点击右侧图标复制。它等同于登录凭证，请勿分享。",
                    fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 10.dp),
                )
            }

            // 购买
            Spacer(Modifier.height(18.dp))
            SectionLabel("购买卡密")
            AgentCard(padding = 14.dp, onClick = {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WalletStore.SHOP_URL))) }
                    .onFailure { Toast.makeText(this@WalletActivity, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Outlined.ShoppingCart, AColors.Pink)
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text("前往商店购买", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("购买「Agent API 激活码」后，将卡密粘贴到下方兑换",
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // 兑换
            Spacer(Modifier.height(18.dp))
            SectionLabel("兑换卡密")
            var card by remember { mutableStateOf("") }
            AgentTextField(card, { card = it }, "粘贴卡密，如 KM10-XXXXXXXXXX-XXXX")
            Spacer(Modifier.height(12.dp))
            PrimaryButton("立即兑换", loading = loading.value) {
                val c = card.trim()
                if (c.isEmpty()) { status.value = "请先粘贴卡密"; return@PrimaryButton }
                if (token.isBlank()) { status.value = "请先登录"; return@PrimaryButton }
                loading.value = true
                status.value = "兑换中…"
                Thread {
                    runCatching { WalletStore.redeem(token, c) }
                        .onSuccess { w ->
                            runOnUiThread {
                                loading.value = false
                                status.value = "✅ 兑换成功"
                                card = ""
                                balance.value = fmt(w.balance)
                                Toast.makeText(this@WalletActivity, "兑换成功，余额 ¥${balance.value}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .onFailure { e ->
                            runOnUiThread { loading.value = false; status.value = "❌ ${e.message}" }
                        }
                }.start()
            }
            if (status.value.isNotBlank()) {
                Text(status.value, fontSize = 12.sp,
                    color = if (status.value.startsWith("❌")) AColors.Danger else AColors.Success,
                    modifier = Modifier.padding(top = 10.dp))
            }

            // 说明
            Spacer(Modifier.height(20.dp))
            AgentCard(background = AColors.SurfaceAlt, border = null) {
                Text("说明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.InkSoft)
                listOf(
                    "卡密面值即到账金额，一卡一次，兑换后不可退",
                    "官方模型按 token 用量计费（分/百万级单价），余额不足时将无法调用",
                    "对话页选择「官方模型」配置即可使用",
                ).forEach {
                    Text("· $it", fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 7.dp), lineHeight = 17.sp)
                }
            }
        }
    }

    private fun maskToken(token: String): String = when {
        token.isBlank() -> "未登录"
        token.length <= 12 -> "••••••••"
        else -> token.take(6) + "••••••••" + token.takeLast(6)
    }

    private fun fmt(v: Double): String =
        String.format(Locale.CHINA, "%.6f", v).trimEnd('0').trimEnd('.').ifBlank { "0" }

    private fun loadBalance() {
        val token = UserStore.token(this)
        if (token.isBlank()) { balance.value = "--"; status.value = "请先登录"; return }
        status.value = "查询中…"
        Thread {
            runCatching { WalletStore.wallet(token) }
                .onSuccess { w -> runOnUiThread { balance.value = fmt(w.balance); status.value = "" } }
                .onFailure { e -> runOnUiThread { balance.value = "--"; status.value = "查询失败：${e.message}" } }
        }.start()
    }
}