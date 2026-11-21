package com.agentpad.workspace.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 聊天侧边栏。
 *
 * 设计取向（与 App 其他页保持同一气质，但**不做卡片堆叠**）：
 *  - 扁平列表：一行一个入口，左边细图标，右边文字 + 可选副标题。
 *  - 无边框、无卡片底色，只用间距和字号做层次 —— 抽屉本身已经是一个
 *    浮层，再往里套卡片会显得又重又挤。
 *  - 分组用小号灰字标题，靠留白分隔。
 *  - 动效统一用 MainActivity 底部导航那套 spring 曲线（Q 弹）。
 */
data class SidebarActions(
    val onClose: () -> Unit,
    val onCompress: () -> Unit,
    val onUndoCompress: () -> Unit,
    val onOpenPlan: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenFileHistory: () -> Unit,
    val onOpenPrompt: () -> Unit,
    val onRegenerateTitle: () -> Unit,
    val onOpenWebPreview: () -> Unit,
    val onRenderVideo: () -> Unit,
    val onOpenVideoPreview: () -> Unit,
    /** Kotlin 项目：扫描当前 AI 工作区的 APK 并调系统安装器。 */
    val onInstallApk: () -> Unit,
    val onOpenFloating: () -> Unit = {},
)

data class SidebarState(
    val activeTask: String? = null,
    val contextEstimate: String = "计算中…",
    val compressionOn: Boolean = false,
    val planDone: Int = 0,
    val planTotal: Int = 0,
    val mode: String = "chat",
)

/** 与 MainActivity.BottomNav 完全一致的弹簧曲线。 */
private val BouncySpring = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun AgentChatSidebar(
    state: SidebarState,
    actions: SidebarActions,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(AColors.Surface)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        // ---------- 顶栏 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "会话",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AColors.Ink,
                modifier = Modifier.weight(1f),
            )
            SidebarIconButton(Icons.Outlined.Close, actions.onClose)
        }

        Spacer(Modifier.height(20.dp))

        // ---------- 当前任务 ----------
        Text(
            state.activeTask ?: "当前没有正在执行的任务",
            fontSize = 14.sp,
            color = if (state.activeTask != null) AColors.Ink else AColors.Muted,
            lineHeight = 21.sp,
            maxLines = 3,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (state.planTotal > 0) {
            // 细进度条：不用卡片，直接一条线
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AColors.Line),
                ) {
                    val fraction by animateFloatAsState(
                        targetValue = (state.planDone.toFloat() / state.planTotal).coerceIn(0f, 1f),
                        animationSpec = BouncySpring,
                        label = "planProgress",
                    )
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AColors.Accent),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${state.planDone}/${state.planTotal}",
                    fontSize = 11.sp,
                    color = AColors.Muted,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        // ---------- 上下文 ----------
        Spacer(Modifier.height(16.dp))
        SidebarRow(
            icon = Icons.Outlined.DataUsage,
            title = state.contextEstimate,
            trailing = if (state.compressionOn) "已压缩" else null,
            trailingTint = AColors.Warning,
            onClick = null,
        )
        SidebarRow(
            icon = if (state.compressionOn) Icons.Outlined.Undo else Icons.Outlined.Compress,
            title = if (state.compressionOn) "撤回上下文压缩" else "压缩上下文",
            onClick = { if (state.compressionOn) actions.onUndoCompress() else actions.onCompress() },
        )

        Divider()

        // ---------- 工具 ----------
        SidebarRow(Icons.Outlined.Checklist, "任务规划", actions.onOpenPlan)
        SidebarRow(Icons.Outlined.OpenInNew, "对话小窗", actions.onOpenFloating)
        SidebarRow(Icons.Outlined.Terminal, "终端控制", actions.onOpenTerminal)
        SidebarRow(Icons.Outlined.History, "浏览 / 撤回 AI 操作", actions.onOpenFileHistory)
        SidebarRow(Icons.Outlined.EditNote, "本会话提示词", actions.onOpenPrompt)
        SidebarRow(Icons.Outlined.Label, "重新生成标题", actions.onRegenerateTitle)

        // ---------- 项目专属 ----------
        if (state.mode == "web") {
            Divider()
            SidebarRow(Icons.Outlined.Web, "网页预览", actions.onOpenWebPreview)
        }
        if (state.mode == "video") {
            Divider()
            SidebarRow(Icons.Outlined.Movie, "渲染视频", actions.onRenderVideo)
            SidebarRow(Icons.Outlined.PlayCircle, "预览视频", actions.onOpenVideoPreview)
        }
        if (state.mode == "kt") {
            Divider()
            SidebarRow(Icons.Outlined.Android, "安装 APK", actions.onInstallApk)
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 细分割线：1dp 实线，左右内缩，替代旧版的卡片堆叠。 */
@Composable
private fun Divider() {
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AColors.LineSoft),
    )
    Spacer(Modifier.height(10.dp))
}

