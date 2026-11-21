package com.agentpad.workspace.ai

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * MCP 服务端的进程内单例管理器。
 *
 * 负责：
 *  - 按 [McpStore.Server] 配置启动/停止 [McpServer]；
 *  - 提供一个「独立于聊天会话」的 [ToolRegistry]，供 MCP 调用者执行工具；
 *  - 对外广播运行状态，供设置界面刷新。
 */
object McpServerManager {

    @Volatile private var server: McpServer? = null
    @Volatile private var bridgeRegistry: ToolRegistry? = null
    @Volatile var lastError: String = ""
        private set

    /** 状态变化回调（设置界面注册，用于刷新 UI）。 */
    @Volatile var onStateChanged: (() -> Unit)? = null

    val isRunning: Boolean get() = server?.isRunning == true
    val boundPort: Int get() = server?.boundPort ?: 0

    /** 给 MCP 服务端用的固定工作区（不依赖任何聊天会话）。 */
    fun bridgeWorkspace(context: Context): File {
        val dir = File(context.filesDir, "mcp-workspace")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 为 MCP 服务端构造的工具执行桥：用固定的隐藏会话工作区。 */
    private fun registry(context: Context): ToolRegistry {
        bridgeRegistry?.let { return it }
        synchronized(this) {
            bridgeRegistry?.let { return it }
            val r = ToolRegistry(context.applicationContext, bridgeWorkspace(context), "mcp-server")
            bridgeRegistry = r
            return r
        }
    }

    /** 按当前存储的配置启动（如果已启用）。 */
    fun startIfEnabled(context: Context) {
        val cfg = McpStore.server(context)
        if (cfg.enabled) start(context, cfg)
    }

    fun start(context: Context, cfg: McpStore.Server) {
        stop()
        lastError = ""
        try {
            val reg = registry(context)
            val mgr = McpServer(
                context = context.applicationContext,
                specProvider = { reg.specs() },
                executor = { name, args ->
                    runCatching { reg.execute(ToolCall("mcp", name, args.toString())).content }
                        .getOrElse { JSONObject().put("success", false).put("error", it.message).toString() }
                },
            )
            mgr.start(McpServer.Config(
                port = cfg.port,
                allowLan = cfg.allowLan,
                token = cfg.token,
                exposedTools = cfg.exposedTools,
            ))
            server = mgr
        } catch (e: Exception) {
            lastError = e.message ?: "启动失败"
        }
        onStateChanged?.invoke()
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        onStateChanged?.invoke()
    }

    /** 保存配置并根据开关启停。 */
    fun apply(context: Context, cfg: McpStore.Server) {
        McpStore.saveServer(context, cfg)
        if (cfg.enabled) start(context, cfg) else stop()
    }
}