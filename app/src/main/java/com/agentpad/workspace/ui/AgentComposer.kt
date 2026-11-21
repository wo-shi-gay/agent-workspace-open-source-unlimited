// Composer 移植自 RikkaHub 3.1.1 ui/components/ai/ChatInput.kt（AGPL-3.0-only）。
//
// 【移植范围】
// 只搬「视觉骨架 + 交互手感」，不搬 RikkaHub 的业务依赖。
// 原文件 835 行里，下面这些是 RikkaHub 专有的，全部剥离：
//   · Koin（koinInject）—— 我们没用依赖注入
//   · Haze（hazeState / chatInputHaze）—— 毛玻璃模糊，我们没有 Haze 库
//   · HugeIcons —— 我们只有 Material Icons
//   · ASR 语音识别（LocalASRState / PermissionManager / SoundEffectPlayer）
//   · MessageQueue 消息队列面板
//   · ChatCompletion 补全弹窗（/ 斜杠补全）
//   · ModelSelectorButton / ReasoningButton 的独立实现
//
// 【保留的 3.1.1 视觉骨架】（这是主人要搬的部分）
//   1. 底部渐变遮罩 drawWithCache：从透明渐变到背景色，
//      按 imeInsets.getBottom 动态收缩，键盘弹出时渐变跟着上移。
//   2. 外层 Surface：圆角 24dp + 1dp outlineVariant(0.5f) 描边。
//   3. 容器内 Column padding(h=8, v=4)，间距 2dp —— 比旧版更紧凑。
//   4. TextField 用 TextFieldLineLimits.MultiLine(maxHeightInLines=5)，
//      指示线全透明，容器色全透明（文字区自己不带框）。
//   5. 底部工具条：左侧可横滑区（附件/模型/思考/搜索），右侧 Send。
//   6. SendButton 三态：busy=DangerSoft、empty=SurfaceAlt、ready=Accent；
//      尺寸 30dp 正圆；图标 18dp。
//   7. FullScreenEditor：BasicAlertDialog + fillMaxHeight(0.9f) +
//      32dp 圆角 TextField，焦点时出现全屏按钮。
//
// 【与 3.1.1 的差异（适配我们的功能）】
//   · 发送按钮用 combinedClickable：短按发送，长按「发送但不请求回答」，
//     对应 3.1.1 的 onSendClick / onLongSendClick 两条通道。
//   · 保留我们的附件预览（ImagePreviewDialog）与 AttachmentChipRow。
//   · 保留 onOpenLocator（消息定位），3.1.1 里没有这个按钮。
//   · 保留 onPickModel / onPickReasoning 回调，但按钮形态改成 3.1.1 的
//     30dp 圆形 ActionIconButton + 文字 Chip 混排。

package com.agentpad.workspace.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * 聊天输入框，RikkaHub 3.1.1 视觉骨架 + 我们的数据接口。
 *
 * 数据流说明：RikkaHub 用 TextFieldState（Compose 新文本 API）做双向绑定，
 * 我们沿用 String + onValueChange，因为 ChatActivity 里还有一个隐藏 EditText
 * 作为 send()/附件的文本缓冲，两边必须镜像同步。这里在内部建一个
 * TextFieldState 做桥接，把它和外部 value 双向对齐。
 */
