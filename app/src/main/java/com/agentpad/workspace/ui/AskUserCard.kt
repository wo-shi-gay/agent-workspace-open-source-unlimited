package com.agentpad.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.AskUserHub
import com.agentpad.workspace.ai.AskUserQuestion
import com.agentpad.workspace.ai.buildAskUserAnswerJson
import com.agentpad.workspace.ai.isAskUserAnswerComplete
import com.agentpad.workspace.ai.readAnsweredText
import com.agentpad.workspace.ai.questionsFromJson
import com.agentpad.workspace.data.ChatStore
import androidx.compose.runtime.LaunchedEffect

/**
 * ask_user 问答卡片 —— 照抄 RikkaHub 3.2.0 `ui/components/message/ChatMessageTools.kt`
 * 的 `AskUserToolStep`（第 338-519 行），逐项对齐：
 *
 *   · 一个问题一块；问题文字用 bodySmall
 *   · options 非空 → FlowRow + FilterChip（single 单选 / multi 多选可叠加）
 *   · **永远附带一个 OutlinedTextField**（minLines 1 / maxLines 3）→ 允许自定义输入
 *   · 底部 FilledTonalButton 提交，全部答完才 enabled
 *   · 已提交后不再显示输入控件，只把答案用 primary 色显示出来
 *
 * 我们的差别（因为没有 AgentLoop 的 Pending/Answered 状态机）：
 *   pending 从 [AskUserHub] 读（当前是否在等用户答题）；
 *   answered 从 part.toolResult 里读（工具返回值里就是 {"answers":{...}}）。
 */
data class AskUserCardState(
    val questions: List<AskUserQuestion>,
    /** true = 正在等用户答（可交互）；false = 已答完（只读展示） */
    val pending: Boolean,
    /** 已提交的答案 JSON（pending=false 时用它回显） */
    val answeredJson: String,
    /** 提交回调：把答案 JSON 交给 AskUserHub */
    val onSubmit: (String) -> Unit,
)

/** 从 part 的 arguments / toolResult 构造卡片状态（历史回放与实时都用它）。 */
@Composable
fun askUserCardStateOf(
    argumentsJson: String,
    toolResult: String,
    pending: Boolean,
    callId: String,
    onSubmit: (String) -> Unit,
): AskUserCardState {
    val questions = remember(argumentsJson) { questionsFromJson(questionsJsonOf(argumentsJson)) }
    return AskUserCardState(
        questions = questions,
        pending = pending,
        answeredJson = toolResult,
        onSubmit = onSubmit,
    )
}

