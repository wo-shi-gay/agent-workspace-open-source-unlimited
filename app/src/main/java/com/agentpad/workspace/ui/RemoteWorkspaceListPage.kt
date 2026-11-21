// 远程工作区列表页。照抄 RikkaHub 3.1.1
//   ui/pages/extensions/workspace/WorkspacePage.kt（284 行）
//
// 【照抄的结构】
//   Scaffold + TopAppBar（标题「工作区」+ 返回 + 新建图标）        69-85
//   LazyColumn，contentPadding 16，间距 16dp                      86-113
//     ├─ 已有工作区列表（每项 CardGroup 一项，点击编辑，长按删除）   114 起
//     └─ CardGroup「新建」→ item「远程服务器 (SSH)」              114-140
//   删除确认 AlertDialog                                          179
//   重命名对话框                                                  256-283
//
// 【与原版的差异】
//   · 原版同时支持「本地」和「SSH」两种工作区。本项目只要远程 SSH，
//     所以「新建」里只保留 SSH 一项（本地工作区我们本来就有目录概念）。
//   · HugeIcons → Material Icons
//       CloudServer → Icons.Outlined.Dns
//       FolderCode  → Icons.Outlined.Folder
//       Add01       → Icons.Outlined.Add
//   · stringResource → 中文字面量

package com.agentpad.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentpad.workspace.data.RemoteWorkspace
import com.agentpad.workspace.data.RemoteWorkspaceStore

/**
 * 远程工作区列表。
 *
 * @param onBack      返回
 * @param onEdit      编辑某个工作区（传 null 表示新建）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteWorkspaceListPage(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val context = LocalContext.current
    // 每次回到这页都重新读，保证保存后列表是新的
    var workspaces by remember { mutableStateOf(RemoteWorkspaceStore.list(context)) }
    var pendingDelete by remember { mutableStateOf<RemoteWorkspace?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程工作区") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(null) }) {
                        Icon(Icons.Outlined.Add, "新建")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 已有工作区 ----
            if (workspaces.isNotEmpty()) {
                item {
                    CardGroup(title = { Text("已配置") }) {
                        workspaces.forEach { ws ->
                            item(
                                onClick = { onEdit(ws.id) },
                                leadingContent = { CardGroupIcon(Icons.Outlined.Dns) },
                                headlineContent = { Text(ws.name) },
                                supportingContent = {
                                    val dir = ws.config.remoteDir.ifBlank { "（家目录）" }
                                    Text("${ws.config.username}@${ws.config.host}:${ws.config.port}\n$dir")
                                },
                                trailingContent = {
                                    TextButton(onClick = { pendingDelete = ws }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---- 新建（原版 114-140）----
            item {
                CardGroup(title = { Text("新建") }) {
                    item(
                        onClick = { onEdit(null) },
                        leadingContent = { CardGroupIcon(Icons.Outlined.Dns) },
                        supportingContent = { Text("通过 SSH 连接远程服务器，AI 可读写远端目录") },
                        headlineContent = { Text("远程服务器（SSH）") },
                    )
                }
            }

            // ---- 空状态提示 ----
            if (workspaces.isEmpty()) {
                item {
                    Text(
                        text = "还没有配置远程工作区。点上面的「远程服务器（SSH）」开始添加。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    // ---- 删除确认（原版 179）----
    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工作区") },
            text = { Text("确定删除「${ws.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    RemoteWorkspaceStore.delete(context, ws.id)
                    workspaces = RemoteWorkspaceStore.list(context)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}