@Composable
fun AgentComposer(
    value: String,
    onValueChange: (String) -> Unit,
    modelName: String,
    reasoningLabel: String,
    busy: Boolean,
    attachments: List<Pair<com.agentpad.workspace.ChatAttachment.Item, String>> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onPickReasoning: () -> Unit,
    onOpenLocator: () -> Unit = {},
    /** 长按发送：把内容送进对话但不触发模型回答。 */
    onSendWithoutAnswer: () -> Unit = {},
    modifier: Modifier = Modifier,
    floatingMode: Boolean = false,
    clearFocusSignal: Int = 0,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // RikkaHub 3.2.0 ChatPage:148–153: release Compose focus before hiding IME.
    LaunchedEffect(clearFocusSignal) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    var focused by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val empty = value.isBlank()

    // 内部 TextFieldState：3.1.1 的 TextField 只吃这个。
    val textState = rememberTextFieldState(value)

    // 【双向同步 —— 修 "发送后输入框不清空"】
    //
    // ⚠ 之前两次都写错了，根因是同一个：
    //   LaunchedEffect(Unit) { snapshotFlow { value }.collect { ... } }
    // LaunchedEffect(Unit) 的 block 只执行一次，里面的 `value`
    // 是**首次组合时的形参快照**，之后外部 value 变成 "" 它永远读不到。
    // snapshotFlow 里捕获的是这个常量，所以永远只发出第一次的值。
    // → 发送后 composeInput.value = "" 根本传不进来，输入框当然不清空。
    //
    // 正确做法（两条独立、互不打架的通道）：
    //   1) 外部 → 内部：LaunchedEffect(value)，value 变了就重建 effect
    //   2) 内部 → 外部：LaunchedEffect(textState)，用 rememberUpdatedState
    //      拿最新的 value 做比较，避免把回声再写回去

    // 始终指向最新的外部 value，供内部回写时比较
    val currentValue by rememberUpdatedState(value)

    // 通道 1：外部变化 → 写入内部。
    // key 用 value，所以每次外部值变化都会重新执行，能真正拿到新值。
    LaunchedEffect(value) {
        if (textState.text.toString() != value) {
            textState.edit {
                replace(0, length, value)
                selection = TextRange(value.length)
            }
        }
    }

    // 通道 2：内部输入 → 通知外部。
    // textState 是稳定对象，effect 只跑一次；比较用 currentValue（始终最新）。
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { v ->
            if (v != currentValue) onValueChange(v)
        }
    }

    // 图片预览：记录从哪张图开始。图片索引在附件列表中的位置。
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    previewIndex?.let { start ->
        val images = attachments.filter { it.first.image }.map { it.first.uri }
        if (images.isNotEmpty()) {
            val startImage = attachments.getOrNull(start)?.takeIf { it.first.image }
            val page = startImage?.let { images.indexOf(it.first.uri) }?.coerceAtLeast(0) ?: 0
            ImagePreviewDialog(
                images = images,
                initialPage = page,
                onDismissRequest = { previewIndex = null },
            )
        }
    }

    // 3.1.1 原文：底部渐变遮罩，从输入区顶部过渡到屏幕底部（键盘弹出时到键盘上沿），
    // 避免消息从输入框下方透到系统导航栏。这是 RikkaHub 输入区最标志性的一层。
    val scrimColor = AColors.Paper
    val imeInsets = WindowInsets.ime
    Surface(
        color = Color.Transparent,
        modifier = modifier.drawWithCache {
            val brush = Brush.verticalGradient(
                colors = listOf(scrimColor.copy(alpha = 0f), scrimColor),
                startY = 0f,
                endY = (size.height - imeInsets.getBottom(this)).coerceAtLeast(1f),
            )
            onDrawBehind { drawRect(brush) }
        },
    ) {
        Column(
            modifier = Modifier
                .then(if (floatingMode) Modifier else Modifier.imePadding().navigationBarsPadding())
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val containerShape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, AColors.Line.copy(alpha = 0.5f)),
                color = AColors.Surface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (attachments.isNotEmpty()) {
                        AttachmentChipRow(
                            attachments = attachments,
                            onRemove = onRemoveAttachment,
                            onPreview = { previewIndex = it },
                        )
                    }

                    // 3.1.1 的 TextInputRow
                    TextField(
                        state = textState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .animateContentSize(),
                        shape = RoundedCornerShape(24.dp),
                        placeholder = {
                            Text("输入消息与 AI 聊天…", color = AColors.Faint, fontSize = 15.sp)
                        },
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // 3.1.1：只有聚焦时才显示全屏按钮
                                if (focused && !floatingMode) {
                                    ActionIconButton(onClick = { fullScreen = true }) {
                                        Icon(
                                            Icons.Outlined.Fullscreen,
                                            contentDescription = "全屏编辑",
                                            tint = ChatMuted,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        },
                    )

                    // 3.1.1 底部工具条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 附件（3.1.1 的 Add01 在右侧，我们保留在左侧，
                            // 因为我们的附件行是独立的，放左侧更顺手）
                            if (!floatingMode) ActionIconButton(onClick = onAttach) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "添加附件",
                                    tint = ChatMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            // 模型选择：只显示 LOGO（模型名在顶栏副标题里已有，底栏不再重复）
                            ModelChip(modelName = modelName, onClick = onPickModel)
                            // 思考强度
                            ActionIconButton(onClick = onPickReasoning) {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    contentDescription = reasoningLabel,
                                    tint = ChatReasoning,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Chip(text = reasoningLabel, onClick = onPickReasoning, tint = ChatReasoning)
                            // 消息定位（我们独有）
                            ActionIconButton(onClick = onOpenLocator) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = "跳转到消息",
                                    tint = ChatMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(4.dp))

                        // 3.1.1 的 SendButton，带 AnimatedVisibility 包裹
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            SendButton(
                                busy = busy,
                                empty = empty,
                                onClick = { if (busy) onStop() else onSend() },
                                onLongClick = { if (!busy) onSendWithoutAnswer() },
                            )
                        }
                    }
                }
            }
        }
    }

    // 3.1.1 的 FullScreenEditor
    if (fullScreen) {
        FullScreenEditor(
            textState = textState,
            placeholder = "输入消息与 AI 聊天…",
        ) { fullScreen = false }
    }
}

