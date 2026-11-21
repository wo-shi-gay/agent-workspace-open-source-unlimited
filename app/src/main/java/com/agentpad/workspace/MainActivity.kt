package com.agentpad.workspace

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.data.HeartbeatManager
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主界面（Compose 重构版 · v2）。
 *
 * 四个页签：首页 / 工作区 / 对话历史 / 设置，切换带滑动+淡入动画。
 * 工作区已改为「文件管理器」样式：顶部会话选择器 + 面包屑路径栏 + 紧凑文件行列表。
 */
class MainActivity : ComponentActivity() {

    private enum class Page { HOME, WORKSPACE, FORUM, HISTORY, SETTINGS }

    private val currentPage = mutableStateOf(Page.HOME)
    private var previousLegacyUi = ThemeStore.legacyUi.value
    private var utilityPage = false

    private fun enterChatHome() {
        val prefs = getSharedPreferences("navigation_prefs", MODE_PRIVATE)
        val lastId = prefs.getString("last_session_id", null)
        Thread {
            val selected = lastId?.let { ChatStore.loadSession(this, it) }
                ?: ChatStore.all(this).lastOrNull()
                ?: ChatStore.createSession(this)
            runOnUiThread { if (!isFinishing && !isDestroyed) openSession(selected) }
        }.start()
    }

    private fun readNavigationIntent() {
        val page = intent.getStringExtra("navigation_page") ?: return
        utilityPage = true
        wsSessionId.value = intent.getStringExtra("session_id")
        when (page) {
            "SETTINGS" -> currentPage.value = Page.SETTINGS
            "WORKSPACE" -> currentPage.value = Page.WORKSPACE
            "FORUM" -> currentPage.value = Page.FORUM
            "NEW_SESSION" -> {
                currentPage.value = Page.HISTORY
                newSessionFolderId.value = intent.getStringExtra("folder_id")
                showNewSession.value = true
            }
            "CHAT_HOME" -> enterChatHome()
        }
    }
    private val refreshTick = mutableStateOf(0)

    /**
     * 通知权限请求 launcher（Android 13+）。
     *
     * 注册在 Activity 上而不是 Compose 里：申请时机在 onCreate，
     * 此时 Compose 还没挂载，用 Activity 的结果 API 最直接。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            com.agentpad.workspace.data.AppLog.i(
                "Permission",
                "POST_NOTIFICATIONS ${if (granted) "已授权" else "被拒绝"}",
            )
            // 授权后如果保活是开启状态，重新拉起一次前台服务，
            // 确保常驻通知立刻出现（之前没权限时 startForeground 的通知被系统丢了）。
            if (granted && KeepAliveNotificationService.isEnabled(this)) {
                runCatching { KeepAliveNotificationService.start(this) }
            }
        }

    /**
     * Android 13+ 需要运行时申请 POST_NOTIFICATIONS。
     *
     * 只有「用户还没做过选择」时才弹窗（`shouldShowRequestPermissionRationale`
     * 为 false 且未授权 = 从未询问过），避免每次进 App 都骚扰。
     * 用户明确拒绝过的话，之后只能在系统设置里改。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return   // Android 13 以下无需申请
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        // 已经拒绝过（且不允许再次询问）时不再弹
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            return
        }
        runCatching {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 返回键双击退出：记录上一次按下返回键的时间戳（毫秒）
    private var lastBackPressedAt = 0L

    // 会话列表：先显示当前快照/骨架，再由后台线程加载 SharedPreferences。
    private val sessionsState = mutableStateOf<List<ChatStore.Session>>(emptyList())
    private val sessionsReady = mutableStateOf(false)
    private val sessionsLoading = mutableStateOf(false)
    private var sessionsRequestId = 0

    // 文件管理器状态
    private val wsSessionId = mutableStateOf<String?>(null)
    private val wsPath = mutableStateOf<String?>(null)
    private val wsEntries = mutableStateOf<List<File>>(emptyList())
    private val wsEntriesPath = mutableStateOf<String?>(null)
    private val wsEntriesLoading = mutableStateOf(false)
    private var wsRequestId = 0

    // 健康检测状态
    private val healthState = mutableStateOf(StatusState.Loading)
    private val healthText = mutableStateOf("检测中")
    private val healthDetail = mutableStateOf("正在连接模型")

    // 对话框状态
    private val showNewSession = mutableStateOf(false)
    private val newSessionFolderId = mutableStateOf<String?>(null)
    private val moveSessionTarget = mutableStateOf<ChatStore.Session?>(null)

    private fun takeNewSessionFolder(): String? = newSessionFolderId.value.also { newSessionFolderId.value = null }

    /**
     * 「新建会话」对话框是否停在「项目开发」二级菜单。
     *
     * 主人要求：一级只留「普通对话 / 项目开发 / 逆向模式」三个，
     * 项目类（APK / Kotlin / 网页 / 视频）收进二级，避免五张卡片竖着占满屏。
     */
    private val showProjectKinds = mutableStateOf(false)
    private val renameTarget = mutableStateOf<ChatStore.Session?>(null)
    private val deleteTarget = mutableStateOf<ChatStore.Session?>(null)
    private val menuTarget = mutableStateOf<ChatStore.Session?>(null)
    private val newFolderDir = mutableStateOf<File?>(null)
    private val renameEntry = mutableStateOf<File?>(null)
    private val deleteEntry = mutableStateOf<File?>(null)

