package com.agentpad.workspace

import android.app.AlertDialog
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.agentpad.workspace.ui.AgentChatMessages
import com.agentpad.workspace.ui.AgentComposer
import com.agentpad.workspace.ai.ReasoningLevel
import com.agentpad.workspace.ai.ToolCall
import com.agentpad.workspace.ai.ToolResult
import com.agentpad.workspace.ai.AskUserHub
import com.agentpad.workspace.ai.ToolProtocol
import androidx.activity.OnBackPressedCallback
import com.agentpad.workspace.ai.PlanStore
import com.agentpad.workspace.ai.ToolRegistry
import com.agentpad.workspace.ai.BrowserSession
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.databinding.ActivityChatBinding
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
class ChatActivity : ComponentActivity(), com.agentpad.workspace.ai.BrowserSlotProvider, com.agentpad.workspace.floating.FloatingSessionHub.Host {
    override fun floatingBusy(): Boolean = busy
    override fun floatingSend(text: String): Boolean {
        if (busy || isFinishing || isDestroyed || text.isBlank() || !::attachments.isInitialized) return false
        if (attachments.current().isNotEmpty()) return false
        ChatStore.append(this, session.id, "user", text.trim())
        syncSessionFromStore()
        userPagedBack = false
        windowStartIndex = -1
        return startTurn() != null
    }
    override fun floatingStop() { abortActiveRequests() }
    private fun openFloatingChat() {
        closeSidebar()
        if (com.agentpad.workspace.floating.AgentFloatingService.open(this, session.id)) moveTaskToBack(true)
    }


    override fun onDestroy() {
        if (::session.isInitialized && openChats[session.id]?.get() === this) openChats.remove(session.id)
        if (::session.isInitialized) com.agentpad.workspace.floating.FloatingSessionHub.detach(this, session.id, this)
        if (contextCompressionRunning) cancelContextCompression()
        // 清掉流式节流的挂起回调，避免 Activity 销毁后 Handler 还持有引用
        runCatching { streamRefreshHandler.removeCallbacks(streamRefreshRunnable) }
        streamRefreshPending = false
        // 清掉浏览器层的状态回调（它持有本 Activity 的引用）
        runCatching { BrowserSession.onUiStateChanged = null }
        // ⚠ 不在这里 destroy 浏览器会话：用户可能只是旋转屏幕/返回后台，
        // 重建后 WebView 还要搬回新的 slot 继续用。真正的销毁在 finish() 时。
        if (isFinishing) runCatching { BrowserSession.destroy() }
        super.onDestroy()
    }

    // ================= AI 浏览器接管层 =================
    //
    // 设计：WebView 由 BrowserSession 创建后永远挂在 ai_browser_layer 里，
    // 从不 removeView、从不换父容器 —— 这是「网页状态不变」的唯一保证。
    // 用户看不看得见，完全靠 z 序：把 chat_content 或 ai_browser_layer
    // 哪个 bringToFront 而已。

    /** 顶栏 + 进度条的实时刷新。必须在主线程。 */
    fun refreshBrowserChrome() {
        if (!::binding.isInitialized) return
        val st = BrowserSession.state()
        binding.aiBrowserTitle.text = st.title.ifBlank {
            if (st.url.isNotBlank()) st.url else "网页"
        }
        binding.aiBrowserSubtitle.text = when {
            st.loading -> "加载中…"
            st.handed -> BrowserSession.handoffReasonText().ifBlank { "请手动完成页面上的操作" }
            else -> st.url.ifBlank { "—" }
        }
        val p = BrowserSession.progressPercent()
        binding.aiBrowserProgress.visibility = if (st.loading && p in 1..99) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if (p in 0..100) binding.aiBrowserProgress.progress = p
    }

    /**
     * 把浏览器层提到最前，交给用户。
     *
     * 只改 z 序，不动 WebView 本身 —— 页面状态（验证码进度、滚动位置、
     * 已填表单）完全保持原样。
     */
    fun openBrowserForUser(reason: String) {
        if (!::binding.isInitialized) return
        // 首次接管时把 WebView 挂进 slot（只挂一次，之后永远不动）
        if (!BrowserSession.isAlive) {
            BrowserSession.attach(binding.aiBrowserSlot)
        }
        BrowserSession.markHandedToUser(reason)
        BrowserSession.onUiStateChanged = { runOnUiThread { refreshBrowserChrome() } }
        binding.aiBrowserLayer.visibility = android.view.View.VISIBLE
        binding.aiBrowserLayer.bringToFront()
        refreshBrowserChrome()
    }

    /** 用户点「交给 AI」：退回聊天界面，控制权还给 AI。 */
    fun handBrowserBackToAgent() {
        if (!::binding.isInitialized) return
        BrowserSession.markReturnedToAgent()
        binding.aiBrowserLayer.visibility = android.view.View.GONE
        binding.chatContent.bringToFront()
        // 让 AI 侧立刻感知到（工具轮询里会消费这个标志）
        toastShort("已交给 AI 继续处理")
    }

    /** 用户点「✕」：退出接管界面，控制权也还给 AI（但不算「已处理完」）。 */
    fun closeBrowserForUser() {
        if (!::binding.isInitialized) return
        BrowserSession.markReturnedToAgent()
        binding.aiBrowserLayer.visibility = android.view.View.GONE
        binding.chatContent.bringToFront()
    }

