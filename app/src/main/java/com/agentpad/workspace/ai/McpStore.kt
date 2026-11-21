package com.agentpad.workspace.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 配置存储。
 *
 * 两个方向：
 *  - **服务端**（[Server]）：本 App 监听本地端口，把自带工具（terminal / read_file …）
 *    以 MCP 协议暴露，供其它 App / 桌面 AI 调用。
 *  - **客户端**（[Remote]）：接入别人的 MCP Server，把远端工具挂到本 App 的对话里。
 *
 * 全部存在 SharedPreferences（配置量小，无需数据库）。
 */
object McpStore {

    private const val PREF = "mcp_config"
    private const val KEY_SERVER = "server"
    private const val KEY_REMOTES = "remotes"
    private const val KEY_REMOTE_TOOLS = "remote_tools_cache"

    // ---------------- 服务端配置 ----------------

    data class Server(
        val enabled: Boolean = false,
        /** 监听端口，默认 8765 */
        val port: Int = 8765,
        /** 是否允许局域网访问（0.0.0.0）。false = 只监听 127.0.0.1（仅本机） */
        val allowLan: Boolean = false,
        /** 访问令牌；非空时请求必须带 Authorization: Bearer <token> */
        val token: String = "",
        /** 对外暴露的工具名白名单（空 = 全部） */
        val exposedTools: Set<String> = emptySet(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled)
            .put("port", port)
            .put("allowLan", allowLan)
            .put("token", token)
            .put("exposedTools", JSONArray(exposedTools.toList()))

        companion object {
            fun from(o: JSONObject?) = Server(
                enabled = o?.optBoolean("enabled", false) ?: false,
                port = o?.optInt("port", 8765) ?: 8765,
                allowLan = o?.optBoolean("allowLan", false) ?: false,
                token = o?.optString("token", "") ?: "",
                exposedTools = o?.optJSONArray("exposedTools")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                } ?: emptySet(),
            )
        }
    }

    // ---------------- 远端 MCP 配置 ----------------

    data class Remote(
        val id: String,
        val name: String,
        /** Streamable HTTP 端点，如 https://example.com/mcp */
        val url: String,
        val enabled: Boolean = true,
        /** 可选 Bearer token */
        val token: String = "",
        /** 上次拉取到的工具数（仅展示用） */
        val toolCount: Int = 0,
        /** 上次连接错误信息（空 = 正常） */
        val lastError: String = "",
        /** 是否把工具暴露给 AI（关闭则从工具列表里隐藏） */
        val exposeToAi: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("url", url)
            .put("enabled", enabled).put("token", token)
            .put("toolCount", toolCount).put("lastError", lastError)
            .put("exposeToAi", exposeToAi)

        companion object {
            fun from(o: JSONObject) = Remote(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                enabled = o.optBoolean("enabled", true),
                token = o.optString("token", ""),
                toolCount = o.optInt("toolCount", 0),
                lastError = o.optString("lastError", ""),
                exposeToAi = o.optBoolean("exposeToAi", true),
            )
        }
    }

    /** 远端工具（拉取 tools/list 后缓存） */
    data class RemoteTool(
        val serverId: String,
        /** 远端原始工具名 */
        val name: String,
        val description: String,
        val schema: String,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 服务端 ----------------

    fun server(c: Context): Server = runCatching {
        val raw = prefs(c).getString(KEY_SERVER, null) ?: return Server()
        Server.from(JSONObject(raw))
    }.getOrDefault(Server())

    fun saveServer(c: Context, s: Server) {
        prefs(c).edit().putString(KEY_SERVER, s.toJson().toString()).apply()
    }

    // ---------------- 远端列表 ----------------

    fun remotes(c: Context): List<Remote> = runCatching {
        val raw = prefs(c).getString(KEY_REMOTES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Remote.from(it) } }
    }.getOrDefault(emptyList())

    fun saveRemotes(c: Context, list: List<Remote>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs(c).edit().putString(KEY_REMOTES, arr.toString()).apply()
    }

    fun upsertRemote(c: Context, r: Remote) {
        val list = remotes(c).toMutableList()
        val idx = list.indexOfFirst { it.id == r.id }
        if (idx >= 0) list[idx] = r else list.add(r)
        saveRemotes(c, list)
    }

    fun removeRemote(c: Context, id: String) {
        saveRemotes(c, remotes(c).filterNot { it.id == id })
        saveRemoteTools(c, remoteTools(c).filterNot { it.serverId == id })
    }

    // ---------------- 远端工具缓存 ----------------

    fun remoteTools(c: Context): List<RemoteTool> = runCatching {
        val raw = prefs(c).getString(KEY_REMOTE_TOOLS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RemoteTool(
                serverId = o.optString("serverId"),
                name = o.optString("name"),
                description = o.optString("description", ""),
                schema = o.optString("schema", "{}"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveRemoteTools(c: Context, tools: List<RemoteTool>) {
        val arr = JSONArray().apply {
            tools.forEach {
                put(JSONObject().put("serverId", it.serverId).put("name", it.name)
                    .put("description", it.description).put("schema", it.schema))
            }
        }
        prefs(c).edit().putString(KEY_REMOTE_TOOLS, arr.toString()).apply()
    }

    /** 替换某个 server 的工具缓存 */
    fun replaceToolsFor(c: Context, serverId: String, tools: List<RemoteTool>) {
        saveRemoteTools(c, remoteTools(c).filterNot { it.serverId == serverId } + tools)
    }

    // ---------------- 工具名映射 ----------------

    /** 本 App 内使用的合成工具名：mcp__<serverId>__<toolName> */
    fun compositeName(serverId: String, toolName: String) = "mcp__${serverId}__$toolName"

    /** 解析合成工具名；返回 (serverId, toolName) 或 null */
    fun parseComposite(name: String): Pair<String, String>? {
        if (!name.startsWith("mcp__")) return null
        val rest = name.removePrefix("mcp__")
        val sep = rest.indexOf("__")
        if (sep <= 0) return null
        return rest.substring(0, sep) to rest.substring(sep + 2)
    }
}