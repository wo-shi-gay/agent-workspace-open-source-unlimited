package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ui.*

/**
 * 关于（Compose 重构版）：版本信息 + 开发名单（点击跳转 B 站）。
 */
class AboutActivity : ComponentActivity() {

    private data class Credit(
        val avatar: Int,
        val name: String,
        val role: String,
        val url: String,
    )

    private val credits = listOf(
        Credit(R.drawable.avatar_dev, "不知天高地厚的屑", "开发者 · 项目发起",
            "https://www.bilibili.com/video/BV1sAhD6wEH6"),
        Credit(R.drawable.avatar_peer, "DeepChTi", "同行借鉴 · 界面参考",
            "https://www.bilibili.com/video/BV1eitm6wE7F"),
    )

    /**
     * 金主名单：点击跳 QQ 资料页。
     *
     * ⚠ 这里显示的是 QQ 昵称，不是账号。昵称里带 U+200B（零宽空格）和
     * U+2060（word joiner）等不可见字符，直接写进源码很容易被编辑器/构建
     * 流程吃掉，所以用 \u 转义拼出来，保证渲染结果和客户端里看到的完全一致。
     */
    private val sponsors = listOf(
        Credit(
            R.drawable.avatar_sponsor,
            // ۩ᅠ۩ᅠ  —— U+06E9 (ARABIC PLACE OF SAJDAH) + U+1160 (HANGUL JUNGSEONG FILLER)
            // 这两个字符在多数字体里是不可见/极窄的，所以看起来像"空名称"。
            // 用 \u 转义写入，避免编辑器或构建流程吃掉它们。
            "\u06E9\u1160\u06E9\u1160",
            "金主 · 大力支持",
            "3946143126",   // 纯数字 = QQ 号，openUrl() 会转成 QQ 名片
        ),
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val version = remember {
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "4.6"
        }
        SubPage("关于", "版本信息与开发名单", onBack = { finish() }) {
            // App 标识区
            Column(
                Modifier.fillMaxWidth().padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(AColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PersonOutline, contentDescription = null,
                        tint = AColors.Accent, modifier = Modifier.size(36.dp))
                }
                Text("Workspace", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = AColors.Ink, modifier = Modifier.padding(top = 14.dp))
                Text("版本 $version", fontSize = 13.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 5.dp))
                Text("运行在 Android 上的 AI 工作空间", fontSize = 12.sp, color = AColors.Faint,
                    modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
            }

            SectionLabel("开发名单")
            credits.forEach { c ->
                CreditCard(c)
                Spacer(Modifier.height(10.dp))
            }

            if (sponsors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SectionLabel("金主名单")
                sponsors.forEach { c ->
                    CreditCard(c)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    /** 一张名单卡片：头像 + 名称 + 角色 + 跳转箭头。 */
    @Composable
    private fun CreditCard(c: Credit) {
        AgentCard(padding = 14.dp, onClick = { openUrl(c.url) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(c.avatar),
                    contentDescription = c.name,
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                )
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
                    Text(c.role, fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(
                        if (c.role.startsWith("金主")) "点击打开 QQ 名片" else "点击访问主页",
                        fontSize = 11.sp, color = AColors.Accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                    tint = AColors.Faint, modifier = Modifier.size(22.dp))
            }
        }
    }

    /**
     * 打开名单里的链接。
     *
     * 金主条目存的是 QQ 号（纯数字），这里拼成 QQ 名片跳转：
     *  1) 优先 mqqapi://card/show_pslcard?uin=<QQ> —— 装了 QQ 会直接拉起名片页
     *  2) 没装 QQ 时回退到 qm.qq.com 的网页名片
     * 其他条目（B 站等）按普通 URL 打开。
     */
    private fun openUrl(url: String) {
        runCatching {
            if (url.all { it.isDigit() }) {
                val qqScheme = Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$url&card_type=person&source=qrcode")
                val started = runCatching { startActivity(Intent(Intent.ACTION_VIEW, qqScheme)) }.isSuccess
                if (!started) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/$url")))
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
}