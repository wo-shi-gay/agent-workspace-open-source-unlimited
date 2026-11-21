package com.agentpad.workspace.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import com.agentpad.workspace.data.ChatBackgroundStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 动态背景清单。
 *
 * 全部用 Canvas + 无限动画绘制，不依赖任何特效库：
 *  - 每帧只画几个渐变大圆 / 线条，GPU 压力极低（低端机也稳 60fps）
 *  - 用 `withFrameNanos` 驱动的 infiniteRepeatable 做时间轴
 *  - 不读写磁盘、不网络请求，纯函数式渲染
 */
data class AnimatedBackground(
    val id: String,
    val name: String,
    val desc: String,
)

object AnimatedBackgrounds {
    val ALL = listOf(
        AnimatedBackground("aurora", "极光", "缓慢流动的彩色光晕"),
        AnimatedBackground("mesh", "星云", "多层渐变缓慢漂移"),
        AnimatedBackground("waves", "波纹", "同心圆呼吸扩散"),
        AnimatedBackground("grid", "网格", "透视网格向前推进"),
        AnimatedBackground("orbs", "浮球", "彩色光球环绕游动"),
    )

    fun byId(id: String): AnimatedBackground =
        ALL.firstOrNull { it.id == id } ?: ALL[0]
}

/**
 * 对话页背景层。
 *
 * 层级：类型 → 底图 → 压暗遮罩。
 * 无论哪种类型，最终都会叠一层 `dim` 黑色的半透明遮罩，
 * 保证深色文字在花哨背景上依然清晰可读。
 */
@Composable
fun ChatBackgroundLayer(
    config: ChatBackgroundStore.Config,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (config.type) {
            ChatBackgroundStore.TYPE_COLOR -> {
                Box(Modifier.fillMaxSize().background(Color(config.color)))
            }
            ChatBackgroundStore.TYPE_IMAGE -> {
                val bmp = remember(config.imagePath) { loadBitmap(config.imagePath) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(AColors.Paper))
                }
            }
            ChatBackgroundStore.TYPE_ANIMATED -> {
                // 底衬：动画本身多半是暗色，垫一层主题纸面避免透出黑
                Box(Modifier.fillMaxSize().background(AColors.Paper))
                AnimatedBackgroundView(config.animatedId, config.speed, Modifier.fillMaxSize())
            }
            ChatBackgroundStore.TYPE_VIDEO -> {
                Box(Modifier.fillMaxSize().background(AColors.Paper))
                VideoBackground(
                    path = config.videoPath,
                    volume = config.videoVolume,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                // default：不画任何东西，透出 chat_root 的主题色
            }
        }

        // 压暗遮罩（0..80%）
        if (config.dim > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (config.dim / 100f).coerceIn(0f, 0.85f)))
            )
        }
    }
}

/**
 * 视频背景。
 *
 * ⚠ 关键点：不能直接用 VideoView 默认布局。
 * VideoView 用「视频原始宽高比」决定测量尺寸（fitCenter 语义），
 * 当视频比例和屏幕比例不一致时（竖屏手机 vs 横屏视频，几乎必然不一致），
 * 上/下或左/右就会留出纯黑边 —— 这就是「顶部发黑」的根因。
 *
 * 解决：先用 MediaMetadataRetriever 读出视频真实宽高，算出 cover 缩放，
 * 再用 Matrix 直接做变换。cover = 铺满容器，超出部分裁掉。
 */
