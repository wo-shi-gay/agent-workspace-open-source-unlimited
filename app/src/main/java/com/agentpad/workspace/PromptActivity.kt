package com.agentpad.workspace

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.PromptStore
import com.agentpad.workspace.ui.*

/**
 * 自定义提示词页。
 *
 * 只叠加、不替换：内置工具提示词原样保留，这里的内容会作为
 * <user_custom_instructions> 区块追加到系统提示词末尾。
 *
 * 支持两级：
 *  - 全局提示词：所有会话生效
 *  - 本会话提示词：仅当通过 session_id 进入时显示
 */
class PromptActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val sessionId = intent.getStringExtra("session_id").orEmpty()
        setContent {
            AgentTheme {
                Content(sessionId = sessionId.ifBlank { null })
            }
        }
    }

    @Composable
    private fun Content(sessionId: String?) {
        var global by remember { mutableStateOf(PromptStore.globalPrompt(this)) }
        var session by remember { mutableStateOf(sessionId?.let { PromptStore.sessionPrompt(this, it) }.orEmpty()) }

        SubPage(
            title = "自定义提示词",
            subtitle = "在原有工具提示词之上再叠加一层，不替换内置规则",
            onBack = { finish() },
        ) {
            SectionLabel("全局提示词")
            Text(
                "对所有会话生效。可以写你的偏好、说话风格、通用约束。",
                fontSize = 12.sp, color = AColors.Muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AgentCard {
                AgentTextField(
                    value = global,
                    onValueChange = { global = it },
                    placeholder = "例如：回答尽量简短；优先用中文；不要主动安装系统级软件……",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Text(
                    "已输入 ${global.length} 字",
                    fontSize = 11.sp, color = AColors.Faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (sessionId != null) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("本会话提示词")
                Text(
                    "只对当前会话生效，优先级高于全局提示词。",
                    fontSize = 12.sp, color = AColors.Muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AgentCard {
                    AgentTextField(
                        value = session,
                        onValueChange = { session = it },
                        placeholder = "例如：这个项目用 Kotlin；所有命令都在工作区根目录下执行……",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    )
                    Text(
                        "已输入 ${session.length} 字",
                        fontSize = 11.sp, color = AColors.Faint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                SecondaryButton("清空本会话提示词") {
                    PromptStore.clearSessionPrompt(this@PromptActivity, sessionId)
                    session = ""
                    Toast.makeText(this@PromptActivity, "已清空本会话提示词", Toast.LENGTH_SHORT).show()
                }
            }

            Spacer(Modifier.height(18.dp))
            PrimaryButton("保存提示词", Modifier.fillMaxWidth()) {
                PromptStore.saveGlobalPrompt(this@PromptActivity, global)
                if (sessionId != null) PromptStore.saveSessionPrompt(this@PromptActivity, sessionId, session)
                Toast.makeText(this@PromptActivity, "已保存，下一次请求生效", Toast.LENGTH_SHORT).show()
                finish()
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("说明")
            AgentCard {
                Column {
                    listOf(
                        "叠加位置：系统提示词末尾的 <user_custom_instructions> 区块。",
                        "不会覆盖工具调用格式、文件交付、计划等内置规则。",
                        "全局与本地同时存在时，会话提示词更具体，放在后面。",
                        "留空即不生效，不额外消耗上下文。",
                        "每次修改在下一次发送消息时生效。",
                    ).forEach {
                        Text("· $it", fontSize = 12.sp, color = AColors.Muted,
                            lineHeight = 18.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
}