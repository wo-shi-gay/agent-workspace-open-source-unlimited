package com.agentpad.workspace.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * MCP 服务端：在手机上开一个 HTTP 端口，把本 App 的工具以 MCP 协议暴露出去，
 * 供其它 App、电脑上的 AI 客户端（Claude Desktop / Cursor / Cherry Studio…）调用。
 *
 * 协议：MCP「Streamable HTTP」——单端点 POST JSON-RPC 2.0，响应 application/json。
 * 支持方法：initialize / notifications/initialized / tools/list / tools/call / ping。
 *
 * 生命周期：由 [McpServerManager] 持有，进程内单例；开关在「设置 → MCP 服务」。
 */
class McpServer(
    private val context: Context,
    /** 工具来源：返回当前可用工具规格 */
    private val specProvider: () -> List<ToolSpec>,
    /** 工具执行：按名字执行，返回 JSON 字符串结果 */
    private val executor: (name: String, args: JSONObject) -> String,
) {

    companion object {
        private const val TAG = "McpServer"
        private const val PROTOCOL_VERSION = "2025-06-18"
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    /** 当前实际监听端口（0 表示未启动）。 */
    @Volatile var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running

    class Config(
        val port: Int,
        val allowLan: Boolean,
        val token: String,
        val exposedTools: Set<String>,
    )

    /**
     * 启动服务。抛异常表示启动失败（端口占用等）。
     */
    @Synchronized
    fun start(config: Config) {
        if (running) return
        val addr = if (config.allowLan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
        val ss = ServerSocket(config.port, 50, addr)
        serverSocket = ss
        boundPort = ss.localPort
        running = true
        acceptThread = Thread({
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept 失败：${e.message}")
                    break
                }
                pool.execute { runCatching { handle(socket, config) }.onFailure { Log.w(TAG, "处理异常：${it.message}") } }
            }
        }, "mcp-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "MCP 服务已启动：${if (config.allowLan) "0.0.0.0" else "127.0.0.1"}:$boundPort")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
    }

    // ---------------- HTTP 处理 ----------------

    private fun handle(socket: Socket, config: Config) {
        socket.use { s ->
            s.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()

            // 读请求行
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeResponse(out, 400, "Bad Request", "{}"); return }
            val method = parts[0].uppercase()
            val path = parts[1]

            // 读头部
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            var contentLength = 0
            while (!line.isNullOrEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
                line = input.readLine()
            }

            // 读 body
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            // ---- 路由 ----
            // 健康检查
            if (method == "GET" && (path == "/health" || path == "/")) {
                writeResponse(out, 200, "OK", JSONObject()
                    .put("status", "ok").put("server", "AgentWorkspace-MCP")
                    .put("protocol", PROTOCOL_VERSION).toString())
                return
            }
            // SSE 端点（部分客户端会先 GET 建流；我们直接提示用 POST）
            if (method == "GET" && path == "/mcp") {
                writeResponse(out, 405, "Method Not Allowed",
                    JSONObject().put("error", "请使用 POST 调用 MCP").toString())
                return
            }
            if (method != "POST") {
                writeResponse(out, 405, "Method Not Allowed", "{}")
                return
            }

            // 鉴权
            if (config.token.isNotBlank()) {
                val auth = headers["authorization"] ?: ""
                if (!auth.equals("Bearer ${config.token}", ignoreCase = true)) {
                    writeResponse(out, 401, "Unauthorized",
                        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                            .put("error", JSONObject().put("code", -32001).put("message", "unauthorized")).toString())
                    return
                }
            }

            val response = try {
                dispatch(body, config)
            } catch (e: Exception) {
                JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put("error", JSONObject().put("code", -32603).put("message", e.message ?: "internal error"))
            }

            // 通知类（无 id）返回 202 空体
            if (response.has("__notify")) {
                writeResponse(out, 202, "Accepted", "")
            } else {
                writeResponse(out, 200, "OK", response.toString())
            }
        }
    }

    /** 分发 JSON-RPC 方法。 */
    private fun dispatch(body: String, config: Config): JSONObject {
        val req = runCatching { JSONObject(body) }.getOrElse {
            return JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                .put("error", JSONObject().put("code", -32700).put("message", "parse error"))
        }
        val id = if (req.has("id")) req.get("id") else null
        val method = req.optString("method")

        // 通知：无 id 的请求不回结果
        if (id == null) {
            return JSONObject().put("__notify", true)
        }

        fun ok(result: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        fun err(code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", msg))

        return when (method) {
            "initialize" -> ok(JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject()
                    .put("name", "AgentWorkspace")
                    .put("version", "2.0")))

            "ping" -> ok(JSONObject())

            "tools/list" -> {
                val specs = exposedSpecs(config)
                val tools = JSONArray()
                specs.forEach { spec ->
                    tools.put(JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("inputSchema", runCatching { JSONObject(spec.parametersSchema) }.getOrElse { JSONObject() }))
                }
                ok(JSONObject().put("tools", tools))
            }

            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                if (name.isBlank()) return err(-32602, "缺少工具名")
                if (!isExposed(name, config)) return err(-32602, "工具未开放：$name")
                val resultText = runCatching { executor(name, args) }
                    .getOrElse { JSONObject().put("success", false).put("error", it.message ?: "执行失败").toString() }
                ok(JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", resultText)))
                    .put("isError", runCatching { JSONObject(resultText).optBoolean("success", true) }.getOrDefault(true).not()))
            }

            else -> err(-32601, "未知方法：$method")
        }
    }

    private fun isExposed(name: String, config: Config): Boolean =
        config.exposedTools.isEmpty() || name in config.exposedTools

    private fun exposedSpecs(config: Config): List<ToolSpec> =
        specProvider().filter { isExposed(it.name, config) }

    // ---------------- HTTP 输出 ----------------

    private fun writeResponse(out: OutputStream, code: Int, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }
}