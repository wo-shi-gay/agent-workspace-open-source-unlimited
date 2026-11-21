package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 文件工具操作历史（仅针对 write_file / edit_file）。
 *
 * 每次 AI 通过文件工具改动一个文件，就在本会话下记一条：
 *  - path        工作区相对/绝对路径
 *  - absPath     真实绝对路径
 *  - beforeText  改动前内容（新建文件为 null）
 *  - afterText   改动后内容
 *  - existedBefore  改动前文件是否已存在（用于撤回时判断"应删除"还是"应还原"）
 *  - tool        "write_file" | "edit_file"
 *  - at          时间戳
 *
 * 撤回：把文件内容还原成 beforeText；若 existedBefore=false（原本不存在），
 * 则直接删除文件。撤回后该条记录标记 reverted=true（不再重复撤回）。
 *
 * 持久化：SharedPreferences("file_history")，键 "session_<id>"，值是 JSON 数组。
 * 每个会话最多保留 [MAX_ENTRIES] 条，超出丢弃最旧的。
 */
object FileHistoryStore {

    private const val PREF = "file_history"
    private const val MAX_ENTRIES = 200
    private const val KEY_PREFIX = "session_"

    data class Entry(
        val id: String,
        val path: String,
        val absPath: String,
        val beforeText: String?,
        val afterText: String,
        val existedBefore: Boolean,
        val tool: String,
        val at: Long,
        val reverted: Boolean,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(sessionId: String) = "$KEY_PREFIX$sessionId"

    /** 追加一条操作记录。 */
    fun record(
        context: Context,
        sessionId: String,
        path: String,
        absPath: String,
        beforeText: String?,
        afterText: String,
        existedBefore: Boolean,
        tool: String,
    ) {
        if (sessionId.isBlank()) return
        val list = load(context, sessionId).toMutableList()
        list.add(
            Entry(
                id = "fh_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}",
                path = path,
                absPath = absPath,
                beforeText = beforeText,
                afterText = afterText,
                existedBefore = existedBefore,
                tool = tool,
                at = System.currentTimeMillis(),
                reverted = false,
            )
        )
        // 超出上限丢最旧的
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        save(context, sessionId, list)
    }

    fun load(context: Context, sessionId: String): List<Entry> {
        if (sessionId.isBlank()) return emptyList()
        val raw = prefs(context).getString(key(sessionId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Entry(
                            id = o.optString("id"),
                            path = o.optString("path"),
                            absPath = o.optString("absPath"),
                            beforeText = if (o.isNull("beforeText")) null else o.optString("beforeText"),
                            afterText = o.optString("afterText"),
                            existedBefore = o.optBoolean("existedBefore", true),
                            tool = o.optString("tool"),
                            at = o.optLong("at"),
                            reverted = o.optBoolean("reverted", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, sessionId: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("path", e.path)
                    .put("absPath", e.absPath)
                    .put("beforeText", e.beforeText ?: JSONObject.NULL)
                    .put("afterText", e.afterText)
                    .put("existedBefore", e.existedBefore)
                    .put("tool", e.tool)
                    .put("at", e.at)
                    .put("reverted", e.reverted)
            )
        }
        prefs(context).edit().putString(key(sessionId), arr.toString()).apply()
    }

    /** 清空某会话的文件操作历史。 */
    fun clear(context: Context, sessionId: String) {
        prefs(context).edit().remove(key(sessionId)).apply()
    }

    /** 撤回结果。 */
    data class RevertResult(val ok: Boolean, val message: String)

    /**
     * 撤回一条记录：把文件还原成改动前的内容。
     * - existedBefore=true  → 写回 beforeText
     * - existedBefore=false → 删除该文件（原本不存在）
     * 撤回成功后在存储里把该条标记 reverted=true。
     */
    fun revert(context: Context, sessionId: String, entryId: String): RevertResult {
        val list = load(context, sessionId).toMutableList()
        val idx = list.indexOfFirst { it.id == entryId }
        if (idx < 0) return RevertResult(false, "找不到这条记录")
        val e = list[idx]
        if (e.reverted) return RevertResult(false, "该操作已撤回")
        val file = runCatching { File(e.absPath) }.getOrNull()
            ?: return RevertResult(false, "路径无效")
        val result = runCatching {
            if (e.existedBefore) {
                val before = e.beforeText
                    ?: return RevertResult(false, "缺少原始内容，无法撤回")
                file.parentFile?.mkdirs()
                file.writeText(before)
                RevertResult(true, "已还原为改动前的内容")
            } else {
                // 原本不存在 → 撤回即删除
                if (file.exists()) file.delete()
                RevertResult(true, "文件已删除（AI 新建前的状态）")
            }
        }.getOrElse { RevertResult(false, "撤回失败：${it.message}") }

        if (result.ok) {
            list[idx] = e.copy(reverted = true)
            save(context, sessionId, list)
        }
        return result
    }
}
