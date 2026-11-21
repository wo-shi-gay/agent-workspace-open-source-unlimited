package com.agentpad.workspace.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ask_user（HITL 问答流）—— 照抄 RikkaHub 3.2.0：
 *   data/ai/tools/local/AskUserTool.kt          （工具 schema）
 *   ui/components/message/ChatMessageTools.kt:338-519 （AskUserToolStep 渲染）
 *
 * 与原版的实现差别（原版走 AgentLoop 的 Pending/Answered 状态机，我们没有那套循环）：
 *   我们的 request() 本来就跑在后台线程（ChatActivity: `Thread { request(...) }.start()`），
 *   所以这里直接用**阻塞等待**：
 *     1) 模型调 ask_user → ToolRegistry.execute() 不返回，先把问题交给 [hub] 并挂起；
 *     2) UI 监听 [AskUserHub.awaiting] 渲染问答卡片；
 *     3) 用户提交 → [AskUserHub.submit] 放行 → execute() 返回答案 JSON 作为 tool 结果；
 *     4) 模型拿到答案继续往下答。
 *   语义与 RikkaHub 一致（AI 停下来问、用户答完继续），但不需要给对话循环加暂停态。
 */
const val ASK_USER_TOOL_NAME = "ask_user"

/** 等待用户回答的上限。超时按「用户没答」返回，避免后台线程永久挂住。 */
private const val ASK_USER_TIMEOUT_MS = 30 * 60 * 1000L

/** 一个问题（对应 schema 里 questions[] 的一项）。 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    /** "text" | "single" | "multi"，默认 text（与源码同） */
    val selectionType: String = "text",
)

/** 一次 ask_user 调用的完整请求。 */
data class AskUserRequest(
    val callId: String,
    val questions: List<AskUserQuestion>,
)

/**
 * 等待中的问答请求 + 放行通道。
 *
 * 纯内存、只服务当前这一轮：进程被杀 / 用户取消时 [cancel] 会让等待方立刻返回失败。
 */
object AskUserHub {

    private val slot = AtomicReference<Slot?>(null)

    private class Slot(val request: AskUserRequest) {
        val latch = CountDownLatch(1)
        @Volatile var answer: String? = null
    }

    /** UI 侧监听用：非 null 表示正在等用户回答。用轮询读，避免引入 Flow 依赖。 */
    fun pending(): AskUserRequest? = slot.get()?.request

    /** 有请求在等（UI 用它决定是否显示卡片）。 */
    fun isWaiting(): Boolean = slot.get() != null

    /** 由 ToolRegistry 调用：登记请求并阻塞等待答案。返回 null 表示超时/被取消。 */
    internal fun await(request: AskUserRequest, timeoutMs: Long = ASK_USER_TIMEOUT_MS): String? {
        // 同一时刻只允许一个 ask_user 在等；新的顶掉旧的（旧的一律按取消处理）
        val s = Slot(request)
        val old = slot.getAndSet(s)
        old?.let { it.answer = null; it.latch.countDown() }

        return try {
            val ok = s.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok) s.answer else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            slot.compareAndSet(s, null)
        }
    }

    /** 用户点了提交：放行等待方。 */
    fun submit(callId: String, answerJson: String): Boolean {
        val s = slot.get() ?: return false
        if (s.request.callId != callId) return false
        s.answer = answerJson
        s.latch.countDown()
        return true
    }

    /** 用户点了「跳过」/ 停止生成：直接放行，答案为空。 */
    fun cancel(callId: String? = null) {
        val s = slot.get() ?: return
        if (callId != null && s.request.callId != callId) return
        s.answer = null
        s.latch.countDown()
    }
}

/**
 * 工具规格 —— JSON Schema 与 RikkaHub AskUserTool.kt 逐字对齐。
 *
 * ⚠ 这里手写 JSON 字符串（与项目里其它工具一致），字段顺序/文案照抄源码：
 *   id / question 必填；options 可选；selection_type = text | single | multi。
 *   空格等无关内容已压缩，语义不含改动。
 */