@Composable
private fun VideoBackground(path: String, volume: Int, modifier: Modifier) {
    if (path.isBlank()) return
    val file = remember(path) { java.io.File(path) }
    if (!file.isFile) return

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val videoViewRef = remember { mutableStateOf<android.widget.VideoView?>(null) }
    // 视频原始宽高，prepare 拿到后更新
    val videoSize = remember(path) { mutableStateOf(0 to 0) }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(volume / 100f, volume / 100f)
                    // 记录真实尺寸，供下方 cover 变换使用
                    videoSize.value = mp.videoWidth to mp.videoHeight
                    applyCoverTransform(this, mp.videoWidth, mp.videoHeight)
                    start()
                }
                setOnErrorListener { _, _, _ -> true }
            }.also { videoViewRef.value = it }
        },
        update = { vv ->
            // 容器尺寸变了（旋转/切分屏）也要重新算 cover
            val (vw, vh) = videoSize.value
            if (vw > 0 && vh > 0) applyCoverTransform(vv, vw, vh)
            if (vv.isPlaying.not() && lifecycleOwner.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED)
            ) {
                runCatching { vv.start() }
            }
        },
    )

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP ->
                    runCatching { videoViewRef.value?.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    runCatching { videoViewRef.value?.start() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { videoViewRef.value?.stopPlayback() }
            videoViewRef.value = null
        }
    }
}

/**
 * cover 变换：把视频按「填满容器」的比例缩放，多出的部分裁掉。
 *
 * 做法：用 Matrix 的缩放 + 平移，作用到 VideoView 的 Surface 上。
 * 关键是把 VideoView 自身也放大到能覆盖容器的尺寸（否则仍会有黑边，
 * 因为 VideoView 内部给视频预留的绘制区永远等于 View 尺寸）。
 */
private fun applyCoverTransform(
    vv: android.widget.VideoView,
    videoW: Int,
    videoH: Int,
) {
    if (videoW <= 0 || videoH <= 0) return
    val container = vv.parent as? android.view.View ?: return
    val cw = container.width
    val ch = container.height
    if (cw <= 0 || ch <= 0) {
        // 容器还没测量，等下一次 update 再来
        vv.post { applyCoverTransform(vv, videoW, videoH) }
        return
    }

    // 让 VideoView 自身的尺寸变成「按 cover 缩放后的视频尺寸」
    val videoRatio = videoW.toFloat() / videoH
    val containerRatio = cw.toFloat() / ch
    val targetW: Int
    val targetH: Int
    if (videoRatio > containerRatio) {
        // 视频更宽：以高度为准撑满，宽度溢出后裁掉
        targetH = ch
        targetW = (ch * videoRatio).toInt()
    } else {
        // 视频更高：以宽度为准撑满，高度溢出后裁掉
        targetW = cw
        targetH = (cw / videoRatio).toInt()
    }

    val lp = vv.layoutParams ?: return
    if (lp.width != targetW || lp.height != targetH) {
        lp.width = targetW
        lp.height = targetH
        vv.layoutParams = lp
        // 居中：溢出部分上下/左右各裁一半
        vv.translationX = (cw - targetW) / 2f
        vv.translationY = (ch - targetH) / 2f
    }
}

private fun loadBitmap(path: String): ImageBitmap? {
    return runCatching {
        val f = java.io.File(path)
        if (!f.isFile) return null
        // 解码时按 2 采样，避免 4K 图占几十 MB 内存
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }.getOrNull()
}

/** 五种动态背景的实际绘制。 */
@Composable
private fun AnimatedBackgroundView(id: String, speed: Float, modifier: Modifier) {
    val s = speed.coerceIn(0.2f, 3f)
    val transition = rememberInfiniteTransition(label = "chatBg")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (24000 / s).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "chatBgT",
    )

    when (id) {
        "mesh" -> MeshBackground(t, modifier)
        "waves" -> WavesBackground(t, modifier)
        "grid" -> GridBackground(t, modifier)
        "orbs" -> OrbsBackground(t, modifier)
        else -> AuroraBackground(t, modifier)
    }
}

