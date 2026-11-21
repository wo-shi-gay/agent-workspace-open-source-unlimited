package com.agentpad.workspace

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ImageGenStore
import com.agentpad.workspace.ui.*

/**
 * 生图配置页（设置 → 生图配置）。
 *
 * 字段与 [ImageGenStore.Config] 一一对应；
 * 模型列表通过 /models 主动拉取（与 API 配置页同一套做法）。
 */
class ImageGenConfigActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        var cfg by remember { mutableStateOf(ImageGenStore.config(this)) }
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        SubPage(
            title = "生图配置",
            subtitle = "OpenAI 兼容接口 · 拉取模型 · 暴露给 AI 当工具",
            onBack = { finish() },
        ) {
            // SubPage(scrollable=true 默认) 已经包了 Column(verticalScroll)，
            // 这里再套一层会变成双重滚动：滚动手感错乱，也容易触发无限约束。
            Column(Modifier.fillMaxWidth()) {

                SectionLabel("总开关")
                AgentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("启用生图", fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text("关掉后完全不注册任何生图工具", fontSize = 11.5.sp, color = AColors.Muted)
                        }
                        Switch(checked = cfg.enabled, onCheckedChange = {
                            cfg = cfg.copy(enabled = it)
                        })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("暴露给 AI 当工具", fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text("开启后任意对话的 AI 都能调 generate_image / read_image",
                                fontSize = 11.5.sp, color = AColors.Muted)
                        }
                        Switch(checked = cfg.exposeTool, onCheckedChange = {
                            cfg = cfg.copy(exposeTool = it)
                        })
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("接口")
                AgentCard {
                    AgentTextField(
                        value = cfg.baseUrl,
                        onValueChange = { cfg = cfg.copy(baseUrl = it) },
                        placeholder = "Base URL，如 https://api.openai.com/v1",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    AgentTextField(
                        value = cfg.apiKey,
                        onValueChange = { cfg = cfg.copy(apiKey = it) },
                        placeholder = "API Key（sk-...）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("模型")
                AgentCard {
                    AgentTextField(
                        value = cfg.model,
                        onValueChange = { cfg = cfg.copy(model = it) },
                        placeholder = "生图模型，如 dall-e-3 / gpt-image-1 / flux-schnell",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton(if (busy) "拉取中..." else "拉取模型列表") {
                            if (busy) return@SecondaryButton
                            val url = cfg.baseUrl.trim()
                            val key = cfg.apiKey.trim()
                            if (url.isBlank() || key.isBlank()) {
                                Toast.makeText(this@ImageGenConfigActivity,
                                    "先填 Base URL 和 Key", Toast.LENGTH_SHORT).show()
                                return@SecondaryButton
                            }
                            busy = true; status = ""
                            Thread {
                                val r = ImageGenStore.fetchModels(url, key)
                                runOnUiThread {
                                    busy = false
                                    r.fold(
                                        onSuccess = { list ->
                                            models = list
                                            status = if (list.isEmpty()) "没有返回模型" else "共 ${'$'}{list.size} 个模型"
                                        },
                                        onFailure = { e -> status = "拉取失败：${'$'}{e.message}" },
                                    )
                                }
                            }.start()
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(status, fontSize = 11.5.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    if (models.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        models.forEach { m ->
                            AgentCard(padding = 10.dp, onClick = { cfg = cfg.copy(model = m) }) {
                                Text(
                                    (if (m == cfg.model) "✓ " else "") + m,
                                    fontSize = 13.sp,
                                    color = if (m == cfg.model) AColors.Accent else AColors.Ink,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("默认尺寸")
                AgentCard {
                    listOf("1024x1024", "1024x1792", "1792x1024").forEach { s ->
                        AgentCard(padding = 10.dp, onClick = { cfg = cfg.copy(size = s) }) {
                            Text(
                                (if (s == cfg.size) "✓ " else "") + s,
                                fontSize = 13.sp,
                                color = if (s == cfg.size) AColors.Accent else AColors.Ink,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(18.dp))
                PrimaryButton("保存配置", Modifier.fillMaxWidth()) {
                    ImageGenStore.save(this@ImageGenConfigActivity, cfg)
                    Toast.makeText(this@ImageGenConfigActivity,
                        "已保存，下一次请求生效", Toast.LENGTH_SHORT).show()
                    finish()
                }

                Spacer(Modifier.height(10.dp))
                SecondaryButton("打开相册", Modifier.fillMaxWidth()) {
                    startActivity(Intent(this@ImageGenConfigActivity, ImageGalleryActivity::class.java))
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel("说明")
                AgentCard {
                    Column {
                        listOf(
                            "走 OpenAI 兼容的 /images/generations。",
                            "所有生成的图都会落盘，即使 AI 没回复也不会丢。",
                            "read_image 会把图缩到长边 1024 再转 base64，避免撑爆请求。",
                            "base64 只用于当次请求，不会被写进聊天历史。",
                        ).forEach {
                            Text("· " + it, fontSize = 12.sp, color = AColors.Muted,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
