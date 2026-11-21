package com.agentpad.workspace.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ChatStore {
    private const val PREF = "agent_workspace_store"
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * 内存会话缓存（写穿 SharedPreferences）。
     *
     * 为什么必须加：流式输出时每个 chunk 都会触发一次 UI 刷新，旧实现每次都
     * `prefs.getString(...)` + `JSONObject(...)` 把整段会话重新解析一遍。长会话
     * （几千条消息 / 几 MB JSON）下这是主线程卡死和 ANR 的元凶。
     * 现在只解析一次，之后读内存；写入时同步更新缓存，语义完全一致。
     */
    private val memorySessions = java.util.concurrent.ConcurrentHashMap<String, Session>()



    /**
     * 流式写入专用的"只更新内存 + 异步落盘"。
     *
     * 为什么必须加：`request()` 里每个 chunk 都调 `setAssistantParts()` → 旧实现
     * 持锁做整段 JSON 序列化 + SharedPreferences 写入，主线程 `loadSession()` 要
     * 拿同一把锁只能干等——这正是"切后台回来 UI 不刷新，直到工具调用才恢复"的
     * 直接原因（思考阶段 chunk 密集，锁被后台线程长期占用；工具调用阶段 chunk
     * 变稀，主线程才抢到锁）。
     *
     * 现在拆成：写路径只更新内存（极快），磁盘写入丢到后台线程慢慢做。UI 刷新
     * 走的 `loadSession()` 永远只读内存，不碰磁盘，主线程不再等锁。
     */
    private val diskWriteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 待落盘的会话（按 id 去重）。
     *
     * ⚠ 流式期间每个 chunk 都会触发一次 persistSessionAsync。旧实现是每次
     * 都排一个任务进队列，10 秒里可能排几百个「整段 JSON 序列化」，队列越积
     * 越长，最后一次落盘可能滞后好几分钟，进程被杀就丢内容。
     *
     * 现在改成「拖尾合并」：只保留每个 session 的最新快照，后台线程取到哪个
     * 就写哪个，中间那些旧快照直接丢弃（它们本来就会被覆盖）。
     */
    private val pendingPersist = java.util.concurrent.ConcurrentHashMap<String, Session>()

    /**
     * 落盘节流间隔（毫秒）。
     *
     * ⚠ 2026-11-04 「切后台回来卡死」的根治参数。
     *
     * 根因（Android 平台行为，不是我们的 bug）：
     *   SharedPreferences.apply() 是异步写盘，任务挂进内部 QueuedWork 队列；
     *   而 Activity.onPause() 会调用 QueuedWork.waitToFinish() ——
     *   **在主线程上同步等待**所有未完成的 apply() 写完。
     *
     *   思考阶段 token 密集：每个 chunk 都 persistSessionAsync → 后台线程
     *   把整份 session JSON（思考文本 + parts，可达数 MB）序列化后 apply()。
     *   这些写盘任务一路堆积，切后台那一刻 onPause 被 waitToFinish() 阻塞等它们
     *   全部写完 → 主线程完全卡死，连系统返回键都排不上队。
     *   一调工具 token 变稀、写入停下、队列排空，界面就"活"了 —— 现象完全吻合。
     *
     * 修法：流式期间（complete = false）把落盘节流到这个间隔。
     *   中间那些中间态本来就会被下一次覆盖（pendingPersist 已经做了拖尾合并），
     *   落盘只是「防进程被杀丢数据」的兜底，不需要每个 token 都写。
     *   节流后队列长度恒定在 O(1)，onPause 的 waitToFinish 几乎瞬间返回。
     *
     * 3000ms 的取舍：进程被杀最多丢 3 秒思考内容，换来的是后台切换不再卡死。
     * 完成态（complete = true）不受节流影响，永远立即落盘。
     */
    private const val STREAM_PERSIST_THROTTLE_MS = 3000L

    /** 每个会话上次真正落盘的时刻。 */
    private val lastPersistAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun persistSessionAsync(context: Context, session: Session) {
        pendingPersist[session.id] = session
        diskWriteExecutor.execute {
            // 取走当前最新快照；如果期间又产生了新的，会在下一轮任务里处理。
            val snapshot = pendingPersist.remove(session.id) ?: return@execute
            runCatching { persistSessionSync(context, snapshot) }
        }
    }

    /**
     * 流式专用的节流落盘。
     *
     * 与 [persistSessionAsync] 的区别：只有距上次落盘超过
     * [STREAM_PERSIST_THROTTLE_MS] 才真正写盘，其余只更新内存快照。
     * 这样 QueuedWork 队列不会积压，切后台时 onPause 的 waitToFinish() 不会卡住主线程。
     *
     * @param complete 本轮是否已结束。结束时必须立即落盘（不受节流限制），
     *                 否则最后一次内容会丢。
     */
    fun persistSessionStreaming(context: Context, session: Session, complete: Boolean) {
        if (complete) {
            // 收尾：立即落盘，并清掉节流计时，让下一轮重新开始
            lastPersistAt.remove(session.id)
            persistSessionAsync(context, session)
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastPersistAt[session.id] ?: 0L
        if (now - last >= STREAM_PERSIST_THROTTLE_MS) {
            lastPersistAt[session.id] = now
            persistSessionAsync(context, session)
        }
        // 未到间隔：只更新内存（pendingPersist 在 persistSessionAsync 里才更新，
        // 这里必须自己放一份，保证切后台时 flush 拿到的是最新内容）
        else {
            pendingPersist[session.id] = session
        }
    }

    private fun persistSessionSync(context: Context, session: Session) {
        val json = JSONObject().put("id", session.id).put("title", session.title)
        if (session.mode != "chat") json.put("mode", session.mode)
        val messages = JSONArray()
        session.messages.forEach { m ->
            messages.put(messageToJson(m))
        }
        json.put("messages", messages)
        val ids = readSessionIndex(context)
        ids.add(session.id)
        writeSessionIndex(context, ids)
        prefs(context).edit().putString(sessionKey(session.id), json.toString()).apply()
    }

    /**
     * 单条消息 → JSON。
     *
     * ⚠ 2026-11-04 抽成独立函数：消息分支（alternatives）要递归复用同一套序列化，
     * 否则本体和分支的字段容易写不一致。分支里不再嵌套 alternatives（只有一层）。
     *
     * ⚠ 2026-11-08 可见性改为 internal（原 private）：Room 桥接层
     * SessionPersistence 要按「一条消息一个节点」逐条调用它序列化 ——
     * 这正是修复 OOM 的关键（不再整段会话一次性 toString）。
     */
    internal fun messageToJson(m: Message, includeAlternatives: Boolean = true): JSONObject {
        val msg = JSONObject().put("id", m.id).put("role", m.role).put("content", m.content)
        if (m.kind == "tool") {
            msg.put("kind", "tool").put("toolName", m.toolName).put("toolArguments", m.toolArguments).put("toolResult", m.toolResult)
        } else if (m.kind == "toolintent") {
            msg.put("kind", "toolintent")
        } else if (m.kind == "toolgroup") {
            msg.put("kind", "toolgroup")
        } else if (m.kind == "assistant") {
            msg.put("kind", "assistant")
        } else if (m.kind == "snapshot") {
            msg.put("kind", "snapshot")
        }
        if (m.parts.isNotEmpty()) {
            val arr = JSONArray()
            m.parts.forEach { p ->
                arr.put(JSONObject().apply {
                    put("type", p.type)
                    if (p.text.isNotEmpty()) put("text", p.text)
                    if (p.toolCallId.isNotEmpty()) put("toolCallId", p.toolCallId)
                    if (p.toolName.isNotEmpty()) put("toolName", p.toolName)
                    if (p.toolArguments.isNotEmpty()) put("toolArguments", p.toolArguments)
                    if (p.toolResult.isNotEmpty()) put("toolResult", p.toolResult)
                    if (p.addedLines > 0) put("addedLines", p.addedLines)
                    if (p.removedLines > 0) put("removedLines", p.removedLines)
                    if (p.running) put("running", true)
                })
            }
            msg.put("parts", arr)
        }
        if (m.stats.isNotBlank()) msg.put("stats", m.stats)
        if (m.thinking.isNotBlank()) msg.put("thinking", m.thinking)
        // 消息分支：只有本体需要带，分支内部不再递归
        if (includeAlternatives && m.alternatives.isNotEmpty()) {
            val alts = JSONArray()
            m.alternatives.forEach { alt -> alts.put(messageToJson(alt, includeAlternatives = false)) }
            msg.put("alternatives", alts)
            msg.put("selectIndex", m.selectIndex)
        }
        return msg
    }

    // ---------- 显示窗口分页（移植 Operit ChatDisplayWindowPaging）----------
    //
    // Operit 不按"固定条数"翻页，而是按「用户消息」切页：每出现
    // DISPLAY_PAGE_TRIGGER_COUNT 个 user 消息就结束一页（summary 消息也强制切页）。
    // 好处是任何一页的起点都落在一次完整提问上，往上翻不会把一问一答劈开，
    // 而且 AI 一轮里插多少工具步骤都不影响页边界。
    const val DISPLAY_PAGE_TRIGGER_COUNT = 5
    /** 内存里最多同时保留几页。超出的老页直接丢掉，翻回去时重新切。 */
    const val MAX_DISPLAY_PAGE_COUNT = 2

    private fun isDisplayPageTrigger(m: Message): Boolean = m.role == "user"

    /**
     * 从最新往回数，返回「最新 pageCount 页」的起始下标（含）。
     * 消息为空返回 0；不足 pageCount 页时返回 0（即整段）。
     */
    fun windowStartForNewestPages(
        messages: List<Message>,
        pageCount: Int = MAX_DISPLAY_PAGE_COUNT,
    ): Int {
        if (messages.isEmpty()) return 0
        val safePages = pageCount.coerceAtLeast(1)
        var cursor = messages.lastIndex
        var pages = 0
        while (cursor >= 0 && pages < safePages) {
            var triggerInPage = 0
            var pageClosed = false
            while (cursor >= 0 && !pageClosed) {
                if (isDisplayPageTrigger(messages[cursor])) {
                    triggerInPage += 1
                    if (triggerInPage >= DISPLAY_PAGE_TRIGGER_COUNT) {
                        pageClosed = true
                        cursor -= 1          // 本页起点是 cursor，翻页后 cursor 退一格
                    }
                }
                if (!pageClosed) cursor -= 1
            }
            if (!pageClosed) cursor = -1     // 走到头了，这一页就是第一页
            pages += 1
        }
        return (cursor + 1).coerceIn(0, messages.size)
    }

    /**
     * 在 [beforeIndex] 之前再切一页，返回新的窗口起点。
     * beforeIndex <= 0 时返回 0（没有更早的了）。
     */
    fun olderWindowStart(messages: List<Message>, beforeIndex: Int): Int {
        if (beforeIndex <= 0) return 0
        val end = beforeIndex.coerceAtMost(messages.size)
        return windowStartForNewestPages(messages.subList(0, end), 1)
    }




    private inline fun <T> withSessionLock(sessionId: String, block: () -> T): T {
        val lock = sessionLocks.computeIfAbsent(sessionId) { Any() }
        return synchronized(lock, block)
    }
    private const val SESSIONS = "sessions"
    private const val API_URL = "api_url"
    private const val API_KEY = "api_key"
    private const val MODEL = "model"
    private const val REASONING = "reasoning_level"
    private const val REASONING_CONTEXT = "reasoning_in_context"
    /** 多子智能体代理协调：开启后主 agent 才能看到 subagent_run / subagent_parallel。 */
    private const val SUB_AGENTS = "sub_agents_enabled"
    /** 多子智能体代理协调（默认关闭）。
     *  开启后 ToolRegistry 才会注册 subagent_run / subagent_parallel 两个工具，
     *  主 agent 才能把任务拆给子代理并行跑；关闭时这两个工具对模型完全不可见。 */
    fun subAgentsEnabled(context: Context): Boolean = prefs(context).getBoolean(SUB_AGENTS, false)
    fun saveSubAgentsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SUB_AGENTS, enabled).apply()
    }

    // ⚠ 2026-11-04 已删除「流式生成触感反馈」开关与实现。
    // 该功能原本默认开启且没有任何界面入口，导致每轮思考/正文更新都在震动，
    // 用户只能忍受。实现已从 AgentChatMessages 侧整块移除，这里同步清掉死代码。

    // Legacy completion-marker strings retained for reading existing chat history only.
    private const val SESSION_PREFIX = "session_"
    private const val SESSION_INDEX = "session_index"
    private const val SESSION_WORKSPACE_PREFIX = "session_workspace_"
    private const val SESSION_ACTIVE_PREFIX = "session_active_"

    /** Last-activity timestamp: refreshed on open and on every message write.
     *  Sorting by it keeps "most recently opened" at the expected position. */
    fun touchSession(context: Context, sessionId: String) {
        prefs(context).edit().putLong(SESSION_ACTIVE_PREFIX + sessionId, System.currentTimeMillis()).apply()
    }

    private fun activeAt(context: Context, sessionId: String): Long =
        prefs(context).getLong(SESSION_ACTIVE_PREFIX + sessionId, sessionId.toLongOrNull() ?: 0L)

    private fun sessionKey(id: String) = "$SESSION_PREFIX$id"

    private fun readSessionIndex(context: Context): MutableSet<String> =
        prefs(context).getStringSet(SESSION_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun writeSessionIndex(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(SESSION_INDEX, ids).apply()
    }
    /** Persisted thinking-strength selection (bulb levels). */
    fun reasoningLevel(context: Context): String = prefs(context).getString(REASONING, "AUTO") ?: "AUTO"

    fun saveReasoningLevel(context: Context, level: String) {
        prefs(context).edit().putString(REASONING, level).apply()
    }

    /** Whether the AI's own reasoning (thinking) text is fed back into the
     *  conversation context on follow-up requests. Purely an API behavior;
     *  persistence of reasoning is always on. */
    fun reasoningInContext(context: Context): Boolean = prefs(context).getBoolean(REASONING_CONTEXT, true)
    fun saveReasoningInContext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(REASONING_CONTEXT, enabled).apply()
    }

    fun workspaceDir(context: Context, sessionId: String): File =
        ChatFolders.directory(context, ChatFolders.workspaceKey(context, sessionId))

    fun ensureWorkspace(context: Context, sessionId: String): File {
        val dir = workspaceDir(context, sessionId)
        dir.mkdirs()
        return dir
    }

    fun workspacePath(context: Context, sessionId: String): String =
        workspaceDir(context, sessionId).absolutePath

    fun saveWorkspacePath(context: Context, sessionId: String, path: String) {
        prefs(context).edit().putString(SESSION_WORKSPACE_PREFIX + sessionId, path).apply()
    }

    fun loadWorkspacePath(context: Context, sessionId: String): String = workspacePath(context, sessionId)

    fun setCurrentWorkspace(context: Context, sessionId: String): File {
        val dir = ensureWorkspace(context, sessionId)
        saveWorkspacePath(context, sessionId, dir.absolutePath)
        touchSession(context, sessionId)
        return dir
    }


    /**
     * One ordered part of an assistant turn — port of RikkaHub's UIMessagePart.
     *  "text"      — visible answer text
     *  "reasoning" — model thinking (rendered as its own collapsible block)
     *  "tool"      — one tool call + its result
     *
     * A single assistant message owns an ORDERED list of these, so text and
     * thinking interleave exactly as they arrived and rounds are not flattened
     * into one blob.
     */
    data class Part(
        val type: String,
        val text: String = "",
        val toolCallId: String = "",
        val toolName: String = "",
        val toolArguments: String = "",
        val toolResult: String = "",
        val addedLines: Int = 0,
        val removedLines: Int = 0,
        val running: Boolean = false
    )

    /** Structured chat item.
     *  kind: "text"  — ordinary user/assistant bubble (role + content)
     *        "tool"  — one persisted tool call (name + arguments + result)
     *        "assistant" — parts-based assistant turn (text/reasoning/tool ordered)
     *  tool messages keep role="tool" so the LLM conversation history stays valid. */
    data class Message(
        val role: String,
        val content: String,
        val kind: String = "text",
        val toolName: String = "",
        val toolArguments: String = "",
        val toolResult: String = "",
        val stats: String = "",
        val thinking: String = "",
        val parts: List<Part> = emptyList(),
        val id: String = java.util.UUID.randomUUID().toString(),
        /**
         * 消息分支（照抄 RikkaHub `MessageNode.messages` + `selectIndex`）。
         *
         * 原版的结构是「一个 node 持有多条同位置的消息，selectIndex 指向当前显示哪条」。
         * 我们这里是扁平 List，直接照搬会牵动整个会话回放与分页逻辑，
         * 所以用等价的最小改造：**主消息本体就是当前选中的分支**，
         * 其余分支以快照形式存在 [alternatives] 里，[selectIndex] 指向合并视图中的位置。
         *
         * 合并视图 = alternatives + 本体，下标 selectIndex 落在其中。
         * 这样"当前内容"永远是本体（渲染、落盘、发给模型都只用本体），
         * 切换分支只是把本体和 alternatives 里的元素互换 —— 语义与线程安全都最简单。
         *
         * 空表 = 只有一条消息（未产生分支），此时 selectIndex 恒为 0。
         */
        val alternatives: List<Message> = emptyList(),
        /** 当前选中分支在「alternatives + 本体」合并视图中的下标。 */
        val selectIndex: Int = 0,
    ) {
        var viewTag: Int = -1
    }
    data class Session(val id: String, val title: String, val messages: List<Message>, val mode: String = "chat")

    private const val CONTEXT_COMPRESSION_PREFIX = "context_compression_"

    data class ContextCompression(
        val summary: String,
        val keepRecentMessages: Int,
        val sourceMessageCount: Int,
        val createdAt: Long,
    )

    /** Request-time context summary. The original message list is untouched. */
    fun loadContextCompression(context: Context, sessionId: String): ContextCompression? =
        runCatching {
            val raw = prefs(context).getString(CONTEXT_COMPRESSION_PREFIX + sessionId, null) ?: return null
            val json = JSONObject(raw)
            ContextCompression(
                summary = json.optString("summary"),
                keepRecentMessages = json.optInt("keepRecentMessages", 32),
                sourceMessageCount = json.optInt("sourceMessageCount", 0),
                createdAt = json.optLong("createdAt", 0L),
            ).takeIf { it.summary.isNotBlank() }
        }.getOrNull()

    fun saveContextCompression(context: Context, sessionId: String, value: ContextCompression) {
        prefs(context).edit().putString(
            CONTEXT_COMPRESSION_PREFIX + sessionId,
            JSONObject()
                .put("summary", value.summary)
                .put("keepRecentMessages", value.keepRecentMessages)
                .put("sourceMessageCount", value.sourceMessageCount)
                .put("createdAt", value.createdAt)
                .toString(),
        ).apply()
    }

    fun clearContextCompression(context: Context, sessionId: String) {
        prefs(context).edit().remove(CONTEXT_COMPRESSION_PREFIX + sessionId).apply()
    }

    /** Session modes:
     *  "chat"    — ordinary assistant conversation (current behavior)
     *  "apk"     — project mode: dedicated APK/toolchain customization session.
     *  "web"     — web project: workspace seeded with index.html.
     *  "video"   — video project: workspace seeded with the video project base.
     *  "reverse" — reverse-engineering mode: an installed APK is unpacked into
     *              the workspace for analysis with the reverse toolchain. */

    /** Operit ModelConfigManager.createConfig(): new profiles REQUIRE a name. */
    fun createProfile(context: Context, name: String): String {
        val id = "p_${System.currentTimeMillis()}"
        val profiles = apiProfiles(context).toMutableList()
        profiles.add(JSONObject().put("id", id).put("name", name).put("url", "").put("key", "").put("model", ""))
        saveApiProfiles(context, profiles)
        return id
    }

    /** Operit semantics: the built-in default profile can NEVER be deleted. */
    fun deleteProfile(context: Context, id: String): Boolean {
        // ⚠ 2026-11-10：只剩 hub 一个内置渠道，它不可删
        if (id == "hub") return false
        val profiles = apiProfiles(context).toMutableList()
        if (profiles.removeAll { it.optString("id") == id }.not()) return false
        saveApiProfiles(context, profiles)
        if (activeProfileId(context) == id) setActiveProfileId(context, "hub")
        return true
    }

    fun updateProfile(context: Context, id: String, name: String, url: String, key: String, model: String) {
        val profiles = apiProfiles(context).toMutableList()
        val idx = profiles.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx]
                .put("name", name.trim())
                .put("url", url.trim())
                .put("key", key.trim())
                .put("model", model.trim())
            saveApiProfiles(context, profiles)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- API profiles (Operit-style multi-config) ----
    // storage shape in one pref string: JSON array of
    // {id, name, url, key, model}. Index 0 order = creation order.
    private const val API_PROFILES = "api_profiles"
    private const val API_ACTIVE_ID = "api_active_id"

    // ⚠ 2026-11-10 按店主决定：**下架全部内置免费模型**。
    //
    // 删掉的三个配置（连同硬编码的第三方 key 一起删，避免继续泄露在 APK 里）：
    //   default  → example.com（免费转发）
    //   default2 → open.bigmodel.cn（智谱试用）
    //   sponsor  → open.bigmodel.cn（赞助额度）
    //
    // 现在只剩「官方模型」（hub，kami 付费转发，按 kami_wallet 余额计费）。
    // 老用户存量的这三个 id 会在 apiProfiles() 里被丢弃，并自动切到 hub。

    // ---------- 官方模型（kami apihub 付费转发） ----------
    // URL 固定指向自建 API 转发网关；key = 登录用户的 users 表 token（动态取，
    // 不落盘到 profile JSON）。计费走 kami_wallet 余额，模型列表从服务端拉。
    const val HUB_BASE = "https://example.com"
    // ⚠ 2026-11-10：收款码/选购页换成主人给的新地址（小马）
    const val HUB_SHOP_URL = "https://example.com"

    fun hubProfile(context: Context): JSONObject {
        val stored = prefs(context).getString("hub_model", "") ?: ""
        return JSONObject()
            .put("id", "hub")
            .put("name", "官方模型")
            .put("url", HUB_BASE)
            .put("key", "@@USER_TOKEN@@")   // 占位符，api() 取值时替换为真实 token
            .put("model", stored)
            .put("builtin", true)
            .put("hub", true)
    }

    fun saveHubModel(context: Context, model: String) {
        prefs(context).edit().putString("hub_model", model.trim()).apply()
    }

    // ---------- 模型列表本地缓存 ----------
    // 为什么要有：切换配置是纯本地动作，不该被网络绑架。在线拉到的列表落盘缓存，
    // 下次选模型直接用缓存，断网/网关抽风也能正常挑模型。
    private const val MODEL_CACHE_PREFIX = "model_cache_"

    /** 读取某配置上次成功拉到的模型列表（无缓存返回空表）。 */
    fun cachedModels(context: Context, profileId: String): List<String> {
        val raw = prefs(context).getString(MODEL_CACHE_PREFIX + profileId, null) ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    /** 缓存某配置的模型列表（空表不覆盖，避免一次失败把好数据抹掉）。 */
    fun saveCachedModels(context: Context, profileId: String, models: List<String>) {
        if (profileId.isBlank() || models.isEmpty()) return
        val arr = org.json.JSONArray()
        models.forEach { if (it.isNotBlank()) arr.put(it.trim()) }
        prefs(context).edit().putString(MODEL_CACHE_PREFIX + profileId, arr.toString()).apply()
    }

    /** 同步拉取 OpenAI 兼容 /models 列表（调用方自行放子线程）。
     *  失败一律返回空表、不抛异常 —— 让上层决定要不要提示。 */
    fun fetchModelsFrom(baseUrl: String, key: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || key.isBlank()) return emptyList()
        return runCatching {
            val c = (java.net.URL("$base/models").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }
            if (c.responseCode !in 200..299) return emptyList()
            val data = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data")
            buildList {
                if (data != null) for (k in 0 until data.length()) {
                    data.optJSONObject(k)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 官方模型可用模型列表（拉服务端 /models，失败返回空 → UI 显示拉取失败） */
    fun fetchHubModels(context: Context, token: String, onDone: (List<String>, String?) -> Unit) {
        Thread {
            val result: Pair<List<String>, String?> = runCatching {
                val c = (java.net.URL("$HUB_BASE/models").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                if (c.responseCode !in 200..299) {
                    val msg = runCatching { org.json.JSONObject(c.errorStream.bufferedReader().use { it.readText() }).optString("msg") }.getOrNull()
                    error(msg ?: "HTTP ${c.responseCode}")
                }
                // 响应可能是 {code:200,data:{data:[...]}}（kami 网关格式）或 OpenAI 裸格式 {data:[...]}
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val dataArray = json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data")
                val list = mutableListOf<String>()
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val o = dataArray.optJSONObject(i)
                        val name = o?.optString("id") ?: dataArray.optString(i)
                        if (name.isNotBlank()) list.add(name)
                    }
                }
                list
            }.fold(
                onSuccess = { models -> models to null },
                onFailure = { emptyList<String>() to (it.message ?: "网络异常") }
            )
            android.os.Handler(context.mainLooper).post { onDone(result.first, result.second) }
        }.start()
    }

    fun apiProfiles(context: Context): List<JSONObject> {
        val raw = prefs(context).getString(API_PROFILES, null)
        // ⚠ 2026-11-10：不再种入任何免费配置，arr 为 null 时下面会只补一个 hub
        val arr = runCatching { raw?.let { org.json.JSONArray(it) } }.getOrNull()
        val out = mutableListOf<JSONObject>()
        var hasDefault = false
        var hasDefault2 = false
        var hasSponsor = false
        var hasHub = false
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("id")) {
                // ⚠ 2026-11-10：这三个免费渠道已下架，存量数据静默丢弃
                "default", "default2", "sponsor" -> Unit
                "hub" -> {
                    hasHub = true
                    out.add(hubProfile(context).put("model", o.optString("model")))
                }
                else -> out.add(o)
            }
        }
        // ⚠ 2026-11-10：只保证 hub（官方付费模型）存在，且永远排在第一位 ——
        //   新用户装完就是它，不用手动切。
        if (!hasHub) out.add(0, hubProfile(context))
        // 兜底：万一 arr 里有 hub 但位置不在0，挪到最前
        if (out.isNotEmpty() && out[0].optString("id") != "hub") {
            val hubIdx = out.indexOfFirst { it.optString("id") == "hub" }
            if (hubIdx > 0) out.add(0, out.removeAt(hubIdx))
        }
        return out
    }

    fun saveApiProfiles(context: Context, profiles: List<JSONObject>) {
        // The default profile must PERSIST too (its model is user-editable);
        // without this, updates to it were silently reverted on next read.
        val arr = org.json.JSONArray()
        profiles.forEach { arr.put(it) }
        prefs(context).edit().putString(API_PROFILES, arr.toString()).apply()
    }

    fun activeProfileId(context: Context): String {
        val stored = prefs(context).getString(API_ACTIVE_ID, "hub") ?: "hub"
        // ⚠ 2026-11-10：老用户可能还停在已下架的免费渠道上，这里统一纠正到 hub
        if (stored == "default" || stored == "default2" || stored == "sponsor") return "hub"
        return stored
    }

    fun setActiveProfileId(context: Context, id: String) {
        prefs(context).edit().putString(API_ACTIVE_ID, id).apply()
    }

    fun activeProfile(context: Context): JSONObject {
        val id = activeProfileId(context)
        return apiProfiles(context).firstOrNull { it.optString("id") == id } ?: apiProfiles(context).first()
    }

    /** Legacy single-config API still works — now routed through the active profile.
     *  官方模型(hub) profile 的 key 是 @@USER_TOKEN@@ 占位符，这里替换成登录用户
     *  的真实 users 表 token（服务端按它鉴权 + 从 kami_wallet 扣费）。 */
    fun api(context: Context): Triple<String, String, String> {
        val p = activeProfile(context)
        val key = if (p.optString("key") == "@@USER_TOKEN@@") com.agentpad.workspace.data.UserStore.token(context) else p.optString("key")
        return Triple(p.optString("url"), key, p.optString("model"))
    }

    fun saveApi(context: Context, url: String, key: String, model: String) {
        // Writes into the ACTIVE profile (new profiles are edited in the
        // profile editor, this legacy path only exists for the default one).
        val id = activeProfileId(context)
        val profiles = apiProfiles(context).toMutableList()
        val idx = profiles.indexOfFirst { it.optString("id") == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx].put("url", url.trim()).put("key", key.trim()).put("model", model.trim())
            saveApiProfiles(context, profiles)
        }
    }

    @Synchronized
    fun createSession(context: Context, title: String = "新对话", mode: String = "chat", folderId: String? = null): Session {
        // Keep numeric IDs for older migration readers, while avoiding same-millisecond collisions.
        var id = System.currentTimeMillis()
        val ids = readSessionIndex(context)
        while (id.toString() in ids || memorySessions.containsKey(id.toString())) id++
        val session = Session(id.toString(), title, emptyList(), mode)
        if (folderId != null) ChatFolders.move(context, session.id, folderId)
        val normalized = normalizeLegacyModeTitle(context, session)
        saveSession(context, normalized)
        return normalized
    }

    /** 删除会话：清 session JSON、索引、活跃时间戳、工作区目录映射键 */
    fun deleteSession(context: Context, id: String) {
        ChatFolders.forgetSession(context, id)
        memorySessions.remove(id)
        val p = prefs(context)
        p.edit().remove(sessionKey(id))
            .remove(SESSION_ACTIVE_PREFIX + id)
            .remove(SESSION_WORKSPACE_PREFIX + id)
            // 标题自动命名相关的两个标记也要清，否则同 id 复用时会误判「已命名」。
            .remove(TITLE_LOCKED_PREFIX + id)
            .remove(TITLE_AUTOGEN_PREFIX + id)
            .apply()
        // 一并清理该会话的自定义提示词与压缩配置，避免偏好无限堆积。
        com.agentpad.workspace.ai.PromptStore.onSessionDeleted(context, id)
        clearContextCompression(context, id)
        val ids = readSessionIndex(context).toMutableSet()
        ids.remove(id)
        writeSessionIndex(context, ids)
        // ⚠ 2026-11-08：Room 那边也要删（conversation + message_node 一起走）。
        // 旧实现只删 SharedPreferences，Room 里会留下孤儿数据。
        SessionPersistence.deleteSession(context, id)
    }

    /** 重命名会话标题 */
    fun renameSession(context: Context, id: String, title: String) = withSessionLock(id) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@withSessionLock
        val s = loadSession(context, id) ?: return@withSessionLock
        saveSession(context, s.copy(title = trimmed))
        // 用户手动改过标题 = 标题已定稿，后续 AI 自动命名不再覆盖。
        markTitleLocked(context, id)
    }

    // ---- AI 自动命名标题 ----
    //
    // 旧逻辑：append() 收第一条用户消息时直接 content.take(28) 当标题。
    // 那样标题只是"用户第一句话的前半截"，上下文不足，不能反映整轮任务。
    //
    // 新逻辑：第 1 轮对话收尾（AI 真正答完）后，让模型读一遍上下文、
    // 生成一个短标题。整个会话只自动生成一次；用户手动改名后永久锁定。

    private const val TITLE_LOCKED_PREFIX = "session_title_locked_"
    private const val TITLE_AUTOGEN_PREFIX = "session_title_done_"

    /** 标题是否已被用户手动改过（锁定后 AI 不得覆盖）。 */
    fun isTitleLocked(context: Context, id: String): Boolean =
        prefs(context).getBoolean(TITLE_LOCKED_PREFIX + id, false)

    /** 标记标题已由用户手动确定。 */
    fun markTitleLocked(context: Context, id: String) {
        prefs(context).edit().putBoolean(TITLE_LOCKED_PREFIX + id, true).apply()
    }

    /** 本会话是否已经做过一次 AI 自动命名（只做一次，避免反复改标题）。 */
    fun isTitleAutoGenerated(context: Context, id: String): Boolean =
        prefs(context).getBoolean(TITLE_AUTOGEN_PREFIX + id, false)

    /** 写入 AI 生成的标题。已被用户锁定时静默忽略。 */
    fun applyAutoTitle(context: Context, id: String, title: String): Boolean {
        val trimmed = title.trim()
            .replace(Regex("^[\\s\"'“”‘’《》「」]+"), "")
            .replace(Regex("[\\s\"'“”‘’《》「」]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return false
        if (isTitleLocked(context, id)) return false
        // 对齐 RikkaHub：不做内容判定，只做长度兜底（提示词已要求 ≤10 字）。
        val safe = if (trimmed.length > 20) trimmed.take(20) else trimmed
        renameSessionInternal(context, id, safe)
        prefs(context).edit().putBoolean(TITLE_AUTOGEN_PREFIX + id, true).apply()
        return true
    }

    /** 内部改名：不触发标题锁定（供 AI 自动命名、逆向会话自动取名使用）。 */
    fun renameSessionInternal(context: Context, id: String, title: String) = withSessionLock(id) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@withSessionLock
        val s = loadSession(context, id) ?: return@withSessionLock
        saveSession(context, s.copy(title = trimmed))
    }

    /** Old mode labels are placeholders, not conversation subjects. */
    private fun normalizeLegacyModeTitle(context: Context, session: Session): Session {
        if (isTitleLocked(context, session.id)) return session
        val title = session.title.trim()
        val legacy = when (session.mode) {
            "apk" -> title in setOf("项目会话", "APK 项目", "APK项目", "项目模式", "项目模式（APK）")
            "kt" -> title in setOf("Kotlin 项目", "Kotlin项目", "KT 项目", "KT项目", "Kt项目", "kt项目")
            "web" -> title in setOf("网页项目", "网页会话")
            "video" -> title in setOf("视频项目", "视频会话")
            "reverse" -> title in setOf("逆向会话", "逆向模式", "逆向项目") || title.startsWith("逆向 · ")
            else -> false
        }
        if (!legacy) return session
        val normalized = session.copy(title = "新对话")
        prefs(context).edit().remove(TITLE_AUTOGEN_PREFIX + session.id).apply()
        saveSession(context, normalized)
        return normalized
    }

    fun loadSession(context: Context, id: String): Session? {
        // 内存命中直接返回（无锁读）：流式刷新每帧都走这里，绝不能碰磁盘锁。
        memorySessions[id]?.let { return it }
        return withSessionLock(id) {
            // 双检：可能别的线程刚写入内存。
            memorySessions[id]?.let { return@withSessionLock it }

            // ⚠ 2026-11-08 迁到 Room：会话从 message_node 逐节点还原。
            // 旧实现是 prefs.getString(sessionKey) + parseSession(整段 JSON)，
            // 单个会话字符串就可能几十 MB，解析时堆里同时存在
            // 「原始 String + JSONObject 树 + 最后的 Message 列表」三份，直接 OOM。
            // 现在逐行读、逐行解析，峰值只跟单条消息有关。
            migrateLegacyIfNeeded(context)

            val fromShard = runCatching { SessionPersistence.readSession(context, id) }.getOrNull()
            val result = fromShard?.let { parsed ->
                // 附件消息压缩（沿用旧逻辑）：base64 大图不进内存历史
                val compact = parsed.messages.any { it.content.startsWith("ATTACHMENT_JSON:") }
                if (compact) {
                    val cleaned = parsed.copy(messages = parsed.messages.map { message ->
                        if (!message.content.startsWith("ATTACHMENT_JSON:")) message
                        else message.copy(content = attachmentSummary(message.content))
                    })
                    saveSession(context, Session(cleaned.id, cleaned.title, cleaned.messages, cleaned.mode))
                    Session(cleaned.id, cleaned.title, cleaned.messages, cleaned.mode)
                } else Session(parsed.id, parsed.title, parsed.messages, parsed.mode)
            } ?: run {
                // Room 里没有 → 回退读旧 SharedPreferences（迁移期/未被搬运的历史会话）
                val raw = prefs(context).getString(sessionKey(id), null) ?: return@withSessionLock null
                runCatching {
                    val parsed = parseSession(raw)
                    val compact = parsed.messages.any { it.content.startsWith("ATTACHMENT_JSON:") }
                    val cleaned = if (compact) parsed.copy(messages = parsed.messages.map { message ->
                        if (!message.content.startsWith("ATTACHMENT_JSON:")) message
                        else message.copy(content = attachmentSummary(message.content))
                    }) else parsed
                    // 搬到 Room，下次直接走快路径
                    saveSession(context, cleaned)
                    cleaned
                }.getOrNull()
            }
            val normalized = result?.let { normalizeLegacyModeTitle(context, it) }
            if (normalized != null) memorySessions[id] = normalized
            normalized
        }
    }

    /**
     * 流式 chunk 专用写入：只更新内存（主线程/任意线程都能瞬间读到），
     * 磁盘写入丢到后台线程，绝不阻塞 UI。与 [setAssistantParts] 语义一致，
     * 区别只是落盘异步。
     */
    fun setAssistantPartsCached(context: Context, sessionId: String, messageId: String, parts: List<Part>, stats: String = "", complete: Boolean = true) {
        val current = memorySessions[sessionId] ?: run {
            // 缓存未命中（进程刚启动、还没 loadSession）：走完整落盘路径。
            setAssistantParts(context, sessionId, messageId, parts, stats, complete)
            return
        }
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) {
            // 消息不存在也走完整路径（内部会重新 loadSession 再判断一次）。
            setAssistantParts(context, sessionId, messageId, parts, stats, complete)
            return
        }
        val text = parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }.trim()
        val think = parts.filter { it.type == "reasoning" }.joinToString("\n\n") { it.text }.trim()
        val messages = current.messages.toMutableList()
        val prev = messages[index]
        messages[index] = prev.copy(
            content = text,
            thinking = think,
            parts = parts,
            stats = stats.ifBlank { prev.stats },
            kind = if (complete) "assistant" else "snapshot"
        )
        val updated = current.copy(messages = messages)
        memorySessions[sessionId] = updated
        // ⚠ 2026-11-04：改走节流落盘。
        // 这是「切后台回来卡死」的直接执行点 —— 思考阶段每个 chunk 都到这里，
        // 每次都把整份 session JSON 序列化后 apply()，QueuedWork 队列一路堆积，
        // 切后台时 onPause 的 waitToFinish() 在主线程同步等待它们全部写完 → 卡死。
        // 节流后队列长度恒定 O(1)，complete = true 时仍立即落盘。
        persistSessionStreaming(context, updated, complete)
    }

    /** 直接拿消息列表（同样走内存缓存）。 */
    fun loadMessages(context: Context, id: String): List<Message> =
        loadSession(context, id)?.messages ?: emptyList()

    /** 只取显示窗口内的消息 —— UI 只渲染这一段，避免整段重排。 */
    fun loadWindowMessages(context: Context, id: String, startIndex: Int): List<Message> {
        val all = loadMessages(context, id)
        if (all.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(0, all.size)
        return all.subList(start, all.size)
    }

    fun all(context: Context): List<Session> {
        migrateLegacyIfNeeded(context)
        // ⚠ 2026-11-09：会话索引优先从分片目录的 meta.json 取（按 updateAt 升序），
        // 仍保留旧 prefs 索引作为兜底（首启迁移期）。
        val shardIds = runCatching { SessionPersistence.listSessionIds(context) }.getOrNull()
        val ids = if (!shardIds.isNullOrEmpty()) shardIds.toSet() else readSessionIndex(context)
        if (ids.isEmpty()) return emptyList()
        // Ascending by last-active: call sites rely on list order semantics —
        // lastOrNull() = most recent (home card), historyPage().reversed() puts
        // the newest first. Sessions never touched fall back to creation id.
        return ids.mapNotNull { loadSession(context, it) }
            .sortedBy { activeAt(context, it.id) }
    }

    /**
     * One-time migration: sessions persisted by the old build live in a single
     * "sessions" JSON blob. Split them into per-session keys ("session_<id>")
     * and build the index, so the app keeps every existing conversation.
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("sessions_migrated_v2", false)) return
        val raw = p.getString(SESSIONS, null)
        if (raw != null && readSessionIndex(context).isEmpty()) {
            runCatching {
                val array = JSONArray(raw)
                val ids = readSessionIndex(context)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    p.edit().putString(sessionKey(id), item.toString()).apply()
                    ids.add(id)
                }
                if (ids.isNotEmpty()) writeSessionIndex(context, ids)
            }
        }
        p.edit().putBoolean("sessions_migrated_v2", true).apply()
    }

    /**
     * 一次性把 SharedPreferences 里的历史会话搬进 Room。
     *
     * ⚠ 2026-11-08 新增。用户升级后不能丢会话，所以：
     *   1) 遍历旧 session_index 里的每个 id
     *   2) 逐个 loadSession（会自动读旧 key 并 saveSession → 落 Room）
     *   3) 标记 migrated，之后不再扫
     *
     * 放在后台线程做，避免首屏被大量历史会话拖住。
     * 单个会话失败不影响其它（runCatching 包住）。
     */
    fun migratePrefsToRoom(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("room_migrated_v1", false)) return
        val appContext = context.applicationContext
        Thread({
            runCatching {
                val ids = readSessionIndex(appContext)
                var ok = 0
                ids.forEach { id ->
                    runCatching {
                        // loadSession 在 Room 未命中时会回落读 prefs，并 saveSession 搬进 Room
                        if (loadSession(appContext, id) != null) ok++
                    }
                }
                // 迁移完成才置标记：中途进程被杀，下次会重跑（幂等，不会重复写）
                p.edit().putBoolean("room_migrated_v1", true)
                    .putInt("room_migrated_count", ok)
                    .apply()
            }
        }, "chat-room-migrate").start()
    }

    fun rewindFromUser(context: Context, id: String, content: String): Boolean = withSessionLock(id) {
        val current = loadSession(context, id) ?: return@withSessionLock false
        val index = current.messages.indexOfLast { it.role == "user" && it.content == content }
        if (index < 0) return@withSessionLock false
        saveSession(context, current.copy(messages = current.messages.take(index)))
        true
    }

    /** Keep messages strictly before [index] — used by edit-and-resend / fork. */
    fun rewindToIndex(context: Context, id: String, index: Int): Boolean = withSessionLock(id) {
        val current = loadSession(context, id) ?: return@withSessionLock false
        if (index < 0 || index > current.messages.size) return@withSessionLock false
        saveSession(context, current.copy(messages = current.messages.take(index)))
        true
    }

    fun append(context: Context, id: String, role: String, content: String) = withSessionLock(id) {
        touchSession(context, id)
        val current = loadSession(context, id) ?: Session(id, "新对话", emptyList())
        // ⚠ 标题不再用「首条用户消息的前 28 字」硬截。
        // 第 1 轮对话收尾后由 AI 读上下文生成（见 ChatActivity.maybeAutoTitle）。
        // 这里只在标题仍是占位时才保留原值，不做任何改写。
        saveSession(context, current.copy(messages = current.messages + Message(role, content)))
    }

    /** Persist the assistant message that requested a set of tools (so later API
     * replay keeps a valid assistant → tool ordering). content = marker json. */
    fun appendAssistantTools(context: Context, id: String, markerJson: String, thinking: String = "") = withSessionLock(id) {
        touchSession(context, id)
        val current = loadSession(context, id) ?: return@withSessionLock
        val message = Message(role = "assistant", content = markerJson, kind = "toolintent", thinking = thinking)
        saveSession(context, current.copy(messages = current.messages + message))
    }

    fun appendAssistantMessage(context: Context, sessionId: String, content: String, thinking: String = "") = withSessionLock(sessionId) {
        touchSession(context, sessionId)
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        saveSession(context, current.copy(messages = current.messages + Message("assistant", content, thinking = thinking)))
    }

    /** Persist one completed tool call. content holds the raw textual tool output,
     * structured fields keep name/arguments for faithful re-render. */
    fun appendTool(context: Context, id: String, callId: String, name: String, arguments: String, result: String) = withSessionLock(id) {
        val current = loadSession(context, id) ?: return@withSessionLock
        val message = Message(
            role = "tool",
            content = result,
            id = callId,
            kind = "tool",
            toolName = name,
            toolArguments = arguments,
            toolResult = result
        )
        saveSession(context, current.copy(messages = current.messages + message))
    }

    /** Persist a completed batch of tool calls (same agent round) as one group.
     * content holds a JSON array: [{id,name,arguments,result}, ...] */
    fun appendToolGroup(context: Context, sessionId: String, items: List<ToolGroupItem>) = withSessionLock(sessionId) {
        if (items.isEmpty()) return@withSessionLock
        touchSession(context, sessionId)
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name).put("arguments", item.arguments).put("result", item.result))
        }
        saveSession(context, current.copy(messages = current.messages + Message(
            role = "tool",
            content = array.toString(),
            id = items.joinToString("|") { it.id },
            kind = "toolgroup"
        )))
    }

    data class ToolGroupItem(val id: String, val name: String, val arguments: String, val result: String)

    /** Lightweight message locator (Operit-style): index + role + short preview
     *  around the query hit. Never loads full message content into memory. */
    data class LocatorPreview(
        val index: Int,
        val role: String,
        val kind: String,
        val preview: String,
        val contentLength: Int
    )

    fun loadLocatorPreviews(context: Context, sessionId: String, query: String = ""): List<LocatorPreview> {
        val messages = loadSession(context, sessionId)?.messages ?: return emptyList()
        val q = query.trim()
        return messages.mapIndexed { index, m ->
            val text = when (m.kind) {
                "tool" -> "${m.toolName} ${m.toolArguments} ${m.toolResult}"
                "toolgroup" -> m.content
                else -> m.content
            }
            val preview = when {
                q.isBlank() -> text.take(48).replace('\n', ' ')
                else -> {
                    val hit = text.indexOf(q, ignoreCase = true)
                    if (hit < 0) ""
                    else {
                        val start = (hit - 24).coerceAtLeast(0)
                        text.substring(start, (start + 48).coerceAtMost(text.length)).replace('\n', ' ')
                    }
                }
            }
            LocatorPreview(index, m.role, m.kind, preview, text.length)
        }.filter { q.isBlank() || it.preview.isNotBlank() }
    }

    /** Creates and updates assistant messages by stable ID for one request turn. */
    fun addAssistantTurn(context: Context, sessionId: String): Message = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId)
            ?: error("Chat session not found: $sessionId")
        val message = Message(role = "assistant", content = "", kind = "snapshot")
        saveSession(context, current.copy(messages = current.messages + message))
        message
    }

    fun updateAssistantTurn(
        context: Context,
        sessionId: String,
        messageId: String,
        content: String,
        thinking: String? = null,
        stats: String? = null,
        complete: Boolean = false
    ) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) return@withSessionLock
        val messages = current.messages.toMutableList()
        val previous = messages[index]
        // Never clobber a parts-based assistant turn back to a flat "text"
        // message — that would drop the ordered text/reasoning/tool rendering.
        val hasParts = previous.parts.isNotEmpty()
        messages[index] = previous.copy(
            content = content,
            kind = if (hasParts) "assistant" else if (complete) "text" else "snapshot",
            thinking = thinking ?: previous.thinking,
            stats = stats ?: previous.stats
        )
        saveSession(context, current.copy(messages = messages))
    }

    fun removeAssistantTurn(context: Context, sessionId: String, messageId: String) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        saveSession(context, current.copy(messages = current.messages.filterNot { it.id == messageId }))
    }

    /** Marks a turn as mid-flight. The store has no dedicated flag, so the
     *  snapshot/text kind carries it: "snapshot" == still streaming. */
    fun setAssistantStreaming(context: Context, sessionId: String, messageId: String, streaming: Boolean) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) return@withSessionLock
        val messages = current.messages.toMutableList()
        val prev = messages[index]
        messages[index] = prev.copy(
            kind = when {
                streaming -> "snapshot"
                prev.parts.isNotEmpty() -> "assistant"
                else -> "text"
            }
        )
        saveSession(context, current.copy(messages = messages))
    }

    /** Delete a single message by id (Compose message menu). */
    fun deleteMessage(context: Context, sessionId: String, messageId: String) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val remaining = current.messages.filterNot { it.id == messageId }
        if (remaining.size == current.messages.size) return@withSessionLock
        saveSession(context, current.copy(messages = remaining))
    }

    /** Rewrite one message's text in place (edit without re-sending). */
    fun updateMessageContent(context: Context, sessionId: String, messageId: String, content: String) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val index = current.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return@withSessionLock
        val messages = current.messages.toMutableList()
        messages[index] = messages[index].copy(content = content)
        saveSession(context, current.copy(messages = messages))
    }

    // ---------- 消息分支（照抄 RikkaHub MessageNode.messages + selectIndex）----------
    //
    // 我们的存储是扁平 List<Message>，分支采用「本体 = 当前选中分支」的方案：
    //   合并视图 allBranches = alternatives + 本体
    //   切换分支    = 把目标分支与本体互换（只改 List 顺序，字段完全不丢）
    // 这样渲染/落盘/发给模型永远只读本体，不会出现「当前是哪条」的歧义。

    /**
     * 「其它分支 + 本体」的合并视图，**按 [Message.selectIndex] 把本体插回它的位置**。
     *
     * 对应 RikkaHub `MessageNode.messages`：本体 = `messages[selectIndex]`。
     * UI 显示 `selectIndex+1 / size`（ChatMessageBranch.kt:65），
     * 所以本体必须落在 selectIndex 处，序号才与真实位置一致。
     */
    fun branchesOf(message: Message): List<Message> {
        val others = message.alternatives
        val self = message.copy(alternatives = emptyList(), selectIndex = 0)
        val idx = message.selectIndex.coerceIn(0, others.size)
        return others.toMutableList().also { it.add(idx, self) }
    }

    /** 当前选中的分支内容就是本体本身。 */
    fun currentOf(message: Message): Message = message

    /**
     * 切换到第 [target] 个分支。
     *
     * 逐条对照 RikkaHub：
     *   `ConversationMessages.kt:55`  `selectBranch(nodeId, selectIndex) = node.copy(selectIndex = ...)`
     *   —— 默认语义就是**只改一个下标，消息数组原封不动**。
     *
     * 我们的等价做法：把选中分支的内容搬进本体，本体身份（id）保持不变，
     * 因为扁平 List 里一切定位（replaceMessage / 渲染 key）都靠这个 id。
     * 搬完之后 selectIndex 指向本体在新合并视图里的位置。
     */
    fun selectBranch(message: Message, target: Int): Message {
        val view = branchesOf(message)
        if (view.size <= 1) return message
        if (target !in view.indices) return message
        if (target == message.selectIndex) return message
        val selected = view[target]
        // 其余分支原样保留（清掉可能残留的嵌套字段）
        val rest = view.filterIndexed { i, _ -> i != target }
            .map { it.copy(alternatives = emptyList(), selectIndex = 0) }
        // ⚠ id 必须沿用本体的：节点身份不变，只换内容是原版 `messages[selectIndex]` 的等价表达
        return selected.copy(
            id = message.id,
            alternatives = rest,
            selectIndex = target,
        )
    }

    /**
     * 为 [message] 追加一个新分支（重新生成用）。
     *
     * 新分支成为「当前选中」，旧本体退到 alternatives 里 —— 与原版
     * `MessageNode.copy(messages = messages + new, selectIndex = lastIndex)` 一致。
     */
    fun appendBranch(
        message: Message,
        newBranch: Message,
    ): Message {
        val branches = branchesOf(message)
        // 旧本体先按当前状态归档
        val archivedTop = message.copy(alternatives = emptyList(), selectIndex = 0)
        val all = message.alternatives + archivedTop + newBranch
        val newIndex = all.lastIndex
        return newBranch.copy(
            alternatives = all.filterIndexed { i, _ -> i != newIndex },
            selectIndex = newIndex,
        )
    }

    /** 就地替换会话里某条消息（分支操作后落盘）。 */
    fun replaceMessage(context: Context, sessionId: String, updated: Message) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val index = current.messages.indexOfFirst { it.id == updated.id }
        if (index < 0) return@withSessionLock
        val messages = current.messages.toMutableList()
        messages[index] = updated
        saveSession(context, current.copy(messages = messages))
    }

    /** Appends visible intermediate assistant content as its own history item. */

    private fun attachmentSummary(raw: String): String {
        if (!raw.startsWith("ATTACHMENT_JSON:")) return raw.take(20000)
        return runCatching {
            val arr = JSONArray(raw.removePrefix("ATTACHMENT_JSON:"))
            val text = buildString {
                for (i in 0 until arr.length()) {
                    val part = arr.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text" -> append(part.optString("text").replace(Regex("data:image/[^;]+;base64,[^\\s\\]]+"), "[图片]").take(2000))
                        "image_url" -> append("\n[图片附件]")
                    }
                }
            }.trim()
            if (text.isBlank()) "[附件消息已保存，请查看会话工作区附件]" else text
        }.getOrDefault("[附件消息已保存，请查看会话工作区附件]")
    }

    private fun parseSession(raw: String): Session {
        val item = JSONObject(raw)
        val messages = buildList {
            val values = item.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until values.length()) {
                add(jsonToMessage(values.getJSONObject(j)))
            }
        }
        return Session(item.optString("id"), item.optString("title"), messages, item.optString("mode", "chat"))
    }

    /**
     * JSON → 单条消息（含消息分支）。
     *
     * ⚠ 2026-11-04 抽成独立函数，与 [messageToJson] 对称。
     * 分支（alternatives）只解析一层，不再递归，避免恶意/损坏数据无限展开。
     *
     * ⚠ 2026-11-08 可见性改为 internal：Room 桥接层按节点逐条还原消息时要用。
     */
    internal fun jsonToMessage(message: JSONObject, parseAlternatives: Boolean = true): Message {
        val parts = buildList {
            val arr = message.optJSONArray("parts") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val p = arr.optJSONObject(k) ?: continue
                add(
                    Part(
                        type = p.optString("type", "text"),
                        text = p.optString("text"),
                        toolCallId = p.optString("toolCallId"),
                        toolName = p.optString("toolName"),
                        toolArguments = p.optString("toolArguments"),
                        toolResult = p.optString("toolResult"),
                        addedLines = p.optInt("addedLines", 0),
                        removedLines = p.optInt("removedLines", 0),
                        running = p.optBoolean("running", false),
                    )
                )
            }
        }
        val alternatives = if (!parseAlternatives) emptyList() else buildList {
            val alts = message.optJSONArray("alternatives") ?: JSONArray()
            for (i in 0 until alts.length()) {
                val alt = alts.optJSONObject(i) ?: continue
                add(jsonToMessage(alt, parseAlternatives = false))
            }
        }
        return Message(
            role = message.optString("role"),
            content = message.optString("content"),
            id = message.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            kind = message.optString("kind", "text"),
            toolName = message.optString("toolName"),
            toolArguments = message.optString("toolArguments"),
            toolResult = message.optString("toolResult"),
            stats = message.optString("stats"),
            thinking = message.optString("thinking"),
            parts = parts,
            alternatives = alternatives,
            selectIndex = message.optInt("selectIndex", 0),
        )
    }

    /** Replace one assistant turn's ordered parts (RikkaHub-style). */
    fun setAssistantParts(context: Context, sessionId: String, messageId: String, parts: List<Part>, stats: String = "", complete: Boolean = true) = withSessionLock(sessionId) {
        val current = loadSession(context, sessionId) ?: return@withSessionLock
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == "assistant" }
        if (index < 0) return@withSessionLock
        val text = parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }.trim()
        val think = parts.filter { it.type == "reasoning" }.joinToString("\n\n") { it.text }.trim()
        val messages = current.messages.toMutableList()
        val prev = messages[index]
        messages[index] = prev.copy(
            content = text,
            thinking = think,
            parts = parts,
            stats = stats.ifBlank { prev.stats },
            kind = if (complete) "assistant" else "snapshot"
        )
        saveSession(context, current.copy(messages = messages))
    }

    /**
     * 落盘一个会话。
     *
     * ⚠ 2026-11-09：改成「按消息分片落盘」（架构等价于 RikkaHub 的 message_node）。
     *
     * 旧实现（已删除）：
     *   ```kotlin
     *   val messages = JSONArray()
     *   session.messages.forEach { messages.put(messageToJson(it)) }
     *   json.put("messages", messages)
     *   prefs.edit().putString(sessionKey(id), json.toString()).apply()
     *   ```
     *   问题：`json.toString()` 会在堆里生成**整个会话**的完整 JSON 字符串。
     *   一轮工具调用返回几 MB 日志时，光这一个字符串就撞穿 256MB 堆 → OOM。
     *   这正是参考源码同类崩溃的根因。
     *
     * 新实现：
     *   1) 内存缓存照旧先更新（读路径零开销）
     *   2) 交给 SessionPersistence 按 CHUNK_SIZE 条消息分片写入文件
     *      单次序列化峰值 = 32 条消息，与历史总长度解耦 → 结构上不可能 OOM
     *
     * 为什么不用 Room：AGP 9.4 内置 Kotlin + KSP 2.3.x 组合下
     * RoomKspProcessor 报 `[MissingType]`（同模块 Kotlin 类型解析不到），
     * 2.3.10 / 2.3.13 均失败。分片文件是同等效果、零依赖的实现。
     */
    private fun saveSession(context: Context, session: Session) {
        // 先更新内存缓存：后续 loadSession 直接从内存拿，不再解析 JSON。
        memorySessions[session.id] = session
        // 逐片落盘（异步、不阻塞调用方）。
        // 峰值 = CHUNK_SIZE 条消息的序列化，与历史总长度无关。
        SessionPersistence.writeSessionAsync(
            context,
            ChatSession(session.id, session.title, session.messages, session.mode)
        )
    }
}