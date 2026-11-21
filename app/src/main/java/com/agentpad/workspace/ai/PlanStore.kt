package com.agentpad.workspace.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话级任务计划存储（完全由 AI 通过 plan 工具自主创建/维护，不强制）。
 * 持久化在 session pref 里的一个 JSON：{"items":[{id,text,status},...]}
 * status: pending / in_progress / done
 */
object PlanStore {

    private const val KEY_PREFIX = "session_plan_"

    data class Item(val id: String, val text: String, val status: String)

    private fun key(sessionId: String) = KEY_PREFIX + sessionId

    fun load(context: Context, sessionId: String): List<Item> {
        val raw = prefs(context).getString(key(sessionId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Item(it.optString("id"), it.optString("text"), it.optString("status", "pending"))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, sessionId: String, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("status", it.status)) }
        prefs(context).edit().putString(key(sessionId), JSONObject().put("items", arr).toString()).apply()
    }

    fun clear(context: Context, sessionId: String) {
        prefs(context).edit().remove(key(sessionId)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("agent_workspace_store", Context.MODE_PRIVATE)
}