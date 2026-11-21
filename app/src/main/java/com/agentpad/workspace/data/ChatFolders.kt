package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Folder identity and workspace identity are stable across renames and backup restore. */
object ChatFolders {
    private const val STORE = "agent_workspace_store"
    private const val FOLDERS = "chat_folders_v1"
    private const val MEMBERSHIP = "chat_folder_of_"
    private const val OWNER = "chat_workspace_owner_"
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    data class Folder(val id: String, val name: String, val workspaceKey: String)

    @Synchronized
    fun list(context: Context): List<Folder> = runCatching {
        val array = JSONArray(prefs(context).getString(FOLDERS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.getString("id")
                val key = item.getString("workspaceKey")
                if (safeKey(id) && safeKey(key)) add(Folder(id, item.getString("name"), key))
            }
        }
    }.getOrDefault(emptyList())

    private fun safeKey(key: String) = key.isNotBlank() && key != "." && key != ".." &&
        key.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun write(context: Context, folders: List<Folder>) {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("workspaceKey", it.workspaceKey)) }
        check(prefs(context).edit().putString(FOLDERS, array.toString()).commit()) { "文件夹保存失败" }
    }

    @Synchronized
    fun create(context: Context, name: String): Folder {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入文件夹名称" }
        val folders = list(context)
        require(folders.none { it.name == trimmed }) { "已有同名文件夹" }
        val id = "folder_" + UUID.randomUUID().toString()
        val folder = Folder(id, trimmed, id)
        check(directory(context, id).let { it.isDirectory || it.mkdirs() }) { "工作区创建失败" }
        write(context, folders + folder)
        return folder
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入文件夹名称" }
        val folders = list(context)
        require(folders.none { it.id != id && it.name == trimmed }) { "已有同名文件夹" }
        write(context, folders.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun folderId(context: Context, sessionId: String): String? {
        val id = prefs(context).getString(MEMBERSHIP + sessionId, null) ?: return null
        return id.takeIf { value -> list(context).any { it.id == value } }
    }

    fun workspaceKey(context: Context, sessionId: String): String {
        val p = prefs(context)
        val folder = p.getString(MEMBERSHIP + sessionId, null)?.let { id -> list(context).find { it.id == id } }
        return folder?.workspaceKey ?: p.getString(OWNER + sessionId, null)?.takeIf(::safeKey) ?: sessionId
    }

    fun directory(context: Context, key: String): File {
        require(safeKey(key)) { "工作区标识错误" }
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "workspaces/$key")
    }

    /** Moving changes binding only: original files stay intact, with no expensive implicit copy. */
    @Synchronized
    fun move(context: Context, sessionId: String, targetId: String?) {
        val key = workspaceKey(context, sessionId)
        val folder = targetId?.let { id -> list(context).find { it.id == id } ?: error("文件夹已移除") }
        val terminalId = com.agentpad.workspace.ai.TerminalService.aiSessionId(sessionId)
        check(!com.agentpad.workspace.WorkingBubble.isShowing(sessionId) &&
            !com.agentpad.workspace.ai.TerminalService.commandRunning(terminalId)) { "请等当前任务结束后再移动对话" }
        val editor = prefs(context).edit()
        if (folder == null) {
            // Ungrouping keeps the same workspace, just like Operit's independent group/workspace fields.
            editor.remove(MEMBERSHIP + sessionId).putString(OWNER + sessionId, key)
        } else {
            editor.putString(MEMBERSHIP + sessionId, folder.id).putString(OWNER + sessionId, folder.workspaceKey)
        }
        editor.remove("session_workspace_$sessionId")
        check(editor.commit()) { "对话归属保存失败" }
        if ((folder?.workspaceKey ?: key) != key) {
            // A shell created before rebinding would otherwise keep using its old initial directory.
            com.agentpad.workspace.ai.TerminalService.closeSession(terminalId)
        }
    }

    /** Removing a folder preserves chats and their existing workspace binding. */
    @Synchronized
    fun remove(context: Context, id: String) {
        val folders = list(context)
        val folder = folders.find { it.id == id } ?: return
        val p = prefs(context)
        val editor = p.edit()
        p.all.forEach { (key, value) ->
            if (key.startsWith(MEMBERSHIP) && value == id) {
                val sessionId = key.removePrefix(MEMBERSHIP)
                editor.remove(key).putString(OWNER + sessionId, folder.workspaceKey)
            }
        }
        check(editor.commit()) { "对话归属保存失败" }
        write(context, folders.filter { it.id != id })
    }

    fun forgetSession(context: Context, sessionId: String) {
        prefs(context).edit().remove(MEMBERSHIP + sessionId).remove(OWNER + sessionId).apply()
    }

    /** Includes empty folders and ungrouped conversations still bound to this workspace. */
    fun isShared(context: Context, sessionId: String): Boolean {
        val key = workspaceKey(context, sessionId)
        if (list(context).any { it.workspaceKey == key }) return true
        val ids = prefs(context).getStringSet("session_index", emptySet()).orEmpty()
        return ids.any { it != sessionId && workspaceKey(context, it) == key }
    }
}
