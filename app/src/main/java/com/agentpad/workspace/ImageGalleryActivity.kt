package com.agentpad.workspace

import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.ImageGenService
import com.agentpad.workspace.data.ImageGenStore
import com.agentpad.workspace.ui.*
import java.io.File

/**
 * 相册（设置 → 相册）。
 *
 * 为什么要有：AI 可能不回复、图片链接会过期、用户也可能只是事后想看。
 * 所有生图都落盘到 filesDir/images，这里只做浏览 / 放大 / 另存。
 */
class ImageGalleryActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        var images by remember { mutableStateOf(ImageGenService.listImages(this)) }
        var preview by remember { mutableStateOf<File?>(null) }

        // ⚠ scrollable = false 必须保留。
        // SubPage 默认会把 content 塞进 Column(verticalScroll)，而本页有 LazyVerticalGrid。
        // 两者嵌套时 LazyGrid 会拿到「无限最大高度」，Compose 直接抛
        // IllegalStateException: Vertically scrollable component was measured with
        // an infinity maximum height constraints（真机 OPPO PCAM10 / API 29 已崩过）。
        SubPage(
            title = "相册",
            subtitle = if (images.isEmpty()) "还没有图片" else "共 ${'$'}{images.size} 张 · 点击放大",
            onBack = { finish() },
            scrollable = false,
        ) {
            if (images.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text("这里会显示 AI 生成/看过的图片", fontSize = 13.sp, color = AColors.Muted)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    // weight(1f)：拿到的是「父容器剩余的有界高度」，不是无限约束。
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(images) { f ->
                        Thumb(f) {
                            startActivity(Intent(this@ImageGalleryActivity, ImagePreviewActivity::class.java)
                                .putExtra("path", f.absolutePath))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 固定高度行：weight(1f) 之后不能再无界撑开，否则又会触发同一类约束错误
                Box(Modifier.fillMaxWidth()) {
                    SecondaryButton("刷新列表", Modifier.fillMaxWidth()) {
                        images = ImageGenService.listImages(this@ImageGalleryActivity)
                    }
                }
            }
        }

        preview?.let { f ->
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text(f.name, fontSize = 14.sp) },
                text = {
                    Column {
                        val bmp = remember(f) {
                            runCatching {
                                BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = f.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                            )
                        } else {
                            Text("无法预览该图片", fontSize = 12.sp, color = AColors.Muted)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${'$'}{(f.length() / 1024)} KB · ${'$'}{f.name}",
                            fontSize = 11.sp, color = AColors.Muted,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val ok = saveToPublicPictures(f)
                        Toast.makeText(this@ImageGalleryActivity,
                            if (ok) "已保存到 Pictures/" else "保存失败（请允许「所有文件访问」）",
                            Toast.LENGTH_SHORT).show()
                    }) { Text("保存到相册") }
                },
                dismissButton = {
                    TextButton(onClick = { preview = null }) { Text("关闭") }
                },
            )
        }
    }

    @Composable
    private fun Thumb(f: File, onClick: () -> Unit) {
        val bmp = remember(f) {
            runCatching {
                // 缩略图只解小图，避免 3 列网格把大图整张读进内存
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = f.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("X", fontSize = 12.sp, color = AColors.Muted)
            }
        }
    }

    /** 用户点保存时才把私有图复制到公共 Pictures（目录不存在自动创建）。 */
    private fun saveToPublicPictures(f: File): Boolean =
        ImageGenStore.exportToPublicPictures(f)
}
