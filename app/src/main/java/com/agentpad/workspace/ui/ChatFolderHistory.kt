package com.agentpad.workspace.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ChatFolders
import com.agentpad.workspace.data.ChatStore

/** Operit-style folder headers + chat rows, flattened into a single lazy list. */
@Composable
fun ChatFolderHistory(
    sessions: List<ChatStore.Session>,
    ready: Boolean,
    revision: Int,
    onRefresh: () -> Unit,
    onNew: (String?) -> Unit,
    onOpen: (ChatStore.Session) -> Unit,
    onMenu: (ChatStore.Session) -> Unit,
    drawerMode: Boolean = false,
    currentSessionId: String? = null,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val folders = remember(revision) { ChatFolders.list(context) }
    val memberships = remember(sessions, revision) { sessions.associate { it.id to ChatFolders.folderId(context, it.id) } }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var editFolder by remember { mutableStateOf<ChatFolders.Folder?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<ChatFolders.Folder?>(null) }
    var removeFolder by remember { mutableStateOf<ChatFolders.Folder?>(null) }
    // The new drawer follows RikkaHub 3.2.0: no inline search editor.
    // Ignore any restored legacy query so every drawer conversation remains visible.
    val needle = if (drawerMode) "" else query.trim()
    val matches: (ChatStore.Session) -> Boolean = { it.title.contains(needle, ignoreCase = true) }
    val sorted = sessions.asReversed()
    val ungrouped = sorted.filter { memberships[it.id] == null && matches(it) }
    val groups = folders.map { folder ->
        folder to sorted.filter { memberships[it.id] == folder.id &&
            (matches(it) || folder.name.contains(needle, ignoreCase = true)) }
    }.filter { (folder, chats) -> needle.isEmpty() || chats.isNotEmpty() || folder.name.contains(needle, true) }

    HistoryLayout(drawerMode) {
        header()
        Row(Modifier.fillMaxWidth().padding(top = if (drawerMode) 4.dp else 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (drawerMode) "会话" else "对话历史", fontSize = if (drawerMode) 18.sp else 24.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
                Text("${folders.size} 个文件夹 · ${sessions.size} 条对话", fontSize = 12.sp,
                    color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onClick = { editFolder = null; showEditor = true }) {
                Icon(Icons.Outlined.CreateNewFolder, "新建对话文件夹", tint = AColors.Accent)
            }
            IconButton(onClick = { onNew(null) }) {
                Icon(Icons.Outlined.Add, "新建对话", tint = AColors.Accent)
            }
        }
        if (!drawerMode) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索对话或文件夹") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ready) {
                item("loading") { Text("正在加载历史会话…", color = AColors.Muted, modifier = Modifier.padding(16.dp)) }
            } else {
                if (sessions.isEmpty() && folders.isEmpty()) {
                    item("empty") {
                        AgentCard {
                            Text("把同一个项目的对话放在一起", fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text("创建文件夹，在里面开启多个对话，共享同一份工作区文件。", color = AColors.Muted,
                                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            TextButton(onClick = { editFolder = null; showEditor = true }) { Text("创建第一个文件夹") }
                        }
                    }
                }
                groups.forEach { (folder, chats) ->
                    val expanded = needle.isNotEmpty() || folder.id !in collapsed
                    item("folder:${folder.id}") {
                        AgentCard(padding = 12.dp, onClick = {
                            collapsed = if (folder.id in collapsed) collapsed - folder.id else collapsed + folder.id
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateContentSize()) {
                                Icon(if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null,
                                    tint = AColors.Accent, modifier = Modifier.size(25.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(folder.name, fontSize = 15.sp, color = AColors.Ink, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${chats.size} 条对话 · 共享工作区", fontSize = 11.sp, color = AColors.Muted,
                                        modifier = Modifier.padding(top = 3.dp))
                                }
                                IconButton(onClick = { onNew(folder.id) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.Add, "在文件夹内新建对话", tint = AColors.Accent)
                                }
                                IconButton(onClick = { folderMenu = folder }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.MoreVert, "文件夹管理", tint = AColors.Muted)
                                }
                                Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                                    if (expanded) "收起文件夹" else "展开文件夹", tint = AColors.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (expanded) {
                        if (chats.isEmpty()) item("empty:${folder.id}") {
                            Text("这里还没有对话，点 ＋ 开始协作", color = AColors.Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
                        }
                        items(chats, key = { "chat:${it.id}" }) { session ->
                            HistoryChatRow(session, true, { onOpen(session) }, { onMenu(session) }, drawerMode, session.id == currentSessionId)
                        }
                    }
                }
                if (ungrouped.isNotEmpty()) {
                    item("ungrouped") {
                        Text("未分组 · ${ungrouped.size}", color = AColors.Muted, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(ungrouped, key = { "chat:${it.id}" }) { session ->
                        HistoryChatRow(session, false, { onOpen(session) }, { onMenu(session) }, drawerMode, session.id == currentSessionId)
                    }
                }
                if (needle.isNotEmpty() && groups.isEmpty() && ungrouped.isEmpty()) item("no-results") {
                    Text("没有匹配的对话或文件夹", color = AColors.Muted, modifier = Modifier.padding(20.dp))
                }
            }
        }
        footer()
    }
    if (showEditor) {
        var name by remember(editFolder?.id) { mutableStateOf(editFolder?.name.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showEditor = false },
            title = { Text(if (editFolder == null) "新建对话文件夹" else "重命名文件夹") },
            text = { Column {
                OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text("文件夹名称") },
                    isError = error != null, modifier = Modifier.fillMaxWidth())
                Text(error ?: "文件夹内的对话共享工作区，聊天记录各自保存。", fontSize = 12.sp,
                    color = if (error == null) AColors.Muted else AColors.Danger, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    val target = editFolder
                    if (target == null) ChatFolders.create(context, name) else ChatFolders.rename(context, target.id, name)
                }.onSuccess { showEditor = false; onRefresh() }.onFailure { error = it.message }
            }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } })
    }
    folderMenu?.let { folder ->
        AlertDialog(onDismissRequest = { folderMenu = null }, title = { Text(folder.name) },
            text = { Column {
                TextButton(onClick = { folderMenu = null; onNew(folder.id) }) { Text("在这里新建对话") }
                TextButton(onClick = { folderMenu = null; editFolder = folder; showEditor = true }) { Text("重命名文件夹") }
                TextButton(onClick = { folderMenu = null; removeFolder = folder }) { Text("移除文件夹", color = AColors.Danger) }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { folderMenu = null }) { Text("关闭") } })
    }
    removeFolder?.let { folder ->
        var error by remember(folder.id) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { removeFolder = null }, title = { Text("移除文件夹") },
            text = { Text(error ?: "移除「${folder.name}」后，里面的对话移到未分组。聊天记录和共享工作区文件全部保留。") },
            confirmButton = { TextButton(onClick = {
                runCatching { ChatFolders.remove(context, folder.id) }
                    .onSuccess { removeFolder = null; onRefresh() }.onFailure { error = it.message }
            }) { Text("移除", color = AColors.Danger) } },
            dismissButton = { TextButton(onClick = { removeFolder = null }) { Text("取消") } })
    }
}

@Composable
private fun HistoryLayout(drawerMode: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (drawerMode) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), content = content)
    } else {
        AgentPage(content = content)
    }
}

