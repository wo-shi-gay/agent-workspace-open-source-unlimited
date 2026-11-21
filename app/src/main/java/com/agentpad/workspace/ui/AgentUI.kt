package com.agentpad.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Workspace 统一设计系统。
 *
 * 全部界面（首页 / 工作区 / 历史 / 设置 / 钱包 / 个人中心 / 市场 ...）
 * 都从这里取色板、字号和组件，保证视觉一致。
 *
 * 色板沿用 App 原有的 XML colors.xml（靛蓝主色 + 浅灰纸面），
 * 但换算成 Compose 语义色，亮度分档更细。
 */

// ---------------- 色板 ----------------

object AColors {
    // 全部动态读取当前主题色板（ThemeStore.palette）。
    // 引用方写法不变（AColors.Xxx），切主题时依赖 Compose 状态自动重组。
    private val p get() = ThemeStore.palette.value

    // 主色
    val Accent get() = p.accent
    val AccentDeep get() = p.accentDeep
    val AccentSoft get() = p.accentSoft
    val AccentGhost get() = p.accentGhost

    // 文字
    val Ink get() = p.ink
    val InkSoft get() = p.inkSoft
    val Muted get() = p.muted
    val Faint get() = p.faint

    // 背景 / 卡片
    val Paper get() = p.paper
    val Surface get() = p.surface
    val SurfaceAlt get() = p.surfaceAlt
    val Line get() = p.line
    val LineSoft get() = p.lineSoft

    // 状态
    val Success get() = p.success
    val SuccessSoft get() = p.successSoft
    val Warning get() = p.warning
    val WarningSoft get() = p.warningSoft
    val Danger get() = p.danger
    val DangerSoft get() = p.dangerSoft

    // 功能色（图标底色）
    val Violet get() = p.violet
    val Teal get() = p.teal
    val Pink get() = p.pink
    val Amber get() = p.amber

    // 代码块 / 行内代码
    val CodeBg get() = p.codeBg
    val CodeFg get() = p.codeFg
    val CodeMuted get() = p.codeMuted
    val InlineCodeBg get() = p.inlineCodeBg
    val InlineCodeFg get() = p.inlineCodeFg

    val IsDark get() = p.isDark
}

// ---------------- 尺寸 / 圆角 ----------------

object ADimens {
    val PagePadding = 20.dp
    val CardRadius = 18.dp
    val CardRadiusSmall = 14.dp
    val ChipRadius = 12.dp
    val CardPadding = 16.dp
    val GapCard = 12.dp
    val GapSection = 24.dp
    val IconBox = 42.dp
}

// ---------------- 主题包装（给 Material3 提供配色） ----------------

private val AgentColorScheme: ColorScheme
    get() {
        val p = ThemeStore.palette.value
        return if (p.isDark) darkColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            primaryContainer = p.accentSoft,
            onPrimaryContainer = p.accentDeep,
            secondary = p.inkSoft,
            onSecondary = Color.White,
            background = p.paper,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            surfaceVariant = p.surfaceAlt,
            onSurfaceVariant = p.muted,
            outline = p.line,
            outlineVariant = p.lineSoft,
            error = p.danger,
            onError = Color.White,
            // ---- Material 3 容器色阶 ----
            // RikkaHub 3.1.1 的消息气泡/思考块直接引用这几个 token：
            //   用户气泡  ChatMessage.kt:408  primaryContainer
            //   助手气泡  ChatMessage.kt:428  surfaceContainerHigh
            //   思考块    ChatMessage.kt:357  surfaceContainerHigh
            // 我们之前没定义，Compose 会退回默认灰，导致颜色和 RikkaHub 对不上。
            // 这里按「离背景越近越浅」的层级映射到自己的色板：
            //   surfaceContainerLowest  比背景更"凹"（几乎不用）
            //   surfaceContainerLow     卡片底
            //   surfaceContainer        中间层
            //   surfaceContainerHigh    气泡/思考块（最常用）
            //   surfaceContainerHighest 最高浮层
            surfaceContainerLowest = p.paper,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surfaceAlt,
            surfaceContainerHigh = p.surfaceAlt,
            surfaceContainerHighest = p.surfaceAlt,
        ) else lightColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            primaryContainer = p.accentSoft,
            onPrimaryContainer = p.accentDeep,
            secondary = p.inkSoft,
            onSecondary = Color.White,
            background = p.paper,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            surfaceVariant = p.surfaceAlt,
            onSurfaceVariant = p.muted,
            outline = p.line,
            outlineVariant = p.lineSoft,
            error = p.danger,
            onError = Color.White,
            // 同上，浅色分支
            surfaceContainerLowest = p.paper,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surfaceAlt,
            surfaceContainerHigh = p.surfaceAlt,
            surfaceContainerHighest = p.surfaceAlt,
        )
    }

