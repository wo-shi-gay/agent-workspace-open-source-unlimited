// 远程工作区页面宿主。
//
// 一个 Activity 装两个 Compose 页面（列表 + 编辑），用内部状态切换，
// 不引 Navigation 库（我们项目本来就没引）。
//
// 对应 RikkaHub 的：
//   ui/pages/extensions/workspace/WorkspacePage.kt      （列表）
//   ui/pages/extensions/workspace/WorkspaceSshEditorPage.kt（编辑）

package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agentpad.workspace.ui.AgentTheme
import com.agentpad.workspace.ui.RemoteWorkspaceEditorPage
import com.agentpad.workspace.ui.RemoteWorkspaceListPage

class RemoteWorkspaceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.agentpad.workspace.ui.applySystemBars(this)

        setContent {
            AgentTheme {
                // editingId: null 表示不在编辑态；"" 表示新建；其他表示编辑对应 id
                // 用两层状态区分「列表 / 编辑」，避免 null 的二义性
                var editing by remember { mutableStateOf(false) }
                var editingId by remember { mutableStateOf<String?>(null) }

                if (editing) {
                    RemoteWorkspaceEditorPage(
                        workspaceId = editingId,
                        onBack = { editing = false },
                        onSaved = { editing = false },
                    )
                } else {
                    RemoteWorkspaceListPage(
                        onBack = { finish() },
                        onEdit = { id ->
                            editingId = id
                            editing = true
                        },
                    )
                }
            }
        }
    }
}