    private var pendingImportDir: File? = null
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val dir = pendingImportDir ?: return@registerForActivityResult
        dir.mkdirs()
        uris.forEach { uri ->
            runCatching {
                val name = queryDisplayName(uri) ?: "import_${System.currentTimeMillis()}"
                contentResolver.openInputStream(uri)?.use { input ->
                    val target = File(dir, uniqueName(dir, name))
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        refresh()
    }

    private fun refresh() {
        refreshTick.value++
        loadSessionsAsync()
        loadWorkspaceEntriesAsync()
    }

    private fun loadSessionsAsync() {
        val request = ++sessionsRequestId
        if (!sessionsReady.value) sessionsLoading.value = true
        Thread {
            val loaded = runCatching { ChatStore.all(this@MainActivity) }.getOrDefault(emptyList())
            runOnUiThread {
                if (request != sessionsRequestId || isFinishing) return@runOnUiThread
                sessionsState.value = loaded
                sessionsReady.value = true
                sessionsLoading.value = false
                refreshTick.value++
                loadWorkspaceEntriesAsync()
            }
        }.start()
    }

    private fun loadWorkspaceEntriesAsync() {
        val sessionId = activeSession(sessionsState.value)?.id ?: return
        val requestedPath = wsPath.value
        val request = ++wsRequestId
        wsEntriesLoading.value = true
        Thread {
            val result = runCatching {
                val root = ChatStore.ensureWorkspace(this@MainActivity, sessionId)
                val dir = requestedPath?.let { File(it) }?.let { candidate ->
                    val rootPath = root.canonicalPath
                    val candidatePath = candidate.canonicalPath
                    if (candidate.isDirectory && (candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator))) candidate else root
                } ?: root
                val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                dir.absolutePath to entries
            }.getOrDefault("" to emptyList())
            runOnUiThread {
                if (request != wsRequestId || wsSessionId.value != sessionId || isFinishing) return@runOnUiThread
                wsEntries.value = result.second
                wsEntriesPath.value = result.first
                wsEntriesLoading.value = false
                refreshTick.value++
            }
        }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        readNavigationIntent()
        if (!ThemeStore.legacyUi.value && !utilityPage) enterChatHome()

        // ⚠ 2026-11-04 通知权限改为运行时申请（Android 13+ 必须）。
        //
        // 之前只在 Manifest 声明 POST_NOTIFICATIONS 是不够的：Android 13 起它属于
        // 运行时权限，用户不授权的话所有通知（包括保活常驻通知、配对码输入通知）
        // 都会被静默丢弃 —— 表现为「保活开了但状态栏没通知」「配对通知不出现」。
        //
        // 这里在首帧后申请一次，用户拒绝也不阻塞功能（只是没有通知）。
        requestNotificationPermissionIfNeeded()

        setContent {
            AgentTheme {
                MainScreen()
                NewSessionDialog()
                RenameDialog()
                DeleteDialog()
                SessionMenuDialog()
                moveSessionTarget.value?.let { target ->
                    ChatFolderMoveDialog(target,
                        onDismiss = { moveSessionTarget.value = null },
                        onMoved = { moveSessionTarget.value = null; wsPath.value = null; refresh() })
                }
                NewFolderDialog()
                RenameEntryDialog()
                DeleteEntryDialog()
            }
        }
        ensureStoragePermission()
        runAiHealthCheck()
    }

    override fun onResume() {
        super.onResume()
        HeartbeatManager.start(this)
        val legacy = ThemeStore.legacyUi.value
        if (legacy != previousLegacyUi) {
            previousLegacyUi = legacy
            if (legacy) currentPage.value = Page.HOME else enterChatHome()
        }
        refresh()
    }

    private fun sessions(): List<ChatStore.Session> = ChatStore.all(this)

    // ---------------- 顶层骨架 ----------------

    @Composable
    private fun MainScreen() {
        // 返回键：连续按两次才退出，避免误触一下就把 App 关掉。
        // 第一次按下弹提示并记录时间戳，2 秒内再按才真正 finish()；超过 2 秒重新计时。
        BackHandler(enabled = true) {
            if (!ThemeStore.legacyUi.value && utilityPage) { finish(); return@BackHandler }
            val now = System.currentTimeMillis()
            val gap = now - lastBackPressedAt
            if (gap in 1..2000L) {
                lastBackPressedAt = 0L
                finish()
            } else {
                lastBackPressedAt = now
                Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
            }
        }
        LaunchedEffect(currentPage.value) {
            if (currentPage.value == Page.HISTORY || currentPage.value == Page.WORKSPACE) {
                loadSessionsAsync()
                if (currentPage.value == Page.WORKSPACE) loadWorkspaceEntriesAsync()
            }
        }
        Column(Modifier.fillMaxSize().background(AColors.Paper)) {
            if (!ThemeStore.legacyUi.value && utilityPage) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("返回会话") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { enterChatHome() }) { Text("会话首页") }
                }
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = currentPage.value,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val dx = if (forward) 1 else -1
                        (slideInHorizontally(tween(260)) { it / 5 * dx } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(tween(260)) { -it / 5 * dx } + fadeOut(tween(150)))
                    },
                    label = "page",
                ) { page ->
                    when (page) {
                        Page.HOME -> if (ThemeStore.legacyUi.value) HomePage() else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("正在打开会话…", color = AColors.Muted)
                            }
                        }
                        Page.WORKSPACE -> WorkspacePage()
                        Page.FORUM -> ForumPage()
                        Page.HISTORY -> HistoryPage()
                        Page.SETTINGS -> SettingsPage()
                    }
                }
            }
            if (ThemeStore.legacyUi.value) BottomNav()
        }
    }

    /**
     * 底部导航（纯图标 · Q 弹丝滑）。
     *
     * 设计：悬浮胶囊容器 + 滑动的高亮指示块 + 弹簧动效。
     * - 指示块用 spring（低阻尼、中低刚度）在四个 Tab 间平移，落位带轻微回弹
     * - 图标随选中放大、按下轻微缩小
     * - 不显示文字（避免选中时文字弹出造成胶囊内尺寸跳动）
     */
    @Composable
    private fun BottomNav() {
        val items = listOf(
            Triple(Page.HOME, Icons.Outlined.Home, "首页"),
            Triple(Page.WORKSPACE, Icons.Outlined.FolderOpen, "文件"),
            Triple(Page.FORUM, Icons.Outlined.Forum, "论坛"),
            Triple(Page.HISTORY, Icons.Outlined.History, "历史"),
            Triple(Page.SETTINGS, Icons.Outlined.Settings, "设置"),
        )
        val selectedIndex = items.indexOfFirst { it.first == currentPage.value }.coerceAtLeast(0)

        // 指示块用「槽位分数」驱动：弹性平移，落位时有轻微回弹（Q弹来源）
        val fraction by animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "navFraction",
        )

        Box(
            Modifier.fillMaxWidth()
                .background(AColors.Paper)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // 胶囊容器
            Box(
                Modifier.fillMaxWidth().height(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AColors.Surface)
                    .border(1.dp, AColors.LineSoft, RoundedCornerShape(22.dp)),
            ) {
                // 滑动高亮块：宽度 = 容器 1/4
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val slot = maxWidth / items.size
                    Box(
                        Modifier
                            .width(slot - 10.dp)
                            .height(48.dp)
                            .align(Alignment.CenterStart)
                            .offset(x = slot * fraction + 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(AColors.Accent, AColors.Violet),
                                ),
                            ),
                    )
                }
                // 四个可点区域
                Row(Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, (page, icon, label) ->
                        val selected = index == selectedIndex
                        NavItem(
                            icon = icon,
                            label = label,
                            selected = selected,
                            modifier = Modifier.weight(1f),
                            onClick = { currentPage.value = page },
                        )
                    }
                }
            }
        }
    }

    /** 单个底部导航项：仅图标，选中放大、按下回缩，文字不入画。 */
    @Composable
    private fun NavItem(
        icon: ImageVector,
        label: String,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()

        // 选中放大 + 按下缩小，都用弹簧
        val scale by animateFloatAsState(
            targetValue = when {
                pressed -> 0.82f
                selected -> 1.12f
                else -> 1f
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "navItemScale",
        )
        val contentColor by animateColorAsState(
            targetValue = if (selected) Color.White else AColors.Muted,
            animationSpec = tween(220),
            label = "navColor",
        )

        Box(
            modifier.fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(25.dp).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }

    // ---------------- 首页 ----------------

    @Composable
    private fun HomePage() {
        val all = sessionsState.value
        val latest = all.lastOrNull()
        val loggedIn = remember(refreshTick.value) { UserStore.isLoggedIn(this) }
        AgentPage {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AGENT WORKSPACE", fontSize = 11.sp, color = AColors.Accent,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(greetingText(), fontSize = 24.sp, color = AColors.Ink,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(AColors.AccentSoft)
                            .clickable { onAvatarClicked() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = "用户",
                            tint = AColors.Accent, modifier = Modifier.size(22.dp))
                    }
                }

                GradientCard(colors = listOf(AColors.Accent, AColors.Violet)) {
                    Text("让 Agent 替你推进工作", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("从一个清晰的目标开始，持续协作，直到任务完成。",
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 8.dp), lineHeight = 19.sp)
                    Box(
                        Modifier.fillMaxWidth().padding(top = 18.dp).height(48.dp)
                            .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(Color.White)
                            .clickable { showNewSession.value = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Add, contentDescription = null,
                                tint = AColors.Accent, modifier = Modifier.size(19.dp))
                            Text("新建 Agent 任务", fontSize = 15.sp, color = AColors.Accent,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentCard(Modifier.weight(1f).height(118.dp), onClick = { currentPage.value = Page.HISTORY }) {
                        Text("历史对话", fontSize = 12.sp, color = AColors.Muted)
                        Text("${all.size}", fontSize = 26.sp, color = AColors.Ink,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Text("条会话记录", fontSize = 11.sp, color = AColors.Success, modifier = Modifier.padding(top = 2.dp))
                    }
                    AgentCard(Modifier.weight(1f).height(118.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AI 状态", fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.weight(1f))
                            StatusBadge(healthState.value, healthText.value)
                        }
                        Text(healthDetail.value, fontSize = 11.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 10.dp), lineHeight = 15.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("点击重新检测", fontSize = 11.sp, color = AColors.Accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp).clickable { runAiHealthCheck() })
                    }
                }

                SectionLabel("最近工作") {
                    Text("查看全部 ›", fontSize = 12.sp, color = AColors.Accent,
                        modifier = Modifier.clickable { currentPage.value = Page.HISTORY })
                }
                if (latest == null) {
                    AgentCard(onClick = { showNewSession.value = true }) {
                        Text("还没有对话", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("新建一个 Agent 任务开始协作", fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                } else {
                    AgentCard(onClick = { openSession(latest) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AColors.AccentSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                SessionModeIcon(latest.mode, size = 28.dp)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(latest.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${latest.messages.size} 条消息 · 点击继续", fontSize = 12.sp,
                                    color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                            }

                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                ActionRow(Icons.Outlined.Api, AColors.Teal, "API 与模型",
                    "配置连接，解锁更强大的 Agent") { openApiConfig() }

                if (!loggedIn) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("账号")
                    ActionRow(Icons.Outlined.PersonOutline, AColors.Muted, "登录 / 注册",
                        "登录后可同步钱包、市场与公告通知") {
                        startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                    }
                }
            }
        }
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "早上好，开始创造吧"
            in 12..17 -> "下午好，继续推进吧"
            in 18..23 -> "晚上好，收尾一下吧"
            else -> "夜深了，灵感还在吗"
        }
    }

    // ---------------- 工作区（文件管理器样式） ----------------

    /** 当前选中的会话（未选中则取第一个）。 */
    private fun activeSession(all: List<ChatStore.Session>): ChatStore.Session? {
        val id = wsSessionId.value
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    /** 当前浏览目录，做越界保护。 */
    private fun currentDir(session: ChatStore.Session): File {
        val root = ChatStore.ensureWorkspace(this, session.id)
        val req = wsPath.value?.let { File(it) }
        return if (req != null && req.isDirectory && req.canonicalPath.startsWith(root.canonicalPath)) req else root
    }

    @Composable
    private fun WorkspacePage() {
        val all = sessionsState.value

        if (!sessionsReady.value) {
            AgentPage {
                PageHeader("文件管理", "正在加载会话工作区")
                LoadingPlaceholder("正在打开文件管理…")
            }
            return
        }
        if (all.isEmpty()) {
            AgentPage {
                PageHeader("文件管理", "每个对话拥有独立的工作区")
                EmptyState(Icons.Outlined.FolderOpen, "还没有工作区",
                    "新建一个对话，每个会话都会自动分配独立目录") {
                    PrimaryButton("新建对话", Modifier.width(180.dp)) { showNewSession.value = true }
                }
            }
            return
        }

        val session = activeSession(all)!!
        val root = ChatStore.workspaceDir(this, session.id)
        val dir = wsPath.value?.let { File(it) } ?: root
        val entries = if (wsEntriesPath.value == dir.absolutePath) wsEntries.value else emptyList()

        AgentPage {
            // 标题 + 顶部操作
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("文件管理", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
                    Text("${entries.size} 个项目", fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 4.dp))
                }
                RoundIconButton(Icons.Outlined.Refresh) { refresh() }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Outlined.CreateNewFolder) { newFolderDir.value = dir }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.Outlined.Upload, AColors.Accent, filled = true) { startImportTo(dir) }
            }

            // 会话选择器（横向滚动 chips）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val folders = com.agentpad.workspace.data.ChatFolders.list(this@MainActivity)
                val selectedKey = com.agentpad.workspace.data.ChatFolders.workspaceKey(this@MainActivity, session.id)
                all.reversed().distinctBy {
                    com.agentpad.workspace.data.ChatFolders.workspaceKey(this@MainActivity, it.id)
                }.forEach { s ->
                    val workspaceKey = com.agentpad.workspace.data.ChatFolders.workspaceKey(this@MainActivity, s.id)
                    val folder = folders.find { it.workspaceKey == workspaceKey }
                    val active = workspaceKey == selectedKey
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (active) AColors.Accent else AColors.Surface)
                            .clickable {
                                wsSessionId.value = s.id
                                wsPath.value = null
                                refresh()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (folder != null) {
                            Icon(Icons.Outlined.Folder, contentDescription = "共享工作区",
                                tint = if (active) Color.White else AColors.Muted, modifier = Modifier.size(18.dp))
                        } else {
                            SessionModeIcon(s.mode, size = 22.dp)
                        }
                        Text(folder?.name ?: s.title, fontSize = 13.sp,
                            color = if (active) Color.White else AColors.InkSoft,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).widthIn(max = 120.dp))
                    }
                }
            }

            // 面包屑路径栏
            BreadcrumbBar(root, dir) { target ->
                wsPath.value = if (target.absolutePath == root.absolutePath) null else target.absolutePath
                refresh()
            }
            Spacer(Modifier.height(10.dp))

            // 文件列表
            if (wsEntriesLoading.value && wsEntriesPath.value != dir.absolutePath) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingPlaceholder("正在加载文件…")
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.FolderOpen, "这里的空文件夹",
                        "点击右上角上传按钮导入文件") {
                        PrimaryButton("导入文件", Modifier.width(160.dp)) { startImportTo(dir) }
                    }
                }
            } else {
                AgentCard(padding = 0.dp, border = AColors.LineSoft) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(entries, key = { it.absolutePath }) { entry ->
                            FileRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        wsPath.value = entry.absolutePath
                                        refresh()
                                    } else openFileExternally(entry)
                                },
                                onRename = { renameEntry.value = entry },
                                onDelete = { deleteEntry.value = entry },
                            )
                            if (entry != entries.last()) {
                                Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp)
                                    .background(AColors.LineSoft))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    /** 单行文件/文件夹条目（文件管理器样式）。 */
    @Composable
    private fun FileRow(
        entry: File,
        onClick: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val isDir = entry.isDirectory
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isDir) AColors.Amber.copy(alpha = .13f) else fileTint(entry).copy(alpha = .13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isDir) Icons.Outlined.Folder else fileIcon(entry),
                    contentDescription = null,
                    tint = if (isDir) AColors.Amber else fileTint(entry),
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isDir) "文件夹 · ${entry.lastModified().toDateString()}"
                    else "${formatSize(entry.length())} · ${entry.lastModified().toDateString()}",
                    fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onRename() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PushPin, contentDescription = "重命名",
                    tint = AColors.Faint, modifier = Modifier.size(17.dp))
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除",
                    tint = AColors.Danger.copy(alpha = .8f), modifier = Modifier.size(17.dp))
            }
        }
    }

    @Composable
    private fun BreadcrumbBar(root: File, current: File, onJump: (File) -> Unit) {
        val segments = remember(root, current) {
            val list = mutableListOf<Pair<String, File>>()
            list.add("根目录" to root)
            val rel = current.absolutePath.removePrefix(root.absolutePath).trim('/')
            if (rel.isNotEmpty()) {
                var acc = root
                rel.split('/').forEach { seg ->
                    acc = File(acc, seg)
                    list.add(seg to acc)
                }
            }
            list
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                .background(AColors.SurfaceAlt)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = AColors.Amber,
                modifier = Modifier.size(16.dp))
            segments.forEachIndexed { index, (name, file) ->
                if (index > 0) {
                    Text("›", fontSize = 14.sp, color = AColors.Faint,
                        modifier = Modifier.padding(horizontal = 6.dp))
                }
                val isLast = index == segments.lastIndex
                Text(
                    name, fontSize = 13.sp,
                    color = if (isLast) AColors.Ink else AColors.Accent,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.clickable(enabled = !isLast) { onJump(file) },
                )
            }
            if (current.absolutePath != root.absolutePath) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "上级",
                    tint = AColors.Accent, modifier = Modifier.size(16.dp).clickable {
                        onJump(current.parentFile ?: root)
                    })
            }
        }
    }

    @Composable
    private fun RoundIconButton(icon: ImageVector, tint: Color = AColors.InkSoft, filled: Boolean = false, onClick: () -> Unit) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                .background(if (filled) tint else AColors.Surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null,
                tint = if (filled) Color.White else tint,
                modifier = Modifier.size(20.dp))
        }
    }

    /** 按扩展名给文件配图标。 */
    private fun fileIcon(file: File): ImageVector = when (file.extension.lowercase()) {
        "kt", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "sh", "json", "xml", "yml", "yaml", "gradle" -> Icons.Outlined.Code
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> Icons.Outlined.Image
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> Icons.Outlined.AudioFile
        "mp4", "mkv", "mov", "avi", "webm" -> Icons.Outlined.VideoFile
        "zip", "tar", "gz", "rar", "7z", "xz", "bz2" -> Icons.Outlined.Archive
        else -> Icons.Outlined.Description
    }

    private fun fileTint(file: File): Color = when (file.extension.lowercase()) {
        "kt", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "sh", "json", "xml", "yml", "yaml", "gradle" -> AColors.Accent
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> AColors.Pink
        "mp3", "wav", "ogg", "flac", "m4a", "aac" -> AColors.Violet
        "mp4", "mkv", "mov", "avi", "webm" -> AColors.Amber
        "zip", "tar", "gz", "rar", "7z", "xz", "bz2" -> AColors.Teal
        else -> AColors.Muted
    }