/** 极光：几个超大渐变大圆缓慢绕行并交替明暗。 */
@Composable
private fun AuroraBackground(t: Float, modifier: Modifier) {
    val p = AColors.Accent
    val p2 = AColors.Violet
    val p3 = AColors.Teal
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.9f
        fun blob(cx: Float, cy: Float, color: Color, alpha: Float, radius: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        val a1 = (sin(t * 2 * PI) * 0.5 + 0.5).toFloat()
        val a2 = (cos(t * 2 * PI) * 0.5 + 0.5).toFloat()
        val a3 = (sin(t * 2 * PI + 1.5) * 0.5 + 0.5).toFloat()

        blob(w * (0.15f + 0.35f * a1), h * (0.18f + 0.12f * a2), p, 0.32f, r)
        blob(w * (0.85f - 0.30f * a2), h * (0.30f + 0.18f * a3), p2, 0.26f, r * 0.9f)
        blob(w * (0.45f + 0.25f * a3), h * (0.85f - 0.20f * a1), p3, 0.22f, r * 0.8f)
    }
}

/** 星云：横向渐变整体缓慢平移 + 轻微旋转。 */
@Composable
private fun MeshBackground(t: Float, modifier: Modifier) {
    val c1 = AColors.Accent
    val c2 = AColors.Pink
    val c3 = AColors.Amber
    Canvas(modifier) {
        val shift = (t * 2 * PI).toFloat()
        rotate(degrees = sin(shift).toFloat() * 6f) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(c1.copy(0.30f), c2.copy(0.22f), c3.copy(0.20f), c1.copy(0.30f)),
                    start = Offset(
                        size.width * (0.5f + 0.4f * cos(shift).toFloat()),
                        size.height * (0.2f + 0.3f * sin(shift).toFloat()),
                    ),
                    end = Offset(
                        size.width * (0.5f - 0.4f * cos(shift).toFloat()),
                        size.height * (0.8f - 0.3f * sin(shift).toFloat()),
                    ),
                ),
            )
        }
    }
}

/** 波纹：3 组同心圆由中心向外扩散、淡出，循环。 */
@Composable
private fun WavesBackground(t: Float, modifier: Modifier) {
    val c = AColors.Accent
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.42f
        val maxR = size.width * 0.85f
        repeat(3) { i ->
            val phase = (t + i / 3f) % 1f
            val r = maxR * phase
            val alpha = (1f - phase) * 0.22f
            drawCircle(
                color = c.copy(alpha = alpha),
                radius = r,
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
            )
        }
    }
}

/** 网格：透视网格向观察者推进（Y 方向加速）。 */
@Composable
private fun GridBackground(t: Float, modifier: Modifier) {
    val c = AColors.Accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.35f
        val lineColor = c.copy(alpha = 0.10f)
        val strokeW = 1.2f
        // 横线：从地平线向下，位置随 t 推进
        val rows = 12
        for (i in 0 until rows) {
            val phase = ((i.toFloat() / rows) + t) % 1f
            // 用平方让近处线距更大，产生透视感
            val y = horizon + (h - horizon) * (phase * phase)
            drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = strokeW)
        }
        // 竖线：从地平线一点向两侧发散
        val cols = 11
        for (i in 0..cols) {
            val fx = i.toFloat() / cols
            val bottomX = w * (fx * 1.6f - 0.3f)
            val topX = w * 0.5f + (bottomX - w * 0.5f) * 0.12f
            drawLine(lineColor, Offset(topX, horizon), Offset(bottomX, h), strokeWidth = strokeW)
        }
    }
}

/** 浮球：几个彩色小球沿椭圆轨道游动。 */
@Composable
private fun OrbsBackground(t: Float, modifier: Modifier) {
    val colors = listOf(AColors.Accent, AColors.Violet, AColors.Teal, AColors.Pink, AColors.Amber)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val angle = (t * 2 * PI).toFloat()
        colors.forEachIndexed { i, c ->
            val phase = (angle + i * (2 * PI / colors.size)).toFloat()
            val rx = w * (0.28f + 0.06f * (i % 4))
            val ry = h * (0.20f + 0.04f * (i % 3))
            val cx = w / 2f + rx * cos(phase).toFloat()
            val cy = h / 2f + ry * sin(phase * 0.8f).toFloat()
            val r = w * (0.13f + 0.02f * (i % 3))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.copy(alpha = 0.26f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}