/** 顶栏圆形图标按钮（按压缩放带弹簧）。 */
@Composable
private fun SidebarIconButton(icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iconScale",
    )
    Box(
        Modifier
            .size(36.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AColors.Muted, modifier = Modifier.size(19.dp))
    }
}

/**
 * 一行入口。扁平、无底色；按下时背景轻微浮现（tween 160ms），
 * 图标连同文字一起做极小的弹簧缩放，手感与主页底栏一致。
 */
@Composable
private fun SidebarRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null,
    trailing: String? = null,
    trailingTint: Color = AColors.Muted,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = onClick != null

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "rowScale",
    )
    val bgAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(160),
        label = "rowBg",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(AColors.SurfaceAlt.copy(alpha = bgAlpha))
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { onClick!!() },
                ) else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) AColors.InkSoft else AColors.Muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            title,
            fontSize = 14.sp,
            color = if (enabled) AColors.Ink else AColors.InkSoft,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, color = trailingTint, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 侧边栏容器：遮罩 + 右侧滑入。
 *
 * 动画走 spring（LowBouncy + MediumLow），和主页底栏指示块同一套曲线：
 * 滑到位后会有一点过冲再回落，就是主人要的那种「Q 弹」。
 */
@Composable
fun SidebarHost(
    visible: Boolean,
    planView: Boolean,
    state: SidebarState,
    planItems: List<Triple<String, String, String>>,
    actions: SidebarActions,
    onBackFromPlan: () -> Unit,
    asToolsSheet: Boolean = false,
    onImages: () -> Unit = {},
    onFiles: () -> Unit = {},
) {
    if (asToolsSheet) {
        ChatToolsSheet(visible, planView, state, planItems, actions, onBackFromPlan, onImages, onFiles)
        return
    }
    /**
     * 用 Animatable 显式驱动进度 0..1，而不是 AnimatedVisibility。
     *
     * 为什么换掉 AnimatedVisibility：
     *  - 它**首次组合时不会播放 enter 动画**（必须观察到 false→true 才动）。
     *    之前 SidebarHost 在 visible=false 时 early return，整棵子树被移除，
     *    等 visible 变 true 才首次组合 → 打开永远没动画。
     *  - 它的 exit 只认有限动画规格，之前写 tween(190) 就是匀速，
     *    所以关掉时是"普普通通的定速动画"。
     *
     * 现在：progress 从 0 开始，LaunchedEffect 里 animateTo(1f) 用 spring，
     * 首次组合也能动，并且落位带过冲（Q 弹来源）。
     */
    val progress = remember { Animatable(0f) }
    val scrim = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            // 打开：弹簧滑入，落位时过冲再回落
            launch { scrim.animateTo(1f, tween(180)) }
            progress.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            // 关闭：同样用弹簧（MediumBouncy 收得干脆但仍有回弹感），不用匀速
            launch { scrim.animateTo(0f, tween(170)) }
            progress.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    val p = progress.value
    val scrimAlpha = (0.30f * scrim.value).coerceIn(0f, 1f)
    // 面板位移：p=0 → 完全在屏幕右侧之外；p=1 → 贴右边缘。
    // spring 会把 p 推过 1（过冲），此时 offsetFraction 为负 →
    // 面板略微"甩出"右边界再弹回，这就是要的 Q 弹。
    val offsetFraction = (1f - p).coerceIn(-0.08f, 1f)
    val panelScale = (0.88f + 0.12f * p).coerceIn(0.88f, 1.015f)
    val panelAlpha = p.coerceIn(0f, 1f)

    /**
     * 关键：这里**绝对不能** early return 掉整棵子树。
     *
     * 之前写 `if (!visible && p <= 0.001f) { Spacer(...); return }`，
     * 看起来省事，实际把 LaunchedEffect 和 remember 一起销毁了 ——
     * 下次打开是一次全新的首次组合，动画状态机被重置，
     * 于是表现为「打开没动画」。现在组合树常驻，只靠 p 控制视觉，
     * 收起时用 pointerInput 让触摸穿透到下层聊天页。
     */
    val interactEnabled = p > 0.02f

    Box(
        Modifier
            .fillMaxSize()
            // 收起状态下整层不拦截触摸，让聊天页正常滚动/点击。
            // 展开时拦下来，避免手势穿透到底层。
            .then(
                if (interactEnabled) Modifier.pointerInput(Unit) {
                    // 空手势检测器：仅用于消费事件
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                } else Modifier
            ),
    ) {
        // ---- 遮罩 ----
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .then(
                    if (interactEnabled) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { actions.onClose() } else Modifier
                ),
        )

        // ---- 右侧面板：位移 / 缩放 / 透明度全部由同一个 progress 驱动 ----
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dx = maxWidth * offsetFraction
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(290.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = dx.toPx()
                        scaleX = panelScale
                        scaleY = panelScale
                        alpha = panelAlpha
                        // 缩放原点贴右边中点：像抽屉从侧边甩出来
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    },
            ) {
                androidx.compose.material3.Surface(
                    color = AColors.Surface,
                    shadowElevation = 12.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (planView) {
                        AgentPlanSidebarView(items = planItems, onBack = onBackFromPlan)
                    } else {
                        AgentChatSidebar(state = state, actions = actions)
                    }
                }
            }
        }
    }
}