@Composable
    private fun LoadingPlaceholder(text: String) {
        AgentCard(
            modifier = Modifier.fillMaxWidth(),
            padding = 18.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotLoading(size = 8.dp, color = AColors.Accent)
                Text(text, fontSize = 13.sp, color = AColors.Muted, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }

    private fun Long.toDateString(): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        return sdf.format(java.util.Date(this))
    }

    // ---------------- 历史 ----------------

    @Composable
    private fun HistoryPage() {
        ChatFolderHistory(
            sessions = sessionsState.value,
            ready = sessionsReady.value,
            revision = refreshTick.value,
            onRefresh = { wsPath.value = null; refresh() },
            onNew = { folderId ->
                newSessionFolderId.value = folderId
                showProjectKinds.value = false
                showNewSession.value = true
            },
            onOpen = { openSession(it) },
            onMenu = { menuTarget.value = it },
        )
    }

    // ---------------- 设置 ----------------

    @Composable
    private fun SettingsPage() {
        val loggedIn = remember(refreshTick.value) { UserStore.isLoggedIn(this) }
        AgentPage {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                PageHeader("设置", "配置连接、环境与账号")

                SectionLabel("账号")
                ActionRow(Icons.Outlined.PersonOutline, AColors.Accent,
                    if (loggedIn) UserStore.username(this@MainActivity) else "登录 / 注册",
                    if (loggedIn) "已登录 · 点击查看账号与钱包" else "登录后可使用钱包、市场发布与通知") {
                    onAvatarClicked()
                }
                if (loggedIn) {
                    Spacer(Modifier.height(8.dp))
                    ActionRow(Icons.Outlined.Storefront, AColors.Success, "钱包与卡密",
                        "余额查询、卡密兑换与购买") {
                        startActivity(Intent(this@MainActivity, WalletActivity::class.java))
                    }
                }
                Spacer(Modifier.height(18.dp))

                SectionLabel("外观")
                ActionRow(Icons.Outlined.Palette, AColors.Accent, "主题与配色",
                    "主色调切换 · 深色模式") {
                    startActivity(Intent(this@MainActivity, ThemeActivity::class.java))
                }
                Spacer(Modifier.height(18.dp))

                SectionLabel("模型与连接")
                ActionRow(Icons.Outlined.Api, AColors.Teal, "API 与模型",
                    "编辑 Base URL、Key 和模型列表") { openApiConfig() }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Image, AColors.Pink, "生图配置",
                    "OpenAI 兼容接口 · 拉取模型 · 是否暴露给 AI") {
                    startActivity(Intent(this@MainActivity, ImageGenConfigActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.GridView, AColors.Violet, "相册",
                    "查看 AI 生成/看过的图片（都在本地，不会丢）") {
                    startActivity(Intent(this@MainActivity, ImageGalleryActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Description, AColors.Accent, "自定义提示词",
                    "在工具提示词之上再叠加你的偏好与约束") {
                    startActivity(Intent(this@MainActivity, PromptActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Palette, AColors.Pink, "对话背景",
                    "纯色 / 图片 / 动态背景，可调模糊与压暗") {
                    startActivity(Intent(this@MainActivity, ChatBackgroundActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Dns, AColors.Violet, "MCP 服务",
                    if (com.agentpad.workspace.ai.McpServerManager.isRunning)
                        "运行中 · 端口 ${com.agentpad.workspace.ai.McpServerManager.boundPort} · 点击管理"
                    else "对外暴露本机工具，或接入别人的 MCP Server") {
                    startActivity(Intent(this@MainActivity, McpActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                // 远程工作区：照抄 RikkaHub 3.1.1 的 Workspace（SSH）页面。
                // 副标题动态显示已配置数量，没有时给引导文案。
                // 注意：这里在 ColumnScope 里，`this` 不是 Context，必须显式写 this@MainActivity。
                ActionRow(Icons.Outlined.Dns, AColors.Teal, "远程工作区",
                    run {
                        val n = com.agentpad.workspace.data.RemoteWorkspaceStore
                            .list(this@MainActivity).size
                        if (n > 0) "已配置 $n 个 · SSH 连接远程服务器" else "通过 SSH 连接远程服务器，AI 读写远端目录"
                    }) {
                    startActivity(Intent(this@MainActivity, RemoteWorkspaceActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Extension, AColors.Accent, "插件",
                    "安装 JS 沙箱插件，成为 AI 可调用的系统级工具") {
                    startActivity(Intent(this@MainActivity, PluginManagerActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Settings, AColors.Violet, "连接状态",
                    "启动时自动发送检测消息", trailingText = healthText.value) { runAiHealthCheck() }
                Spacer(Modifier.height(18.dp))

                SectionLabel("内容与工具")
                ActionRow(Icons.Outlined.Security, AColors.Accent, "屏幕控制",
                    if (com.agentpad.workspace.screencontrol.ScreenControlSettings.enabled(this@MainActivity))
                        "已开启 · 无障碍屏幕操作" else "读取界面、截图、点击、滑动与输入") {
                    startActivity(Intent(this@MainActivity, ScreenControlActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Storefront, AColors.Violet, "应用市场",
                    "浏览、下载 AI 构建的应用，或发布你的作品") {
                    startActivity(Intent(this@MainActivity, MarketActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Campaign, AColors.Pink, "平台公告",
                    "全站公告，无需登录即可查看") {
                    startActivity(Intent(this@MainActivity, AnnouncementActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.CloudDownload, AColors.Amber, "下载环境（编译工具链）",
                    "粘贴网盘分享链接，下载并部署编译链到沙箱") { openDownloadEnvironment() }
                Spacer(Modifier.height(8.dp))
                // 「系统管理」：切换终端执行器系统（rootfs）。
                // 跟「下载环境」的分工：
                //   下载环境 = 往**当前**系统里装工具链（Java/Kotlin/NDK…）
                //   系统管理 = 换**系统本身**（Debian / Alpine / 外部链接）
                ActionRow(Icons.Outlined.Dns, AColors.Teal, "系统管理",
                    run {
                        val active = com.agentpad.workspace.ai.DistroManager.activeId(this@MainActivity)
                        val label = com.agentpad.workspace.ai.DistroManager
                            .find(this@MainActivity, active)?.label ?: active
                        "当前执行器：$label · 可切换或从链接安装"
                    }) {
                    startActivity(Intent(this@MainActivity, SystemManageActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                // 「部署服务」跟「下载环境」分开：
                //   下载环境 = 装工具链给 AI 用（装完即完事）
                //   部署服务 = 部署能访问的服务（有运行状态/端口/模型配置）
                ActionRow(Icons.Outlined.Dns, AColors.Violet, "部署服务",
                    "部署 Codex Web UI 等可访问服务，可切换模型") {
                    startActivity(Intent(this@MainActivity, DeployServiceActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.CloudDownload, AColors.Teal, "数据迁移（导出 / 导入）",
                    "导出全部会话与工作区，或从迁移包恢复数据") {
                    showMigrationMenu()
                }
                Spacer(Modifier.height(18.dp))

                SectionLabel("关于")
                ActionRow(Icons.Outlined.Info, AColors.Muted, "关于 Workspace",
                    "版本信息与开发名单") {
                    startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.BugReport, AColors.Muted, "运行日志",
                    "实时查看崩溃、网络请求与异常") {
                    startActivity(Intent(this@MainActivity, LogActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Security, AColors.Success, "ADB 权限",
                    "通过 Shizuku 升级 AI 权限 · 后台保活") {
                    startActivity(Intent(this@MainActivity, AdbPermissionActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Wifi, AColors.Teal, "无线调试（配对码）",
                    "不依赖 Shizuku · 直连设备 ADB") {
                    startActivity(Intent(this@MainActivity, WirelessAdbActivity::class.java))
                }
                Spacer(Modifier.height(8.dp))
                // ⚠ 2026-11-04 新增：后台保活设置入口。
                // 三层保活（通知常驻 / 无障碍 / ADB）统一在这里开关与跳转。
                ActionRow(
                    Icons.Outlined.Security,
                    AColors.Success,
                    "后台保活",
                    run {
                        val notifyOn = KeepAliveNotificationService.isEnabled(this@MainActivity)
                        val a11yOn = KeepAliveAccessibilityService.isEnabled(this@MainActivity)
                        buildString {
                            append(if (notifyOn) "通知常驻 开" else "通知常驻 关")
                            append(" · ")
                            append(if (a11yOn) "无障碍 开" else "无障碍 关")
                        }
                    },
                ) {
                    showKeepAliveDialog()
                }
                Spacer(Modifier.height(8.dp))
                ActionRow(Icons.Outlined.Language, AColors.Muted, "站点托管",
                    "管理我的网页部署") {
                    startActivity(Intent(this@MainActivity, DeployActivity::class.java))
                }
            }
        }
    }

    // ---------------- 对话框 ----------------

    @Composable
    private fun NewSessionDialog() {
        if (!showNewSession.value) return
        AlertDialog(
            onDismissRequest = { showNewSession.value = false; showProjectKinds.value = false; newSessionFolderId.value = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 二级页面给一个返回箭头，避免用户困在子菜单里
                    if (showProjectKinds.value) {
                        // 用 Box+clickable 而不是 IconButton：
                        // 本文件没有 import material3.IconButton（其余按钮都走 AgentCard/Box 风格），
                        // 直接加 import 也行，但保持同一种写法更一致，也少一个依赖点。
                        Box(
                            Modifier.size(32.dp)
                                .clip(CircleShape)
                                .clickable { showProjectKinds.value = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回",
                                tint = AColors.Accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (showProjectKinds.value) "项目开发" else "新建会话",
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Column {
                    val folderName = newSessionFolderId.value?.let { id ->
                        com.agentpad.workspace.data.ChatFolders.list(this@MainActivity).find { it.id == id }?.name
                    }
                    if (folderName != null) {
                        Text("文件夹：$folderName · 共享工作区", fontSize = 12.sp, color = AColors.Accent,
                            modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (showProjectKinds.value) {
                        // ============ 二级：项目开发下的四类项目 ============
                        DialogOption("APK 项目", "在沙箱内用 Gradle 编译打包 APK，需要编译工具链") {
                            showNewSession.value = false; showProjectKinds.value = false
                            startProjectSession()
                        }
                        Spacer(Modifier.height(8.dp))
                        DialogOption("Kotlin 项目", "生成标准 Gradle 工程，用 Kotlin + Compose 编写，可编真机 APK") {
                            showNewSession.value = false; showProjectKinds.value = false
                            launchSession("kt")
                        }
                        Spacer(Modifier.height(8.dp))
                        DialogOption("网页项目", "自动生成 index.html，AI 帮你改页面，侧边栏可预览 / 导出") {
                            showNewSession.value = false; showProjectKinds.value = false
                            launchSession("web")
                        }
                        Spacer(Modifier.height(8.dp))
                        DialogOption("视频项目", "从零生成视频：AI 改 project.json 场景，侧边栏可预览 / 导出 out.mp4") {
                            showNewSession.value = false; showProjectKinds.value = false
                            launchSession("video")
                        }
                    } else {
                        // ============ 一级：只有「普通对话」+「项目开发」入口 ============
                        //
                        // 2026-11-18 主人要求：原来五张卡片竖着排太占屏，
                        // 改成「项目开发」收进二级菜单；没有归属分类的项
                        // （普通对话、逆向模式）留在第一屏直接显示。
                        DialogOption("普通对话", "日常问答与协作，不依赖编译工具链") {
                            showNewSession.value = false; launchSession("chat")
                        }
                        Spacer(Modifier.height(8.dp))
                        DialogOption("项目开发  ›", "APK / Kotlin / 网页 / 视频，四类可编译项目") {
                            showProjectKinds.value = true
                        }
                        Spacer(Modifier.height(8.dp))
                        DialogOption("逆向模式（APK 逆向）", "选一个已安装的 APK 解压到工作区，用逆向工具链分析") {
                            showNewSession.value = false; startReverseSession()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton({
                    if (showProjectKinds.value) showProjectKinds.value = false
                    else { showNewSession.value = false; newSessionFolderId.value = null }
                }) { Text(if (showProjectKinds.value) "返回" else "取消") }
            },
        )
    }

    @Composable
    private fun DialogOption(title: String, subtitle: String, onClick: () -> Unit) {
        AgentCard(border = AColors.Line, padding = 14.dp, onClick = onClick) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
            Text(subtitle, fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun RenameDialog() {
        val target = renameTarget.value ?: return
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget.value = null },
            title = { Text("修改标题", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("会话标题") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton({
                    val t = text.trim()
                    if (t.isNotEmpty()) { ChatStore.renameSession(this, target.id, t); renameTarget.value = null; refresh() }
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ renameTarget.value = null }) { Text("取消") } },
        )
    }

    @Composable
    private fun DeleteDialog() {
        val target = deleteTarget.value ?: return
        var alsoWorkspace by remember(target.id) { mutableStateOf(false) }
        val ws = ChatStore.workspaceDir(this, target.id)
        val shared = com.agentpad.workspace.data.ChatFolders.isShared(this, target.id)
        val wsInfo = if (shared) "这是共享工作区，删除本条对话会保留工作区及其他对话。"
            else if (ws.exists()) "可选择同时清理该对话的独立工作区。" else ""
        AlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("删除会话", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确定删除「${target.title}」？删除后聊天记录不可恢复。$wsInfo",
                        fontSize = 14.sp, color = AColors.InkSoft, lineHeight = 20.sp)
                    if (!shared && ws.exists()) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(alsoWorkspace, { alsoWorkspace = it })
                            Text("同时删除会话工作区", fontSize = 14.sp, color = AColors.InkSoft)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val deleteWorkspace = alsoWorkspace && !com.agentpad.workspace.data.ChatFolders.isShared(this, target.id)
                    ChatStore.deleteSession(this, target.id)
                    if (deleteWorkspace && ws.exists()) ws.deleteRecursively()
                    deleteTarget.value = null
                    android.widget.Toast.makeText(this, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text("删除", color = AColors.Danger) }
            },
            dismissButton = { TextButton({ deleteTarget.value = null }) { Text("取消") } },
        )
    }

    @Composable
    private fun SessionMenuDialog() {
        val target = menuTarget.value ?: return
        AlertDialog(
            onDismissRequest = { menuTarget.value = null },
            title = { Text(target.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    DialogOption("移动到文件夹", "同一文件夹内的对话共享工作区") {
                        menuTarget.value = null; moveSessionTarget.value = target
                    }
                    Spacer(Modifier.height(8.dp))
                    DialogOption("打开文件管理", "查看当前对话绑定的工作区") {
                        menuTarget.value = null
                        wsSessionId.value = target.id; wsPath.value = null
                        currentPage.value = Page.WORKSPACE
                        refresh()
                    }
                    if (target.mode == "web") {
                        Spacer(Modifier.height(8.dp))
                        DialogOption("网页预览 / 导出", "在浏览器里查看 index.html，或导出整个工作区") {
                            menuTarget.value = null
                            startActivity(Intent(this@MainActivity, WebPreviewActivity::class.java)
                                .putExtra("session_id", target.id))
                        }
                    }
                    if (target.mode == "video") {
                        Spacer(Modifier.height(8.dp))
                        DialogOption("视频预览 / 导出", "播放 out.mp4，或导出整个视频项目") {
                            menuTarget.value = null
                            startActivity(Intent(this@MainActivity, VideoPreviewActivity::class.java)
                                .putExtra("session_id", target.id))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    DialogOption("导出工作区到 Download", "把该会话的沙箱文件复制到公共下载目录") {
                        menuTarget.value = null; exportWorkspace(target)
                    }
                    Spacer(Modifier.height(8.dp))
                    DialogOption("修改标题", "重命名这条会话") {
                        menuTarget.value = null; renameTarget.value = target
                    }
                    Spacer(Modifier.height(8.dp))
                    DialogOption("删除会话", "删除聊天记录（可选同时删工作区）") {
                        menuTarget.value = null; deleteTarget.value = target
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ menuTarget.value = null }) { Text("取消") } },
        )
    }

    @Composable
    private fun NewFolderDialog() {
        val dir = newFolderDir.value ?: return
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDir.value = null },
            title = { Text("新建文件夹", fontWeight = FontWeight.Bold) },
            text = { AgentTextField(name, { name = it }, "文件夹名称") },
            confirmButton = {
                TextButton({
                    val n = name.trim()
                    if (n.isEmpty()) { android.widget.Toast.makeText(this, "请输入名称", android.widget.Toast.LENGTH_SHORT).show(); return@TextButton }
                    val target = File(dir, n)
                    if (target.exists()) {
                        android.widget.Toast.makeText(this, "已存在同名文件", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val ok = target.mkdirs()
                        newFolderDir.value = null
                        android.widget.Toast.makeText(this, if (ok) "已创建" else "创建失败", android.widget.Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton({ newFolderDir.value = null }) { Text("取消") } },
        )
    }

    @Composable
    private fun RenameEntryDialog() {
        val entry = renameEntry.value ?: return
        var name by remember(entry.absolutePath) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameEntry.value = null },
            title = { Text("重命名", fontWeight = FontWeight.Bold) },
            text = { AgentTextField(name, { name = it }, "新名称") },
            confirmButton = {
                TextButton({
                    val n = name.trim()
                    if (n.isEmpty()) { android.widget.Toast.makeText(this, "请输入名称", android.widget.Toast.LENGTH_SHORT).show(); return@TextButton }
                    val dest = File(entry.parentFile, n)
                    val ok = entry.renameTo(dest)
                    renameEntry.value = null
                    android.widget.Toast.makeText(this, if (ok) "已重命名" else "重命名失败", android.widget.Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text("保存") }
            },
            dismissButton = { TextButton({ renameEntry.value = null }) { Text("取消") } },
        )
    }

    @Composable
    private fun DeleteEntryDialog() {
        val entry = deleteEntry.value ?: return
        val isDir = entry.isDirectory
        val itemCount = if (isDir) entry.walkTopDown().count() - 1 else 0
        AlertDialog(
            onDismissRequest = { deleteEntry.value = null },
            title = { Text(if (isDir) "删除文件夹" else "删除文件", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isDir) "确定删除文件夹「${entry.name}」？共 $itemCount 项内容，此操作不可撤销。"
                    else "确定删除「${entry.name}」？此操作不可撤销。",
                    fontSize = 14.sp, color = AColors.InkSoft, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton({
                    runCatching { if (isDir) entry.deleteRecursively() else entry.delete() }
                    deleteEntry.value = null
                    android.widget.Toast.makeText(this, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                    refresh()
                }) { Text("删除", color = AColors.Danger) }
            },
            dismissButton = { TextButton({ deleteEntry.value = null }) { Text("取消") } },
        )
    }

    // ---------------- 逻辑 ----------------

    private fun openSession(session: ChatStore.Session) {
        if (ThemeStore.legacyUi.value) {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("session_id", session.id))
        } else {
            ChatActivity.open(this, session.id)
            finish()
        }
    }

    private fun launchSession(mode: String) {
        val title = "新对话"
        val session = ChatStore.createSession(this, title, mode, takeNewSessionFolder())
        // 网页项目：立刻在工作区放一个 index.html 模板
        if (mode == "web") runCatching { com.agentpad.workspace.data.WebProject.ensureTemplate(this, session.id) }
        // 视频项目：立刻在工作区铺好视频项目基座（project.json / render.py / build.sh / 中文字体）
        if (mode == "video") runCatching { com.agentpad.workspace.data.VideoProject.ensureTemplate(this, session.id) }
        // Kotlin 项目：解压主人提供的 AgentKotlin 模板包到会话根目录
        if (mode == "kt") runCatching { com.agentpad.workspace.data.KotlinProject.ensureTemplate(this, session.id) }
        openSession(session)
    }

    private fun toolchainStatus(): Pair<Boolean, Boolean> {
        val rootfs = com.agentpad.workspace.ai.SandboxInstaller.rootfsDir(this)
        val java = File(rootfs, "usr/local/jre17/bin/java").isFile
        val tc = File(rootfs, "opt/toolchain-pack/compiler").isDirectory
        return Pair(java, tc)
    }

    /** 逆向模式：先建会话（mode=reverse），立刻进入 ReverseImportActivity 选 APK。
     *  注意——不是直接进聊天页，而是先进「选 APK」界面。 */
    private fun startReverseSession() {
        val session = ChatStore.createSession(this, "新对话", "reverse", takeNewSessionFolder())
        startActivity(Intent(this, ReverseImportActivity::class.java).putExtra("session_id", session.id))
        refresh()
    }

    private fun startProjectSession() {
        val (java, tc) = toolchainStatus()
        if (java && tc) { launchSession("apk"); return }
        val missing = buildString {
            if (!java) appendLine("• Java 17 运行时（未安装）")
            if (!tc) appendLine("• 编译工具链（未安装）")
        }
        val rootfsReady = File(
            com.agentpad.workspace.ai.SandboxInstaller.rootfsDir(this), "bin/sh"
        ).isFile
        val hint = if (!rootfsReady)
            "沙箱尚未初始化：请先创建任意会话让 AI 执行一次终端命令，再到 设置→下载环境 部署。"
        else
            "检测到以下组件未安装：\n$missing\n请到 设置→下载环境 下载部署后再创建项目会话。"
        android.app.AlertDialog.Builder(this)
            .setTitle("编译工具链未就绪")
            .setMessage(hint)
            .setPositiveButton("去下载环境") { _, _ -> openDownloadEnvironment() }
            .setNegativeButton("仍要创建") { _, _ -> launchSession("apk") }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return
        if (Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val message = if (Build.VERSION.SDK_INT >= 30)
            "终端沙箱需要访问全部文件。请在系统设置中允许「所有文件访问」。"
        else
            "终端沙箱需要读写存储以访问 /sdcard。请在系统设置或文件管理器中授予存储权限。"
        android.app.AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("去授权") { _, _ -> openStorageSettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun startImportTo(dir: File) {
        pendingImportDir = dir
        importLauncher.launch(arrayOf("*/*"))
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name; var i = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)}($i)${name.substring(dot)}" else "$name($i)"
            i++
        }
        return candidate
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    private fun openFileExternally(file: File) {
        runCatching {
            val ext = file.extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开 ${file.name}"))
        }.onFailure {
            android.widget.Toast.makeText(this, "没有应用能打开这个文件", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportWorkspace(session: ChatStore.Session) {
        val src = ChatStore.workspaceDir(this, session.id)
        if (src == null || !src.exists() || src.listFiles().isNullOrEmpty()) {
            android.widget.Toast.makeText(this, "工作区为空，没有可导出的内容", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var dest = File(download, sanitizeFileName(session.title))
        var i = 1
        while (dest.exists()) { dest = File(download, "${sanitizeFileName(session.title)}($i)"); i++ }
        Thread {
            val ok = runCatching { src.copyRecursively(dest, overwrite = false) }.isSuccess
            runOnUiThread {
                if (ok) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("导出成功")
                        .setMessage("工作区已复制到：\n${dest.absolutePath}")
                        .setPositiveButton("好", null)
                        .show()
                } else android.widget.Toast.makeText(this, "导出失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "未命名会话" }

    private fun openApiConfig() { startActivity(Intent(this, ApiConfigActivity::class.java)) }
    private fun openDownloadEnvironment() { startActivity(Intent(this, DownloadEnvironmentActivity::class.java)) }

    /**
     * 后台保活设置（2026-11-04）。
     *
     * 三层保活各自独立，可任意组合：
     *   1. 通知常驻 —— 前台服务 + 常驻通知。零特殊权限，默认开启。
     *   2. 无障碍保活 —— 需在系统设置手动开启，最抗系统清理。
     *   3. ADB 保活 —— 需 Shizuku/ADB 权限，见「ADB 权限」页。
     *
     * 用系统原生 AlertDialog（项目没有 appcompat），条目按当前状态给出可执行动作。
     */
    private fun showKeepAliveDialog() {
        val notifyOn = KeepAliveNotificationService.isEnabled(this)
        val a11yOn = KeepAliveAccessibilityService.isEnabled(this)
        val adbOn = com.agentpad.workspace.data.AdbPermission.isEnabled(this)

        val items = arrayOf(
            if (notifyOn) "① 通知常驻：已开启（点击关闭）" else "① 通知常驻：已关闭（点击开启）",
            if (a11yOn) "② 无障碍保活：已开启（点击去关闭）" else "② 无障碍保活：未开启（点击去开启）",
            if (adbOn) "③ ADB 保活：ADB 权限已开启（去管理）" else "③ ADB 保活：需要 ADB 权限（去设置）",
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("后台保活")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val next = !notifyOn
                        KeepAliveNotificationService.setEnabled(this, next)
                        android.widget.Toast.makeText(
                            this,
                            if (next) "已开启通知常驻" else "已关闭通知常驻",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    1 -> {
                        // 无障碍没有公开 API 能直接开关，只能跳到系统设置由用户勾选
                        if (a11yOn) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("关闭无障碍保活")
                                .setMessage("请在系统「设置 → 无障碍 → 已下载的应用 / Workspace 后台保活」里取消勾选。\n\n现在跳过去吗？")
                                .setPositiveButton("去设置") {
                                        _,
                                        _ ->
                                    KeepAliveAccessibilityService.openSettings(this)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        } else {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("开启无障碍保活")
                                .setMessage(
                                    "开启后系统会把本应用当作无障碍服务提供者，\n" +
                                        "后台被清理的概率显著降低。\n\n" +
                                        "本服务不读取屏幕内容、不模拟点击，仅用于保活。\n\n" +
                                        "请在弹出的系统设置里找到「Workspace 后台保活」并勾选。"
                                )
                                .setPositiveButton("去设置") { _, _ ->
                                    KeepAliveAccessibilityService.openSettings(this)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                    2 -> startActivity(Intent(this, AdbPermissionActivity::class.java))
                }
                refreshTick.value++
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 数据迁移入口菜单：导出 or 导入。
     *
     * 拆成两个二级页而不是一页两栏，是因为两边都是重 IO 的大流程，
     * 各自有独立的进度与结果展示，混在一页会互相干扰。
     */
    private fun showMigrationMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("数据迁移")
            .setItems(arrayOf(
                "导出迁移包（换机 / 换版本前备份）",
                "导入迁移包（从备份恢复数据）",
            )) { _, which ->
                val target = if (which == 0) MigrationActivity::class.java else DataImportActivity::class.java
                startActivity(Intent(this, target))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onAvatarClicked() {
        if (!UserStore.isLoggedIn(this)) {
            startActivity(Intent(this, AuthActivity::class.java)); return
        }
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    private fun runAiHealthCheck() {
        healthState.value = StatusState.Loading
        healthText.value = "检测中"
        healthDetail.value = "正在连接模型"
        val api = ChatStore.api(this)
        if (api.first.isBlank() || api.second.isBlank() || api.third.isBlank()) {
            healthState.value = StatusState.Fail
            healthText.value = "未配置"
            healthDetail.value = "请先配置 API 与模型"
            return
        }
        Thread {
            try {
                val c = (URL(api.first.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 12000
                    setRequestProperty("Authorization", "Bearer ${api.second}")
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use {
                    it.write(JSONObject().put("model", api.third)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                        .put("max_tokens", 1).toString().toByteArray())
                }
                val ok = c.responseCode in 200..299
                runOnUiThread {
                    healthState.value = if (ok) StatusState.Ok else StatusState.Fail
                    healthText.value = if (ok) "正常" else "失败"
                    healthDetail.value = if (ok) "模型连接可用" else "请检查配置或网络"
                }
            } catch (_: Exception) {
                runOnUiThread {
                    healthState.value = StatusState.Fail
                    healthText.value = "超时"
                    healthDetail.value = "请检查网络连接"
                }
            }
        }.start()
    }
}