/** 从工具 arguments JSON 里取出 questions 数组文本（schema: {"questions":[...]}）。 */
internal fun questionsJsonOf(argumentsJson: String): String = runCatching {
    org.json.JSONObject(argumentsJson).optJSONArray("questions")?.toString() ?: "[]"
}.getOrDefault("[]")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskUserCard(
    state: AskUserCardState,
    modifier: Modifier = Modifier,
) {
    // 与源码一致：text/single 用 answers，multi 用 multiAnswers
    val answers = remember(state.questions) { mutableStateMapOf<String, String>() }
    val multiAnswers = remember(state.questions) { mutableStateMapOf<String, Set<String>>() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (state.pending) {
                    // ① 选项芯片（照抄源码的 FlowRow + FilterChip）
                    if (q.options.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            q.options.forEach { option ->
                                val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                FilterChip(
                                    selected = if (q.selectionType == "multi") {
                                        option in selectedOptions
                                    } else {
                                        answers[q.id] == option
                                    },
                                    onClick = {
                                        if (q.selectionType == "multi") {
                                            multiAnswers[q.id] = if (option in selectedOptions) {
                                                selectedOptions - option
                                            } else {
                                                selectedOptions + option
                                            }
                                        } else {
                                            answers[q.id] = option
                                        }
                                    },
                                    label = {
                                        Text(text = option, style = MaterialTheme.typography.labelSmall)
                                    },
                                )
                            }
                        }
                    }

                    // ② 自定义输入框（永远在，single/multi 也能补写）
                    OutlinedTextField(
                        value = answers[q.id] ?: "",
                        onValueChange = { answers[q.id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        placeholder = {
                            Text("也可以直接输入自定义内容", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                } else {
                    // ③ 已答：只回显答案（primary 色，与源码同）
                    Text(
                        text = readAnsweredText(state.answeredJson, q.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ④ 提交（全部答完才可点；multi 允许只选芯片或只写文字）
        if (state.pending) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = {
                        state.onSubmit(
                            buildAskUserAnswerJson(
                                questions = state.questions,
                                singleAnswers = answers,
                                multiAnswers = multiAnswers,
                            )
                        )
                    },
                    enabled = isAskUserAnswerComplete(state.questions, answers, multiAnswers),
                ) {
                    Text("提交", modifier = Modifier.padding(start = 4.dp), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * 问答卡片外壳（思考链里的一步）：标题「AI 在问你」+ 可选「跳过」。
 * 跳过 = AskUserHub.cancel()，模型会收到「用户没有回答」，可自行决定继续或改问法。
 */
@Composable
fun AskUserCardShell(
    questionCount: Int,
    firstQuestion: String,
    pending: Boolean,
    onSkip: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (questionCount <= 1) firstQuestion
                else "AI 有 $questionCount 个问题要确认",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            if (pending && onSkip != null) {
                TextButton(onClick = onSkip) { Text("跳过", fontSize = 12.sp) }
            }
        }
        content()
    }
}

/** 顶部「跳过确认」对话框（避免误触把手输内容丢掉）。 */
@Composable
fun AskUserSkipDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳过这次提问？") },
        text = { Text("AI 会收到「用户没有回答」，然后按它自己的判断继续。", fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("跳过") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续作答") } },
    )
}

/** 供 UI 轮询：当前是否有问题在等（照抄源码 ask_user 的 Pending 判定语义）。 */
fun isAskUserPending(): Boolean = AskUserHub.isWaiting()

/** 提交入口（UI 调它，等价于源码的 onToolAnswer → handleApproval(Answered)）。 */
fun submitAskUser(answerJson: String): Boolean = AskUserHub.submit("ask_user", answerJson)

/** 跳过入口（等价于取消这次提问）。 */
fun skipAskUser() = AskUserHub.cancel()

/**
 * 思考链里的 ask_user 步骤 —— 对应源码 ChatMessageTools.kt:338 的 `AskUserToolStep`。
 *
 * pending 的判定：这次调用**还没拿到 tool 结果**（part.toolResult 为空）
 * 且 Hub 里正等着（说明确实轮到它）。这样历史回放时自动是「已答」只读态，
 * 不会出现「翻旧消息还能再答一次」。
 */
@Composable
fun AskUserStep(part: ChatStore.Part) {
    val questions = remember(part.toolArguments) {
        questionsFromJson(questionsJsonOf(part.toolArguments))
    }
    // Hub 状态用轻量轮询感知（不引入 Flow 依赖）；1s 一次，代价可忽略。
    var hubWaiting by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(part.toolCallId) {
        while (true) {
            hubWaiting = isAskUserPending()
            kotlinx.coroutines.delay(600)
        }
    }
    val pending = part.toolResult.isBlank() && hubWaiting

    var showSkip by remember { mutableStateOf(false) }

    AskUserCardShell(
        questionCount = questions.size,
        firstQuestion = questions.firstOrNull()?.question ?: "AI 想确认几个问题",
        pending = pending,
        onSkip = { showSkip = true },
    ) {
        AskUserCard(
            state = AskUserCardState(
                questions = questions,
                pending = pending,
                answeredJson = part.toolResult,
                onSubmit = { json -> submitAskUser(json) },
            )
        )
    }

    if (showSkip) {
        AskUserSkipDialog(
            onDismiss = { showSkip = false },
            onConfirm = { skipAskUser(); showSkip = false },
        )
    }
}