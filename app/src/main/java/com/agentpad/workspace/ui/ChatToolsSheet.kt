package com.agentpad.workspace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The new composer's plus menu owns attachments and all former sidebar actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatToolsSheet(visible: Boolean, planView: Boolean, state: SidebarState,
    planItems: List<Triple<String, String, String>>, actions: SidebarActions,
    onBackFromPlan: () -> Unit, onImages: () -> Unit, onFiles: () -> Unit) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = actions.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AColors.Surface) {
        BackHandler { if (planView) onBackFromPlan() else actions.onClose() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            if (planView) {
                AgentPlanSidebarView(planItems, onBackFromPlan)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImages, modifier = Modifier.weight(1f)) { Text("选择图片") }
                    OutlinedButton(onClick = onFiles, modifier = Modifier.weight(1f)) { Text("选择文件") }
                }
                AgentChatSidebar(state, actions)
            }
        }
    }
}