@Composable
private fun HistoryChatRow(session: ChatStore.Session, nested: Boolean, onOpen: () -> Unit, onMenu: () -> Unit,
    compact: Boolean = false, selected: Boolean = false) {
    if (compact) {
        Row(Modifier.fillMaxWidth().padding(start = if (nested) 12.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AColors.AccentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SessionModeIcon(session.mode, size = 22.dp)
            Text(session.title, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                fontSize = 14.sp, color = if (selected) AColors.Accent else AColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, "对话操作", tint = AColors.Muted, modifier = Modifier.size(18.dp))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = if (nested) 14.dp else 0.dp)) {
        AgentCard(modifier = Modifier.weight(1f), padding = 14.dp, onClick = onOpen, onLongClick = onMenu) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionModeIcon(session.mode, size = 30.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(session.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AColors.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${session.messages.size} 条消息", fontSize = 11.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.MoreVert, "对话操作", tint = AColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun ChatFolderMoveDialog(session: ChatStore.Session, onDismiss: () -> Unit, onMoved: () -> Unit) {
    val context = LocalContext.current
    val folders = remember { ChatFolders.list(context) }
    val current = remember { ChatFolders.folderId(context, session.id) }
    var selected by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("移动对话") },
        text = { Column {
            Text("归入文件夹后使用目标文件夹的共享工作区；原工作区文件留在原目录。移到未分组时保留当前工作区绑定。",
                color = AColors.Muted, fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { selected = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == null, { selected = null }); Text("未分组", color = AColors.Ink)
                    }
                }
                items(folders, key = { it.id }) { folder ->
                    Row(Modifier.fillMaxWidth().clickable { selected = folder.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == folder.id, { selected = folder.id })
                        Text(folder.name, color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { Text(it, color = AColors.Danger, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = {
            if (com.agentpad.workspace.WorkingBubble.isShowing(session.id) ||
                com.agentpad.workspace.floating.FloatingSessionHub.isBusy(session.id)) {
                error = "请等当前任务结束后再移动对话"
            } else runCatching { ChatFolders.move(context, session.id, selected) }
                .onSuccess { onMoved() }.onFailure { error = it.message }
        }, enabled = selected != current) { Text("移动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
