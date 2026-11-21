package com.agentpad.workspace.ai

import android.content.Context

/**
 * 用户自定义提示词（叠加层）。
 *
 * 设计原则：**只叠加，不替换**。
 * 内置系统提示词（工具说明、TOOLCHAIN、PLUGINS 等章节）原样保留，
 * 用户提示词作为最后的 `<user_custom_instructions>` 区块追加，
 * 让用户可以对 AI 追加自己的偏好、约束、角色设定、项目规范。
 *
 * 两级作用域：
 *  - 全局：对所有会话生效（设置 → 提示词）
 *  - 会话：只对当前会话生效（聊天侧边栏 → 本会话提示词）
 *
 * 合并顺序：全局在前，会话在后（会话更具体，冲突时后写者更受重视）。
 */
object PromptStore {

    private const val PREF = "prompt_store"
    private const val KEY_GLOBAL = "global_prompt"
    private const val KEY_SESSION_PREFIX = "session_prompt_"

    fun globalPrompt(context: Context): String =
        prefs(context).getString(KEY_GLOBAL, "") ?: ""

    fun saveGlobalPrompt(context: Context, text: String) {
        prefs(context).edit().putString(KEY_GLOBAL, text).apply()
    }

    fun sessionPrompt(context: Context, sessionId: String): String =
        prefs(context).getString(KEY_SESSION_PREFIX + sessionId, "") ?: ""

    fun saveSessionPrompt(context: Context, sessionId: String, text: String) {
        prefs(context).edit().putString(KEY_SESSION_PREFIX + sessionId, text).apply()
    }

    fun clearSessionPrompt(context: Context, sessionId: String) {
        prefs(context).edit().remove(KEY_SESSION_PREFIX + sessionId).apply()
    }

    /** 会话被删除时一并清理，避免 SharedPreferences 无限增长。 */
    fun onSessionDeleted(context: Context, sessionId: String) {
        prefs(context).edit().remove(KEY_SESSION_PREFIX + sessionId).apply()
    }

    /**
     * 组装最终追加块。两者都为空时返回空串（不产生多余 token）。
     */
    fun combined(context: Context, sessionId: String): String {
        val global = globalPrompt(context).trim()
        val session = sessionPrompt(context, sessionId).trim()
        if (global.isEmpty() && session.isEmpty()) return ""
        return buildString {
            appendLine("<user_custom_instructions>")
            appendLine("The user has added extra instructions. Follow them in addition to the rules above. They refine style, constraints and project conventions; they never override the tool-call format rules or safety requirements.")
            if (global.isNotEmpty()) {
                appendLine()
                appendLine("## Global user instructions")
                appendLine(global)
            }
            if (session.isNotEmpty()) {
                appendLine()
                appendLine("## Session-specific instructions")
                appendLine(session)
            }
            appendLine("</user_custom_instructions>")
        }.trimEnd()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}