fun askUserSpec(): ToolSpec = ToolSpec(
    name = ASK_USER_TOOL_NAME,
    description = "Ask the user one or more questions when you need clarification, additional information, or confirmation. " +
        "Each question can optionally provide a list of suggested options for the user to choose from. " +
        "The user may provide a free-text answer for every question, including single and multi selection questions. " +
        "For multi selection questions, custom text can be combined with selected options. " +
        "The answers will be returned as a JSON object mapping question IDs to the user's responses. " +
        "The call BLOCKS until the user answers, then you get their answers and continue.",
    parametersSchema = """
    {
      "type": "object",
      "properties": {
        "questions": {
          "type": "array",
          "description": "List of questions to ask the user",
          "items": {
            "type": "object",
            "properties": {
              "id": { "type": "string", "description": "Unique identifier for this question" },
              "question": { "type": "string", "description": "The question text to display to the user" },
              "options": {
                "type": "array",
                "description": "Optional list of suggested options for the user to choose from",
                "items": { "type": "string" }
              },
              "selection_type": {
                "type": "string",
                "enum": ["text", "single", "multi"],
                "description": "Answer type: text (free text input, default), single (select one option or enter custom text), multi (select options and/or enter custom text)"
              }
            },
            "required": ["id", "question"]
          }
        }
      },
      "required": ["questions"]
    }
    """.trimIndent(),
)

/** 解析模型给的 arguments → 问题列表（字段名与源码一一对应）。 */
fun parseAskUserQuestions(args: JSONObject): List<AskUserQuestion> {
    val arr = args.optJSONArray("questions") ?: return emptyList()
    val out = ArrayList<AskUserQuestion>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id").trim()
        val q = o.optString("question").trim()
        if (id.isBlank() && q.isBlank()) continue
        val opts = ArrayList<String>()
        o.optJSONArray("options")?.let { a ->
            for (j in 0 until a.length()) {
                val s = a.optString(j).trim()
                if (s.isNotEmpty()) opts.add(s)
            }
        }
        out.add(
            AskUserQuestion(
                id = id.ifBlank { "q${i + 1}" },
                question = q,
                options = opts,
                selectionType = o.optString("selection_type").trim().lowercase()
                    .takeIf { it == "single" || it == "multi" || it == "text" } ?: "text",
            )
        )
    }
    return out
}

/**
 * 把用户的选择委拼成答案 JSON —— 与源码 AskUserToolStep 提交时完全一致：
 *   {"answers":{"q1":"选项","q2":"选中项A, 选中项B, 手输内容"}}
 * multi 的拼法：已选芯片按顺序 + 手输内容（若非空），用 ", " 连接。
 */
fun buildAskUserAnswerJson(
    questions: List<AskUserQuestion>,
    singleAnswers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): String {
    val answers = JSONObject()
    questions.forEach { q ->
        val value = if (q.selectionType == "multi") {
            val picked = multiAnswers[q.id].orEmpty().toList()
            val custom = singleAnswers[q.id]?.takeIf { it.isNotBlank() }
            (picked + listOfNotNull(custom)).joinToString(", ")
        } else {
            singleAnswers[q.id].orEmpty()
        }
        answers.put(q.id, value)
    }
    return JSONObject().put("answers", answers).toString()
}

/** 供 UI 判断是否全部答完（照抄源码 submit 按钮的 enabled 判定）。 */
fun isAskUserAnswerComplete(
    questions: List<AskUserQuestion>,
    singleAnswers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): Boolean = questions.all { q ->
    if (q.selectionType == "multi") {
        !multiAnswers[q.id].isNullOrEmpty() || !singleAnswers[q.id].isNullOrBlank()
    } else {
        !singleAnswers[q.id].isNullOrBlank()
    }
}

/** 解析已提交答案里的某一问（历史回放时用，与源码 answeredState 解析同逻辑）。 */
fun readAnsweredText(answersJson: String, questionId: String): String = runCatching {
    JSONObject(answersJson).optJSONObject("answers")?.optString(questionId).orEmpty()
}.getOrDefault("")

/** 反序列化存进 part 的问题列表（历史回放）。 */
fun questionsToJson(questions: List<AskUserQuestion>): String {
    val arr = JSONArray()
    questions.forEach { q ->
        arr.put(
            JSONObject()
                .put("id", q.id)
                .put("question", q.question)
                .put("options", JSONArray().apply { q.options.forEach { put(it) } })
                .put("selection_type", q.selectionType)
        )
    }
    return arr.toString()
}

fun questionsFromJson(raw: String): List<AskUserQuestion> = runCatching {
    parseAskUserQuestions(JSONObject().put("questions", JSONArray(raw.ifBlank { "[]" })))
}.getOrDefault(emptyList())
