package com.agentpad.workspace.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG

/**
 * 模型 LOGO —— 照抄 RikkaHub `AutoAIIcon` 的行为。
 *
 * 规则：
 *   1. 用模型名去 [AIIconMatcher] 里匹配，命中就用对应品牌图标
 *   2. 命中不到 → 退化成「首字母圆角块」文字头像（与参考源码 TextAvatar 同义）
 *
 * 为什么分 SVG / PNG 两条路：
 *   assets/icons/ 里 55 个图标不全是 SVG，有 6 个是 PNG（juhenext、tavern、
 *   ling、bing、linkup、tavily…）。androidsvg 只能解 SVG，PNG 得走 BitmapFactory。
 *
 * ⚠ SVG 用 androidsvg 而不是 Compose 的 ImageVector：
 *   有一批图标（gemini 等）是「3 层 path + linearGradient」，
 *   ImageVector 不支持渐变，转过去会变成纯色块，品牌色就丢了。
 */
@Composable
fun AutoModelLogo(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    fallbackColor: Color = AColors.Accent,
) {
    val context = LocalContext.current
    val asset = remember(name) { AIIconMatcher.iconFor(name) }

    if (asset == null) {
        TextAvatar(name = name, size = size, color = fallbackColor, modifier = modifier)
        return
    }

    if (asset.endsWith(".png", ignoreCase = true)) {
        // PNG 图标：直接解码成 Bitmap（55 个里只有 6 个，解码开销可接受）
        val bitmap = remember(asset) {
            runCatching {
                context.assets.open("icons/$asset").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
        if (bitmap == null) {
            TextAvatar(name = name, size = size, color = fallbackColor, modifier = modifier)
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = modifier.size(size),
            )
        }
        return
    }

    // SVG 图标：解析一次缓存住，之后每帧只做 draw
    val svg = remember(asset) {
        runCatching {
            context.assets.open("icons/$asset").use { SVG.getFromInputStream(it) }
        }.getOrNull()?.also { s ->
            // ⚠ 2026-11-10 关键修复（首版 LOGO 完全不显示的根因）：
            //   lobehub 这批图标的根节点写的是 height="1em" width="1em"，
            //   viewBox="0 0 24 24"。androidsvg 不认识 `em` 单位，
            //   会把 documentWidth/documentHeight 解析成 0，
            //   于是 renderToCanvas 什么都不画，下面那个 `docW <= 0` 判断直接返回。
            //   这里强制把文档尺寸设成 viewBox 的 24×24，让 renderToCanvas 有画布可用。
            runCatching {
                if (s.documentWidth <= 0f) s.setDocumentWidth("24")
                if (s.documentHeight <= 0f) s.setDocumentHeight("24")
            }
        }
    }
    if (svg == null) {
        TextAvatar(name = name, size = size, color = fallbackColor, modifier = modifier)
        return
    }

    Canvas(modifier = modifier.size(size)) {
        val docW = svg.documentWidth
        val docH = svg.documentHeight
        if (docW <= 0f || docH <= 0f) return@Canvas
        // 等比缩放并居中（图标 viewBox 都是 24x24，但不同文件可能略有差异）
        val scale = minOf(this.size.width / docW, this.size.height / docH)
        val dx = (this.size.width - docW * scale) / 2f
        val dy = (this.size.height - docH * scale) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            native.translate(dx, dy)
            native.scale(scale, scale)
            runCatching { svg.renderToCanvas(native) }
            native.restoreToCount(save)
        }
        // 避免 "unused parameter" 警告
        if (dx < -1e9f) drawCircle(Color.Transparent, 0f, Offset.Zero)
    }
}

/**
 * 兜底头像：模型名首字母 + 圆角色块。
 *
 * 与参考源码 `TextAvatar` 同一语义：认不出品牌时至少给个稳定、可区分的视觉标识，
 * 而不是显示一长串模型 id。
 */
@Composable
fun TextAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = AColors.Accent,
) {
    val letter = remember(name) {
        name.trim().takeIf { it.isNotEmpty() }?.first()?.uppercaseChar()?.toString() ?: "?"
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = color,
            fontSize = (size.value * 0.55f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}