/** IntOffset 版弹簧（slideIn/OutHorizontally 需要 FiniteAnimationSpec<IntOffset>）。 */
private fun BouncySpringOffset() = spring<androidx.compose.ui.unit.IntOffset>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 任务规划子页（沿用扁平风格）。 */
@Composable
fun AgentPlanSidebarView(
    items: List<Triple<String, String, String>>,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(AColors.Surface)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SidebarIconButton(Icons.Outlined.ArrowBack, onBack)
            Spacer(Modifier.width(6.dp))
            Text("任务规划", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
        }

        Spacer(Modifier.height(18.dp))

        if (items.isEmpty()) {
            Text(
                "暂无计划。\n\nAI 处理多步任务时会用 plan 工具创建计划，进度会实时显示在这里。",
                fontSize = 13.sp,
                color = AColors.Muted,
                lineHeight = 20.sp,
            )
            return@Column
        }

        items.forEach { (text, status, _) ->
            val done = status == "done"
            val running = status == "in_progress"
            val (dotColor, dotIcon) = when {
                done -> AColors.Success to Icons.Outlined.CheckCircle
                running -> AColors.Accent to Icons.Outlined.PlayCircleOutline
                else -> AColors.Muted to Icons.Outlined.RadioButtonUnchecked
            }
            // 每个计划项：左侧状态图标 + 文本，扁平无卡片
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    dotIcon,
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(17.dp).padding(top = 1.dp),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text,
                    fontSize = 13.5.sp,
                    color = if (done) AColors.Muted else AColors.Ink,
                    lineHeight = 20.sp,
                    textDecoration = if (done)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}