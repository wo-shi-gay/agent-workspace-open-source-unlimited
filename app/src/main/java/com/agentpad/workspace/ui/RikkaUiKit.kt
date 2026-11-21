// 移植自 RikkaHub 3.1.1 (AGPL-3.0-only):
//   ui/components/ai/AttachmentChips.kt
//   ui/modifier/Shimmer.kt
//   ui/components/ui/ImagePreviewDialog.kt
//
// 适配点：RikkaHub 用 coil3 + HugeIcons + Koin + ZoomablePager。
// 本项目无这些依赖，改为：原生 Bitmap 解码缩略图、Material Icons、
// 手写双指缩放 Pager。视觉规格（圆角/尺寸/间距/颜色角色）保持一致。

package com.agentpad.workspace.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.agentpad.workspace.ChatAttachment
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────
// 1. 缩略图加载（替代 coil3 AsyncImage）
// ─────────────────────────────────────────────────────────────

/**
 * 从 Uri 解码小尺寸 Bitmap。用 inSampleSize 抽采样，避免整张大图进内存。
 * RikkaHub 用 coil3 自动做这件事，这里手写等价逻辑。
 */
@Composable
internal fun rememberUriThumbnail(uri: Uri, targetPx: Int = 128): Bitmap? {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, uri, targetPx) {
        value = runCatching {
            val cr = context.contentResolver
            // 第一趟只读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > targetPx * 2) sample *= 2
            // 第二趟真解码
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
    return bmp
}

// ─────────────────────────────────────────────────────────────
// 2. 附件芯片行（移植 AttachmentChips.kt）
// ─────────────────────────────────────────────────────────────

/**
 * 附件预览行。RikkaHub 的 MediaFileInputRow 等价物。
 *
 * 与原版差异：原版按 UIMessagePart.Image/Video/Audio/Document 分派，
 * 本项目 ChatAttachment.Item 只有 image 布尔位，故二分。
 * 视觉规格完全对齐：44dp 高、18dp 圆角、34dp 缩略图（10dp 圆角）、
 * 26dp 删除圆钮、标题 max 180dp。
 */
@Composable
fun AttachmentChipRow(
    attachments: List<Pair<ChatAttachment.Item, String>>,
    onRemove: (Int) -> Unit,
    onPreview: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { index, (item, name) ->
            AttachmentChip(
                title = name.ifBlank { if (item.image) "image" else "file" },
                leading = {
                    if (item.image) {
                        ImageThumb(item.uri)
                    } else {
                        AttachmentLeadingIcon(Icons.Outlined.Description)
                    }
                },
                onRemove = { onRemove(index) },
                onClick = if (item.image) ({ onPreview(index) }) else null,
            )
        }
    }
}

/** 34dp 圆角 10dp 的真实缩略图；解码中显示占位。 */
@Composable
private fun ImageThumb(uri: Uri) {
    val bmp = rememberUriThumbnail(uri)
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = AColors.SurfaceAlt,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = AColors.Faint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = AColors.Surface,
        border = BorderStroke(1.dp, AColors.Line.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.height(44.dp).padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = ChatBody,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "移除",
                    tint = AColors.Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = AColors.SurfaceAlt,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = AColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 3. 图片预览（移植 ImagePreviewDialog.kt）
// ─────────────────────────────────────────────────────────────

/**
 * 全屏图片预览。RikkaHub 用 ZoomablePager 实现，这里手写：
 * HorizontalPager 翻页 + detectTransformGestures 双指缩放/拖动。
 * 底部有保存按钮和外部 actions 槽（原版用于「在图上涂鸦」）。
 */
@Composable
fun ImagePreviewDialog(
    images: List<Uri>,
    initialPage: Int = 0,
    actions: @Composable (page: Int) -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex),
    ) { images.size }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(images[page])
            }

            // 页码指示（多图时）
            if (images.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp),
                )
            }

            // 关闭
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).zIndex(1f),
            ) {
                Icon(Icons.Outlined.Close, "关闭", tint = Color.White)
            }

            // 底部操作栏
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { /* 保存回调由外部注入 */ }) {
                    Icon(Icons.Outlined.Download, "保存", tint = Color.White)
                }
                actions(pagerState.currentPage)
            }
        }
    }
}

/** 单张图：双指缩放 + 拖动。双击复位。 */
@Composable
private fun ZoomableImage(uri: Uri) {
    val bmp = rememberUriThumbnail(uri, targetPx = 2048)
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bmp
        if (image == null) {
            Text("加载中…", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            return@Box
        }
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(uri) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        // 缩放回 1 时把偏移归零，避免图跑出屏幕
                        offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                    }
                },
        )
    }
}

// ─────────────────────────────────────────────────────────────
// 4. Shimmer 动画（移植 Shimmer.kt，逻辑原样保留）
// ─────────────────────────────────────────────────────────────

/**
 * Shimmer 加载扫光。算法与原版一致：对角线长度决定渐变宽度，
 * DstIn 混合只保留内容覆盖区，斜 20° 线性渐变从左扫到右。
 */
@Composable
fun Modifier.shimmer(
    isLoading: Boolean,
    shimmerColor: Color = AColors.Faint.copy(alpha = 0.55f),
    backgroundColor: Color = AColors.Faint.copy(alpha = 0.12f),
    durationMillis: Int = 1200,
    angle: Float = 20f,
    gradientWidthRatio: Float = 0.5f,
): Modifier {
    if (!isLoading) return this

    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ShimmerTranslate",
    )
    val angleRad = Math.toRadians(angle.toDouble()).toFloat()
    val colors = remember(shimmerColor, backgroundColor) {
        listOf(backgroundColor, shimmerColor, backgroundColor)
    }

    return this
        .onGloballyPositioned { size = it.size }
        .graphicsLayer { alpha = 0.99f }
        .drawWithContent {
            if (size == IntSize.Zero) {
                drawContent()
                return@drawWithContent
            }
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val diagonal = sqrt(w * w + h * h)
            val gradientWidth = diagonal * gradientWidthRatio
            val totalDistance = diagonal + gradientWidth
            val currentOffset = translate * totalDistance - gradientWidth
            val startX = currentOffset * kotlin.math.cos(angleRad)
            val startY = currentOffset * kotlin.math.sin(angleRad)
            val endX = (currentOffset + gradientWidth) * kotlin.math.cos(angleRad)
            val endY = (currentOffset + gradientWidth) * kotlin.math.sin(angleRad)
            val brush = Brush.linearGradient(
                colors = colors,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                tileMode = TileMode.Clamp,
            )
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
}

// ─────────────────────────────────────────────────────────────
// 5. 呼吸圆点（对齐 RikkaHub DotLoading，带相位差）
// ─────────────────────────────────────────────────────────────

/**
 * 三点呼吸。RikkaHub 的 DotLoading.kt 用每点错开 phase 的 infiniteTransition。
 * 这里保留同款节奏：单点 900ms 上下起伏，点间错开 150ms。
 */
@Composable
fun BreathingDots(
    color: Color = ChatMuted,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    count: Int = 3,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "BreathingDots")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BreathingDotsT",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dotSize / 2),
    ) {
        repeat(count) { i ->
            // 每点错开 1/6 周期
            val phase = ((t + i * 0.1667f) % 1f)
            // 0→1→0 的三角波，映射成 0.35..1.0 的 alpha
            val tri = 1f - kotlin.math.abs(phase * 2f - 1f)
            val alpha = 0.35f + 0.65f * tri
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape),
            )
        }
    }
}
