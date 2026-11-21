// 移植自 RikkaHub 3.1.1 (AGPL-3.0-only):
//   ui/components/message/ChatMessageActions.kt  (ChatMessageActionButton / FlowRow 布局)
//   ui/components/message/ChatMessageBranch.kt   (ChatMessageBranchSelector)
//
// 适配点：RikkaHub 用 HugeIcons + ModalBottomSheet + SegmentedListItem。
// 本项目改用 Material Icons；底部动作面板改用 DropdownMenu 风格的自绘 Sheet，
// 因为本项目 AGP 9 / Material3 版本下 SegmentedListItem 不可用。
// 视觉与交互规格对齐：18dp 图标、extraSmall 容器、FlowRow 4dp 间距、
// 按下时圆形→方角形状切换、分支 < 1/3 > 三段式。

package com.agentpad.workspace.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit


import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ActionIconSize = 18.dp

/**
 * 消息下方的小号图标按钮。
 * 对齐 ChatMessageActionButton：不占 48dp 最小触控尺寸（否则间距被撑开），
 * 图标 18dp，容器 extraSmall。
 */
@Composable
fun ChatMessageActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ChatMuted,
) {
    // Material3 1.3.0 没有 IconButtonDefaults.shapes()/extraSmallContainerSize()，
    // 改用 32dp 固定容器 + 0 最小触控尺寸，视觉上与 RikkaHub 的 extraSmall 一致。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(32.dp),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        ) {
            Icon(icon, contentDescription, modifier = Modifier.size(ActionIconSize))
        }
    }
}

/**
 * 消息操作栏。对齐 ChatMessageActionButtons 的 FlowRow 布局。
 * RikkaHub 原版顺序：复制 → 重新生成 → (朗读/翻译，仅助手) → 更多 → 分支 → 时间。
 * 本项目没有 TTS/翻译，故省去那两枚；其余顺序保持一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatMessageActionRow(
    isAssistant: Boolean,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    branchIndex: Int = 0,
    branchCount: Int = 1,
    onBranchChange: ((Int) -> Unit)? = null,
    timeLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onCopy != null) {
            ChatMessageActionButton(Icons.Outlined.ContentCopy, "复制", onCopy)
        }
        if (onRegenerate != null) {
            ChatMessageActionButton(Icons.Outlined.Refresh, "重新生成", onRegenerate)
        }
        if (onMore != null) {
            ChatMessageActionButton(Icons.Outlined.MoreVert, "更多", onMore)
        }
        if (branchCount > 1 && onBranchChange != null) {
            ChatMessageBranchSelector(
                index = branchIndex,
                count = branchCount,
                onChange = onBranchChange,
            )
        }
        if (!timeLabel.isNullOrBlank()) {
            Text(
                text = timeLabel,
                fontSize = 11.sp,
                color = ChatFaint.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * 分支选择器。对齐 ChatMessageBranchSelector：
 * 只有 count > 1 才显示；到头的那侧箭头 alpha 0.5 且不可点；
 * 中间显示 `当前/总数`。图标 16dp，padding 8dp。
 */
@Composable
fun ChatMessageBranchSelector(
    index: Int,
    count: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val atStart = index <= 0
        val atEnd = index >= count - 1

        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = "上一条分支",
            modifier = Modifier
                .clip(CircleShape)
                .alpha(if (atStart) 0.5f else 1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    enabled = !atStart,
                    onClick = { if (!atStart) onChange(index - 1) },
                )
                .padding(8.dp)
                .size(16.dp),
            tint = ChatMuted,
        )

        Text(
            text = "${index + 1}/$count",
            fontSize = 11.sp,
            color = ChatMuted,
        )

        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "下一条分支",
            modifier = Modifier
                .clip(CircleShape)
                .alpha(if (atEnd) 0.5f else 1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    enabled = !atEnd,
                    onClick = { if (!atEnd) onChange(index + 1) },
                )
                .padding(8.dp)
                .size(16.dp),
            tint = ChatMuted,
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 消息动作面板（对齐 ChatMessageActionsSheet）
// ─────────────────────────────────────────────────────────────

/** 面板里的一项。destructive 项用 error 容器色。 */
data class MessageSheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 消息动作面板。RikkaHub 用 ModalBottomSheet + SegmentedListItem，
 * 本项目用自绘卡片：首尾项各自圆角、中间方角，破坏性项用 DangerSoft 底。
 */
@Composable
fun ChatMessageActionsSheet(
    actions: List<MessageSheetAction>,
    onDismissRequest: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                actions.forEachIndexed { i, action ->
                    val shape = when {
                        actions.size == 1 -> RoundedCornerShape(12.dp)
                        i == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        i == actions.lastIndex -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    val bg = if (action.destructive) AColors.DangerSoft else AColors.SurfaceAlt
                    val fg = if (action.destructive) AColors.Danger else ChatBody
                    Surface(
                        color = bg,
                        shape = shape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismissRequest()
                                action.onClick()
                            },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(action.label, fontSize = 14.sp, color = fg)
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
}