package com.agentpad.workspace.ai

import android.content.Context
import com.agentpad.workspace.data.ChatStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 多子智能体代理协调（参考 Operit `examples/subagent` 的 passive subagent）。
 *
 * 语义：
 *  1. 子代理是被动的：只接受主 agent 的一次明确委托，不反问、不打断主 agent；
 *  2. 子代理用同一套工具，但禁止再派生子代理（防无限递归放大账单）；
 *  3. 子代理的中间过程不进主上下文，只把结论压成摘要回传；
 *  4. 并行子代理共享「同伴任务清单」，靠提示词自我去重。
 */
class SubAgentRunner(
    private val context: Context,
    private val registry: ToolRegistry,
) {

    companion object {
        const val MAX_CONCURRENCY = 4
        private const val MAX_STEPS_LIMIT = 12
        private const val MAX_SUB_RESULT = 6000
        private const val MAX_SUMMARY = 4000
        private val SELF_TOOLS = setOf("subagent_run", "subagent_parallel")
    }

    /** 单个子代理：执行一次委托任务，返回 JSON 字符串。 */
    fun runTask(task: String, contextText: String, maxSteps: Int): String {
        if (task.isBlank()) {
            return JSONObject().put("success", false).put("error", "task 不能为空").toString()
        }
        val steps = maxSteps.coerceIn(1, MAX_STEPS_LIMIT)
        return runCatching { runOne(task, contextText, steps).toString() }
            .getOrElse { e ->
                JSONObject().put("success", false)
                    .put("error", "子代理执行异常：${e.message}")
                    .toString()
            }
    }

    /** 多子代理并行协调。tasks 支持 [{task,context}] 或 ["任务A","任务B"]。 */
    fun runParallel(tasks: JSONArray, contextText: String, maxSteps: Int): String {
        if (tasks.length() == 0) {
            return JSONObject().put("success", false).put("error", "tasks 不能为空").toString()
        }
        val steps = maxSteps.coerceIn(1, MAX_STEPS_LIMIT)
        val items = ArrayList<Pair<String, String>>()
        for (i in 0 until tasks.length()) {
            val o = tasks.optJSONObject(i)
            val t = if (o != null) o.optString("task") else tasks.optString(i)
            if (t.isBlank()) continue
            items.add(t.trim() to (o?.optString("context").orEmpty()))
        }
        if (items.isEmpty()) {
            return JSONObject().put("success", false).put("error", "tasks 里没有有效任务").toString()
        }

        val labels = items.mapIndexed { i, _ -> "子代理${i + 1}" }
        val pool = Executors.newFixedThreadPool(items.size.coerceAtMost(MAX_CONCURRENCY))
        val futures: List<java.util.concurrent.Future<Pair<String, String>>> = items.mapIndexed { i, item ->
            val task = item.first
            val ownContext = item.second
            pool.submit(Callable<Pair<String, String>> {
                val peers = items.filterIndexed { j, _ -> j != i }
                    .joinToString("；") { it.first.take(120) }
                val merged = buildString {
                    if (ownContext.isNotBlank()) append(ownContext)
                    if (peers.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append("【同一批并行下发的其他子任务，别重复做，也别抢同一个文件】：")
                        append(peers)
                    }
                    if (contextText.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append("【共享上下文】：").append(contextText)
                    }
                }
                val payload = runCatching { runOne(task, merged, steps).toString() }
                    .getOrElse { e ->
                        JSONObject().put("success", false)
                            .put("error", "子代理异常：${e.message}").toString()
                    }
                labels[i] to payload
            })
        }

        val results = JSONArray()
        var succeeded = 0
        futures.forEachIndexed { i, f ->
            val pair = runCatching { f.get(420, TimeUnit.SECONDS) }.getOrElse { e ->
                labels[i] to JSONObject().put("success", false)
                    .put("error", "超时或被取消：${e.message}").toString()
            }
            val ok = runCatching { JSONObject(pair.second).optBoolean("success") }.getOrDefault(false)
            if (ok) succeeded++
            results.put(
                JSONObject()
                    .put("agent", pair.first)
                    .put("task", items[i].first)
                    .put("result", pair.second),
            )
        }
        pool.shutdown()

        return JSONObject()
            .put("success", succeeded > 0)
            .put("agents", items.size)
            .put("succeeded", succeeded)
            .put("results", results)
            .toString()
    }

    // ---------------- 单个子代理环路 ----------------

    private fun runOne(task: String, contextText: String, maxSteps: Int): JSONObject {
        val api = ChatStore.api(context)
        val base = api.first.trim().trimEnd('/')
        if (base.isBlank() || api.second.isBlank()) {
            throw IllegalStateException("当前 API 配置缺少地址或密钥，无法派发子代理")
        }
        val url = "$base/chat/completions"
        val model = api.third
        val subSpecs = registry.specs().filter { it.name !in SELF_TOOLS }
        val tools = ToolProtocol.buildToolsJson(subSpecs)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt(subSpecs)))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    buildString {
                        append("<subagent_delegation>\n")
                        append("任务：").append(task.trim()).append('\n')
                        if (contextText.isNotBlank()) {
                            append("补充上下文：").append(contextText.trim()).append('\n')
                        }
                        append("步数预算：").append(maxSteps).append(" 步\n")
                        append("</subagent_delegation>")
                    },
                ),
            )

        val trail = ArrayList<String>()
        var answer = ""
        var toolCallsTotal = 0
        var lastError = ""

        for (step in 1..maxSteps) {
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", messages)
            if (tools.length() > 0) {
                body.put("tools", tools).put("tool_choice", "auto")
            }

            val resp = postJson(url, api.second, body)
            val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val raw = msg?.optString("content", "").orEmpty()
            val clean = ToolProtocol.cleanAssistantText(raw).trim()
            if (clean.isNotBlank()) answer = clean

            val calls = ToolProtocol.extractToolCalls(resp)
            if (calls.isEmpty()) {
                trail.add("第 $step 步：模型收尾（无工具调用）")
                break
            }
            trail.add("第 $step 步：" + calls.joinToString("、") { it.name })
            toolCallsTotal += calls.size

            // assistant 的 tool_calls 必须原样回填，否则 tool 结果对不上 id
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", raw)
                    .put("tool_calls", msg?.optJSONArray("tool_calls") ?: JSONArray()),
            )

            for (call in calls) {
                val content = if (call.name in SELF_TOOLS) {
                    JSONObject().put("success", false)
                        .put("error", "子代理内部禁止再次派生子代理").toString()
                } else {
                    runCatching { registry.execute(call).content }.getOrElse { e ->
                        lastError = e.message ?: "工具执行失败"
                        JSONObject().put("success", false)
                            .put("error", "工具 ${call.name} 执行异常：$lastError").toString()
                    }
                }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", content.take(MAX_SUB_RESULT)),
                )
            }
            if (step == maxSteps) trail.add("已达步数上限 $maxSteps，强制收尾")
        }

        val summary = extractSummary(answer).ifBlank { lastError }.ifBlank { "（子代理未产出文字结论）" }
        return JSONObject()
            .put("success", answer.isNotBlank())
            .put("summary", summary.take(MAX_SUMMARY))
            .put("steps_used", trail.size)
            .put("tool_calls", toolCallsTotal)
            .put("trail", JSONArray(trail))
            .apply { if (lastError.isNotBlank()) put("last_error", lastError) }
    }

    // ---------------- 提示词 ----------------

    private fun systemPrompt(specs: List<ToolSpec>): String = buildString {
        appendLine(ToolProtocol.systemPrompt(specs))
        appendLine()
        appendLine("<subagent_role>")
        appendLine("你是主 agent 派出的**被动子代理**，只负责完成下面这一件委托任务。")
        appendLine("- 不要反问、不要等确认、不要复述委托内容；缺信息就按最合理方式自行假设，并在结论里注明。")
        appendLine("- 不要派生其他子代理（subagent 工具对你不可用），也不要请主 agent 帮忙。")
        appendLine("- 只在必要时调用工具；能一次查清的别分十次，每一步最多 3 个工具调用。")
        appendLine("- 有多个子代理并行时，不要重复它们的工作，也不要并发修改同一个文件。")
        appendLine("- 完成后必须用下面格式给出结论，这是主 agent 唯一会看到的内容：")
        appendLine("<subagent_summary>")
        appendLine("结论：一句话说清你完成了什么")
        appendLine("产出：文件路径 / 命令 / 数据（没有就写「无」）")
        appendLine("注意事项：失败点、未验证项（没有就写「无」）")
        appendLine("</subagent_summary>")
        appendLine("</subagent_role>")
    }

    /** 优先摘 <subagent_summary>，退化成整段正文。 */
    private fun extractSummary(text: String): String {
        if (text.isBlank()) return ""
        val re = Regex("<subagent_summary>([\\s\\S]*?)</subagent_summary>", RegexOption.IGNORE_CASE)
        re.find(text)?.groupValues?.getOrNull(1)?.trim()?.let { if (it.isNotBlank()) return it }
        return text.trim()
    }

    // ---------------- HTTP ----------------

    private fun postJson(url: String, key: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code：${text.take(300)}")
            JSONObject(text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}