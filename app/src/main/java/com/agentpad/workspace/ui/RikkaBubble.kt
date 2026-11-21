// Ported from RikkaHub 3.1.1 (AGPL-3.0-only):
//   ui/components/message/ChatMessage.kt      (ChatMessageBubble / clipDrawing / 圆角常量)
//   ui/components/message/ChatMessageAttachments.kt (animatePressProgress / FileChip / MediaTile)
// 适配本项目：Material3 1.3.0（无 motionScheme）、无 HugeIcons、无 coil3。

package com.agentpad.workspace.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat

/** RikkaHub 原版气泡圆角常量。 */
private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubblePressedCorner = 12.dp

/**
 * 按压进度：按下趋向 1，松开回到 0，用来让容器圆角在按下时收紧。
 * RikkaHub 3.1.1 原文用 MaterialTheme.motionScheme.fastSpatialSpec()，
 * material3 1.5.0-beta02 已公开该 API，直接照抄。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun animatePressProgress(interactionSource: InteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = androidx.compose.material3.MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "PressProgress",
    )
}

/**
 * 只裁剪绘制内容，不像 clip 那样把形状设成图层轮廓。
 *
 * RikkaHub 原注释：四个角半径不同时图层轮廓是 Path，Compose 判断触摸点是否落在
 * 轮廓内时用 Path 求交，坐标超过 8192px 后 float 精度不够，求交结果为空，
 * 长消息下半部分（例如展开的长代码块）就收不到触摸了。
 */
internal fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
    }
    onDrawWithContent {
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}

/**
 * 消息气泡：大圆角，靠头像一侧的顶角收紧；可点击时按下其余圆角也跟着收紧。
 *
 * 对齐 RikkaHub `ChatMessageBubble`：
 *  - 用户：topStart 收紧（用户在右）
 *  - 助手：topStart 收紧（助手在左）
 *  - 按下时圆角从 24dp 动画收紧到 12dp
 *
 * @param isUser true 为用户侧，决定哪一侧用「尾巴角」。
 */
@Composable
internal fun RikkaBubble(
    isUser: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    val corner = lerp(BubbleCorner, BubblePressedCorner, pressProgress)
    val shape = if (isUser) {
        RoundedCornerShape(
            topStart = corner,
            topEnd = BubbleTailCorner,
            bottomEnd = corner,
            bottomStart = corner,
        )
    } else {
        RoundedCornerShape(
            topStart = BubbleTailCorner,
            topEnd = corner,
            bottomEnd = corner,
            bottomStart = corner,
        )
    }
    val contentColor = contentColorFor(color)
    Box(
        modifier = modifier
            .clipDrawing(shape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            // MarkdownView 自带内边距，这里补足到 RikkaHub 的 10dp
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * 文件胶囊：文档附件、工作区里改过的文件、引用条。
 *
 * 对齐 RikkaHub `ChatMessageFileChip`：
 *  - 胶囊形 50% 圆角，按下收紧到 22%
 *  - 水平 12dp / 垂直 8dp 内边距
 *  - 文本最多 200dp 宽，单行省略
 */
@Composable
internal fun RikkaFileChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AColors.AccentSoft,
    icon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(percent = lerpFloat(50f, 22f, pressProgress).coerceIn(0f, 50f).toInt()),
        color = color,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.invoke()
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}


/**
 * 图片瓦片：显示本地图片缩略图（生图/识图结果）。
 *
 * 参考 RikkaHub ChatMessage.kt:490 的 `is UIMessagePart.Image`：
 * 图片直接作为消息内容里的瓦片渲染（不是“调用工具”里的小方块），
 * 高度 72dp？不 —— 这里用 160dp，因为生图结果值得看清一点；
 * 点击进入 App 内全屏预览（ImagePreviewActivity），不再扔给系统相册。
 *
 * 尺寸放大到 160dp 后，单张图解码用 inSampleSize 控制，避免大图进内存。
 */
@Composable
internal fun RikkaImageTile(
    path: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = java.io.File(path)
                if (!f.isFile) return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > 720) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(path, opts)
            }.getOrNull()
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(lerp(18.dp, 10.dp, pressProgress)),
        color = AColors.SurfaceAlt,
        interactionSource = interactionSource,
    ) {
        Box(
            Modifier
                .widthIn(max = 240.dp)
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 解码中/失败：给一个柔和的占位，别让用户以为什么都没发生
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BreathingDots(color = ChatMuted, dotSize = 5.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                    Text("图片读取中…", fontSize = 10.sp, color = ChatMuted)
                }
            }
        }
    }
}

/**
 * 「正在生图」占位卡。
 *
 * 主人指出：如果生成过程完全没反馈，用户会以为卡死。
 * 这里在 assistant 消息里显示一张带呼吸点的卡：
 *   · 有 prompt 时把 prompt 作为副标题（用户知道自己让它画什么）
 *   · 完成后这张卡自然消失（tool part 被替换成真实图片瓦片）
 */
@Composable
internal fun GeneratingImageCard(
    prompt: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = AColors.SurfaceAlt,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BreathingDots(color = ChatAccent, dotSize = 6.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text("正在生成图片", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AColors.Ink)
                Text(
                    prompt.ifBlank { "请稍候，通常需要 10-60 秒" },
                    fontSize = 11.sp, color = AColors.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 媒体瓦片：图片加载占位、视频/音频附件。
 *
 * 对齐 RikkaHub `ChatMessageMediaTile`：72dp 方块，圆角 20dp，按下收紧到 8dp。
 */
@Composable
internal fun RikkaMediaTile(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(lerp(20.dp, 8.dp, pressProgress)),
        color = AColors.SurfaceAlt,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = ChatMuted,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}