// RikkaHub 3.1.1 Theme.kt:111 原文就是这一行。
// material3 已升到 1.5.0-beta02，MotionScheme 是公开 API，可以直接用。
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val p = ThemeStore.palette.value   // 订阅主题状态，切换时重组
    // 系统栏（状态栏/导航栏）跟随主题：深色不再露白条。所有界面经由此处统一生效。
    SystemBarsEffect()
    MaterialTheme(
        colorScheme = AgentColorScheme,
        // RikkaHub 3.1.1 Theme.kt:111 原文：motionScheme = MotionScheme.expressive()
        motionScheme = androidx.compose.material3.MotionScheme.expressive(),
        typography = MaterialTheme.typography.copy(
            titleLarge = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.ink),
            titleMedium = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.ink),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(color = p.inkSoft),
            labelSmall = MaterialTheme.typography.labelSmall.copy(color = p.muted),
        ),
        content = content,
    )
}

/** 页面根容器：统一底色 + 留白 + 状态栏安全区。 */
@Composable
fun AgentPage(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ADimens.PagePadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AColors.Paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = horizontalPadding),
        content = content,
    )
}

// ---------------- 基础组件 ----------------

/** 大标题 + 副标题的页头。 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, fontSize = 13.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 6.dp), lineHeight = 18.sp,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 区块小标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.InkSoft, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** 标准卡片容器。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentCard(
    modifier: Modifier = Modifier,
    radius: Dp = ADimens.CardRadius,
    padding: Dp = ADimens.CardPadding,
    background: Color = AColors.Surface,
    border: Color? = AColors.LineSoft,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    var mod = modifier.fillMaxWidth().clip(shape).background(background)
    if (border != null) mod = mod.border(1.dp, border, shape)
    if (onClick != null || onLongClick != null) {
        mod = mod.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    }
    Column(mod.padding(padding), content = content)
}

/**
 * 带图标的功能行：左侧彩色图标方块 + 标题/副标题 + 右侧箭头。
 * 首页、设置页、个人中心的主要导航都用它。
 */
@Composable
fun ActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    AgentCard(padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(icon, iconTint)
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle, fontSize = 12.sp, color = AColors.Muted,
                        modifier = Modifier.padding(top = 4.dp), lineHeight = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingText != null) {
                Text(trailingText, fontSize = 13.sp, color = AColors.Muted, fontWeight = FontWeight.Medium)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = AColors.Faint,
                modifier = Modifier.size(22.dp).padding(start = 2.dp),
            )
        }
    }
}

/** 圆角图标方块。 */
@Composable
fun IconBox(icon: ImageVector, tint: Color, size: Dp = ADimens.IconBox) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.11f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** 主按钮（实心）。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(ADimens.CardRadiusSmall),
        colors = ButtonDefaults.buttonColors(
            containerColor = AColors.Accent,
            contentColor = Color.White,
            disabledContainerColor = AColors.Accent.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.75f),
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 次按钮（描边）。 */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(ADimens.CardRadiusSmall),
        border = androidx.compose.foundation.BorderStroke(1.dp, AColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AColors.Ink),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 圆角小标签（状态/模式）。 */
@Composable
fun Tag(text: String, color: Color, background: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 状态点（健康检测三态）。 */
@Composable
fun StatusBadge(state: StatusState, text: String) {
    val (icon, tint, bg) = when (state) {
        StatusState.Ok -> Triple(Icons.Outlined.CheckCircle, AColors.Success, AColors.SuccessSoft)
        StatusState.Fail -> Triple(Icons.Outlined.ErrorOutline, AColors.Danger, AColors.DangerSoft)
        StatusState.Loading -> Triple(Icons.Outlined.Sync, AColors.Warning, AColors.WarningSoft)
    }
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(bg)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

enum class StatusState { Ok, Fail, Loading }

/** 空状态占位。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(AColors.AccentGhost),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AColors.Accent.copy(alpha = 0.6f), modifier = Modifier.size(30.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.InkSoft, modifier = Modifier.padding(top = 16.dp))
        if (!hint.isNullOrBlank()) {
            Text(hint, fontSize = 13.sp, color = AColors.Muted, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) Spacer(Modifier.height(18.dp)); action?.invoke()
    }
}

/** 主色渐变卡（首页 hero / 钱包余额）。 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(AColors.Accent, AColors.Violet),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(ADimens.CardRadius))
            .background(Brush.linearGradient(colors))
            .padding(20.dp),
        content = content,
    )
}

/** 细分隔线。 */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AColors.LineSoft))
}

/** 子页面顶栏：返回按钮 + 标题（可选副标题）。 */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AColors.Surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回",
                tint = AColors.Ink, modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 12.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 子页面脚手架：底色 + 状态栏安全区 + 顶栏 + 可滚动内容。
 * 所有二级页面（钱包/个人中心/通知/公告/关于/市场…）统一用它。
 */
@Composable
fun SubPage(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AgentPage {
        TopBar(title, subtitle, onBack, trailing)
        if (scrollable) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
                content = content,
            )
        } else {
            Column(Modifier.weight(1f), content = content)
        }
    }
}

/** 圆角输入框（统一样式）。 */
@Composable
fun AgentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = AColors.Faint, fontSize = 14.sp) },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(ADimens.CardRadiusSmall),
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AColors.Accent,
            unfocusedBorderColor = AColors.Line,
            focusedContainerColor = AColors.Surface,
            unfocusedContainerColor = AColors.Surface,
        ),
    )
}