/** 3.1.1 的 ActionIconButton：30dp 正圆、透明底、图标居中。 */
@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** 文字 Chip，比 3.1.1 的 ModelSelectorButton 简化：只显示名字。 */
@Composable
private fun Chip(text: String, onClick: () -> Unit, tint: Color = ChatMuted) {
    Surface(
        color = tint.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text,
            color = tint,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * 模型按钮：只显示品牌 LOGO（2026-11-10 按主人要求改）。
 *
 * 参考源码 RikkaHub 就是这个形态：底栏只放一个小图标，模型名在顶栏副标题里已经有了，
 * 这里再写一遍纯属重复占地方。长模型 id 原本会把工具条顶爆，现在彻底没了这个问题。
 *
 * 认不出品牌时 [AutoModelLogo] 内部退化成首字母圆角块，
 * 所以永远有个可点、可辨识的方块，不会出现空白。
 */
@Composable
private fun ModelChip(modelName: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AutoModelLogo(name = modelName, size = 22.dp)
    }
}

/**
 * 3.1.1 的 SendButton 三态配色 + 尺寸：
 *   busy  → DangerSoft / Danger，图标 Close（停止）
 *   empty → SurfaceAlt / Faint，图标 ArrowUpward
 *   ready → Accent / White，图标 ArrowUpward
 * 差异：3.1.1 用 combinedClickable 区分短按/长按，我们照做。
 */
@Composable
private fun SendButton(
    busy: Boolean,
    empty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        busy -> AColors.DangerSoft
        empty -> AColors.SurfaceAlt
        else -> ChatAccent
    }
    val contentColor = when {
        busy -> AColors.Danger
        empty -> AColors.Faint
        else -> Color.White
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = busy || !empty,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = containerColor,
            content = {},
        )
        AnimatedVisibility(
            visible = busy,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "停止",
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = !busy,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Icon(
                Icons.Outlined.ArrowUpward,
                contentDescription = "发送",
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 3.1.1 的 FullScreenEditor：全屏 BasicAlertDialog，
 * 从底部弹出 90% 高度的面板，圆角 32dp 的 TextField。
 *
 * BasicAlertDialog 在 material3 里仍是 experimental，必须显式 OptIn，
 * 否则编译报 "This material API is experimental"（实测行 458）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenEditor(
    textState: TextFieldState,
    placeholder: String,
    onDone: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = { onDone() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = AColors.Surface,
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(onClick = { onDone() }) {
                            Text("保存", color = ChatAccent)
                        }
                    }
                    TextField(
                        state = textState,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = { Text(placeholder, color = AColors.Faint) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}