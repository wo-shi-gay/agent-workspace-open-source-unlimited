// 设置页分组卡片。照抄 RikkaHub 3.1.1
//   ui/components/ui/CardGroup.kt（395 行）
//
// 【照抄原则】
// 结构、圆角、间距、动画规范全部保留：
//   · 组圆角 20dp / 组内项 4dp / 项间距 2dp
//   · 首尾项外圆角、中间项内圆角，按下时圆角回弹到 20dp
//   · 圆角动画用 MaterialTheme.motionScheme.fastSpatialSpec()
//   · 标题用 titleSmallEmphasized + primary 色
//   · 表单项（formItem）标题行 + 下方自定义内容，padding h16 v12，间距 12dp
//
// 【与原版的差异】
//   · 原版用 CustomColors.listItemColors / topBarColors（RikkaHub 自定义配色）。
//     我们没这个对象，改用 MaterialTheme 的标准 token：
//       listItem 容器色  → surfaceContainerHigh（我们色板里映射到 surfaceAlt）
//       标题/图标色      → 照旧用 primary / secondaryContainer
//   · 原版 CardGroupIcon 用 animateColorAsState + motionScheme.defaultEffectsSpec()。
//     保留（material3 1.5.0-beta02 已支持）。
//   · Preview 里用到的 LargeFlexibleTopAppBar / Scaffold 仅预览用，未移植。
//   · switchItem 保留，设置页常用。

package com.agentpad.workspace.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed

private val CardGroupCorner = 20.dp
private val CardGroupItemSpacing = 2.dp
private val CardGroupInnerCorner = 4.dp

/**
 * 列表项配色。原版用 CustomColors.listItemColors，
 * 这里等价映射到 Material3 标准 token：
 *   容器色 surfaceContainerHigh（我们色板里是 surfaceAlt）
 *   文字色 onSurface
 */
@Composable
private fun agentListItemColors(): ListItemColors = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    headlineColor = MaterialTheme.colorScheme.onSurface,
    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: (@Composable () -> Unit)?,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    // 不为空时是表单项，标题行下方接这块内容
    val formContent: (@Composable ColumnScope.() -> Unit)? = null,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )

    // 表单项：标题行下方带一块自定义内容（输入框、滑块等），不传标题时整项都是自定义内容
    fun formItem(
        modifier: Modifier = Modifier,
        headlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            )
        )
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = null,
                formContent = content,
            )
        )
    }
}

// 带开关的项，点整行也能切换
fun CardGroupScope.switchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) = item(
    onClick = if (enabled) {
        { onCheckedChange(!checked) }
    } else null,
    modifier = modifier,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
    headlineContent = headlineContent,
)

// 分组里第 index 项的形状，给不方便走 CardGroup 的列表（比如可拖拽排序的）用
fun cardGroupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) CardGroupCorner else CardGroupInnerCorner
    val bottom = if (index == count - 1) CardGroupCorner else CardGroupInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// 列表项开头带圆形底色的图标
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CardGroupIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .background(animatedContainerColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = animatedContentColor,
        )
    }
}

// 标题行右侧显示当前取值的小标签，比如滑块的数值
@Composable
fun CardGroupValue(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            // 等宽数字，拖动滑块时标签宽度不跳
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
private fun CardGroupFormItem(
    item: CardGroupItem,
    formContent: @Composable ColumnScope.() -> Unit,
    count: Int,
    index: Int,
) {
    Surface(
        modifier = item.modifier.fillMaxWidth(),
        shape = cardGroupItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.headlineContent != null || item.leadingContent != null || item.trailingContent != null) {
                Row(
                    // 和 ListItem 一致：带说明文字时，两侧内容靠上对齐
                    verticalAlignment = if (item.supportingContent != null) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item.leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.headlineContent?.let { headline ->
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                        }
                        item.supportingContent?.let { supporting ->
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                            }
                        }
                    }
                    item.trailingContent?.invoke()
                }
            }
            formContent()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    ListItem(
        headlineContent = item.headlineContent ?: {},
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: agentListItemColors(),
    )
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                    Box(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        val count = scope.items.size
        scope.items.fastForEachIndexed { index, item ->
            val formContent = item.formContent
            if (formContent != null) {
                CardGroupFormItem(item = item, formContent = formContent, count = count, index = index)
            } else {
                CardGroupListItem(item = item, count = count, index = index)
            }
            if (index != count - 1) {
                Spacer(modifier = Modifier.height(CardGroupItemSpacing))
            }
        }
    }
}