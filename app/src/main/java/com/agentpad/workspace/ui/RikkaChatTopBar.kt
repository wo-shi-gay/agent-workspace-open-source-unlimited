// 本文件是 RikkaHub 3.1.1 的逐行照抄，不是重写。
//
// 原始文件：rikkahub-master/app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
// 原始行号：560-643（private fun TopBar）
//
// 【照抄原则】
// 结构、层级、修饰符、字号、透明度、对齐方式全部保持原样。
// 只做三处「不得不改」的替换，每处都在下面注释里标明原始行号：
//   1. HugeIcons → Material Icons（我们没引 huge-icons 依赖）
//        HugeIcons.Menu03            → Icons.Outlined.Menu
//        HugeIcons.Cancel01          → Icons.Outlined.Close
//        HugeIcons.LeftToRightListBullet → Icons.AutoMirrored.Outlined.List
//        HugeIcons.MessageAdd01      → Icons.Outlined.AddComment
//   2. stringResource(R.string.xxx) → 中文字面量（我们没这套 strings.xml）
//   3. 数据源：Conversation/Assistant/Model → 我们的 ChatStore.Session + 字符串
//
// 特别注意（这是和旧版顶部栏最大的区别，也是主人最想改的地方）：
//   · TopAppBar 的 title 默认就是**左对齐**，不是居中
//   · 标题字号是 bodyMedium（正文大小），不是 21sp 加粗
//   · 标题下方还有第二行「助手名 / 模型名」，8sp，65% 透明度
//   · 背景 containerColor = Color.Transparent，完全透明
//   · 左侧是菜单（开抽屉），不是返回箭头
//   · 右侧两个图标：列表选项 + 新建对话

package com.agentpad.workspace.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对话页顶部栏。
 *
 * 骨架来自 RikkaHub 3.1.1 ChatPage.kt:560-643，但按本项目的实际功能做了删减：
 *
 * 【与原版的差异 —— 都是"我们没有那个功能"】
 *   1. 原版右侧有两个按钮：切换预览模式 + 新建对话。
 *      我们没有"预览模式"，"新建对话"也已按主人要求删除 → 右侧只留菜单。
 *   2. 原版菜单在**左侧**（用于开抽屉）。主人要求把菜单放到**右侧**，
 *      左侧改回**返回箭头** → 左侧 back，右侧 menu。
 *   3. 原版标题左对齐。保留（这是 RikkaHub 的特征，主人没要求改）。
 *   4. 原版第二行「助手名 / 模型名 (供应商)」。保留为模型名一行。
 *
 * 其余保持照抄：透明背景、bodyMedium 标题、labelSmall 8sp 副标题、
 * 65% 透明度副标题、TopAppBar 标准高度。
 *
 * @param title        会话标题
 * @param subtitle     第二行（模型名），空则不显示
 * @param onBack       左侧返回箭头
 * @param onClickTitle 点击标题改名
 * @param onOpenMenu   右侧菜单按钮（开侧边栏）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RikkaChatTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onClickTitle: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    mode: String = "chat",
    legacyUi: Boolean = true,
    onNewChat: () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            // 左侧：返回箭头（原版这里是菜单，按主人要求换回返回）
            IconButton(onClick = onBack) {
                Icon(if (legacyUi) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                    if (legacyUi) "返回" else "会话列表")
            }
        },
        title = {
            Surface(
                onClick = onClickTitle,
                color = Color.Transparent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionModeIcon(mode, modifier = Modifier.padding(end = 10.dp), size = 24.dp)
                    Column {
                    // 原版 606-611：标题，bodyMedium，单行省略
                    Text(
                        text = title.ifBlank { "新对话" },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 原版 612-622：第二行，labelSmall + 8.sp + 65% 透明度
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            ),
                        )
                    }
                    }
                }
            }
        },
        actions = {
            // 右侧：菜单（开侧边栏）。原版的新建对话按钮已删除。
            if (legacyUi) {
                IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "会话工具") }
            } else {
                IconButton(onClick = onNewChat) { Icon(Icons.Outlined.AddComment, "新建会话") }
            }
        },
    )
}