package com.agentpad.workspace

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ChatBackgroundStore
import com.agentpad.workspace.ui.*

/**
 * 对话背景设置页。
 *
 * ⚠️ 使用 SubPage 时内容里**禁止**再套 verticalScroll / LazyColumn，
 *    否则会触发「滚动容器套滚动容器 → 无限高度」崩溃
 *    （v4.2.1 PromptActivity 就是这么崩的）。这里全部用普通 Column。
 */
class ChatBackgroundActivity : ComponentActivity() {

    private var config by mutableStateOf(ChatBackgroundStore.Config())

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = ChatBackgroundStore.importImage(this, uri)
        if (path == null) {
            Toast.makeText(this, "图片导入失败", Toast.LENGTH_SHORT).show()
        } else {
            config = config.copy(type = ChatBackgroundStore.TYPE_IMAGE, imagePath = path)
            persist()
        }
    }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = ChatBackgroundStore.importVideo(this, uri)
        if (path == null) {
            Toast.makeText(this, "视频导入失败", Toast.LENGTH_SHORT).show()
        } else {
            config = config.copy(type = ChatBackgroundStore.TYPE_VIDEO, videoPath = path)
            persist()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        config = ChatBackgroundStore.load(this)
        setContent {
            AgentTheme {
                Content()
            }
        }
    }

    private fun persist() {
        ChatBackgroundStore.save(this, config)
    }

    @Composable
    private fun Content() {
        SubPage(
            title = "对话背景",
            subtitle = "自定义聊天界面的背景",
            onBack = { finish() },
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------- 实时预览 ----------
            SectionLabel("预览")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(ADimens.CardRadius))
                    .background(AColors.Paper)
                    .border(1.dp, AColors.Line, RoundedCornerShape(ADimens.CardRadius)),
            ) {
                ChatBackgroundLayer(config = config, modifier = Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("对话标题", fontSize = 13.sp, color = AColors.Ink, fontWeight = FontWeight.Medium)
                    }
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.72f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AColors.Accent.copy(alpha = 0.85f))
                                .padding(10.dp),
                        ) {
                            Text("这是一条用户消息", fontSize = 11.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth(0.86f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AColors.Surface.copy(alpha = 0.92f))
                                .padding(10.dp),
                        ) {
                            Text("这是 AI 的回复，用来检查背景上的可读性。", fontSize = 11.sp, color = AColors.Ink)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 类型 ----------
            SectionLabel("背景类型")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip("默认", config.type == ChatBackgroundStore.TYPE_DEFAULT) {
                    config = config.copy(type = ChatBackgroundStore.TYPE_DEFAULT); persist()
                }
                TypeChip("纯色", config.type == ChatBackgroundStore.TYPE_COLOR) {
                    config = config.copy(type = ChatBackgroundStore.TYPE_COLOR); persist()
                }
                TypeChip("图片", config.type == ChatBackgroundStore.TYPE_IMAGE) {
                    config = config.copy(type = ChatBackgroundStore.TYPE_IMAGE); persist()
                }
                TypeChip("动态", config.type == ChatBackgroundStore.TYPE_ANIMATED) {
                    config = config.copy(type = ChatBackgroundStore.TYPE_ANIMATED); persist()
                }
                TypeChip("视频", config.type == ChatBackgroundStore.TYPE_VIDEO) {
                    config = config.copy(type = ChatBackgroundStore.TYPE_VIDEO); persist()
                }
            }

            // ---------- 纯色 ----------
            if (config.type == ChatBackgroundStore.TYPE_COLOR) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("选择颜色")
                val swatches = listOf(
                    0xFFF7F8FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F4F9.toInt(),
                    0xFFEEF0FF.toInt(), 0xFFEAF7F1.toInt(), 0xFFFFF6E8.toInt(),
                    0xFFFDEEF4.toInt(), 0xFFEAF2FB.toInt(), 0xFF1A1D24.toInt(),
                    0xFF12161E.toInt(), 0xFF0B0E13.toInt(), 0xFF2A2038.toInt(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    swatches.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                val selected = config.color == c
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(c))
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) AColors.Accent else AColors.Line,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .clickable {
                                            config = config.copy(color = c); persist()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = if (isDark(c)) Color.White else AColors.Ink,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 图片 ----------
            if (config.type == ChatBackgroundStore.TYPE_IMAGE) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("图片")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                        .background(AColors.SurfaceAlt)
                        .clickable { imagePicker.launch(arrayOf("image/*")) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (config.imagePath.isBlank()) "从相册选择图片" else "重新选择图片",
                        fontSize = 14.sp,
                        color = AColors.Ink,
                    )
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("压暗（提高文字可读性）")
                SliderRow(
                    value = config.dim.toFloat(),
                    range = 0f..80f,
                    steps = 15,
                    label = "${config.dim}%",
                ) { config = config.copy(dim = it.toInt()); persist() }
            }

            // ---------- 动态 ----------
            if (config.type == ChatBackgroundStore.TYPE_ANIMATED) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("动画效果")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedBackgrounds.ALL.forEach { bg ->
                        val selected = config.animatedId == bg.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                                .background(if (selected) AColors.AccentSoft else AColors.SurfaceAlt)
                                .clickable {
                                    config = config.copy(animatedId = bg.id); persist()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(AColors.Accent, AColors.Violet, AColors.Teal)
                                        )
                                    ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bg.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                                Text(bg.desc, fontSize = 11.5.sp, color = AColors.Muted)
                            }
                            if (selected) {
                                Icon(Icons.Outlined.Check, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("速度")
                SliderRow(
                    value = config.speed,
                    range = 0.3f..2.5f,
                    steps = 21,
                    label = "${"%.1f".format(config.speed)}×",
                ) { config = config.copy(speed = it); persist() }

                Spacer(Modifier.height(16.dp))
                SectionLabel("压暗（提高文字可读性）")
                SliderRow(
                    value = config.dim.toFloat(),
                    range = 0f..80f,
                    steps = 15,
                    label = "${config.dim}%",
                ) { config = config.copy(dim = it.toInt()); persist() }
            }

            // ---------- 视频 ----------
            if (config.type == ChatBackgroundStore.TYPE_VIDEO) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("视频")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                        .background(AColors.SurfaceAlt)
                        .clickable { videoPicker.launch(arrayOf("video/*")) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (config.videoPath.isBlank()) "从相册选择视频" else "重新选择视频",
                            fontSize = 14.sp,
                            color = AColors.Ink,
                        )
                        Text(
                            "建议 10 秒内、1080p 以下，循环播放会持续耗电",
                            fontSize = 11.sp,
                            color = AColors.Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("音量")
                SliderRow(
                    value = config.videoVolume.toFloat(),
                    range = 0f..100f,
                    steps = 19,
                    label = "${config.videoVolume}%",
                ) { config = config.copy(videoVolume = it.toInt()); persist() }

                Spacer(Modifier.height(16.dp))
                SectionLabel("压暗（提高文字可读性）")
                SliderRow(
                    value = config.dim.toFloat(),
                    range = 0f..80f,
                    steps = 15,
                    label = "${config.dim}%",
                ) { config = config.copy(dim = it.toInt()); persist() }
            }

            // ---------- 生效范围 ----------
            Spacer(Modifier.height(20.dp))
            SectionLabel("生效范围")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(AColors.SurfaceAlt)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("应用到所有对话", fontSize = 14.sp, color = AColors.Ink)
                    Text("关闭则只影响当前设置", fontSize = 11.5.sp, color = AColors.Muted)
                }
                Switch(
                    checked = config.global,
                    onCheckedChange = { config = config.copy(global = it); persist() },
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "提示：动态背景由 App 本地实时绘制，不上传、不联网。若电量吃紧或觉得卡，可切回「默认」或「纯色」。",
                fontSize = 11.5.sp,
                color = AColors.Muted,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    @Composable
    private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .clip(RoundedCornerShape(ADimens.ChipRadius))
                .background(if (selected) AColors.Accent else AColors.SurfaceAlt)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else AColors.InkSoft,
            )
        }
    }

    @Composable
    private fun SliderRow(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        label: String,
        onChange: (Float) -> Unit,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.width(48.dp))
        }
    }

    /** 粗略判断颜色明暗，决定勾选图标用黑还是白。 */
    private fun isDark(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < 140
    }
}