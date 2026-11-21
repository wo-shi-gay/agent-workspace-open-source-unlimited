package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 会话存储层 —— 「按消息分片」落盘。
 *
 * ⚠ 2026-11-09 为什么不是 Room：
 *   本想照抄 RikkaHub 的 message_node 表（Room 2.8.5 + KSP），但云端实测：
 *     e: [ksp] MessageNodeEntity.kt:37: [MissingType]: Element
 *        'com.agentpad.workspace.data.db.MessageNodeEntity' references a type that is not present
 *     e: [ksp] RoomKspProcessor was unable to process 'ChatDatabase'
 *   AGP 9.4 的内置 Kotlin 与 KSP 2.3.x 组合下，KSP 解析不到同模块内 Kotlin 类型，
 *   升到 2.3.13 同样失败。这条路在当前工具链上走不通。
 *
 *   但 RikkaHub 架构的**精华**不是「用 Room」这件事本身，而是：
 *     **一条消息一个存储单元，绝不把整个会话拼成一个字符串。**
 *   Room 只是载体。这里用「分片文件」做同样的载体，达成同样的保证。
 *
 * 目录结构：
 *   filesDir/sessions/<sessionId>/
 *     meta.json          会话元信息（id/title/mode/时间戳）—— 极小
 *     node_00000.json    第 0~31 条消息（含分支）
 *     node_00032.json    第 32~63 条消息
 *     ...
 *
 * ⚠ 为什么分片而不是一条消息一个文件：
 *   单条消息一个文件在长会话里会生成上千个小文件，open/close 开销明显。
 *   按 [CHUNK_SIZE] 条一片，单次序列化峰值 = 32 条消息，仍然与历史总长度无关，
 *   OOM 风险被彻底消除；文件数也控制在可接受范围。
 *
 * ⚠ 与旧实现的根本差别：
 *   旧：整个 Session（全部消息 + 全部工具结果）→ 一个 JSONObject.toString()
 *       → SharedPreferences。工具返回几 MB 时峰值就是几 MB 的字符串 → OOM。
 *   新：一次最多 32 条消息 → 一次 toString()。峰值恒定，不随会话增长。
 */
internal object SessionPersistence {

    /** 每个分片最多装多少条消息 */
    private const val CHUNK_SIZE = 32

    /** 单线程串行落盘：保证同一会话的写顺序 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-shard-write").apply { isDaemon = true }
    }

    private fun root(context: Context): File =
        File(context.filesDir, "sessions").apply { mkdirs() }

    private fun sessionDir(context: Context, id: String): File {
        // 会话 id 可能含特殊字符，做一次安全化，避免目录穿越
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(root(context), safe)
    }

    // ---------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------

    /** 同步写（只在 io 线程或 runBlocking 里调） */
    fun writeSession(context: Context, session: ChatSession) {
        val dir = sessionDir(context, session.id)
        dir.mkdirs()

        // 1) 元信息（极小，单独一个文件）
        val meta = JSONObject()
            .put("id", session.id)
            .put("title", session.title)
            .put("mode", session.mode)
            .put("createAt", if (dir.exists()) readCreateAt(dir) else System.currentTimeMillis())
            .put("updateAt", System.currentTimeMillis())
        File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)

        // 2) 消息分片：每 CHUNK_SIZE 条一个文件
        val chunks = session.messages.chunked(CHUNK_SIZE)
        chunks.forEachIndexed { ci, msgs ->
            val arr = JSONArray()
            msgs.forEach { m ->
                // 一条消息 → 一个元素；峰值只跟这一片有关
                arr.put(ChatStore.messageToJson(m))
            }
            File(dir, nodeFileName(ci)).writeText(arr.toString(), Charsets.UTF_8)
        }

        // 3) 清掉多余的旧分片（会话变短时）
        val keep = chunks.size
        dir.listFiles { f -> f.name.startsWith("node_") }?.forEach { f ->
            val idx = f.name.removePrefix("node_").removeSuffix(".json").toIntOrNull() ?: return@forEach
            if (idx >= keep) f.delete()
        }
    }

    private fun readCreateAt(dir: File): Long = runCatching {
        JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8)).optLong("createAt", System.currentTimeMillis())
    }.getOrDefault(System.currentTimeMillis())

    private fun nodeFileName(chunkIndex: Int): String =
        "node_" + chunkIndex.toString().padStart(5, '0') + ".json"

    /** 异步写（不阻塞调用线程） */
    fun writeSessionAsync(context: Context, session: ChatSession) {
        val appContext = context.applicationContext
        io.execute { runCatching { writeSession(appContext, session) } }
    }

    // ---------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------

    /** 读整个会话；不存在返回 null。逐片读、逐片解析，峰值恒定。 */
    fun readSession(context: Context, id: String): ChatSession? {
        val dir = sessionDir(context, id)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null

        val meta = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return null

        val nodes = dir.listFiles { f -> f.name.startsWith("node_") }?.sortedBy { it.name } ?: emptyList()
        val messages = ArrayList<ChatStore.Message>()
        nodes.forEach { f ->
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { messages.add(ChatStore.jsonToMessage(it)) }
                }
            }
        }
        return ChatSession(
            id = meta.optString("id", id),
            title = meta.optString("title", "新对话"),
            messages = messages,
            mode = meta.optString("mode", "chat"),
        )
    }

    // ---------------------------------------------------------------
    // 索引 / 删除 / 改名
    // ---------------------------------------------------------------

    /** 所有会话 id，按 update_at 升序（与旧 readSessionIndex 的顺序语义一致） */
    fun listSessionIds(context: Context): List<String> {
        val dirs = root(context).listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val meta = runCatching {
                JSONObject(File(d, "meta.json").readText(Charsets.UTF_8))
            }.getOrNull() ?: return@mapNotNull null
            Triple(meta.optString("id", d.name), meta.optLong("updateAt", 0L), d)
        }.sortedBy { it.second }.map { it.first }
    }

    fun exists(context: Context, id: String): Boolean =
        File(sessionDir(context, id), "meta.json").isFile

    fun deleteSession(context: Context, id: String) {
        io.execute {
            runCatching { sessionDir(context, id).deleteRecursively() }
        }
    }

    fun renameSession(context: Context, id: String, title: String) {
        io.execute {
            runCatching {
                val dir = sessionDir(context, id)
                val f = File(dir, "meta.json")
                if (f.isFile) {
                    val meta = JSONObject(f.readText(Charsets.UTF_8))
                    meta.put("title", title).put("updateAt", System.currentTimeMillis())
                    f.writeText(meta.toString(), Charsets.UTF_8)
                }
            }
        }
    }

    /** 整个会话目录占用字节数（诊断用） */
    fun sessionBytes(context: Context, id: String): Long =
        sessionDir(context, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * 存储层用的轻量会话载体。
 *
 * 与 ChatStore.Session 字段完全一致，但放在这里可以避免
 * SessionPersistence 与 ChatStore 互相引用造成的循环初始化问题。
 */
internal data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatStore.Message>,
    val mode: String = "chat",
)