    /** 绑定接管层顶栏的三个按钮。onCreate 里调一次。 */
    private fun bindBrowserLayer() {
        binding.aiBrowserClose.setOnClickListener { closeBrowserForUser() }
        binding.aiBrowserHandback.setOnClickListener { handBrowserBackToAgent() }
        // 接管界面优先消费返回键：先退回聊天，而不是直接退出 Activity。
        // 用 addCallback 而不是 onBackPressed（ComponentActivity 已废弃后者）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::binding.isInitialized &&
                    binding.aiBrowserLayer.visibility == android.view.View.VISIBLE
                ) {
                    closeBrowserForUser()
                } else if (sessionDrawerVisible.value) {
                    sessionDrawerVisible.value = false
                } else if (sidebarVisible.value) {
                    if (sidebarPlanView.value) sidebarPlanView.value = false else closeSidebar()
                } else if (!com.agentpad.workspace.ui.ThemeStore.legacyUi.value) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun toastShort(msg: String) {
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ---- BrowserSlotProvider 实现 ----

    /** 把 WebView 挂到 slot（幂等）。由 BrowserTools 在 browser_open 时调用。 */
    override fun ensureBrowserAttached(): Boolean {
        if (!::binding.isInitialized) return false
        return runCatching {
            if (!BrowserSession.isAlive) BrowserSession.attach(binding.aiBrowserSlot)
            BrowserSession.onUiStateChanged = { runOnUiThread { refreshBrowserChrome() } }
            true
        }.getOrDefault(false)
    }

    /**
     * 弹窗问用户是否接手页面。
     *
     * 刻意做成「先问再切」：直接抢走界面太粗暴，用户可能正在看别的消息。
     */
    override fun askUserToTakeOver(reason: String, onResult: (Boolean) -> Unit) {
        if (!::binding.isInitialized || isFinishing) { onResult(false); return }
        runCatching {
            // 项目没有 appcompat 依赖，用系统原生 AlertDialog
            android.app.AlertDialog.Builder(this)
                .setTitle("需要你手动处理一下")
                .setMessage(reason)
                .setPositiveButton("接手页面") { _, _ ->
                    openBrowserForUser(reason)
                    onResult(true)
                }
                .setNegativeButton("让 AI 自己想办法") { _, _ -> onResult(false) }
                .setCancelable(false)
                .show()
        }.onFailure { onResult(false) }
    }


    private fun openDeliveredFile(rawPath: String) {
        val raw = rawPath.trim().trim('`', '"', '\'', ' ')
        val workspace = sessionWorkspace
        val candidates = ArrayList<java.io.File>()
        fun add(f: java.io.File?) { if (f != null) candidates.add(f) }

        when {
            // 旧别名兼容：2026-11 之前沙箱把工作区挂在 /workspace，老会话/老脚本可能还带这个前缀
            raw.startsWith("/workspace/") -> add(java.io.File(workspace, raw.removePrefix("/workspace/")))
            // 沙箱内 /tmp/xxx 通常是相对工作区的临时产物 → 先按工作区解析
            raw.startsWith("/tmp/") -> {
                add(java.io.File(workspace, raw.removePrefix("/tmp/")))
                add(java.io.File(workspace, raw.substringAfterLast('/')))
            }
            raw.startsWith("/sdcard/") -> add(java.io.File(raw.replaceFirst("/sdcard", "/storage/emulated/0")))
            raw.startsWith("/storage/") -> add(java.io.File(raw))
            raw.startsWith("/") -> {
                add(java.io.File(raw))
                add(java.io.File(workspace, raw.trimStart('/')))
            }
            else -> add(java.io.File(workspace, raw))
        }
        // 兜底：不管路径怎么写，先在工作区里按文件名找一遍
        val baseName = raw.substringAfterLast('/').ifBlank { raw }
        add(runCatching { workspace.walkTopDown().firstOrNull { it.isFile && it.name == baseName } }.getOrNull())

        val root = runCatching { workspace.canonicalFile }.getOrNull() ?: return
        val file = candidates.asSequence()
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .firstOrNull { it.isFile && it.path.startsWith(root.path + java.io.File.separator) }
            ?: candidates.asSequence()
                .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
                .firstOrNull { it.isFile }   // 工作区外的绝对路径也允许（如 /sdcard）

        if (file == null) {
            Toast.makeText(this, "交付文件不存在：$baseName", Toast.LENGTH_LONG).show()
            return
        }
        // 图片走 App 内全屏预览：不依赖系统相册，双指缩放/保存都在里面
        val ext = file.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
            runCatching {
                startActivity(android.content.Intent(this, ImagePreviewActivity::class.java)
                    .putExtra("path", file.absolutePath))
            }.onFailure { Toast.makeText(this, "无法打开预览", Toast.LENGTH_LONG).show() }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "打开 ${file.name}"))
        }.onFailure { Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_LONG).show() }
    }
    private lateinit var binding: ActivityChatBinding
    private lateinit var session: ChatStore.Session
    private lateinit var toolRegistry: ToolRegistry
    private var busy = false
    private var reasoningLevel = ReasoningLevel.AUTO
    private lateinit var sessionWorkspace: java.io.File
    private lateinit var attachments: ChatAttachment
    private var transientAttachmentContent: JSONArray? = null

    /** One agent round = user send → all tool rounds → final text. */
    private data class RoundStats(
        var promptTokens: Long = 0,
        var completionTokens: Long = 0,
        var cachedTokens: Long = 0,
        var startedAt: Long = System.currentTimeMillis(),
        var elapsedMs: Long = 0,
        /** 工具调用步数（一个 turn 里跑了几轮）。对齐 RikkaHub 的 stepCount。 */
        var steps: Int = 1,
        /** 最后一次请求的 prompt/completion，用于 nerd line 的「上下文占用」。 */
        var lastPromptTokens: Long = 0,
        var lastCompletionTokens: Long = 0,
        var lastCachedTokens: Long = 0,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val cacheRate: Double get() = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else 0.0
        /** 最后一次请求的上下文占用 = 末轮输入 + 末轮输出。 */
        val lastContextTokens: Long get() = lastPromptTokens + lastCompletionTokens
        /** 生成速度 tok/s：末轮输出 token ÷ 总耗时。 */
        val tps: Double get() = if (elapsedMs > 0) completionTokens * 1000.0 / elapsedMs else 0.0
    }

    private var roundStats: RoundStats? = null
    @Volatile private var abortRequested = false
    @Volatile private var requestGeneration = 0
    /**
     * 单次 API 调用最多自动重试几次。
     *
     * 对齐参考源码 RikkaHub 的 `RetryPolicy.NETWORK`（ai/agent/Retry.kt:1-8，
     * maxRetries = 3、只重试 IOException、退避 1s→2s→4s），
     * 但按主人要求把次数提到 5：一次任务里网络抖一下就整段中断太蠢了。
     */
    private val MAX_API_RETRIES = 5
    /** 每次重试前的等待（毫秒），指数退避，末档封顶 8s。 */
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)
    /** 流式刷新最小间隔（毫秒）。100ms 约等于 10fps，肉眼已足够顺滑。 */
    private val STREAM_REFRESH_MS = 100L
    /** 本轮（一个 assistant turn）是否调用过工具。收尾时据此判断"AI 还在干活中"。 */
    // 工具调用由当前 agentic loop 直接驱动，不用于结束后自动补发。

    private val activeConnections = java.util.Collections.synchronizedList(mutableListOf<HttpURLConnection>())
    private val composeMessages = mutableStateOf<List<ChatStore.Message>>(emptyList())
    private lateinit var composeView: ComposeView
    /** Compose composer state (single source of truth for the visible input bar). */
    private val composeInput = mutableStateOf("")
    private val composerClearFocus = mutableStateOf(0)

    private fun releaseInputFocus() {
        composerClearFocus.value++
        binding.input.clearFocus()
        binding.root.requestFocus()
        androidx.core.view.WindowInsetsControllerCompat(window, binding.root)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
    private val composeBusy = mutableStateOf(false)
    private val composeStreaming = mutableStateOf(false)
    private val composeModel = mutableStateOf("")
    private val composeAttachments = mutableStateOf<List<Pair<ChatAttachment.Item, String>>>(emptyList())
    /** Bumped by the composer's search button to open the Compose message locator. */
    private val composeOpenLocator = mutableStateOf(0)
    /** 用户点「渲染视频」时的进行中标记（避免重复点）。 */
    @Volatile private var videoRendering = false

    // ---- 显示窗口分页（Operit 同款）----
    private val composeHasOlder = mutableStateOf(false)
    private val composeLoadingOlder = mutableStateOf(false)
    /** 补完历史页后要求列表滚到指定下标（防止视图跳到顶部）。 */
    private val composeScrollToIndex = mutableStateOf(-1)
    /** 每次自增触发一次滚动动作。 */
    private val composeScrollToIndexSignal = mutableStateOf(0)
    /** 用户点「回到底部」：窗口重新锚到最新几页（等价 Operit showLatestMessagesForCurrentChat）。 */
    private val composeReturnToLatest = mutableStateOf(0)

    // NOTE: there used to be a 180ms `composeSync` poll here that re-read and
    // re-parsed the WHOLE session JSON on the UI thread forever. With the store
    // lock held by the streaming writer, that could block the main thread long
    // enough to ANR ("frozen screen, then back to a fresh app screen" with no
    // crash log — the OS kills the process). Every update path now repaints
    // explicitly via refreshComposeMessages(), so polling is unnecessary.
    private fun setBusyUi(busyNow: Boolean) {
        composeBusy.value = busyNow
        if (::session.isInitialized) com.agentpad.workspace.floating.FloatingSessionHub.refresh(this, session.id, true)
    }

    private fun abortActiveRequests() {
        requestGeneration++
        abortRequested = true
        com.agentpad.workspace.screencontrol.ScreenControlSettings.setEnabled(this, false)
        synchronized(activeConnections) { activeConnections.toList().forEach { runCatching { it.disconnect() } } }
        // Also kill any running container command so its reader threads exit.
        // 真共享之后「命令」跑在交互 shell 里，必须按会话 id 往那条 pty 打 ETX，
        // 光 destroy Process 打不到（那只是一次性通道的兜底）。
        com.agentpad.workspace.ai.SandboxRunner.abortSession(
            com.agentpad.workspace.ai.TerminalService.aiSessionId(session.id)
        )
        // 立即复位 UI（不等后台线程收尾）——否则按钮一直是 ✕、看起来"点了没反应"。
        runOnUiThread {
            busy = false
            setBusyUi(false)
            roundStats = null
            runCatching { WorkingBubble.stopIfShowing(this, session.id) }
            refreshComposeMessages()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::attachments.isInitialized && (requestCode == ChatAttachment.IMAGE_REQUEST || requestCode == ChatAttachment.FILE_REQUEST)) attachments.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // 普通模式：显示系统状态栏（水滴屏不遮挡内容），内容由系统自动避让。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.requestFocus()
        val id = intent.getStringExtra("session_id") ?: ChatStore.createSession(this).id
        session = ChatStore.loadSession(this, id) ?: ChatStore.createSession(this)
        getSharedPreferences("navigation_prefs", MODE_PRIVATE).edit().putString("last_session_id", session.id).apply()
        com.agentpad.workspace.floating.FloatingSessionHub.attach(this, session.id, this)
        openChats[session.id] = java.lang.ref.WeakReference(this)
        sessionWorkspace = ChatStore.setCurrentWorkspace(this, session.id)
        toolRegistry = ToolRegistry(this, sessionWorkspace, session.id) { runOnUiThread { refreshPlanPanel(); refreshComposeMessages() } }
        // 每次进入会话都重新加载远端 MCP 工具，保证「设置 → MCP 服务」的改动即时生效。
        // ⚠ 必须在后台线程：loadRemoteTools() 会连远端 MCP 服务器发网络请求，
        // 放在主线程会把 onCreate 卡死（悬浮窗点击回到会话时 UI 冻住就是这个原因）。
        Thread { runCatching { toolRegistry.loadRemoteTools() } }.start()
        com.agentpad.workspace.ai.BrowserTools.bindActivity(this)
        bindBrowserLayer()
        composeView = binding.composeMessages.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                com.agentpad.workspace.ui.AgentTheme {
                    AgentChatMessages(
                        messages = composeMessages.value,
                        modifier = Modifier.fillMaxSize(),
                        streaming = composeStreaming.value,
                        openLocatorSignal = composeOpenLocator.value,
                        hasOlderHistory = composeHasOlder.value,
                        isLoadingOlder = composeLoadingOlder.value,
                        onLoadOlder = { loadOlderPage() },
                        scrollToIndex = composeScrollToIndex.value,
                        scrollToIndexSignal = composeScrollToIndexSignal.value,
                        returnToLatestSignal = composeReturnToLatest.value,
                        onReturnToLatest = {
                            // 回到底部 = 窗口重锚到最新几页 + 贴底。
                            userPagedBack = false
                            windowStartIndex = -1
                            refreshComposeMessages()
                        },
                        onRegenerate = { message ->
                            // ⚠ 2026-11-04 逐条照抄 RikkaHub ChatService.kt:466-521
                            // + :577-607 的 regenerateAtMessage / runTurn(regenerateNodeId)。
                            //
                            // 原版语义（助手消息分支）：
                            //   history = currentMessages.take(index)   ← 只发该节点之前的消息
                            //   replyNodeId = regenerateNodeId          ← 新回复挂回同一节点的分支
                            //   全程不动用户消息、不 rewind、不重发
                            //
                            // 所以这里**绝不能**再 append(user)/append(assistant)，
                            // 也不能 rewind —— 之前的实现就是因此产生了"多个你好"。
                            if (!busy) {
                                val prior = ChatStore.loadSession(this@ChatActivity, session.id)?.messages.orEmpty()
                                val idx = prior.indexOfFirst { it.id == message.id }
                                if (idx >= 0) {
                                    // 旧分支集合 = 已有分支 + 旧本体（本体先摘掉 alternatives 防嵌套）
                                    val oldBranches = message.alternatives +
                                        message.copy(alternatives = emptyList(), selectIndex = 0)
                                    startTurn(regenerateNodeId = message.id, regenerateBranches = oldBranches)
                                }
                            }
                        },
                        onSelectBranch = { message, target ->
                            // 照抄 RikkaHub `selectBranch`：只改显示哪一条，不动消息数组。
                            //
                            // 刷新机制：LazyColumn 的 item key 是 "${id}#${selectIndex}"
                            // （见 AgentChatMessages），切换后 key 变化 → Compose 必然重组，
                            // 所以这里只需落盘 + 常规 refresh，不必清空列表（那样会闪）。
                            val updated = ChatStore.selectBranch(message, target)
                            if (updated !== message) {
                                ChatStore.replaceMessage(this@ChatActivity, session.id, updated)
                                refreshComposeMessages()
                            }
                        },
                        onEdit = { message -> editMessage(message) },
                        onDelete = { message -> deleteMessage(message) },
                        onShare = { message -> shareMessage(displayTextOf(message)) },
                        onOpenDeliveredFile = { path -> openDeliveredFile(path) },
                        onFork = { message ->
                            val prior = ChatStore.loadSession(this@ChatActivity, session.id)?.messages.orEmpty()
                            val index = prior.indexOfFirst { it.id == message.id }
                            val user = prior.take(index).lastOrNull { it.role == "user" } ?: message
                            forkFrom(user.content)
                        },
                        listBackground = chatListBackground(),
                        // ⚠ 2026-11-04 顶栏叠加：
                        // 列表现在铺满整个区域（顶栏浮在它上面），必须自己让出顶栏高度，
                        // 否则第一条消息会被顶栏压住。同时这个值也是 topFadingEdge 的
                        // 渐隐高度 —— 照抄 RikkaHub ChatList.kt:340
                        // `topFadingEdge(innerPadding.calculateTopPadding() + 24.dp)`。
                        contentTopPadding = topBarHeightDp(),
                    )
                }
            }
        }
        // Composer is Compose-owned now; the legacy EditText stays as the text
        // buffer that send()/attachments already rely on.
        binding.composeComposer.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeComposer.setContent {
            com.agentpad.workspace.ui.AgentTheme {
                AgentComposer(
                    clearFocusSignal = composerClearFocus.value,
                    value = composeInput.value,
                    onValueChange = {
                        composeInput.value = it
                        if (binding.input.text?.toString() != it) binding.input.setText(it)
                    },
                    modelName = composeModel.value,
                    reasoningLabel = reasoningLevel.label,
                    busy = composeBusy.value,
                    attachments = composeAttachments.value,
                    onRemoveAttachment = { index ->
                        composeAttachments.value.getOrNull(index)?.let { attachments.remove(it.first) }
                    },
                    onSend = { send() },
                    onStop = { abortActiveRequests() },
                    onAttach = {
                        if (com.agentpad.workspace.ui.ThemeStore.legacyUi.value) attachments.showSheet()
                        else toggleSidebar()
                    },
                    onPickModel = { showModelPicker() },
                    onPickReasoning = { showReasoningPicker() },
                    onOpenLocator = { composeOpenLocator.value++ },
                )
            }
        }
        // (the 180ms composeSync poll was removed — see the note above)
        attachments = ChatAttachment(this)
        attachments.setCallbacks(onImages = { /* send integration follows */ }, onFiles = { /* send integration follows */ })
        attachments.onChanged = { runOnUiThread { refreshComposeMessages() } }
        // attachments must exist before the first refresh: it mirrors the
        // selected files into the Compose composer.
        refreshComposeMessages()
        reasoningLevel = ReasoningLevel.fromName(ChatStore.reasoningLevel(this))
        refreshChatTitle()
        // 顶部栏（Compose 版，照抄 RikkaHub 3.1.1 ChatPage.kt:560-643）
        setupTopBar()
        setupChatBackground()
        applyThemeToNativeUi()
        buildSidebarPanel()
        refreshPlanPanel()
    }
    // ---- Chat sidebar (Compose owned) ----
    /** 侧边栏是否展开（驱动 Compose 侧的滑入/滑出动画）。 */
    private val sidebarVisible = mutableStateOf(false)
    private val sessionDrawerVisible = mutableStateOf(false)
    private var previousLegacyUi = com.agentpad.workspace.ui.ThemeStore.legacyUi.value

    companion object {
        private val openChats = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<ChatActivity>>()
        fun open(context: android.content.Context, id: String) {
            // Standard launch carries the target ID through onCreate. Component-level
            // REORDER cannot distinguish two ChatActivity instances by session_id.
            context.startActivity(Intent(context, ChatActivity::class.java).putExtra("session_id", id))
        }
    }

    private fun openSessionDrawer() {
        closeSidebar()
        releaseInputFocus()
        sessionDrawerVisible.value = true
    }

    private fun openSessionFromDrawer(target: ChatStore.Session) {
        sessionDrawerVisible.value = false
        releaseInputFocus()
        if (target.id == session.id) return
        open(this, target.id)
        // Keep a running engine alive; idle pages have no reason to accumulate in the stack.
        if (!busy && !contextCompressionRunning && !videoRendering) finish()
    }
    /** 侧边栏是否处于「任务规划」子页。 */
    private val sidebarPlanView = mutableStateOf(false)
    /** 侧边栏数据（任务 / 上下文 / 计划进度）。 */
    private val sidebarState = mutableStateOf(com.agentpad.workspace.ui.SidebarState())
    /** 任务规划条目：(text, status, id)。 */
    private val sidebarPlanItems = mutableStateOf<List<Triple<String, String, String>>>(emptyList())
    private var sidebarCompose: ComposeView? = null

    // Request-time context compression. Original chat history stays untouched.
    @Volatile private var contextCompressionRunning = false
    @Volatile private var contextCompressionCancelled = false
    @Volatile private var contextCompressionConnection: HttpURLConnection? = null
    private var contextCompressionDialog: android.app.AlertDialog? = null
    private val contextEstimateText = mutableStateOf("上下文计算中…")

    /** 侧边栏等原生 View 用：取当前主题色（Argb）。 */
    private fun themeArgb(sel: (com.agentpad.workspace.ui.APalette) -> androidx.compose.ui.graphics.Color): Int =
        sel(com.agentpad.workspace.ui.ThemeStore.palette.value).toArgb()

    /** 侧边栏卡片背景：跟随主题（Surface 底 + Line 描边 + 圆角 20）。 */
    private fun themedInputBg(): android.graphics.drawable.GradientDrawable {
        val p = com.agentpad.workspace.ui.ThemeStore.palette.value
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(p.surfaceAlt.toArgb())
            setStroke(dp(1), p.line.toArgb())
        }
    }

    /** 把主题色应用到聊天页的原生 UI（根容器）。
     *  顶部栏已改为 Compose（RikkaChatTopBar），颜色由 Compose 侧 MaterialTheme 管，
     *  这里不再需要给 chatTopbar/title/back/menu 上色。 */
    private fun applyThemeToNativeUi() {
        val p = com.agentpad.workspace.ui.ThemeStore.palette.value
        // 普通模式：显示系统状态栏（水滴屏不遮挡内容），内容由系统自动避让。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        binding.chatRoot.setBackgroundColor(p.paper.toArgb())
        // 状态栏 / 导航栏 / window 背景 / 图标明暗 四件套跟随主题（含 Android 15 边到边兜底）
        com.agentpad.workspace.ui.applySystemBars(this)
    }

    // ---- 顶部栏（Compose，照抄 RikkaHub 3.1.1 ChatPage.kt:560-643） ----
    /** 顶部栏标题（会话名，带模式前缀）。 */
    private val topBarTitle = mutableStateOf("新对话")
    /** 顶部栏副标题：「助手名 / 模型名」，对应原版 assistant.name + chatModel.displayName。 */
    private val topBarSubtitle = mutableStateOf("")

    /**
     * 建顶部栏。原版用 Scaffold(topBar = { TopBar(...) })，
     * 我们的页面是 XML + ComposeView 混合，所以单独挂一个 ComposeView。
     */
    private fun setupTopBar() {
        binding.composeTopbar.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeTopbar.setContent {
            com.agentpad.workspace.ui.AgentTheme {
                com.agentpad.workspace.ui.RikkaChatTopBar(
                    title = topBarTitle.value,
                    subtitle = topBarSubtitle.value,
                    mode = session.mode,
                    // 左侧：返回（原版这里是菜单，按主人要求换回返回）
                    legacyUi = com.agentpad.workspace.ui.ThemeStore.legacyUi.value,
                    onNewChat = {
                        startActivity(Intent(this@ChatActivity, MainActivity::class.java).putExtra("navigation_page", "NEW_SESSION"))
                    },
                    onBack = {
                        if (!com.agentpad.workspace.ui.ThemeStore.legacyUi.value) openSessionDrawer()
                        else if (contextCompressionRunning) cancelContextCompression() else finish()
                    },
                    // 点击标题改名
                    onClickTitle = { showRenameDialog() },
                    // 右侧：菜单，开侧边栏
                    onOpenMenu = { toggleSidebar() },
                )
            }
        }
        refreshTopBarSubtitle()
    }

    /** 副标题：模型名。原版格式是「助手名 / 模型名 (供应商)」，我们没有助手概念，只放模型名。 */
    private fun refreshTopBarSubtitle() {
        topBarSubtitle.value = composeModel.value.orEmpty()
    }

    /** 点击标题改名（原版 titleState.open(conversation.title)）。 */
    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(ChatStore.loadSession(this@ChatActivity, session.id)?.title.orEmpty())
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("修改会话标题")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = input.text?.toString()?.trim().orEmpty()
                if (newTitle.isNotBlank()) {
                    ChatStore.renameSession(this, session.id, newTitle)
                    refreshChatTitle()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


    // ---- 对话背景（Compose 层） ----
    private val bgConfig = mutableStateOf(com.agentpad.workspace.data.ChatBackgroundStore.Config())

    private fun setupChatBackground() {
        bgConfig.value = com.agentpad.workspace.data.ChatBackgroundStore.load(this)
        binding.chatBackground.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.chatBackground.setContent {
            com.agentpad.workspace.ui.AgentTheme {
                com.agentpad.workspace.ui.ChatBackgroundLayer(config = bgConfig.value)
            }
        }
    }

    /** 从背景设置页返回时刷新。 */
    private fun reloadChatBackground() {
        if (!::binding.isInitialized) return
        val next = com.agentpad.workspace.data.ChatBackgroundStore.load(this)
        if (next != bgConfig.value) bgConfig.value = next
    }

    /** 消息列表底色：默认背景用纸面色，其他类型透明让背景层透上来。 */
    private fun chatListBackground(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color.Transparent

    /**
     * 顶栏高度（含状态栏 inset），供消息列表让出顶部空间。
     *
     * 照抄 RikkaHub ChatPage.kt:405-408 —— Scaffold 把 innerPadding 交给 ChatList，
     * ChatList 用其中 top 部分做 contentPadding 与 topFadingEdge 的高度。
     * 我们是 XML + ComposeView 混合，拿不到 Scaffold 的 innerPadding，
     * 所以自己按 TopAppBar 的标准高度（64dp）+ 状态栏 inset 算。
     */
    private fun topBarHeightDp(): androidx.compose.ui.unit.Dp {
        val statusBar = androidx.core.view.ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            ?.top ?: 0
        val density = resources.displayMetrics.density
        return androidx.compose.ui.unit.Dp(64f + statusBar / density)
    }

    /**
     * 侧边栏容器：一个覆盖全屏的 ComposeView（黑幕 + 右侧滑入面板）。
     *
     * 旧实现是手搓 LinearLayout + 一堆 TextView 按钮，视觉与设计系统脱节，
     * 且每次刷新 removeAllViews() 全量重建。现在交给 Compose 管理，
     * 状态变化只重组差值，并由 AnimatedVisibility 做滑入滑出。
     */
    private fun buildSidebarPanel() {
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content) ?: return
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setContent {
                com.agentpad.workspace.ui.AgentTheme {
                    com.agentpad.workspace.ui.SidebarHost(
                        visible = sidebarVisible.value,
                        planView = sidebarPlanView.value,
                        state = sidebarState.value,
                        planItems = sidebarPlanItems.value,
                        actions = com.agentpad.workspace.ui.SidebarActions(
                            onClose = { closeSidebar() },
                            onCompress = { showCompressionDialog() },
                            onUndoCompress = { undoContextCompression() },
                            onOpenPlan = { sidebarPlanView.value = true },
                            onOpenTerminal = {
                                closeSidebar()
                                startActivity(
                                    android.content.Intent(this@ChatActivity, TerminalActivity::class.java)
                                        .putExtra("session_id", session.id),
                                )
                            },
                            onOpenFileHistory = {
                                closeSidebar()
                                startActivity(
                                    android.content.Intent(this@ChatActivity, FileHistoryActivity::class.java)
                                        .putExtra("session_id", session.id),
                                )
                            },
                            onOpenPrompt = {
                                closeSidebar()
                                startActivity(
                                    android.content.Intent(this@ChatActivity, PromptActivity::class.java)
                                        .putExtra("session_id", session.id),
                                )
                            },
                            onRegenerateTitle = {
                                closeSidebar()
                                maybeAutoTitle(force = true)
                            },
                            onOpenWebPreview = {
                                closeSidebar()
                                startActivity(
                                    android.content.Intent(this@ChatActivity, WebPreviewActivity::class.java)
                                        .putExtra("session_id", session.id),
                                )
                            },
                            onRenderVideo = {
                                closeSidebar()
                                renderVideoNow()
                            },
                            onOpenVideoPreview = {
                                closeSidebar()
                                startActivity(
                                    android.content.Intent(this@ChatActivity, VideoPreviewActivity::class.java)
                                        .putExtra("session_id", session.id),
                                )
                            },
                            onInstallApk = { showWorkspaceApkPicker() },
                            onOpenFloating = { openFloatingChat() },
                        ),
                        onBackFromPlan = { sidebarPlanView.value = false },
                        asToolsSheet = !com.agentpad.workspace.ui.ThemeStore.legacyUi.value,
                        onImages = { closeSidebar(); attachments.pick(true) },
                        onFiles = { closeSidebar(); attachments.pick(false) },
                    )
                    if (!com.agentpad.workspace.ui.ThemeStore.legacyUi.value) {
                        com.agentpad.workspace.ui.SessionDrawerHost(
                            visible = sessionDrawerVisible.value, currentId = session.id,
                            onClose = { sessionDrawerVisible.value = false },
                            onOpen = { openSessionFromDrawer(it) })
                    }
                }
            }
        }
        // 外层始终 VISIBLE：显示/隐藏由 SidebarHost 内部的 Animatable 控制，
// 这样组合树一直存活，打开时才能播放弹簧入场动画。
        cv.visibility = View.VISIBLE
        root.addView(cv)
        sidebarCompose = cv
    }

    /**
     * 侧边栏外层 ComposeView 始终保持 VISIBLE。
     *
     * 之前这里会在关闭时把整个 ComposeView 设成 GONE —— 那会直接把
     * Compose 的组合树销毁。等下次打开时是新的一次「首次组合」，
     * AnimatedVisibility 类的入场动画根本不会播放，所以"打开没动画"。
     *
     * 现在外层一直 VISIBLE，显示/隐藏完全交给 SidebarHost 内部的
     * Animatable：收起且动画结束时它自己渲染成 0 尺寸，不会挡住聊天页触摸。
     */
    private fun setSidebarComposeVisible(show: Boolean) {
        sidebarCompose?.visibility = View.VISIBLE
    }

    private fun closeSidebar() {
        sidebarVisible.value = false
        sidebarPlanView.value = false
        setSidebarComposeVisible(false)
    }

    /** Kotlin 项目侧栏：扫描当前 AI 工作区的所有 APK，点选后交给系统安装器。 */
    private fun showWorkspaceApkPicker() {
        closeSidebar()
        val entries = com.agentpad.workspace.data.WorkspaceApkInstaller.scan(this, session.id)
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(this, "当前工作区没有 APK 文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.relativePath }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择要安装的 APK（${entries.size} 个）")
            .setItems(labels) { _, which ->
                val file = entries.getOrNull(which)?.file ?: return@setItems
                com.agentpad.workspace.data.WorkspaceApkInstaller.install(this, file)
                    .onFailure {
                        android.widget.Toast.makeText(
                            this,
                            "无法打开安装器：${it.message ?: "未知错误"}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleSidebar() {
        releaseInputFocus()
        if (sidebarVisible.value) {
            closeSidebar()
        } else {
            sidebarPlanView.value = false
            // 顺序很重要：refreshContextEstimate() 里有
            // `if (!sidebarVisible.value) return` 守卫。
            // 旧顺序（先 refresh 后置 true）会让上下文永远停在「计算中…」。
            setSidebarComposeVisible(true)
            sidebarVisible.value = true
            refreshPlanPanel()
        }
    }

    private fun showPlanView() {
        sidebarPlanView.value = true
        refreshPlanPanel()
    }

    private fun refreshContextEstimate() {
        if (!sidebarVisible.value) return
        Thread {
            val bytes = runCatching { conversationJson().toString().toByteArray(Charsets.UTF_8).size }.getOrDefault(0)
            val tokens = bytes / 4.0
            val text = if (tokens >= 1000) "上下文约 ${"%.1f".format(java.util.Locale.US, tokens / 1000.0)}K tokens"
            else "上下文约 ${tokens.toInt()} tokens"
            runOnUiThread {
                if (!isFinishing) {
                    contextEstimateText.value = text
                    // 同步进 Compose 侧边栏状态，让面板文字立即更新
                    sidebarState.value = sidebarState.value.copy(contextEstimate = text)
                }
            }
        }.start()
    }

    private fun showCompressionDialog() {
        if (contextCompressionRunning) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val target = android.widget.EditText(this).apply {
            hint = "摘要目标 token 数，例如 2000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("2000")
        }
        val keep = android.widget.EditText(this).apply {
            hint = "保留最近消息数，例如 32"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("32")
        }
        val extra = android.widget.EditText(this).apply {
            hint = "附加要求（可选）"
            minLines = 2
            maxLines = 4
        }
        box.addView(target)
        box.addView(keep, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(extra, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("压缩上下文")
            .setMessage("原始聊天记录不会被删除。压缩后请求只携带摘要和最近消息，可随时撤回。")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始压缩", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val targetTokens = target.text.toString().toIntOrNull()?.coerceIn(300, 8000) ?: 2000
                val keepRecent = keep.text.toString().toIntOrNull()?.coerceIn(4, 128) ?: 32
                dialog.dismiss()
                startContextCompression(targetTokens, keepRecent, extra.text.toString())
            }
        }
        dialog.show()
    }

    private fun startContextCompression(targetTokens: Int, keepRecentMessages: Int, additional: String) {
        val history = ChatStore.loadSession(this, session.id)?.messages.orEmpty()
        if (history.size <= keepRecentMessages) {
            Toast.makeText(this, "历史消息不足，无需压缩", Toast.LENGTH_SHORT).show()
            return
        }
        contextCompressionRunning = true
        contextCompressionCancelled = false
        val progress = TextView(this).apply {
            text = "正在生成上下文摘要…\n可以随时取消，原始历史不会改变。"
            setPadding(dp(28), dp(22), dp(28), dp(22))
            textSize = 15f
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("上下文压缩中")
            .setView(progress)
            .setNegativeButton("取消") { _, _ -> cancelContextCompression() }
            .create()
        dialog.setOnCancelListener { cancelContextCompression() }
        contextCompressionDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        Thread {
            val result = runCatching {
                if (contextCompressionCancelled) return@runCatching ""
                val old = history.dropLast(keepRecentMessages)
                val material = old.joinToString("\n\n") { m ->
                    val body = when {
                        m.role == "assistant" && m.parts.isNotEmpty() -> m.parts.joinToString("\n") { p ->
                            when (p.type) {
                                "tool" -> "[工具 ${p.toolName}] 参数=${p.toolArguments.take(1200)} 结果=${p.toolResult.take(1800)}"
                                "reasoning" -> "[思考] ${p.text.take(1200)}"
                                else -> p.text.take(3000)
                            }
                        }
                        else -> m.content.take(4000)
                    }
                    "${m.role}: $body"
                }
                val prompt = buildString {
                    appendLine("你是对话上下文压缩助手。请把下面的历史压缩成可供后续模型继续工作的上下文摘要。")
                    appendLine("要求：保留关键事实、用户目标、已做决定、文件路径、工具执行结果、未完成事项和重要约束；使用原对话语言；直接输出摘要，不要解释压缩过程；摘要开头写[历史上下文摘要]；目标约 $targetTokens tokens。")
                    if (additional.isNotBlank()) appendLine("附加要求：$additional")
                    appendLine("<conversation>")
                    append(material)
                    appendLine("\n</conversation>")
                }
                requestCompressionSummary(prompt)
            }.getOrElse { "\u0000${it.message ?: "网络异常"}" }
            runOnUiThread {
                contextCompressionRunning = false
                contextCompressionConnection = null
                contextCompressionDialog?.dismiss()
                contextCompressionDialog = null
                if (!contextCompressionCancelled && result.startsWith("\u0000")) {
                    Toast.makeText(this, "上下文摘要生成失败：${result.removePrefix("\u0000")}", Toast.LENGTH_LONG).show()
                } else if (!contextCompressionCancelled && result.isNotBlank()) {
                    ChatStore.saveContextCompression(
                        this, session.id,
                        ChatStore.ContextCompression(result, keepRecentMessages, history.size, System.currentTimeMillis()),
                    )
                    Toast.makeText(this, "上下文压缩已启用，原始历史保持不变", Toast.LENGTH_LONG).show()
                    refreshPlanPanel()
                } else if (!contextCompressionCancelled) {
                    Toast.makeText(this, "摘要为空，压缩未应用", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun cancelContextCompression() {
        contextCompressionCancelled = true
        contextCompressionConnection?.disconnect()
        contextCompressionConnection = null
        contextCompressionDialog?.dismiss()
        contextCompressionDialog = null
        contextCompressionRunning = false
    }

    private fun requestCompressionSummary(prompt: String): String {
        val api = ChatStore.api(this)
        val conn = (URL(api.first.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${api.second}")
            setRequestProperty("Content-Type", "application/json")
        }
        contextCompressionConnection = conn
        val body = JSONObject()
            .put("model", api.third)
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        return response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content").orEmpty().trim()
    }

    private fun undoContextCompression() {
        ChatStore.clearContextCompression(this, session.id)
        contextEstimateText.value = "上下文计算中…"
        Toast.makeText(this, "已撤回上下文压缩，下一次请求恢复完整历史", Toast.LENGTH_LONG).show()
        refreshPlanPanel()
    }

    /**
     * 刷新侧边栏数据。
     *
     * 旧版这里是「removeAllViews() + 重建全部原生 View」，每个工具步骤都会跑一次，
     * 直接把主线程时间吃掉。现在是往 Compose 状态里写值，只有真正变化的文字重组。
     */
    private fun refreshPlanPanel() {
        refreshContextEstimate()
        val items = PlanStore.load(this, session.id)
        val active = items.firstOrNull { it.status == "in_progress" }
            ?: items.firstOrNull { it.status == "pending" }
        val compression = ChatStore.loadContextCompression(this, session.id)
        sidebarPlanItems.value = items.map { Triple(it.text, it.status, it.id) }
        sidebarState.value = com.agentpad.workspace.ui.SidebarState(
            activeTask = active?.text,
            contextEstimate = contextEstimateText.value,
            compressionOn = compression != null,
            planDone = items.count { it.status == "done" },
            planTotal = items.size,
            mode = session.mode,
        )
    }


    // ---- 视频项目：用户自主渲染 ----

    /**
     * 用户点侧边栏「渲染视频」：在工作区**真实路径**下跑 bash build.sh，
     * 沙箱与原生工具看到的是同一个绝对路径（2026-11 起不再有 /workspace 别名），
     * 所以这里用 SandboxRunner 直接执行即可（等价于 AI 跑 terminal 工具）。
     *
     * 结果用 Toast 反馈，成功后提示去「预览视频」查看。
     */
    private fun renderVideoNow() {
        if (videoRendering) {
            Toast.makeText(this, "正在渲染中，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        val initScript = com.agentpad.workspace.ai.SandboxInstaller.initScript(this)
        val rootfs = com.agentpad.workspace.ai.SandboxInstaller.rootfsDir(this)
        if (!rootfs.isDirectory || !initScript.isFile) {
            Toast.makeText(this, "沙箱尚未初始化，请先让 AI 执行一次终端命令", Toast.LENGTH_LONG).show()
            return
        }
        // 工具链没装时先拦一下，给出明确指引（避免用户干等）
        val toolchain = java.io.File(rootfs, "opt/video-buildchain/pypkgs")
        if (!toolchain.isDirectory) {
            android.app.AlertDialog.Builder(this)
                .setTitle("缺少视频工具链")
                .setMessage("渲染需要「视频工具链」。\n\n请到「设置 → 下载环境」里下载并安装『视频工具链』，安装完成后回来再点渲染。")
                .setPositiveButton("去下载环境") { _, _ ->
                    startActivity(android.content.Intent(this, DownloadEnvironmentActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        videoRendering = true
        runCatching { WorkingBubble.start(this, session.id) }
        Toast.makeText(this, "开始渲染视频…", Toast.LENGTH_SHORT).show()
        Thread {
            val runner = com.agentpad.workspace.ai.SandboxRunner(
                sandboxDir = com.agentpad.workspace.ai.SandboxInstaller.sandboxDir(this),
                rootfs = rootfs,
                nativeDir = com.agentpad.workspace.ai.SandboxInstaller.nativeDir(this),
                initScript = initScript,
                projectDir = sessionWorkspace,
                monitorSessionId = com.agentpad.workspace.ai.TerminalService.aiSessionId(session.id),
            )
            val out = runCatching {
                runner.run(com.agentpad.workspace.ai.TerminalRequest(
                    command = "cd " + sessionWorkspace.absolutePath + " && bash build.sh",
                    cwd = sessionWorkspace.absolutePath,
                    timeoutMs = 600_000L,
                ))
            }.getOrElse { com.agentpad.workspace.ai.TerminalOutput(-1, "", it.message ?: "渲染失败") }
            val ok = out.exitCode == 0 && java.io.File(sessionWorkspace, "out.mp4").exists()
            videoRendering = false
            runCatching { WorkingBubble.stopIfShowing(this, session.id) }
            runOnUiThread {
                if (ok) {
                    val size = java.io.File(sessionWorkspace, "out.mp4").length()
                    Toast.makeText(this, "渲染完成（${size / 1024} KB），点「预览视频」查看", Toast.LENGTH_LONG).show()
                } else {
                    val detail = (out.stderr.ifBlank { out.stdout }).takeLast(600)
                    android.app.AlertDialog.Builder(this)
                        .setTitle("渲染失败")
                        .setMessage(if (out.exitCode == 124) "渲染超时，请重试或减少场景时长。\n\n$detail" else "退出码 ${out.exitCode}\n\n$detail")
                        .setPositiveButton("好", null)
                        .show()
                }
            }
        }.start()
    }

    /** Model chip label for the Compose composer (the picker itself is
     *  showModelPicker(); the old native slot/menu wiring is gone). */
    private fun activeModelLabel(): String {
        val p = ChatStore.activeProfile(this)
        return p.optString("model").ifBlank { "选择模型" }
    }


    private fun bulbIconRes(level: ReasoningLevel): Int = when (level) {
        ReasoningLevel.OFF -> R.drawable.ic_bulb_off
        ReasoningLevel.AUTO -> R.drawable.ic_bulb_auto
        ReasoningLevel.LOW -> R.drawable.ic_bulb_low
        ReasoningLevel.MEDIUM -> R.drawable.ic_bulb_medium
        ReasoningLevel.HIGH -> R.drawable.ic_bulb_high
        ReasoningLevel.XHIGH -> R.drawable.ic_bulb_xhigh
        ReasoningLevel.MAX -> R.drawable.ic_bulb_max
    }

    private fun showReasoningPicker() {
        val levels = listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH
        )
        val selected = levels.indexOf(reasoningLevel).takeIf { it >= 0 } ?: 1
        val accent = Color.rgb(140, 109, 240)
        val ink = Color.rgb(16, 19, 26)
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.bg_reasoning_sheet)
            setPadding(dp(34), dp(18), dp(34), dp(22))
        }

        sheet.addView(View(this).apply { background = getDrawable(R.drawable.bg_reasoning_handle) }, LinearLayout.LayoutParams(dp(54), dp(6)).apply { bottomMargin = dp(34) })
        sheet.addView(TextView(this).apply {
            text = "调整模型思考深度"
            textSize = 28f
            setTextColor(ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(TextView(this).apply {
            text = "#非所有模型都支持深度调整。请参考模型和提供商文档。"
            textSize = 15f
            setTextColor(themeArgb { it.inkSoft })
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(26))
        }, LinearLayout.LayoutParams(-1, -2))

        val heroIcon = ImageView(this).apply {
            setImageResource(bulbIconRes(levels[selected]))
            setColorFilter(if (levels[selected] == ReasoningLevel.OFF) ink else accent)
        }
        sheet.addView(heroIcon, LinearLayout.LayoutParams(dp(58), dp(58)).apply { bottomMargin = dp(10) })
        val levelTitle = TextView(this).apply {
            text = if (levels[selected] == ReasoningLevel.MEDIUM) "中等" else levels[selected].label
            textSize = 25f
            setTextColor(ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        sheet.addView(levelTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(30) })

        val segmentViews = mutableListOf<TextView>()
        val labelBars = mutableListOf<View>()
        val labelTexts = mutableListOf<TextView>()
        fun applySheetSelection(level: ReasoningLevel) {
            val selectedIndex = levels.indexOf(level).takeIf { it >= 0 } ?: 1
            reasoningLevel = level
            ChatStore.saveReasoningLevel(this@ChatActivity, level.name)
            heroIcon.setImageResource(bulbIconRes(level))
            heroIcon.setColorFilter(if (level == ReasoningLevel.OFF) ink else accent)
            levelTitle.text = if (level == ReasoningLevel.MEDIUM) "中等" else level.label
            segmentViews.forEachIndexed { index, view ->
                view.setTextColor(if (index <= selectedIndex) Color.WHITE else Color.rgb(124, 92, 104))
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (index <= selectedIndex) accent else Color.TRANSPARENT)
                }
                view.animate().scaleX(if (index == selectedIndex) 1.06f else 1f).scaleY(if (index == selectedIndex) 1.06f else 1f).setDuration(130).start()
            }
            labelBars.forEachIndexed { index, view ->
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (index == selectedIndex) accent else Color.rgb(205, 209, 218))
                }
            }
            labelTexts.forEachIndexed { index, view ->
                view.setTextColor(if (index == selectedIndex) accent else Color.rgb(74, 76, 86))
            }
        }

        val trackWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val trackBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(238, 240, 244)) }
        trackWrap.background = trackBg
        trackWrap.setPadding(0, 0, 0, 0)
        levels.forEachIndexed { index, level ->
            val segment = TextView(this).apply {
                text = "•"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(if (index <= selected) Color.WHITE else Color.rgb(124, 92, 104))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (index <= selected) accent else Color.TRANSPARENT)
                }
                setOnClickListener { applySheetSelection(level) }
            }
            segmentViews.add(segment)
            trackWrap.addView(segment, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        sheet.addView(trackWrap, LinearLayout.LayoutParams(-1, dp(34)).apply { leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(22) })

        val labels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        levels.forEachIndexed { index, level ->
            val selectedLabel = index == selected
            val labelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val bar = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (selectedLabel) accent else Color.rgb(205, 209, 218))
                }
            }
            labelBars.add(bar)
            labelBox.addView(bar, LinearLayout.LayoutParams(dp(40), dp(6)).apply { bottomMargin = dp(10) })
            val labelText = TextView(this).apply {
                text = if (level == ReasoningLevel.MEDIUM) "中等" else level.label
                textSize = 16f
                setTextColor(if (selectedLabel) accent else Color.rgb(74, 76, 86))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            labelTexts.add(labelText)
            labelBox.addView(labelText)
            labelBox.setOnClickListener { applySheetSelection(level) }
            labels.addView(labelBox, LinearLayout.LayoutParams(0, -2, 1f))
        }
        sheet.addView(labels, LinearLayout.LayoutParams(-1, -2))

        dialog.setContentView(sheet)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.35f)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48f).toInt())
            w.attributes = w.attributes.apply { windowAnimations = R.style.ReasoningSheetAnimation }
        }
        dialog.show()
        dialog.window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48f).toInt())
    }

    /** Keeps the hidden EditText buffer (used by send()/attachments) mirrored
     *  into the Compose composer's state. */
    private fun setupSendButtonState() {
        binding.input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                if (composeInput.value != text) composeInput.value = text
            }
        })
    }
