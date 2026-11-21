package com.agentpad.workspace.ai

import org.json.JSONObject

data class TerminalRequest(val command: String, val cwd: String? = null, val timeoutMs: Long? = null)
data class TerminalOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val exitCodeKnown: Boolean = true,
    val completed: Boolean = true,
    val completionReason: String = "process_exit",
)

interface TerminalBackend {
    fun run(request: TerminalRequest): TerminalOutput
}

class TerminalTool(private val backend: TerminalBackend) {
    fun execute(args: JSONObject): String {
        val request = TerminalRequest(
            command = args.optString("command"),
            cwd = args.optString("cwd").takeIf { it.isNotBlank() },
            timeoutMs = args.optLong("timeout_ms", 120_000L).takeIf { it > 0 }
        )
        val result = backend.run(request)
        val stdout = truncate(result.stdout, 1800)
        val stderr = truncate(result.stderr, 1200)
        // 用户从终端 Ctrl+C 打断时，必须明确告诉模型「这是人掐的」，否则
        // 它看到 exit_code=130 会当成普通失败并反复重试同一条命令。
        val interrupted = result.exitCode == 130 ||
            result.stderr.contains("interrupted by user") ||
            result.stderr.contains("aborted")
        val state = when {
            interrupted -> "interrupted"
            !result.completed -> "running"
            !result.exitCodeKnown -> "completed"
            result.exitCode == 0 -> "success"
            else -> "error"
        }
        val summary = buildString {
            append(state)
            append(" | completion_reason=").append(result.completionReason)
            append(" | exit_code=")
            append(if (result.exitCodeKnown) result.exitCode.toString() else "unknown")
            if (interrupted) append(" | interrupted_by_user=true")
            if (stdout.isNotBlank()) append(" | stdout_chars=").append(result.stdout.length)
            if (stderr.isNotBlank()) append(" | stderr_chars=").append(result.stderr.length)
        }
        return JSONObject()
            .put("success", !interrupted && (!result.exitCodeKnown || result.exitCode == 0))
            .put("status", state)
            .put("completed", result.completed)
            .put("exit_code_known", result.exitCodeKnown)
            .put("completion_reason", result.completionReason)
            .put("summary", summary)
            .put("exit_code", if (result.exitCodeKnown) result.exitCode else JSONObject.NULL)
            // 单独给一个布尔位：模型不用去猜 130 是什么含义。
            .put("interrupted_by_user", interrupted)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", result.stdout.length > stdout.length)
            .put("stderr_truncated", result.stderr.length > stderr.length)
            .toString()
    }

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = text.take(maxChars / 2)
        val tail = text.takeLast(maxChars / 2)
        return "$head\n... [truncated ${text.length - maxChars} chars] ...\n$tail"
    }
}