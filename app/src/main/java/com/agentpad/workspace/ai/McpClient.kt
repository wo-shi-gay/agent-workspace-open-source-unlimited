package com.agentpad.workspace.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 客户端：通过 Streamable HTTP 传输与远端 MCP Server 通信（JSON-RPC 2.0）。
 *
 * 实现的是 MCP 规范里最常用的「Streamable HTTP」：
 *  - 每次 POST JSON-RPC 到 endpoint；
 *  - 响应可能是 `application/json`（单条）或 `text/event-stream`（SSE 流，取最后一条 result）；
 *  - 首次需要 `initialize`，之后发 `notifications/initialized`；
 *  - 服务端若返回 `Mcp-Session-Id` 头，后续请求要带上。
 *
 * 所有方法都是同步阻塞的，调用方必须放到子线程。
 */
object McpClient {

    private val idGen = AtomicLong(1)

    class McpException(message: String) : Exception(message)

    /** 一次会话上下文，保存 session id 与协议版本。 */
    class Session {
        var sessionId: String = ""
        var protocolVersion: String = "2025-06-18"
        var initialized: Boolean = false
    }

    private const val CLIENT_NAME = "AgentWorkspace"
    private const val CLIENT_VERSION = "2.0"

    /** 初始化连接（initialize + notifications/initialized）。 */
    fun initialize(url: String, token: String): Session {
        val session = Session()
        val params = JSONObject()
            .put("protocolVersion", session.protocolVersion)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION))
        val result = rpc(url, token, session, "initialize", params, notify = false)
        session.protocolVersion = result.optString("protocolVersion", session.protocolVersion)
        // 发 initialized 通知（无 id，服务端不返回结果）
        runCatching { rpc(url, token, session, "notifications/initialized", JSONObject(), notify = true) }
        session.initialized = true
        return session
    }

    /** 拉取工具列表。 */
    fun listTools(url: String, token: String, session: Session): List<McpStore.RemoteTool> {
        val result = rpc(url, token, session, "tools/list", JSONObject(), notify = false)
        val arr = result.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<McpStore.RemoteTool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isBlank()) continue
            out.add(
                McpStore.RemoteTool(
                    serverId = "",   // 由调用方填充
                    name = name,
                    description = t.optString("description", ""),
                    schema = (t.optJSONObject("inputSchema") ?: JSONObject()).toString(),
                )
            )
        }
        return out
    }

    /** 调用远端工具，返回文本结果（content 数组拼起来）。 */
    fun callTool(url: String, token: String, session: Session, toolName: String, arguments: JSONObject): String {
        val params = JSONObject().put("name", toolName).put("arguments", arguments)
        val result = rpc(url, token, session, "tools/call", params, notify = false)
        // result: { content: [ {type:"text", text:"..."} , ... ], isError?: bool }
        val isError = result.optBoolean("isError", false)
        val sb = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> sb.append(c.optString("text"))
                    "image" -> sb.append("[image ${c.optString("mimeType", "image")}]")
                    "resource" -> sb.append(c.optJSONObject("resource")?.optString("text", "") ?: "[resource]")
                    else -> sb.append(c.optString("text", ""))
                }
                if (i < content.length() - 1) sb.append("\n")
            }
        }
        val text = sb.toString().ifBlank { result.toString() }
        return if (isError) JSONObject().put("success", false).put("error", text).toString()
        else JSONObject().put("success", true).put("result", text).toString()
    }

    // ---------------- 底层 JSON-RPC ----------------

    /**
     * 发送一条 JSON-RPC 请求。
     * @param notify 通知类请求（无 id，服务端可能返回 202/204 空响应）
     * @return result 对象（通知类返回空 JSONObject）
     */
    private fun rpc(
        url: String,
        token: String,
        session: Session,
        method: String,
        params: JSONObject,
        notify: Boolean,
    ): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
        if (params.length() > 0) payload.put("params", params)
        if (!notify) payload.put("id", idGen.getAndIncrement())

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (session.sessionId.isNotBlank()) setRequestProperty("Mcp-Session-Id", session.sessionId)
            if (notify) setRequestProperty("X-Mcp-Notification", "1")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        // 捕获服务端返回的 session id
        conn.getHeaderField("Mcp-Session-Id")?.let { if (it.isNotBlank()) session.sessionId = it }

        if (notify && code in 200..299) return JSONObject()

        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { r -> r.readText() } }.getOrNull() ?: ""
            throw McpException("HTTP $code ${err.take(300)}")
        }

        val contentType = conn.contentType ?: ""
        val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()

        val body = if (contentType.contains("text/event-stream")) {
            // SSE：取最后一条含 result/error 的 data 行
            parseSse(text)
        } else {
            runCatching { JSONObject(text) }.getOrElse { throw McpException("响应不是合法 JSON：${text.take(200)}") }
        }

        if (body.has("error")) {
            val e = body.optJSONObject("error")
            throw McpException(e?.optString("message") ?: "远端返回错误")
        }
        return body.optJSONObject("result") ?: JSONObject()
    }

    /** 从 SSE 文本里抽取最后一条 JSON-RPC 响应。 */
    private fun parseSse(raw: String): JSONObject {
        var last: JSONObject? = null
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (!l.startsWith("data:")) return@forEach
            val data = l.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return@forEach
            runCatching { JSONObject(data) }.getOrNull()?.let { obj ->
                if (obj.has("result") || obj.has("error")) last = obj
            }
        }
        return last ?: throw McpException("SSE 流里没有有效响应")
    }

    /**
     * 一次性探测：初始化 → 拉工具列表。用于「测试连接」按钮。
     * @return Pair(工具列表, 错误信息)。成功时错误为 null。
     */
    fun probe(url: String, token: String): Pair<List<McpStore.RemoteTool>, String?> {
        return try {
            val session = initialize(url, token)
            val tools = listTools(url, token, session)
            Pair(tools, null)
        } catch (e: Exception) {
            Pair(emptyList(), e.message ?: "连接失败")
        }
    }
}