private fun send(): String? {
        if (busy) { abortActiveRequests(); return null }
        val text = binding.input.text.toString().trim()
        val selected = attachments.current()
        if (text.isEmpty() && selected.isEmpty()) return null
        val apiContent = if (selected.isEmpty()) null else attachments.buildContent(sessionWorkspace, text)
        transientAttachmentContent = apiContent
        val displayText = buildString { append(text); selected.forEach { if (isNotEmpty()) append("\n"); append(if (it.image) "🖼️ ${it.name}" else "📎 ${it.name}") } }
        binding.input.setText("")
        composeInput.value = ""
        // Persist only a short attachment summary. Image Base64 stays in memory for this request only.
        // ⚠ 主线程不做整段会话序列化：append() 会把整个 session 转成 JSON 再写盘，
        // 几百条消息时是几十 MB 的字符串拼装，放在点击回调里就是肉眼可见的卡顿。
        // 这里改为后台线程写，写完再启动本轮。
        userPagedBack = false
        windowStartIndex = -1
        attachments.clear()
        ChatStore.append(this, session.id, "user", displayText)
        syncSessionFromStore()
        return startTurn()
    }


    /** 开始一轮请求：建 assistant 占位 + 忙于状态 + 悬浮窗 + 后台线程。 */
    private fun startTurn(): String? {
        if (busy) return null
        val assistantTurnId = ChatStore.addAssistantTurn(this, session.id).id
        busy = true
        abortRequested = false
        // Regeneration follows the standard single-turn agent loop.
        setBusyUi(true)
        roundStats = RoundStats()
        runCatching { WorkingBubble.start(this, session.id) }
        runOnUiThread { refreshComposeMessages() }
        Thread { request(assistantTurnId) }.start()
        return assistantTurnId
    }

    /**
     * 重新生成助手回复 —— 照抄 RikkaHub `ChatService.regenerateAtMessage` +
     * `runTurn(session, regenerateNodeId)`（ChatService.kt:466-521 / :577-607）。
     *
     * 原版语义：
     *   - `history = currentMessages.take(index)` ：只把该节点**之前**的消息发给模型
     *   - `replyNodeId = regenerateNodeId`        ：新回复挂回**同一个节点**，成为它的新分支
     *   - 用户消息原样保留，不 rewind、不重发、不新增消息
     *
     * 我们的实现：
     *   1. 清空该节点已有的临时输出（它就是要被重写的那一条）
     *   2. 旧分支归档进 alternatives（用 replaceMessage 落盘）
     *   3. 直接用**同一个 message id** 当作本轮 turnId 起请求
     *      —— request() 内部走 setAssistantPartsCached(messageId)，正好写回这条
     *   4. 流式过程中 selectIndex 指向"新回复"那个位置
     *
     * @param regenerateNodeId 被重新生成的消息 id（新回复写到这条上）
     * @param regenerateBranches 旧分支集合（旧回复 + 它原有的 alternatives）
     */
    private fun startTurn(regenerateNodeId: String, regenerateBranches: List<ChatStore.Message>) {
        if (busy) return
        // 1) 旧本体清空成占位 + 归档旧分支
        val current = ChatStore.loadSession(this, session.id)?.messages.orEmpty()
        val node = current.firstOrNull { it.id == regenerateNodeId } ?: return
        val placeholder = node.copy(
            content = "",
            thinking = "",
            stats = "",
            parts = emptyList(),
            kind = "snapshot",
            alternatives = regenerateBranches,
            // 合并视图 = alternatives + 本体，新回复本体排在最后
            selectIndex = regenerateBranches.size,
        )
        ChatStore.replaceMessage(this, session.id, placeholder)

        busy = true
        abortRequested = false
        // Regeneration starts a fresh single assistant turn.
        setBusyUi(true)
        roundStats = RoundStats()
        runCatching { WorkingBubble.start(this, session.id) }
        runOnUiThread { refreshComposeMessages() }
        // 2) 同一个 message id 起请求：流式内容会写回这条消息本体。
        //    同时把该 id 当作历史截断点 —— 只发它之前的消息（ChatService.kt:596-599）。
        Thread { request(regenerateNodeId, regenerateNodeId) }.start()
    }

