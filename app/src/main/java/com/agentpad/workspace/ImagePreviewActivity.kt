package com.agentpad.workspace

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 全屏图片预览（App 内部，不跳外部图库）。
 *
 * 为什么要它：以前点缩略图走 FileProvider + ACTION_VIEW，等于把图扔给系统相册，
 * 有些 ROM 根本没有能接 PNG 的应用，用户看到的就是“点了没反应”。
 * 这里自己画：双指缩放 / 双指拖动 / 双击复原，底部可保存到公共相册。
 *
 * 入口：Intent extra "path" = 本地图片绝对路径。
 */
class ImagePreviewActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val path = intent.getStringExtra("path").orEmpty()
        setContent {
            com.agentpad.workspace.ui.AgentTheme {
                Content(path)
            }
        }
    }

    @Composable
    private fun Content(path: String) {
        val file = remember(path) { File(path) }
        val bmp = remember(path) {
            runCatching {
                // 预览要看细节，但也要防 OOM：长边超过 2048 就抽采样
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > 2048) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }

        // 缩放/位移状态。双击在 1x 与 2.5x 之间切换。
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            offset = if (scale <= 1.01f) Offset.Zero else offset + panChange
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0D)),
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f; offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .transformable(state),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("图片无法解码：${file.name}", color = Color(0xFFB9B9C0), fontSize = 13.sp)
                }
            }

            // 顶部：关闭 + 文件名
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "✕",
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .pointerInput(Unit) { detectTapGestures { finish() } },
                )
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                    Text(
                        "${file.length() / 1024} KB · 双指缩放，双击还原",
                        color = Color(0xFF9A9AA2), fontSize = 11.sp,
                    )
                }
            }

            // 底部：保存到公共相册
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                com.agentpad.workspace.ui.PrimaryButton("保存到相册", Modifier.fillMaxWidth()) {
                    // 只有这里（用户点保存）才会碰公共 Pictures；目录不存在会自动创建
                    val ok = com.agentpad.workspace.data.ImageGenStore.exportToPublicPictures(file)
                    Toast.makeText(
                        this@ImagePreviewActivity,
                        if (ok) "已保存到 Pictures/" else "保存失败（请允许「所有文件访问」）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}