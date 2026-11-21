package com.agentpad.workspace.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentpad.workspace.*
import com.agentpad.workspace.data.ChatFolders
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.data.UserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** RikkaHub 3.2.0 ChatDrawer.kt:191–320 / 379–460: profile, weighted list, bottom actions.
 * Adapts the shell to our existing folder/shared-workspace model; no second session store.
 */
@Composable
fun SessionDrawerContent(currentId: String, onOpen: (ChatStore.Session) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(emptyList<ChatStore.Session>()) }
    var target by remember { mutableStateOf<ChatStore.Session?>(null) }
    var rename by remember { mutableStateOf<ChatStore.Session?>(null) }
    var delete by remember { mutableStateOf<ChatStore.Session?>(null) }
    var move by remember { mutableStateOf<ChatStore.Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        sessions = withContext(Dispatchers.IO) { ChatStore.all(context) }
        ready = true
    }
    fun utility(page: String, sessionId: String = currentId, folder: String? = null) {
        onClose()
        context.startActivity(Intent(context, MainActivity::class.java)
            .putExtra("navigation_page", page).putExtra("session_id", sessionId).putExtra("folder_id", folder))
    }
    ChatFolderHistory(sessions = sessions, ready = ready, revision = revision,
        onRefresh = { revision++ },
        onNew = { folder -> utility("NEW_SESSION", folder = folder) },
        onOpen = onOpen, onMenu = { target = it }, drawerMode = true, currentSessionId = currentId,
        header = {
            val loggedIn = UserStore.isLoggedIn(context)
            val name = UserStore.username(context).ifBlank { "Agent Workspace" }
            Row(Modifier.fillMaxWidth().clickable {
                onClose()
                context.startActivity(Intent(context, if (loggedIn) ProfileActivity::class.java else AuthActivity::class.java))
            }.padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(AColors.AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, "个人信息", tint = AColors.Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (loggedIn) name else "登录 / 注册", fontWeight = FontWeight.SemiBold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (loggedIn) "个人信息与钱包" else "开始你的创作", fontSize = 12.sp, color = AColors.Muted)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭会话列表", tint = AColors.Muted) }
            }
        }, footer = {
            HorizontalDivider(color = AColors.LineSoft)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                DrawerFooterAction(Icons.Outlined.FolderOpen, "文件") { utility("WORKSPACE") }
                DrawerFooterAction(Icons.Outlined.Forum, "论坛") { utility("FORUM") }
                DrawerFooterAction(Icons.Outlined.GridView, "相册") {
                    onClose(); context.startActivity(Intent(context, ImageGalleryActivity::class.java))
                }
                DrawerFooterAction(Icons.Outlined.Settings, "设置") { utility("SETTINGS") }
            }
        })
    target?.let { item ->
        AlertDialog(onDismissRequest = { target = null }, title = { Text(item.title, maxLines = 1) },
            text = { Column {
                TextButton(onClick = { target = null; rename = item }) { Text("修改标题") }
                TextButton(onClick = { target = null; move = item }) { Text("移动到文件夹") }
                TextButton(onClick = { target = null; utility("WORKSPACE", item.id) }) { Text("打开工作区") }
                TextButton(onClick = { target = null; delete = item; error = null }) { Text("删除会话", color = AColors.Danger) }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { target = null }) { Text("关闭") } })
    }
    rename?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.title) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("修改标题") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                ChatStore.renameSession(context, item.id, name.trim()); rename = null; revision++
            }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
    move?.let { item -> ChatFolderMoveDialog(item, { move = null }, { move = null; revision++ }) }
    delete?.let { item ->
        var alsoWorkspace by remember(item.id) { mutableStateOf(false) }
        val shared = ChatFolders.isShared(context, item.id)
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除会话") },
            text = { Column {
                Text(error ?: "删除「${item.title}」的聊天记录？${if (shared) "共享工作区与其他会话保留。" else "工作区默认保留。"}")
                if (!shared) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoWorkspace, { alsoWorkspace = it }); Text("同时删除独立工作区")
                }
            } }, confirmButton = { TextButton(onClick = {
                if (WorkingBubble.isShowing(item.id) || com.agentpad.workspace.floating.FloatingSessionHub.isBusy(item.id)) {
                    error = "请等当前任务结束后再删除"
                } else {
                    val workspace = ChatStore.workspaceDir(context, item.id)
                    val removeFiles = alsoWorkspace && !ChatFolders.isShared(context, item.id)
                    ChatStore.deleteSession(context, item.id)
                    if (removeFiles) workspace.deleteRecursively()
                    delete = null; revision++
                    if (item.id == currentId) {
                        onClose()
                        utility("CHAT_HOME")
                    }
                }
            }) { Text("删除", color = AColors.Danger) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } })
    }
}

@Composable
private fun DrawerFooterAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, title, tint = AColors.InkSoft, modifier = Modifier.size(22.dp))
        Text(title, color = AColors.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Overlay stays dormant when closed, so the XML message/browser layers keep their ownership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDrawerHost(visible: Boolean, currentId: String, onClose: () -> Unit, onOpen: (ChatStore.Session) -> Unit) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    LaunchedEffect(visible) {
        if (visible) {
            state.open()
            snapshotFlow { state.currentValue }.first { it == DrawerValue.Closed }
            onClose()
        } else state.close()
    }
    BackHandler(visible) { onClose() }
    if (visible || state.currentValue == DrawerValue.Open || state.isAnimationRunning) {
        ModalNavigationDrawer(drawerState = state, gesturesEnabled = true, drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 300.dp).fillMaxHeight(),
                drawerContainerColor = AColors.Surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                SessionDrawerContent(currentId, onOpen, onClose)
            }
        }) { Box(Modifier.fillMaxSize()) }
    }
}