/**
     * 把本轮统计序列化成 JSON 存进 message.stats。
     *
     * 旧实现是拼一串中文文本（"⏱ 3.2s · ↑123 · ↓456 · Σ579 tokens · 缓存 78%"），
     * 界面只能原样显示。换成 JSON 后，NerdLine 可以按 RikkaHub 的规格渲染成
     * 「步数 / 速度 / 上下文（已缓存）」三段 FlowRow，点击还能弹出用量详情面板。
     */
    private fun formatStats(s: RoundStats, running: Boolean): String {
        val secs = s.elapsedMs / 1000.0
        return buildString {
            append('{')
            append("\"secs\":").append(String.format(java.util.Locale.US, "%.2f", secs)).append(',')
            append("\"prompt\":").append(s.promptTokens).append(',')
            append("\"completion\":").append(s.completionTokens).append(',')
            append("\"cached\":").append(s.cachedTokens).append(',')
            append("\"steps\":").append(s.steps).append(',')
            append("\"lastPrompt\":").append(s.lastPromptTokens).append(',')
            append("\"lastCompletion\":").append(s.lastCompletionTokens).append(',')
            append("\"lastCached\":").append(s.lastCachedTokens).append(',')
            append("\"tps\":").append(String.format(java.util.Locale.US, "%.2f", s.tps)).append(',')
            append("\"running\":").append(running)
            append('}')
        }
    }

    /** Merge one API usage payload into the current round accumulator. */
    private fun accumulateUsage(u: JSONObject) {
        val s = roundStats ?: return
        val p = u.optLong("prompt_tokens")
        val c = u.optLong("completion_tokens")
        s.promptTokens += p
        s.completionTokens += c
        val cached = u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens")
            ?: u.optJSONObject("input_tokens_details")?.optLong("cached_tokens")
            ?: u.optLong("cache_hit_tokens")
        s.cachedTokens += cached
        // nerd line 的「上下文占用」只显示最后一次请求，不是累计值。
        // 每个 usage 块都是一次独立请求的统计，所以直接覆盖。
        if (p > 0 || c > 0) {
            s.lastPromptTokens = p
            s.lastCompletionTokens = c
            s.lastCachedTokens = cached
        }
    }

    private fun request(assistantTurnId: String, regenerateBeforeId: String? = null) {
        val generation = requestGeneration
        val api = ChatStore.api(this)
        if (api.first.isBlank() || api.second.isBlank() || api.third.isBlank()) {
            // 配置缺失也是「错误」，同样走提示框，不往上下文里塞一行文字。
            failTurn(
                assistantTurnId, generation,
                IllegalStateException("还没有配置 API 地址 / Key / 模型。请到「设置 → API 配置」填好再发。"),
            )
            return
        }
        var forceReasoningContext = ChatStore.reasoningInContext(this)
        // ⚠ 重新生成：只发该节点之前的消息（照抄 ChatService.kt:596-599）
        var loopMessages = JSONArray(conversationJson(regenerateBeforeId).toString())
        try {
            var turnId = assistantTurnId

            // Every round of this turn appends into ONE ordered part list, so
            // reasoning / tool / body stay in arrival order across all rounds.
            val allParts = mutableListOf<ChatStore.Part>()

            // `post()` streams chunks into a LOCAL list for this request only.
            // Here we merge the already-committed history with that live tail and
            // persist the union — that is what makes thinking appear token by
            // token without wiping earlier rounds.
            var liveRound: List<ChatStore.Part> = emptyList()
            fun publishLive() {
                // 流式 chunk：只更新内存 + 异步落盘，主线程 UI 刷新不再等锁。
                runCatching {
                    ChatStore.setAssistantPartsCached(
                        this, session.id, turnId,
                        allParts + liveRound, complete = false,
                    )
                }
                // ★ 节流：思考阶段 token 极密（每秒几十次），
                //   以前每个 chunk 都 refreshComposeMessages() 全量重载 session，
                //   主线程被 JSON 解析塞满 → 切后台回来卡死、背景动画停摆。
                //   现在合并成最多每 100ms 一次。
                requestStreamRefresh()
            }

            var response = post(api, loopMessages, true, turnId) { live ->
                liveRound = live
                publishLive()
            }
            var calls = ToolProtocol.extractToolCalls(response)
            fun roundText(resp: JSONObject): String = runCatching {
                resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
            }.getOrDefault("")
            fun respParts(resp: JSONObject): List<ChatStore.Part> = runCatching {
                val arr = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optJSONArray("_parts")
                buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(ChatStore.Part(
                            type = o.optString("type", "text"),
                            text = o.optString("text"),
                            toolCallId = o.optString("toolCallId"),
                            toolName = o.optString("toolName"),
                            toolArguments = o.optString("toolArguments"),
                            toolResult = o.optString("toolResult"),
                            addedLines = o.optInt("addedLines", 0),
                            removedLines = o.optInt("removedLines", 0),
                            running = o.optBoolean("running", false),
                        ))
                    }
                }
            }.getOrDefault(emptyList())

            var roundNo = 0
            while (true) {
                if (abortRequested) return
                roundNo++
                if (roundNo == 49) {
                    failTurn(turnId, generation, IllegalStateException("工具调用连续 48 轮仍未收敛，已自动停止（防死循环 / 内存膨胀）。"))
                    return
                }
                // This round finished: fold its live tail into the committed
                // history and clear the tail so the next round starts clean
                // (otherwise the next request would stack on top of it).
                allParts.addAll(respParts(response))
                liveRound = emptyList()
                ChatStore.setAssistantPartsCached(this, session.id, turnId, allParts.toList(), complete = calls.isEmpty())
                runOnUiThread { refreshComposeMessages() }

                if (calls.isEmpty()) {
                    // ⚠ 关键修复（2026-10-19）：收尾取正文必须是「用户已经看到的
                    // 那份原文」，不能再用 cleanAssistantText 二次加工。
                    // allParts 里的 text 片段就是流式阶段逐块显示的内容（未清洗），
                    // 一旦这里换成清洗后的版本，就会出现「流完了 → 字被吞」。
                    // 只有真·完全空白时才回退到接口原文，最后才是占位提示。
                    val streamedText = allParts.filter { it.type == "text" }
                        .joinToString("\n\n") { it.text }.trim()
                    val finalText = streamedText.ifBlank { roundText(response).trim() }
                    val finalThink = allParts.filter { it.type == "reasoning" }
                        .joinToString("\n\n") { it.text }.trim()
                    val msgObj = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val truncated = msgObj?.optBoolean("_stream_truncated") == true
                    val parseFails = msgObj?.optInt("_parse_failures", 0) ?: 0
                    if (truncated || parseFails != 0) {
                        val why = if (truncated)
                            "上游提前断开（没收到 finish_reason / [DONE]），本轮只收到 " + finalText.length + " 字"
                        else
                            "有 " + parseFails + " 个流数据块解析失败（可能丢了正文）"
                        failTurn(turnId, generation, java.io.IOException(why + "。已收到的内容已保留，可点重试继续。"))
                        return
                    }
                    if (finalText.isBlank() && finalThink.isBlank()) {
                        // 「接口 200 但正文和思考都空」——这也是失败（上游掉包/被吞），
                        // 以前会写一句「接口没有返回正文。」进上下文，纯污染。
                        // 现在当错误处理：删占位 + 弹提示框。
                        failTurn(turnId, generation, IllegalStateException("上游返回成功但正文为空（可能是中转站掉包或内容被过滤）。"))
                        return
                    }
                    completeTurn(turnId, finalText, finalThink, generation)
                    return
                }

                val marker = calls.joinToString("\n") { "<tool_call>{\"name\":\"${it.name}\",\"arguments\":${it.argumentsJson}}</tool_call>" }
                // 这一轮的思考内容必须跟着 toolintent 一起落到存储里，否则下一轮
                // 回放时那条 assistant 消息没有 reasoning_content，网关 400。
                val roundThinking = allParts.filter { it.type == "reasoning" }.joinToString("\n\n") { it.text }.trim()
                ChatStore.appendAssistantTools(this, session.id, marker, roundThinking)
                // 当前工具调用由 agent loop 处理。
                roundStats?.let { it.steps += 1 }   // nerd line 的「N 步」
                val callIds = calls.mapIndexed { i, c -> c.id.ifBlank { "call_$i" } }
                calls.forEachIndexed { i, c ->
                    val callId = callIds[i]
                    val (added, removed) = completeToolLineStats(c.name, c.argumentsJson)
                    val existing = allParts.indexOfLast {
                        it.type == "tool" && (
                            it.toolCallId == callId ||
                                (it.toolCallId.startsWith("stream_") && it.toolName == c.name)
                            )
                    }
                    val next = ChatStore.Part(
                        type = "tool",
                        toolCallId = callId,
                        toolName = c.name,
                        toolArguments = c.argumentsJson,
                        addedLines = added,
                        removedLines = removed,
                        running = true,
                    )
                    if (existing >= 0) allParts[existing] = next else allParts.add(next)
                }
                ChatStore.setAssistantPartsCached(this, session.id, turnId, allParts.toList(), complete = false)
                runOnUiThread { refreshComposeMessages() }

                val transientImageResults = mutableMapOf<String, String>()
                calls.forEachIndexed { i, c ->
                    // ⚠ ask_user 会阻塞等用户答题（AskUserHub）。阻塞期间：
                    //   1) 每秒把 UI 状态刷一次，让「停止生成」按钮仍然可用；
                    //   2) abortRequested / AskUserHub.cancel 任一触发就打断等待。
                    // 这样既保住 RikkaHub 的「AI 停下来问、用户答完继续」语义，
                    // 又不会把请求线程永久挂住。
                    val result = if (c.name == "ask_user") {
                        var done = false
                        var res: ToolResult? = null
                        val fan = Thread {
                            res = toolRegistry.execute(c)
                            done = true
                        }
                        fan.isDaemon = true
                        fan.start()
                        while (!done) {
                            if (abortRequested) {
                                AskUserHub.cancel()
                                break
                            }
                            runOnUiThread { refreshComposeMessages() }
                            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                        }
                        res ?: ToolResult(callIds[i], c.name, "{\"success\":false,\"cancelled\":true}")
                    } else {
                        toolRegistry.execute(c)
                    }
                    if (com.agentpad.workspace.ai.ImageGenService.extractDataUrl(result.content) != null) {
                        transientImageResults[callIds[i]] = result.content
                    }
                    val idx = allParts.indexOfLast { it.type == "tool" && it.toolCallId == callIds[i] }
                    if (idx >= 0) allParts[idx] = allParts[idx].copy(toolResult = com.agentpad.workspace.ai.ImageGenService.stripDataUrl(result.content), running = false)
                    ChatStore.setAssistantPartsCached(this, session.id, turnId, allParts.toList(), complete = false)
                    runOnUiThread { refreshComposeMessages() }
                }

                val assistantTurn = JSONObject().put("role", "assistant").put("content", marker)
                val declaredCalls = JSONArray()
                calls.forEachIndexed { i, c ->
                    declaredCalls.put(JSONObject()
                        .put("id", callIds[i])
                        .put("type", "function")
                        .put("function", JSONObject().put("name", c.name).put("arguments", c.argumentsJson)))
                }
                assistantTurn.put("tool_calls", declaredCalls)
                loopMessages.put(assistantTurn)
                calls.forEachIndexed { i, item ->
                    val res = allParts.firstOrNull { it.type == "tool" && it.toolCallId == callIds[i] }?.toolResult.orEmpty()
                    val imgMsg = com.agentpad.workspace.ai.ImageGenService
                        .toolMessageWithImage(callIds[i], item.name, transientImageResults.remove(callIds[i]) ?: res)
                    loopMessages.put(imgMsg ?: JSONObject().put("role", "tool").put("tool_call_id", callIds[i]).put("name", item.name).put("content", res))
                }
                // Follow-up round reuses the same callback so its thinking
                // also streams into the bubble instead of landing at once.
                response = post(api, loopMessages, true, turnId) { live ->
                    liveRound = live
                    publishLive()
                }
                calls = ToolProtocol.extractToolCalls(response)
            }
        } catch (error: Exception) {
            if (abortRequested) return
            val msg = error.message ?: ""
            if (!forceReasoningContext && msg.contains("reasoning_content") && msg.contains("passed back")) {
                forceReasoningContext = true
                ChatStore.saveReasoningInContext(this, true)
                runOnUiThread { runCatching { Toast.makeText(this, "检测到思考模式：已自动开启思考回传并重试", Toast.LENGTH_LONG).show() } }
                try {
                    // 关键：重试必须用"开启了思考回传"重建成的新请求体。
                    // 直接复用旧 loopMessages 会原样再撞一次 400，用户看到的就是反复失败。
                    loopMessages = JSONArray(conversationJson().toString())
                    var rTurn = assistantTurnId
                    // Retry path keeps its own committed list + live tail, so its
                    // rounds also stream token-by-token.
                    val rParts = mutableListOf<ChatStore.Part>()
                    var rLive: List<ChatStore.Part> = emptyList()
                    fun publishRLive() {
                        runCatching {
                            ChatStore.setAssistantPartsCached(
                                this, session.id, rTurn, rParts + rLive, complete = false,
                            )
                        }
                        // 同样走节流，重试路径也是逐 token 回调
                        requestStreamRefresh()
                    }
                    var rResponse = post(api, loopMessages, true, rTurn) { live ->
                        rLive = live
                        publishRLive()
                    }
                    var rCalls = ToolProtocol.extractToolCalls(rResponse)
                    while (true) {
                        if (abortRequested) return
                        val (body, fold) = normalizeAnswer(
                            rResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: "",
                            sanitize(rResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("_streamed_reasoning") ?: ""),
                            hasToolCalls = rCalls.isNotEmpty()
                        )
                        // ⚠ 关键修复（2026-10-19）：重试路径原先直接
                        //   rLive = emptyList(); rParts.add(清洗后的 body)
                        // 等于把「已经流式显示的原文」换成 cleanAssistantText 二次
                        // 加工的结果。一旦清洗把它删空，气泡整段消失。
                        // 现在优先用这一轮真实的流式 parts（未清洗），只有拿不到
                        // _parts（非流式回包）时才退回清洗后的 body/fold。
                        val rMessage = rResponse.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        val rawRoundParts = runCatching {
                            val arr = rMessage?.optJSONArray("_parts")
                            buildList {
                                if (arr != null) for (i in 0 until arr.length()) {
                                    val o = arr.optJSONObject(i) ?: continue
                                    val t = o.optString("type", "text")
                                    val tx = o.optString("text")
                                    if (tx.isNotEmpty() || t == "tool") add(ChatStore.Part(
                                        type = t,
                                        text = tx,
                                        toolCallId = o.optString("toolCallId"),
                                        toolName = o.optString("toolName"),
                                        toolArguments = o.optString("toolArguments"),
                                        toolResult = o.optString("toolResult"),
                                        addedLines = o.optInt("addedLines", 0),
                                        removedLines = o.optInt("removedLines", 0),
                                        running = o.optBoolean("running", false),
                                    ))
                                }
                            }
                        }.getOrDefault(emptyList())
                        rLive = emptyList()
                        if (rawRoundParts.isNotEmpty()) {
                            rParts.addAll(rawRoundParts)
                        } else {
                            if (fold.isNotBlank()) rParts.add(ChatStore.Part("reasoning", text = fold))
                            if (body.isNotBlank()) rParts.add(ChatStore.Part("text", text = body))
                        }
                        ChatStore.setAssistantPartsCached(this, session.id, rTurn, rParts.toList(), complete = rCalls.isEmpty())
                        runOnUiThread { refreshComposeMessages() }
                        if (rCalls.isEmpty()) {
                            val rStreamed = rParts.filter { it.type == "text" }
                                .joinToString("\n\n") { it.text }.trim()
                            val rFinal = rStreamed.ifBlank { body }
                            val rThink = rParts.filter { it.type == "reasoning" }
                                .joinToString("\n\n") { it.text }.trim().ifBlank { fold }
                            if (rFinal.isBlank() && rThink.isBlank()) {
                                // 同主路径：200 却全空 = 失败，绝不写占位文字进历史。
                                failTurn(rTurn, generation, IllegalStateException("上游返回成功但正文为空（可能是中转站掉包或内容被过滤）。"))
                                return
                            }
                            completeTurn(rTurn, rFinal, rThink, generation)
                            return
                        }
                        val marker = rCalls.joinToString("\n") { "<tool_call>{\"name\":\"${it.name}\",\"arguments\":${it.argumentsJson}}</tool_call>" }
                        // 重试路径同样要把这一轮思考带进 toolintent，否则下一轮继续 400。
                        val rRoundThinking = rParts.filter { it.type == "reasoning" }.joinToString("\n\n") { it.text }.trim()
                        ChatStore.appendAssistantTools(this, session.id, marker, rRoundThinking)
                        val callIdsR = rCalls.mapIndexed { i, c -> c.id.ifBlank { "call_$i" } }
                        rCalls.forEachIndexed { i, c ->
                            val callId = callIdsR[i]
                            val (added, removed) = completeToolLineStats(c.name, c.argumentsJson)
                            val existing = rParts.indexOfLast {
                                it.type == "tool" && (
                                    it.toolCallId == callId ||
                                        (it.toolCallId.startsWith("stream_") && it.toolName == c.name)
                                    )
                            }
                            val next = ChatStore.Part(
                                type = "tool",
                                toolCallId = callId,
                                toolName = c.name,
                                toolArguments = c.argumentsJson,
                                addedLines = added,
                                removedLines = removed,
                                running = true,
                            )
                            if (existing >= 0) rParts[existing] = next else rParts.add(next)
                        }
                        ChatStore.setAssistantPartsCached(this, session.id, rTurn, rParts.toList(), complete = false)
                        runOnUiThread { refreshComposeMessages() }
                        val declared = JSONArray()
                        rCalls.forEachIndexed { i, c ->
                            declared.put(JSONObject().put("id", callIdsR[i]).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.argumentsJson)))
                        }
                        val assistantTurn = JSONObject().put("role", "assistant").put("content", marker).put("tool_calls", declared)
                        loopMessages.put(assistantTurn)
                        rCalls.forEachIndexed { i, c ->
                            val result = toolRegistry.execute(c)
                            val idx = rParts.indexOfLast { it.type == "tool" && it.toolCallId == callIdsR[i] }
                            if (idx >= 0) rParts[idx] = rParts[idx].copy(toolResult = com.agentpad.workspace.ai.ImageGenService.stripDataUrl(result.content), running = false)
                            ChatStore.setAssistantPartsCached(this, session.id, rTurn, rParts.toList(), complete = false)
                            runOnUiThread { refreshComposeMessages() }
                            val imgMsgR = com.agentpad.workspace.ai.ImageGenService
                                .toolMessageWithImage(callIdsR[i], c.name, result.content)
                            loopMessages.put(imgMsgR ?: JSONObject().put("role", "tool").put("tool_call_id", callIdsR[i]).put("name", c.name).put("content", result.content))
                        }
                        rTurn = ChatStore.addAssistantTurn(this, session.id).id
                        rResponse = post(api, loopMessages, true, rTurn) { live ->
                            rLive = live
                            publishRLive()
                        }
                        rCalls = ToolProtocol.extractToolCalls(rResponse)
                    }
                } catch (retryError: Exception) {
                    // ⚠ 2026-11-13：这里原来写的是
                    //   completeTurn(assistantTurnId, "⚠ 请求失败（已重试）：$detail", ...)
                    // 那就是把报错文本当成 assistant 正文落盘 —— 下一轮请求整段
                    // 带上去，模型把「HTTP 500」当上下文，越聊越歪。
                    // 现在改走提示框：一个字都不进 session。
                    failTurn(assistantTurnId, generation, retryError)
                    return
                }
            }
            runOnUiThread {
                // ⚠ 2026-11-13 主人要求：**报错不许污染上下文**。
                //
                // 旧实现（历史注释记录的就是当时现场）：
                //   persisted = 已落盘的 content
                //   text = if (persisted.isNotBlank()) persisted else "⚠ 请求失败：$msg"
                //   updateAssistantTurn(text)
                // 两个死症：
                //   1) 把「⚠ 请求失败：HTTP 500…」当 assistant 正文写进 session，
                //      下一轮请求整段带上去 → 上下文被污染；
//

                // 现在：正文已流出 → 只把流式态收成完成态，内容原样保留；
                //       一个字没有 → 直接删掉空占位（不留空气泡进历史）；
                //       错误只走提示框，一个字都不落盘。
                failTurn(assistantTurnId, generation, error)
            }
        }
    }

    /**
     * 请求彻底失败（重试 5 次仍失败）后的收尾 —— **错误不进上下文**。
     *
     * 对齐参考源码 RikkaHub 的错误呈现路径：
     *   `service/ChatService.kt:155-169` 把失败塞进 `_errors: StateFlow<List<ChatError>>`，
     *   由 `ui/components/ui/ErrorCard.kt:16-54 & 56-99` 在对话上方渲染红色卡片，
     *   卡片只显示 `error.error.message`，**从不写进消息列表、从不进入下一次请求**。
     * 我们这里没有 ChatError 流，改用等价的 AlertDialog（项目里到处都在用它）。
     *
     * 同时按主人要求加「重试」按钮，对应参考实现 `MediaCreationService.retry()`
     * 那种「失败可一键重发」的语义。
     */
    private fun failTurn(assistantTurnId: String, generation: Int, error: Throwable) = runOnUiThread {
        if (requestGeneration != generation) return@runOnUiThread
        val node = ChatStore.loadSession(this, session.id)?.messages
            ?.firstOrNull { it.id == assistantTurnId }
        val hasVisible = node != null && (
            node.content.isNotBlank() ||
                node.thinking.isNotBlank() ||
                node.parts.any { it.type == "text" && it.text.isNotBlank() }
            )
        busy = false
        setBusyUi(false)
        roundStats = null
        if (hasVisible) {
            // 半截正文留给用户看，但把 kind 从 "snapshot"(流式中) 收成完成态。
            runCatching { ChatStore.setAssistantStreaming(this, session.id, assistantTurnId, false) }
        } else if ((node?.alternatives?.size ?: 0) > 0) {
            // 「重新生成」失败：这条消息身上挂着旧分支，直接删会把分支一起丢掉。
            // 保留消息壳、内容清空即可 —— 空 assistant 消息在 conversationJson()
            // 里会被显式跳过（`m.role == "assistant" && content 空 && parts 空` → Unit），
            // 所以它既不会污染上下文，也不会丢失历史分支。
            runCatching {
                ChatStore.replaceMessage(
                    this, session.id,
                    node!!.copy(content = "", thinking = "", stats = "", parts = emptyList(), kind = "text"),
                )
            }
        } else {
            // 完全没内容 → 删掉空占位，绝不让「空气泡」留在历史里。
            runCatching { ChatStore.removeAssistantTurn(this, session.id, assistantTurnId) }
        }
        refreshComposeMessages()
        runCatching { refreshPlanPanel() }
        runCatching { WorkingBubble.stopIfShowing(this, session.id) }
        // 收尾后副标题恢复模型名（重试提示是临时占位）。
        runCatching { refreshTopBarSubtitle() }
        // ⚠ 失败**不再自动补「继续」**：那会往历史里塞用户消息，同样属于污染上下文。
        // 续跑与否交给提示框上的「重试」按钮，由用户决定。
        runCatching { com.agentpad.workspace.ai.ChatErrorLog.record(this, error, "turn") }
        showChatErrorBanner(
            title = errorTitleOf(error),
            detail = errorDetailOf(error, hasVisible),
            onRetry = java.lang.Runnable { retryTurn(assistantTurnId) },
            onDetail = java.lang.Runnable { showRequestErrorDialog(assistantTurnId, generation, error, hasVisible) },
        )
    }

    /**
     * 报错提示框 —— 就是参考源码 ErrorCard 的原生版。
     *
     * 内容只做一件事：把 `error.message` 原样摊给用户看（照抄 ErrorCard.kt:93-98
     * 的 `text = error.error.message ?: "Unknown error"`），
     * 再给三个动作：重试 / 复制错误 / 关闭。
     */
    // ---- 错误浮条（照 RikkaHub 3.2.0 ErrorCard 的语义：不打断输入，可重试 / 看详情）----

    private var chatErrorBanner: android.view.View? = null

    private fun errorTitleOf(error: Throwable): String = when {
        error is java.net.UnknownHostException -> "网络不可达"
        error is java.net.SocketTimeoutException -> "请求超时（上游长时间无响应）"
        error.message.orEmpty().contains("提前断开", true) -> "上游断开，回答不完整"
        error.message.orEmpty().contains("解析失败", true) -> "部分内容在传输中丢失"
        error is java.io.IOException -> "网络中断"
        else -> "请求失败"
    }

    private fun errorDetailOf(error: Throwable, hasVisible: Boolean): String {
        val base = (error.message ?: error.javaClass.simpleName).take(500)
        return if (hasVisible) base + "\n（本轮已收到的内容已保留在对话里）" else base
    }

    /** 重试：用同一个 assistant 占位重跑，保留已有分支。 */
    private fun retryTurn(assistantTurnId: String) {
        if (busy) return
        busy = false
        val node = ChatStore.loadSession(this, session.id)?.messages?.firstOrNull { it.id == assistantTurnId }
        if (node == null) {
            startTurn()
        } else {
            val oldBranches = (node.alternatives + node.copy(alternatives = emptyList(), selectIndex = 0))
                .filter { it.content.isNotBlank() || it.parts.isNotEmpty() || it.thinking.isNotBlank() }
            startTurn(regenerateNodeId = assistantTurnId, regenerateBranches = oldBranches)
        }
    }

    private fun dismissChatErrorBanner() {
        val v = chatErrorBanner
        chatErrorBanner = null
        runCatching { v?.let { vv -> (vv.parent as? android.view.ViewGroup)?.removeView(vv) } }
    }

    /** 非阻塞错误浮条：贴在内容区顶部，带 重试 / 详情 / 复制 / 关闭。 */
    private fun showChatErrorBanner(
        title: String,
        detail: String,
        onRetry: java.lang.Runnable? = null,
        onDetail: java.lang.Runnable? = null,
    ) {
        runCatching {
            dismissChatErrorBanner()
            val d = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * d).toInt()
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xF7FFF2F0.toInt())
                    setStroke(dp(1), 0x55D33025)
                }
            }
            card.addView(android.widget.TextView(this).apply {
                text = title
                setTextColor(0xFFB3261E.toInt())
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(android.widget.TextView(this).apply {
                text = detail
                setTextColor(0xFF7A2C26.toInt())
                textSize = 11.5f
                maxLines = 6
                setPadding(0, dp(4), 0, 0)
            })
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(4), 0, 0)
            }
            fun action(label: String, act: java.lang.Runnable) {
                row.addView(android.widget.TextView(this).apply {
                    text = label
                    textSize = 12.5f
                    setTextColor(0xFFB3261E.toInt())
                    setPadding(dp(12), dp(6), dp(4), dp(6))
                    setOnClickListener { act.run() }
                })
            }
            if (onRetry != null) action("重试", java.lang.Runnable { dismissChatErrorBanner(); onRetry.run() })
            if (onDetail != null) action("详情", java.lang.Runnable { onDetail.run() })
            action("复制", java.lang.Runnable {
                runCatching {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("error", title + "\n" + detail))
                    toastShort("错误信息已复制")
                }
            })
            action("关闭", java.lang.Runnable { dismissChatErrorBanner() })
            card.addView(row)
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = android.view.Gravity.TOP
                setMargins(dp(10), dp(72), dp(10), 0)
            }
            addContentView(card, lp)
            chatErrorBanner = card
        }.onFailure { runCatching { toastShort(title + "：" + detail) } }
    }

    private fun showRequestErrorDialog(
        assistantTurnId: String,
        generation: Int,
        error: Throwable,
        hasVisibleContent: Boolean,
    ) {
        if (isFinishing) return
        val detail = (error.message ?: error.javaClass.simpleName).take(1200)
        val title = when {
            error is java.net.UnknownHostException -> "网络不可达"
            error is java.net.SocketTimeoutException -> "请求超时"
            detail.contains("HTTP 4", true) -> "请求被拒绝"
            else -> "请求失败（已重试 $MAX_API_RETRIES 次）"
        }
        // 附上重试轨迹，用户/开发者一眼能看出是不是反复撞同一个 4xx。
        val body = buildString {
            append(detail)
            append("\n\n— 已自动重试 $MAX_API_RETRIES 次仍未成功")
            if (hasVisibleContent) append("（本轮已有部分内容保留在对话里）")
            append("\n错误信息不会写入对话上下文，下一轮请求不会带上它。")
        }
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("重试") { _, _ ->
                    if (busy) return@setPositiveButton
                    busy = false
                    // 重试 = 用同一个 turn id 重跑本回合。错误没进上下文，重建的请求体是干净的。
                    // ⚠ 分支不能丢：这是「重新生成」失败时，旧的几条回复还挂在 alternatives 上。
                    // 直接调 startTurn(id, emptyList()) 会把它们清空，等于烧掉历史分支。
                    val node = ChatStore.loadSession(this, session.id)?.messages
                        ?.firstOrNull { it.id == assistantTurnId }
                    if (node == null) {
                        // failTurn 已经把「完全没内容的空气泡」删掉了，
                        // 此时没有节点可复用 → 走普通发送路径新建一条 assistant 占位。
                        startTurn()
                    } else {
                        val oldBranches =
                            (node.alternatives + node.copy(alternatives = emptyList(), selectIndex = 0))
                                .filter { it.content.isNotBlank() || it.parts.isNotEmpty() || it.thinking.isNotBlank() }
                        startTurn(regenerateNodeId = assistantTurnId, regenerateBranches = oldBranches)
                    }
                }
                .setNeutralButton("复制错误") { _, _ ->
                    runCatching {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("error", body))
                        toastShort("错误信息已复制")
                    }
                }
                .setNegativeButton("关闭", null)
                .setCancelable(true)
                .show()
        }.onFailure {
            // 极端情况（Activity 正在销毁）兜底成 Toast，绝不让用户什么都看不到。
            runCatching { toastShort("请求失败：$detail") }
        }
    }

    /** Finish a round: persist the final answer + stats, release the busy state. */
    private fun completeTurn(assistantTurnId: String, content: String, reasoning: String, generation: Int) = runOnUiThread {
        if (!busy || requestGeneration != generation) return@runOnUiThread
        // ⚠ 关键修复（2026-10-19）：这里**绝不能再清洗一次**。
        // content 已经是 request() 拼好的落盘文本，而 parts 在 post() 收尾时
        // 也已统一 clean 过。在这里再跑一次 normalizeAnswer ->
        // cleanAssistantText，会把已经流式显示完的正文做第二次裁剪：
        //   - 正文里出现 <tool_call> 片段 → 从该处起全删；
        //   - 正文里出现 {"name":...,"arguments":...} → stripBareToolJson 整块删；
        //   - 清洗后变空 → normalizeAnswer 返回 "" to think，正文归零。
        // 用户看到的就是「正文明明已经流出来了，一结束就被 App 吞了」。
        // 收尾只做「是否完成」判定，不再二次改写正文。
        val cleanContent = content
        val cleanReasoning = reasoning
        val finalStats = roundStats?.let { s ->
            s.elapsedMs = System.currentTimeMillis() - s.startedAt
            formatStats(s, running = false)
        }.orEmpty()
        ChatStore.updateAssistantTurn(this, session.id, assistantTurnId, cleanContent, thinking = cleanReasoning, stats = finalStats, complete = true)
        roundStats = null
        busy = false
        setBusyUi(false)
        runCatching { WorkingBubble.stopIfShowing(this, session.id) }
        refreshComposeMessages()
        runCatching { refreshPlanPanel() }
        runCatching { maybeAutoTitle() }
    }

    // ── AI 自动命名会话标题 ───────────────────────────────────────────────
    //
    // 触发时机：第 1 轮对话真正收尾之后（completeTurn 里 done || 不再自动继续）。
    // 触发条件：本会话从未自动命名过，且用户没手动改过标题。
    // 做法：把「用户第一句 + AI 首轮回复（截断）」丢给模型，只要求它回一个短标题。
    // 失败静默：网络/解析失败一律不打扰用户，标题保持占位，用户可随时手改。

    /** 已发起过自动命名（防重入，避免同一次收尾被重复触发）。 */
    @Volatile private var autoTitleRunning = false

    private fun maybeAutoTitle(force: Boolean = false) {
        if (autoTitleRunning) return
        // Project mode is shown by its icon. All modes can generate a content-based title.
        if (!force) {
            if (ChatStore.isTitleAutoGenerated(this, session.id)) return
            if (ChatStore.isTitleLocked(this, session.id)) return
            // 至少要有一轮完整往来，否则模型没素材可总结。
            val hasReply = ChatStore.loadSession(this, session.id)
                ?.messages?.any { it.role == "assistant" && it.content.isNotBlank() } == true
            if (!hasReply) return
        } else {
            // 手动触发也尊重「用户已经改过名字」这一事实
            if (ChatStore.isTitleLocked(this, session.id)) {
                Toast.makeText(this, "标题已手动设置，未覆盖", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val sid = session.id
        autoTitleRunning = true
        Thread {
            val title = runCatching { requestAutoTitle() }.getOrNull()
            runOnUiThread {
                autoTitleRunning = false
                if (!title.isNullOrBlank()) {
                    val ok = ChatStore.applyAutoTitle(this, sid, title)
                    if (ok) {
                        refreshChatTitle()
                        Toast.makeText(this, "标题已自动生成：$title", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    /** 组一段极简对话素材，请求模型返回一个标题（非流式，短超时）。
     *
     * 参考 RikkaHub `ChatService.generateTitle` + `TitleSummary.kt`：
     *  - 素材取最近 4 条消息，每条截断 500 字（不是只取第一条）
     *  - 提示词用编号约束 + <content> 包裹
     *  - ⚠ 后台任务必须显式关闭思考，否则模型把「我要起标题…」的思考当正文返回
     *    （真机踩过：标题变成「我们需要回答标题。需要为对话起标题。中文不超」）
     */
    private fun requestAutoTitle(): String? {
        val history = ChatStore.loadSession(this, session.id)?.messages ?: return null
        if (history.isEmpty()) return null

        val api = ChatStore.api(this)
        if (api.first.isBlank() || api.second.isBlank() || api.third.isBlank()) return null

        // 素材：最近 4 条，每条最多 500 字，附件转成占位符。
        val content = history.takeLast(4).joinToString("\n\n") { m ->
            val role = when (m.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> "System"
            }
            val body = m.content
                .replace(Regex("(?s)ATTACHMENT_JSON:.*"), "[附件]")
                .trim()
                .take(500)
            if (body.isBlank()) "" else "[$role]: $body"
        }.trim()
        if (content.isBlank()) return null

        // 与 RikkaHub DEFAULT_TITLE_PROMPT 对齐的模板。
        val prompt = buildString {
            appendLine("I will give you some dialogue content in the <content> block.")
            appendLine("You need to summarize the conversation between user and assistant into a short title.")
            appendLine("1. The title language should be consistent with the user's primary language")
            appendLine("2. Do not use punctuation or other special symbols")
            appendLine("3. Reply directly with the title, no explanation, no thinking")
            appendLine("4. The title should not exceed 10 characters")
            appendLine()
            appendLine("<content>")
            appendLine(content)
            appendLine("</content>")
        }

        val conn = (URL(api.first.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Authorization", "Bearer ${api.second}")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject()
                .put("model", api.third)
                .put("stream", false)
                .put("max_tokens", 64)
                // ⚠ 关键：后台任务一律关闭思考。不传这个，模型会把 reasoning
                // 内容写进 content，标题就会变成一段自问自答。
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("reasoning_effort", "none")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val raw = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty().trim()
            if (raw.isBlank()) return null
            // 对齐 RikkaHub：只取正文（toText().trim() 语义），不做内容判定。
            return raw.lineSequence().firstOrNull { it.isNotBlank() }
                ?.replace(Regex("^(标题|Title)\\s*[:：]\\s*"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 带自动重试的请求入口（主人 2026-11-13 要求：失败自动重试 5 次）。
     *
     * 对齐参考源码 RikkaHub `ai/agent/Retry.kt` 的 `withRetry(RetryPolicy.NETWORK)`：
     *   `AgentLoop.kt:106` 把整个「一次 provider 调用」包在 withRetry 里，
     *   失败时发 `AgentEvent.Retrying`（`ChatService.kt:19-22` 变成 processingStatus 提示），
     *   退避 = initialDelay * (1 shl (retries-1))（Retry.kt:34），
     *   并且每次重试都**从头重放本步**，不会把两次尝试的输出接在一起
     *   （`AgentLoop.kt:107-109` 注：各次尝试共用同一个消息 id，调用方直接覆盖）。
     *
     * 这里把次数按主人要求提到 5，退避 1s→2s→4s→8s→8s。
     * 重试只重放一次请求：postOnce 内部的 parts 是局部变量，
     * 新一次尝试会整段覆盖上一尝试的 live tail，不会拼接半截输出。
     */
    private fun post(
        api: Triple<String, String, String>,
        messages: JSONArray,
        stream: Boolean,
        assistantTurnId: String,
        onLiveParts: ((List<ChatStore.Part>) -> Unit)? = null,
    ): JSONObject {
        var lastError: Exception? = null
        for (attempt in 0..MAX_API_RETRIES) {
            if (abortRequested) throw InterruptedException("aborted")
            try {
                val r = postOnce(api, messages, stream, assistantTurnId, onLiveParts)
                // 重试成功后把副标题从「重试 n/5…」恢复成模型名（参考实现里
                // ChatService.kt:403-404 收到新内容就把 processingStatus 清空）。
                runOnUiThread {
                    if (!isFinishing) runCatching { refreshTopBarSubtitle() }
                }
                return r
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                lastError = e
                if (!isRetryableError(e) || attempt >= MAX_API_RETRIES) throw e
                // 参考实现语义（AgentLoop.kt:107-109）：重试时**本步从头重放**，
                // 上一次尝试流出来的半截内容必须整段丢掉，不能和新尝试拼接。
                // 这里把 live tail 清空 → 调用方落盘的只剩已提交的 allParts。
                runCatching { onLiveParts?.invoke(emptyList()) }
                // 1-based：第 N 次重试（共 MAX_API_RETRIES 次）
                notifyRetrying(attempt + 1, MAX_API_RETRIES, e)
                val wait = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    throw InterruptedException("aborted")
                }
            }
        }
        throw lastError ?: IllegalStateException("请求失败")
    }

    /**
     * 哪些错误值得重试。
     *
     * 参考实现只重试 IOException（`Retry.kt:5` `retryOn = { it is IOException }`），
     * 4xx「请求本身非法」不重试（`AgentLoopTest` 里明确断言
     * `errors other than network failures are not retried`）。这里按同一原则，
     * 但把 HTTP 5xx / 429 也算进去 —— 那是上游抖动，重试有意义。
     */
    private fun isRetryableError(e: Throwable): Boolean {
        if (e is InterruptedException) return false
        // 连不上、读到一半断开、DNS 失败：一律重试
        if (e is java.io.IOException) return true
        val m = e.message.orEmpty()
        if (m.contains("timeout", true) || m.contains("timed out", true)) return true
        if (m.contains("Connection reset", true)) return true
        if (m.contains("unexpected end of stream", true)) return true
        if (m.contains("Broken pipe", true)) return true
        // HTTP 状态码：5xx / 429 / 408 可重试；其它 4xx 是请求非法，重试只会再撞一次
        val code = Regex("HTTP\\s+(\\d{3})").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (code > 0) return code >= 500 || code == 429 || code == 408
        // kami 网关业务错误恒 200，凭据/余额问题重试无意义
        if (m.contains("官方模型：")) {
            return !(m.contains("余额") || m.contains("登录") || m.contains("密钥") ||
                m.contains("key", true) || m.contains("未授权") || m.contains("过期"))
        }
        return true
    }

    /**
     * 重试中的状态提示：顶栏副标题显示「重试 2/5…」，
     * 对应参考实现 `ChatService.retryStatus()`（ChatService.kt:36-48，
     * 按 UnknownHost / Timeout / Disconnected 给不同文案）。
     * 一句话就恢复，不能留着占位。
     */
    private fun notifyRetrying(retry: Int, maxRetries: Int, cause: Throwable) {
        val why = when {
            cause is java.net.UnknownHostException -> "网络不可达"
            cause is java.net.SocketTimeoutException -> "请求超时"
            cause.message?.contains("timeout", true) == true -> "请求超时"
            else -> "连接中断"
        }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            topBarSubtitle.value = "$why，正在重试 $retry/$maxRetries…"
        }
        try {
            Thread.sleep(60)
        } catch (_: InterruptedException) {
        }
    }

    /** Streams one request. [onLiveParts] receives THIS request's accumulated
     *  parts tail on every meaningful chunk so the caller can merge it with the
     *  already-committed rounds and repaint. */
    private fun postOnce(
        api: Triple<String, String, String>,
        messages: JSONArray,
        stream: Boolean,
        assistantTurnId: String,
        onLiveParts: ((List<ChatStore.Part>) -> Unit)? = null,
    ): JSONObject {
        if (abortRequested) throw InterruptedException("aborted")
        val conn = (URL(api.first.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer ${api.second}"); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        }
        activeConnections.add(conn)
        try {
            if (abortRequested) throw InterruptedException("aborted")
            val specs = toolRegistry.specs()
            val body = JSONObject().put("model", api.third).put("messages", messages).put("stream", stream)
            // OpenAI 原生 tool call：tools + tool_choice 必须成对出现。
            // 照 Operit OpenAIProvider 的做法（jsonObject.put("tool_choice", "auto")），
            // 否则部分中转站会忽略 tools、退回「纯文本回答」，模型再也不调工具。
            if (specs.isNotEmpty()) {
                body.put("tools", ToolProtocol.buildToolsJson(specs))
                body.put("tool_choice", "auto")
            }
            // 思考模式开关（按 DeepSeek 官方文档）：
            //   thinking: {"type":"enabled"|"disabled"}
            //   reasoning_effort: none | low | high | max   （none = 关闭思考）
            //
            // ⚠ 旧实现把 OFF 映射成 "low"（注释说"OpenAI 兼容端点拒绝 none"），
            // 但 low 是【开启】思考模式——用户点「关闭」反而打开思考，随后所有
            // 历史 assistant 消息都缺 reasoning_content，第一个字就 400。
            // 现在严格按官方语义传，并把 AUTO 也显式钉成 disabled，
            // 避免服务端默认 enabled 导致普通对话也被要求回传思考内容。
            when (reasoningLevel) {
                ReasoningLevel.OFF, ReasoningLevel.AUTO ->
                    body.put("thinking", JSONObject().put("type", "disabled"))
                else -> body.put("reasoning_effort", reasoningLevel.wireEffort)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)}")
            if (!stream) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                // kami 网关（官方模型）业务错误恒 HTTP 200：{code:401/402/404, msg}
                // 如无 choices 却有 code/msg，说明是网关错误（余额不足/登录过期等）。
                runCatching { JSONObject(text) }.getOrNull()?.let { j ->
                    if (!j.has("choices") && j.has("code") && j.has("msg") && j.optInt("code") != 200) {
                        throw IllegalStateException("官方模型：${j.optString("msg")}")
                    }
                }
                val json = JSONObject(text)
                json.optJSONObject("usage")?.let { u -> accumulateUsage(u) }
                return json
            }
            // 流式首行探测：kami 网关错误也走 HTTP 200，但 body 是 JSON 而非 SSE。
            // ⚠️ HttpURLConnection 的响应流（ChunkedInputStream 等）不支持 mark/reset，
            // 直接调 mark() 会抛 "mark/reset not supported"（真机踩过），
            // 所以自己用 BufferedInputStream 包一层——它的 markSupported() 恒为 true。
            val rawStream = conn.inputStream
            val stream = if (rawStream.markSupported()) rawStream else java.io.BufferedInputStream(rawStream, 8192)
            stream.mark(512)
            val probe = ByteArray(256).let { buf ->
                val n = stream.read(buf); String(buf, 0, if (n > 0) n else 0)
            }
            runCatching { JSONObject(probe.trim()) }.getOrNull()?.let { j ->
                if (!j.has("choices") && j.has("code") && j.has("msg") && j.optInt("code") != 200) {
                    throw IllegalStateException("官方模型：${j.optString("msg")}")
                }
            }
            stream.reset()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var finishReason = ""
        var sawDone = false
        var parseFailures = 0
        var lastPersist = 0L
        var snapshotStarted = false
        val toolIds = linkedMapOf<Int, String>()
        val toolNames = linkedMapOf<Int, String>()
        val toolArguments = linkedMapOf<Int, StringBuilder>()
        // Ordered parts (RikkaHub model): text and reasoning are appended as
        // separate parts in arrival order, so each thinking block renders on its
        // own instead of being merged into the body.
        val parts = mutableListOf<ChatStore.Part>()
        fun appendPartText(type: String, chunkText: String) {
            if (chunkText.isEmpty()) return
            val last = parts.lastOrNull()
            if (last != null && last.type == type) {
                parts[parts.size - 1] = last.copy(text = last.text + chunkText)
            } else {
                parts.add(ChatStore.Part(type = type, text = chunkText))
            }
        }
        fun refreshStreamingToolParts() {
            toolNames.keys.sorted().forEach { slot ->
                val name = toolNames[slot].orEmpty()
                if (name.isBlank()) return@forEach
                val callId = toolIds[slot] ?: "stream_$slot"
                val args = toolArguments[slot]?.toString().orEmpty()
                val (added, removed) = streamedToolLineStats(name, args)
                val existing = parts.indexOfFirst {
                    it.type == "tool" && (it.toolCallId == callId || it.toolCallId == "stream_$slot")
                }
                val next = ChatStore.Part(
                    type = "tool",
                    toolCallId = callId,
                    toolName = name,
                    // Keep the live part light. The complete JSON is attached
                    // only after the stream ends and the call is about to run.
                    toolArguments = "",
                    addedLines = added,
                    removedLines = removed,
                    running = true,
                )
                if (existing >= 0) parts[existing] = next else parts.add(next)
            }
        }
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (abortRequested) break   // ✕ 中断：立即退出流式读取，不等待上游断开
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") { sawDone = true; continue }
                runCatching {
                    if (!snapshotStarted) {
                        snapshotStarted = true
                        ChatStore.updateAssistantTurn(this@ChatActivity, session.id, assistantTurnId, "")
                    }
                    val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
                    if (choice.has("finish_reason") && !choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason")
                    val delta = choice.optJSONObject("delta") ?: JSONObject()
                    val text = sanitize(delta.optString("content", ""))
                    val thought = sanitize(delta.optString("reasoning_content", delta.optString("reasoning", "")))
                    if (text.isNotEmpty()) content.append(text)
                    if (thought.isNotEmpty()) reasoning.append(thought)
                    // Ordered parts (RikkaHub): reasoning and text are SEPARATE
                    // parts, so thinking never becomes the body.
                    appendPartText("reasoning", thought)
                    appendPartText("text", text)
                    // Usage may ride on any chunk (usually the last); accumulate.
                    JSONObject(payload).optJSONObject("usage")?.let { u -> accumulateUsage(u) }
                    delta.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val call = calls.optJSONObject(i) ?: continue
                            val fn = call.optJSONObject("function") ?: continue
                            // Streamed deltas identify their call via "index";
                            // never key by array position (always 0 mid-stream).
                            val slot = call.optInt("index", i)
                            call.optString("id").takeIf { it.isNotEmpty() }?.let { toolIds[slot] = it }
                            fn.optString("name").takeIf { it.isNotEmpty() }?.let { toolNames[slot] = it }
                            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { toolArguments.getOrPut(slot) { StringBuilder() }.append(it) }
                        }
                        refreshStreamingToolParts()
                    }
                    val hasToolDelta = delta.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true
                    if (text.isNotEmpty() || thought.isNotEmpty() || hasToolDelta) {
                        val now = System.currentTimeMillis()
                        // Throttle to ~5/s. `parts` here is THIS request's tail
                        // only; the caller merges it with the committed rounds
                        // and repaints. Reasoning-only chunks must also fire, or
                        // a model that thinks first would show nothing until the
                        // whole block lands at once.
                        if (now - lastPersist >= 200L) {
                            lastPersist = now
                            runCatching { onLiveParts?.invoke(parts.toList()) }
                        }
                    }
                }.onFailure { parseFailures++ }
            }
        }
        // The live tool part intentionally carries only counters. Once the SSE
        // stream is complete, attach the full JSON exactly once for the detail
        // dialog and for the following tool execution round.
        toolNames.keys.sorted().forEach { slot ->
            val name = toolNames[slot].orEmpty()
            val callId = toolIds[slot] ?: "stream_$slot"
            val args = toolArguments[slot]?.toString().orEmpty()
            val (added, removed) = completeToolLineStats(name, args)
            val index = parts.indexOfLast {
                it.type == "tool" && (it.toolCallId == callId || it.toolCallId == "stream_$slot")
            }
            if (index >= 0) {
                parts[index] = parts[index].copy(
                    toolCallId = callId,
                    toolName = name,
                    toolArguments = args,
                    addedLines = added,
                    removedLines = removed,
                    running = true,
                )
            }
        }
        val truncated = !sawDone && finishReason.isBlank()
        val rawContent = content.toString()
        val cleanContent = ToolProtocol.cleanAssistantText(rawContent)
        // NOTE: this method must NOT persist anything. `parts` here is only THIS
        // request's tail, while the assistant turn accumulates every round in
        // request()'s `allParts`. Writing here would overwrite the committed
        // history on each follow-up request — that is what made a half-streamed
        // answer vanish right after a tool round. The caller owns persistence.
        val message = JSONObject().put("role", "assistant").put("content", cleanContent).put("reasoning_content", reasoning.toString())
            .put("_finish_reason", finishReason)
            .put("_incomplete_tool_payload", ToolProtocol.hasIncompleteToolPayload(rawContent))
            .put("_stream_truncated", truncated)
            .put("_parse_failures", parseFailures)
        message.put("_parts", org.json.JSONArray().apply {
            parts.forEach { p ->
                put(JSONObject().apply {
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
        })
        if (toolNames.isNotEmpty()) {
            val calls = JSONArray()
            toolNames.keys.sorted().forEach { index ->
                calls.put(JSONObject().put("id", toolIds[index] ?: "call_$index").put("type", "function").put("function", JSONObject().put("name", toolNames[index]).put("arguments", toolArguments[index]?.toString() ?: "{}")))
            }
            message.put("tool_calls", calls)
        }
        // Expose this round's streamed reasoning to the caller so it can be
        // persisted per-round (tool-only rounds used to lose their thinking).
        message.put("_streamed_reasoning", reasoning.toString())
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))
        } finally {
            activeConnections.remove(conn)
        }
    }

    /** Operit-style corrective follow-up for blocked duplicate calls: a
     *  protocol-valid assistant(tool_calls) + role:tool pair whose results
     *  carry a BLOCKED notice instead of a re-execution, so the model can
     *  pivot using <executed_tools_history> and still produce a final answer. */
    /** org.json's optString() returns the literal string "null" for JSON null
     *  values (and for absent keys with no default). Strip those artifacts —
     *  they were leaking into the chat body/thinking text. */
    private fun sanitize(raw: String?): String =
        if (raw == null || raw == "null" || raw.equals("undefined", true)) "" else raw

    /** Count logical lines in a possibly incomplete JSON string field.
     * This only scans the short tool delta snapshot and never touches the file
     * system or renders the full argument payload. */
    private fun partialJsonLineCount(raw: String, key: String): Int {
        val marker = "\"$key\""
        val keyStart = raw.indexOf(marker)
        if (keyStart < 0) return 0
        var i = keyStart + marker.length
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length || raw[i] != ':') return 0
        i++
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length || raw[i] != '\"') return 0
        i++
        var lines = 1
        var hasText = false
        var escaped = false
        while (i < raw.length) {
            val c = raw[i]
            if (escaped) {
                if (c == 'n') {
                    lines++
                    hasText = true
                } else if (c != 'u') {
                    hasText = true
                }
                escaped = false
            } else {
                when (c) {
                    '\\' -> escaped = true
                    '\"' -> break
                    else -> {
                        hasText = true
                        if (c == '\n') lines++
                    }
                }
            }
            i++
        }
        return if (hasText) lines else 0
    }

private fun streamedToolLineStats(name: String, rawArguments: String): Pair<Int, Int> = when (name) {
        "write_file" -> partialJsonLineCount(rawArguments, "content") to 0
        "edit_file" -> partialJsonLineCount(rawArguments, "new") to partialJsonLineCount(rawArguments, "old")
        else -> 0 to 0
    }

    private fun completeToolLineStats(name: String, argumentsJson: String): Pair<Int, Int> = runCatching {
        val args = JSONObject(argumentsJson)
        fun lineCount(value: String): Int = if (value.isBlank()) 0 else value.count { it == '\n' } + 1
        when (name) {
            "write_file" -> lineCount(args.optString("content")) to 0
            "edit_file" -> lineCount(args.optString("new")) to lineCount(args.optString("old"))
            else -> 0 to 0
        }
    }.getOrDefault(0 to 0)

    /**
     * Single source of truth for "what is the answer vs what is the thinking".
     *
     * Some gateways (e.g. ai.lcog.cn + deepseek) put the ANSWER in
     * `reasoning_content` and leave `content` empty, or echo the SAME text in
     * both. Naively rendering content→body and reasoning→fold produced
     * "answer inside 思考过程" and duplicate paragraphs.
     *
     * Rules:
     *  - content is the answer when present;
     *  - if content is empty but reasoning has text, that reasoning IS the answer;
     *  - the thinking fold is only shown when its text differs from the answer.
     */
    private fun normalizeAnswer(content: String, reasoning: String, hasToolCalls: Boolean = false): Pair<String, String> {
        // ⚠ 2026-10-19 对齐 Operit 参考实现（OpenAIProvider.kt:2234 processContentDelta）：
        // 参考实现里流式/收尾两处都只是「原样 emitContent」，**从不**对正文做
        // 任何内容清理；它在渲染层也不清理，只在「发给 API 的历史消息」里做
        // think 标签拆分。我们这里的 cleanAssistantText 会删 <tool_call> 之后的
        // 全部正文 / 整块裸工具 JSON，是「正文流完就被吞」的根源。
        // 因此正文只保留 sanitize（去掉 org.json 的 "null"/"undefined" 字符串），
        // 不再做任何内容裁剪。
        val body = sanitize(content)
        val think = sanitize(reasoning)
        if (hasToolCalls) {
            return body to (think.takeIf { it.isNotBlank() && it != body } ?: "")
        }
        return when {
            body.isNotBlank() -> body to (if (think.isNotBlank() && think != body) think else "")
            else -> "" to think
        }
    }

    private fun conversationJson(beforeMessageId: String? = null) = JSONArray().apply {
        val allMessages = ChatStore.loadSession(this@ChatActivity, session.id)?.messages ?: emptyList()
        // ⚠ 照抄 RikkaHub ChatService.kt:596-599：
        //   history = if (regenerateNodeId != null) currentMessages.take(index) else currentMessages
        // 重新生成时必须**只发被重生成节点之前**的消息，
        // 否则模型会看到"自己已经答过"的那条，导致回答错乱或重复。
        val fullHistory = if (beforeMessageId != null) {
            val cut = allMessages.indexOfFirst { it.id == beforeMessageId }
            if (cut >= 0) allMessages.take(cut) else allMessages
        } else allMessages
        val compression = ChatStore.loadContextCompression(this@ChatActivity, session.id)
        val history = if (compression != null && fullHistory.size > compression.keepRecentMessages) {
            var start = (fullHistory.size - compression.keepRecentMessages).coerceAtLeast(0)
            while (start > 0 && fullHistory[start].role != "user") start--
            fullHistory.drop(start)
        } else fullHistory
        // Executed-tools summary (Operit-style): the model must SEE what it
        // already ran, otherwise every new request starts blind and it
        // re-executes the same commands — the root cause of repeat loops.
        val executed = history.flatMap { m ->
            when {
                // Legacy grouped records.
                m.kind == "toolgroup" -> runCatching {
                    val arr = JSONArray(m.content)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        o.optString("arguments") to o.optString("result")
                    }
                }.getOrDefault(emptyList())
                // Current model: tool steps are parts of the assistant turn.
                // They were never collected here, so the "already executed"
                // system-prompt block stayed empty and the model re-ran things.
                m.role == "assistant" && m.parts.any { it.type == "tool" } ->
                    m.parts.filter { it.type == "tool" }
                        .map { it.toolArguments to it.toolResult }
                else -> emptyList()
            }
        }
        // ⚠ 工作区绝对路径必须写进系统提示词。
        // 以前只发工具清单，模型不知道自己在哪工作，只能 find/ls 满盘摸。
        // 照参考源码 SystemPromptConfig.getWorkspaceGuidelines 的做法。
        val wsGuide = ToolProtocol.workspaceGuidance(sessionWorkspace.absolutePath)
        val sysBase = ToolProtocol.systemPrompt(toolRegistry.specs())
        val sys = if (executed.isNotEmpty()) {
            wsGuide + sysBase + "\n" + ToolProtocol.executedToolsSummary(executed)
        } else {
            wsGuide + sysBase
        }
        // 用户自定义提示词：叠加在最后，不替换任何内置章节。
        val userCue = com.agentpad.workspace.ai.PromptStore.combined(this@ChatActivity, session.id)
        val sysWithUser = if (userCue.isBlank()) sys else sys + "\n\n" + userCue
        // Project mode (APK session): append the project-specific discipline —
        // this session is dedicated to APK/toolchain build work.
        if (session.mode == "apk") {
            put(JSONObject().put("role", "system").put("content", sysWithUser + "\n" + ToolProtocol.projectModePrompt()))
        } else if (session.mode == "web") {
            put(JSONObject().put("role", "system").put("content", sysWithUser + "\n" + ToolProtocol.webModePrompt()))
        } else if (session.mode == "video") {
            put(JSONObject().put("role", "system").put("content", sysWithUser + "\n" + ToolProtocol.videoModePrompt()))
        } else if (session.mode == "kt") {
            put(JSONObject().put("role", "system").put("content", sysWithUser + "\n" + ToolProtocol.ktModePrompt()))
        } else if (session.mode == "reverse") {
            put(JSONObject().put("role", "system").put("content", sysWithUser + "\n" + ToolProtocol.reverseModePrompt()))
        } else {
            put(JSONObject().put("role", "system").put("content", sysWithUser))
        }
        // Request-time summary sits after system instructions and before the
        // retained raw tail. Clearing the config restores the full raw history.
        if (compression != null) {
            put(JSONObject().put("role", "user").put("content", "[请求级历史上下文摘要]\n${compression.summary}"))
        }
        val reasoningContext = ChatStore.reasoningInContext(this@ChatActivity)
        // 思考模式网关（deepseek / 智谱 thinking 等）有一条硬规则：只要历史里
        // 有任何 assistant 轮次回过 reasoning_content，后续每次请求都必须把该轮
        // 的 reasoning_content 原样带回去，否则直接 400：
        //   "The `reasoning_content` in the thinking mode must be passed back to the API."
        // 所以这里不能只看用户开关：历史上只要存过 thinking，就必须回传。
        val mustEchoReasoning = reasoningContext || history.any { it.role == "assistant" && it.thinking.isNotBlank() }
        // 思考模式真的开着时（低/中/高/最大），网关要求【每一条】assistant 消息都
        // 携带 reasoning_content——缺一条就整单 400。老会话、被编辑/删改过的消息
        // 往往没有 thinking，所以这里用空串兜底，而不是跳过。
        val thinkingModeOn = reasoningLevel.isEnabled && reasoningLevel != ReasoningLevel.AUTO
        history.forEachIndexed { historyIndex, m ->
            when {
                // Notices are UI-only and must never enter the next API request.
                m.role == "notice" -> Unit
                // Full tool-history replay (Rikkahub-style): rebuild each
                // persisted tool-intent round as a proper assistant(tool_calls)
                // turn with its results as role:tool messages. Without this the
                // model starts every new user turn blind — it cannot see its
                // own prior tool calls, so it claims "nothing was done".
                m.kind == "toolintent" -> {
                    val intentMsg = JSONObject().put("role", "assistant").put("content", m.content)
                    // 工具调用轮同样是 assistant 消息：思考模式下这一轮的
                    // reasoning_content 也必须回传，否则网关只看到"有 content 没
                    // reasoning"就整单 400。老数据没有 thinking 时补空串占位。
                    if (mustEchoReasoning || thinkingModeOn) intentMsg.put("reasoning_content", m.thinking)
                    put(intentMsg)
                }
                m.kind == "toolgroup" -> {
                    val brief = toolGroupBrief(m.content)
                    if (brief.isNotBlank()) {
                        // MUST NOT be role:tool: a tool message without a matching
                        // preceding assistant(tool_calls) turn makes the gateway
                        // reject the whole request (HTTP 400 "function_call_output
                        // requires item_reference ids"). Replay as a user-side
                        // history note instead — same context, no pairing rule.
                        put(JSONObject().put("role", "user").put("content", "[历史工具执行记录]\n$brief"))
                    }
                }
                m.kind == "tool" -> Unit
                // 本轮 startTurn() 刚插入的空 assistant 占位（content/parts/thinking 全空）
                // 绝不能发给网关：思考模式网关看到一条 assistant 消息却没有
                // reasoning_content，第一句话就 400。它不是历史，只是 UI 占位。
                m.role == "assistant" && m.content.isBlank() && m.parts.isEmpty() && m.thinking.isBlank() -> Unit
                else -> {
                    val savedAttachment = m.content.removePrefix("ATTACHMENT_JSON:").takeIf { m.content.startsWith("ATTACHMENT_JSON:") }
                    // Attachments belong to the newest USER message. An assistant
                    // placeholder is appended before this runs, so the old
                    // `historyIndex == history.lastIndex` test never matched and
                    // the base64 image payload was dropped (model saw only a name).
                    val lastUserIndex = history.indexOfLast { it.role == "user" }
                    val currentContent = if (historyIndex == lastUserIndex && m.role == "user") transientAttachmentContent else null
                    val msg = when {
                        currentContent != null -> JSONObject().put("role", m.role).put("content", currentContent)
                        savedAttachment != null && savedAttachment.length < 200_000 -> runCatching { JSONObject().put("role", m.role).put("content", JSONArray(savedAttachment)) }.getOrElse { JSONObject().put("role", m.role).put("content", "[附件消息已保存，请查看会话工作区附件]" ) }
                        savedAttachment != null -> JSONObject().put("role", m.role).put("content", "[附件消息已保存，请查看会话工作区附件]")
                        else -> JSONObject().put("role", m.role).put("content", m.content.take(20_000))
                    }
                    // 只有 assistant 消息才带 reasoning_content；user/system/tool 带上会被网关
                    // 判为非法字段。思考模式开启时即使 thinking 为空也补空串占位。
                    if (m.role == "assistant" && (mustEchoReasoning || thinkingModeOn)) {
                        msg.put("reasoning_content", m.thinking)
                    }
                    put(msg)
                    // Tool rounds live inside this assistant message's `parts`
                    // (message.content only holds the final prose). Without
                    // replaying them, a new user turn starts with the model
                    // unable to see any tool it ran or what came back — the
                    // "amnesia" symptom. Replayed as a user-side note because a
                    // bare role:tool message without its pairing
                    // assistant(tool_calls) turn is rejected by some gateways.
                    if (m.role == "assistant" && m.parts.any { it.type == "tool" }) {
                        val note = m.parts.filter { it.type == "tool" }.joinToString("\n") { p ->
                            val args = p.toolArguments.replace("\n", " ").trim().take(300)
                            val res = p.toolResult.replace("\n", " ").trim().take(400)
                            "- ${p.toolName}($args) -> $res"
                        }
                        if (note.isNotBlank()) {
                            put(JSONObject().put("role", "user").put("content", "[历史工具执行记录]\n$note"))
                        }
                    }
                }
            }
        }
    }

    /** Compact per-tool summary of a persisted toolgroup record: name +
     *  arguments + full result so the model sees what was done. */
    private fun toolGroupBrief(content: String): String = buildString {
        runCatching {
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                appendLine("tool: ${o.optString("name")} args: ${o.optString("arguments").take(200)}")
                appendLine("result: ${o.optString("result").take(800)}")
            }
        }
    }.trim()

    /** Refresh the cached session snapshot from the store. */
    private fun syncSessionFromStore() {
        ChatStore.loadSession(this, session.id)?.let { session = it }
    }

    /** 顶栏标题：每次进入/回前台从存储读最新标题（重命名后立即生效） */
    // ---- 显示窗口分页（Operit 同款）----
    /**
     * 当前显示窗口在完整消息列表里的起始下标。
     * 只有这一段会交给 Compose 渲染，往上翻一页就把起点前移一页。
     * -1 表示"还没初始化"，首次刷新时自动落到最新两页。
     */
    private var windowStartIndex = -1
    /** 用户是否主动往上翻过历史。翻过之后不再自动重锚窗口，避免刚补的页被裁掉。 */
    private var userPagedBack = false
    /** 上次计算窗口起点时的消息总数：数量没变就跳过 O(n) 重算。 */
    private var lastWindowCalcSize = -1

    private fun refreshComposeMessages() {
        if (::session.isInitialized && com.agentpad.workspace.floating.FloatingSessionHub.showing) {
            com.agentpad.workspace.floating.FloatingSessionHub.refresh(this, session.id)
        }
        if (!::composeView.isInitialized || isFinishing) return
        val all = ChatStore.loadSession(this, session.id)?.messages ?: session.messages
        if (windowStartIndex < 0) {
            windowStartIndex = ChatStore.windowStartForNewestPages(all)
            lastWindowCalcSize = all.size
        } else if (!userPagedBack) {
            if (all.size != lastWindowCalcSize) {
                lastWindowCalcSize = all.size
                val newestStart = ChatStore.windowStartForNewestPages(all)
                if (newestStart > windowStartIndex) windowStartIndex = newestStart
            }
        }
        windowStartIndex = windowStartIndex.coerceIn(0, all.size)
        val latest = if (windowStartIndex == 0) all else all.subList(windowStartIndex, all.size).toList()
        composeHasOlder.value = windowStartIndex > 0
        composeMessages.value = latest
        composeModel.value = activeModelLabel()
        composeStreaming.value = latest
            .lastOrNull { it.role == "assistant" && it.kind != "toolintent" }
            ?.kind == "snapshot"
        if (::attachments.isInitialized) {
            composeAttachments.value = attachments.current().map { it to it.name }
        }
    }

    // ---- 流式刷新节流 ----
    /**
     * 流式期间**不要**每个 token 都全量刷新。
     *
     * 旧行为：`publishLive()`（每个 SSE chunk 一次）→ 主线程
     *   ChatStore.loadSession()  → 解析整份 session JSON（O(n)）
     *   refreshComposeMessages() → 重建消息列表
     * 思考阶段 token 密集（每秒几十次），主线程任务队列被塞满。
     * 切后台再切回时，队列还没消化完 → UI 整体卡住、动画停摆；
     * 直到思考结束、开始调工具（token 流暂停）队列才排空，界面“瞬间恢复”。
     *
     * 现在：请求里来的刷新走 [requestStreamRefresh]，最多每
     * [STREAM_REFRESH_MS] 合并一次；非流式刷新（发送、工具完成、
     * 用户操作）仍然直接走 [refreshComposeMessages] 保证即时性。
     */
    private var lastStreamRefreshAt = 0L
    private var streamRefreshPending = false
    /**
     * 前后台状态。
     *
     * ⚠ 这是「切后台回来卡死」的真正开关：
     *   后台时 Choreographer 停止回调，Compose 不再重组；
     *   但流式线程如果继续写 composeMessages.value，状态会不断累积。
     *   回前台时一次性消费全部积压 → 主线程卡死。
     *   所以后台期间必须彻底停止 UI 状态写入。
     */
    @Volatile private var appInForeground = false
    /** 后台期间攒下的脏标记：回前台时补一次刷新即可。 */
    @Volatile private var streamRefreshDirtyWhileBackground = false
    private val streamRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val streamRefreshRunnable = Runnable {
        streamRefreshPending = false
        lastStreamRefreshAt = android.os.SystemClock.uptimeMillis()
        refreshComposeMessages()
    }

    /** 流式专用：合并高频刷新，主线程最多每 100ms 重建一次列表。 */
    private fun requestStreamRefresh() {
        if (::session.isInitialized && com.agentpad.workspace.floating.FloatingSessionHub.showing) {
            com.agentpad.workspace.floating.FloatingSessionHub.refresh(this, session.id)
        }
        if (isFinishing || !::composeView.isInitialized) return
        // ★ 核心：后台期间**完全不碰 Compose 状态**。
        //
        // 为什么这才是根因：
        //   前台时 Choreographer 每帧消费一次状态写入，写入/消费大致平衡，
        //   所以看起来流畅（其实一直满负荷）。
        //   切后台后 Android 暂停帧回调，Recomposer 不再消费，
        //   但流式线程仍在不停 composeMessages.value = latest（每次都 new List）、
        //   不停 loadSession()、不停标记脏状态。
        //   30 秒思考 = 几百上千份待处理重组。
        //   切回前台时 Recomposer 恢复，一次性处理全部积压 + Looper 队列里的
        //   排队任务 → 主线程被卡死，直到 token 流暂停（调工具）才排空。
        //
        // 所以后台期间要做的不是"降频"，而是"什么都不做"，只记一个脏标记。
        if (!appInForeground) {
            streamRefreshDirtyWhileBackground = true
            return
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            streamRefreshHandler.post { requestStreamRefresh() }
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = now - lastStreamRefreshAt
        if (elapsed >= STREAM_REFRESH_MS) {
            streamRefreshHandler.removeCallbacks(streamRefreshRunnable)
            streamRefreshPending = false
            lastStreamRefreshAt = now
            refreshComposeMessages()
        } else if (!streamRefreshPending) {
            streamRefreshPending = true
            streamRefreshHandler.postDelayed(streamRefreshRunnable, STREAM_REFRESH_MS - elapsed)
        }
    }

    /**
     * 往上翻一页（对应 Operit 的 loadOlderMessagesForCurrentChat）。
     *
     * 把窗口起点前移一页：新数据插到列表头部，用户视线会跳，所以这里
     * 前移后把列表滚到"新加载的第一条"，而不是继续贴底。
     */
    private fun loadOlderPage() {
        if (composeLoadingOlder.value) return
        val all = ChatStore.loadSession(this, session.id)?.messages ?: return
        if (windowStartIndex <= 0) {
            composeHasOlder.value = false
            return
        }
        composeLoadingOlder.value = true
        userPagedBack = true
        val previousStart = windowStartIndex
        val previousCount = composeMessages.value.size
        val nextStart = ChatStore.olderWindowStart(all, previousStart)
        windowStartIndex = nextStart.coerceIn(0, all.size)
        val latest = if (windowStartIndex == 0) all else all.subList(windowStartIndex, all.size).toList()
        composeHasOlder.value = windowStartIndex > 0
        composeMessages.value = latest
        composeLoadingOlder.value = false
        // 新补进来的条数 = 新窗口长度 - 旧窗口长度（按 item 数比较），
        // 滚到"新补的第一条"，用户视线不动。
        val added = (latest.size - previousCount).coerceAtLeast(0)
        runOnUiThread {
            composeScrollToIndex.value = added
            composeScrollToIndexSignal.value = composeScrollToIndexSignal.value + 1
        }
    }

    // ---- Compose message actions ----

    private fun displayTextOf(message: ChatStore.Message): String =
        if (message.role == "assistant") {
            message.parts.filter { it.type == "text" }.joinToString("\n\n") { it.text }
                .ifBlank { message.content }
        } else message.content

    /** Edit in place via a Compose dialog, then re-render. */
    private fun editMessage(message: ChatStore.Message) {
        val input = android.widget.EditText(this).apply {
            setText(displayTextOf(message))
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val updated = input.text.toString()
                if (updated.isNotBlank()) {
                    ChatStore.updateMessageContent(this, session.id, message.id, updated)
                    refreshComposeMessages()
                }
            }
            .setNeutralButton("重新发送") { _, _ ->
                val updated = input.text.toString()
                if (updated.isNotBlank() && !busy) {
                    ChatStore.updateMessageContent(this, session.id, message.id, updated)
                    if (chatStore_rewindTo(message)) {
                        binding.input.setText(updated)
                        composeInput.value = updated
                        refreshComposeMessages()
                        send()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Cut the session back to just before this message (used by 重新发送/回溯). */
    private fun chatStore_rewindTo(message: ChatStore.Message): Boolean {
        val prior = ChatStore.loadSession(this, session.id)?.messages.orEmpty()
        val index = prior.indexOfFirst { it.id == message.id }
        if (index < 0) return false
        val keep = if (message.role == "user") index else index
        return ChatStore.rewindToIndex(this, session.id, keep)
    }

    private fun deleteMessage(message: ChatStore.Message) {
        ChatStore.deleteMessage(this, session.id, message.id)
        refreshComposeMessages()
    }

    private fun shareMessage(text: String) {
        if (text.isBlank()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "分享消息"))
    }

    private fun forkFrom(text: String) {
        if (busy) { Toast.makeText(this, "请先等待当前回复结束", Toast.LENGTH_SHORT).show(); return }
        if (!ChatStore.rewindFromUser(this, session.id, text)) return
        syncSessionFromStore()
        refreshComposeMessages()
        Toast.makeText(this, "已回溯，可编辑后重新发送", Toast.LENGTH_SHORT).show()
    }

    /** Model/profile picker — shared by the composer chip and the legacy slot.
     *
     *  设计原则（为什么这么改）：
     *  「切换配置」和「换模型」是两件事。切换配置是纯本地动作，只改
     *  activeProfileId，不联网、不弹错。换模型才需要模型列表，且列表来源
     *  按「配置固定表 → 本地缓存 → 在线 /models」逐级兜底，任何一级失败都
     *  降级到下一种，绝不因为拉不到列表就报错。 */
    private fun showModelPicker() {
        val profiles = ChatStore.apiProfiles(this)
        val activeId = ChatStore.activeProfileId(this)
        val names = profiles.map { p ->
            val flag = if (p.optString("id") == activeId) "✓ " else ""
            "$flag${p.optString("name")} · ${p.optString("model").ifBlank { "未设置模型" }}"
        }
        AlertDialog.Builder(this)
            .setTitle("切换模型配置")
            .setItems(names.toTypedArray()) { _, i ->
                val picked = profiles[i]
                // ① 切配置：纯本地，立刻生效，不联网。
                ChatStore.setActiveProfileId(this, picked.optString("id"))
                composeModel.value = picked.optString("model")
                Toast.makeText(
                    this,
                    "已切换到「${picked.optString("name")}」"
                        + picked.optString("model").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    Toast.LENGTH_SHORT,
                ).show()
                // ② 顺带问一句要不要换这个配置里的模型？纯本地判断，绝不用网络。
                maybeOfferModelSwitch(picked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切完配置后，仅当「能拿到模型清单」时才提示换模型。
     *  拿不到清单（固定表为空 + 无缓存 + 网络不可用）就安静跳过 —— 配置已经切好了。 */
    private fun maybeOfferModelSwitch(picked: JSONObject) {
        // 固定模型表（default2 / sponsor 这类）→ 直接给列表。
        val fixed = picked.optJSONArray("fixedModels")
        if (fixed != null && fixed.length() > 0) {
            val items = Array(fixed.length()) { k -> fixed.optString(k) }
            showModelList(picked, items)
            return
        }
        // 官方模型（hub）：必须联网拿清单，但失败也只是「不提示」，不报错。
        if (picked.optBoolean("hub", false)) {
            val hubToken = com.agentpad.workspace.data.UserStore.token(this)
            if (hubToken.isBlank()) return   // 没登录就不弹，切配置本身已完成
            ChatStore.fetchHubModels(this, hubToken) { hubModels, _ ->
                if (hubModels.isNotEmpty()) {
                    ChatStore.saveCachedModels(this, "hub", hubModels)
                    showModelList(picked, hubModels.toTypedArray())
                }
            }
            return
        }
        // 普通自定义配置：先用本地缓存，缓存没有才联网去拉。
        val cached = ChatStore.cachedModels(this, picked.optString("id"))
        if (cached.isNotEmpty()) {
            showModelList(picked, cached.toTypedArray())
            return
        }
        val base = picked.optString("url").trimEnd('/')
        val key = picked.optString("key")
        if (base.isBlank() || key.isBlank()) return   // 配置没填全，不弹提示
        Thread {
            val models = ChatStore.fetchModelsFrom(base, key)
            if (models.isEmpty()) return@Thread
            ChatStore.saveCachedModels(this, picked.optString("id"), models)
            runOnUiThread { showModelList(picked, models.toTypedArray()) }
        }.start()
    }

    /** 展示某配置下的模型清单；选中的模型立即生效。 */
    private fun showModelList(profile: JSONObject, items: Array<String>) {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("选择模型（${profile.optString("name")}）")
            .setItems(items) { _, k -> pickModel(profile, items[k]) }
            .setNegativeButton("保持当前模型", null)
            .show()
    }

    private fun pickModel(profile: JSONObject, model: String) {
        ChatStore.updateProfile(
            this, profile.optString("id"), profile.optString("name"),
            profile.optString("url"), profile.optString("key"), model,
        )
        // 官方模型额外写一份 hub_model，保证 ApiConfigActivity 与这里读取一致。
        if (profile.optBoolean("hub", false)) ChatStore.saveHubModel(this, model)
        composeModel.value = model
        Toast.makeText(this, "已切到模型：$model", Toast.LENGTH_SHORT).show()
    }

    private fun refreshChatTitle() {
        val s = ChatStore.loadSession(this, session.id) ?: session
        val shown = s.title
        // 原生 TextView 已随旧顶部栏一起移除，现在只喂 Compose 顶部栏。
        topBarTitle.value = shown
    }

    override fun onResume() {
        super.onResume()
        // ★ 先置前台标志：下面的补刷新才有意义
        appInForeground = true
        if (::session.isInitialized) {
            if (ChatStore.loadSession(this, session.id) == null) { finish(); return }
            getSharedPreferences("navigation_prefs", MODE_PRIVATE).edit().putString("last_session_id", session.id).apply()
        }
        val legacy = com.agentpad.workspace.ui.ThemeStore.legacyUi.value
        if (legacy != previousLegacyUi) {
            previousLegacyUi = legacy
            closeSidebar()
            sessionDrawerVisible.value = false
        }
        if (::binding.isInitialized) {
            releaseInputFocus()
            com.agentpad.workspace.ai.BrowserTools.bindActivity(this)
            // An existing chat Activity may return from history after its folder binding changed.
            if (::sessionWorkspace.isInitialized && !busy) {
                val resolved = ChatStore.workspaceDir(this, session.id)
                if (resolved.absolutePath != sessionWorkspace.absolutePath) {
                    sessionWorkspace = ChatStore.setCurrentWorkspace(this, session.id)
                    val registry = ToolRegistry(this, sessionWorkspace, session.id) {
                        runOnUiThread { refreshPlanPanel(); refreshComposeMessages() }
                    }
                    toolRegistry = registry
                    Thread { runCatching { registry.loadRemoteTools() } }.start()
                }
            }
            refreshChatTitle()
            // Existing conversations can obtain a subject title immediately on open.
            if (!busy && !composeStreaming.value) runCatching { maybeAutoTitle() }
            // 从主题页 / 背景页返回时重新上色
            runCatching { applyThemeToNativeUi() }
            runCatching { reloadChatBackground() }
            // 后台期间流式刷新被完全跳过（只记脏标记），
            // 回前台时把这段时间的内容一次性补上 —— 只要一次，不是几百次。
            if (streamRefreshDirtyWhileBackground || composeStreaming.value || composeBusy.value) {
                streamRefreshDirtyWhileBackground = false
                lastStreamRefreshAt = android.os.SystemClock.uptimeMillis()
                runCatching { refreshComposeMessages() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // ★ 置后台标志：流式刷新从此只记脏标记，不写任何 Compose 状态。
        //   这是「切后台回来卡死」的根因修复点。
        appInForeground = false
        // 清掉已排队的节流回调，避免它在后台白白唤起主线程
        runCatching { streamRefreshHandler.removeCallbacks(streamRefreshRunnable) }
        streamRefreshPending = false
    }

    /** Handle reordered instances and external entry points; a different ID gets its own engine. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val targetId = intent.getStringExtra("session_id")
        if (targetId != null && ::session.isInitialized && targetId != session.id) {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("session_id", targetId))
            return
        }
        setIntent(intent)
        releaseInputFocus()
        runCatching {
            refreshChatTitle()
            refreshComposeMessages()
        }
    }

    // ---- Full history rendering (no pagination) ----
    // Every message lives in the View tree. The per-session store keeps reads
    // cheap and tool results render as summaries, so this stays responsive;
    // jump/locate can always find any message without window gymnastics.
    /**
     * Render a complete immutable store snapshot in one transaction-like pass.
     * The old frame-chunked renderer allowed send/tool callbacks to mutate the
     * same container while a stale snapshot was still being appended. That was
     * the source of duplicated bubbles and history overwrites.
     */
    /** Render one message (any kind) into the container. */
    /** Append a new round's calls to an existing (live) group so consecutive
     *  pure-tool rounds render as ONE "工具 ×N" block. Re-opens the body. */
    /** One tool call inside a group: name + status chip, expandable args, result preview. */
    // ---- Ordered-part renderers (RikkaHub-style assistant message) ----

    /**
     * One collapsible block containing consecutive steps (reasoning / tool),
     * mirroring RikkaHub's ThinkingBlock. A step is either a reasoning
     * paragraph or a tool line — never the answer body.
     */
    /**
     * RikkaHub's ChatMessage layout: consecutive thinking steps (reasoning +
     * tool) become ONE chain-of-thought card; a Text part ends the chain and is
     * rendered as the answer below it. Used by history AND live turns.
     */
    /**
     * RikkaHub ChainOfThought: a rounded card listing steps on a vertical
     * timeline. Steps collapse to the last 2 when there are more than 2.
     */
    /** One chain-of-thought step: icon + label, optionally expandable content. */
    /** Re-render one assistant bubble from its parts (live + history). */
    /** A markdown-rendered answer block. */
    // (Pagination removed — full render with per-session store + summarized tool results.)

    // ---- Native list renderers removed ----
    // The transcript renders in Compose (ui/AgentChatMessages.kt and
    // ui/MarkdownView.kt); this Activity no longer builds message views.

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}