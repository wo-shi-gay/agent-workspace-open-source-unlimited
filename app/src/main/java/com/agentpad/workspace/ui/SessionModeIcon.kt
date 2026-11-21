package com.agentpad.workspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG

/** Project type is conveyed by an icon, independent of the conversation title. */
@Composable
fun SessionModeIcon(mode: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val context = LocalContext.current
    val description = when (mode) {
        "kt" -> "Kotlin 项目"; "apk" -> "APK 项目"; "web" -> "网页项目"
        "video" -> "视频项目"; "reverse" -> "逆向模式"; else -> "普通对话"
    }
    val svg = remember(mode) {
        runCatching {
            if (mode == "apk") context.assets.open("session-icons/apk.svg").use { SVG.getFromInputStream(it) }
            else modeSvg(mode)?.let { SVG.getFromString(it) }
        }.getOrNull()
    }
    if (svg == null) {
        Icon(Icons.Outlined.ChatBubbleOutline, description, tint = AColors.Accent, modifier = modifier.size(size))
        return
    }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val width = svg.documentWidth
        val height = svg.documentHeight
        if (width <= 0f || height <= 0f) return@Canvas
        val scale = minOf(this.size.width / width, this.size.height / height)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            native.translate((this.size.width - width * scale) / 2f, (this.size.height - height * scale) / 2f)
            native.scale(scale, scale)
            try { svg.renderToCanvas(native) } finally { native.restoreToCount(save) }
        }
    }
}

private fun modeSvg(mode: String): String? = when (mode) {
    "kt" -> """<svg xmlns="http://www.w3.org/2000/svg" width="900" height="1040" viewBox="90 10 920 1040" fill="#A4C900">
<title>安卓 LOGO</title>
<g fill="none" stroke="#A4C900" stroke-width="28" stroke-linecap="round"><path d="M430 132 372 34"/><path d="M674 132 734 34"/></g>
<path d="M260 350 C278 205 400 92 550 92 C700 92 822 205 840 350 Z"/>
<g fill="#FFFFFF"><circle cx="418" cy="226" r="27"/><circle cx="680" cy="226" r="27"/></g>
<rect x="109" y="365" width="125" height="390" rx="62.5"/><rect x="864" y="365" width="125" height="390" rx="62.5"/>
<path d="M260 380 H840 V766 Q840 856 750 856 H350 Q260 856 260 766 Z"/>
<rect x="382" y="790" width="125" height="260" rx="62.5"/><rect x="591" y="790" width="125" height="260" rx="62.5"/></svg>"""
    "web" -> """<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128" fill="none"><title>蓝色线框地球</title>
<defs><linearGradient id="globe-blue" x1="24" y1="18" x2="104" y2="110" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#38BDF8"/><stop offset="0.5" stop-color="#2585F5"/><stop offset="1" stop-color="#2454D8"/></linearGradient></defs>
<g stroke="url(#globe-blue)" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="64" cy="64" r="49"/><ellipse cx="64" cy="64" rx="24" ry="49"/><path d="M64 15V113"/><path d="M15 64H113"/><path d="M24.8 34.6 C45 45.5 83 45.5 103.2 34.6"/><path d="M24.8 93.4 C45 82.5 83 82.5 103.2 93.4"/></g></svg>"""
    "video" -> """<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128" fill="none"><title>蓝色线框摄像机</title>
<defs><linearGradient id="video-blue" x1="14" y1="36" x2="114" y2="92" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#67DFFF"/><stop offset="0.4" stop-color="#29A8FF"/><stop offset="0.75" stop-color="#2478ED"/><stop offset="1" stop-color="#1746B8"/></linearGradient></defs>
<g stroke="url(#video-blue)" stroke-width="3.8" stroke-linecap="round" stroke-linejoin="round"><path d="M86 54 L109 42 Q114 39 114 45 V83 Q114 89 109 86 L86 74"/><rect x="14" y="36" width="72" height="56" rx="10"/><path d="M44 53 L62 64 L44 75 Z" stroke-width="3.2"/></g></svg>"""
    "reverse" -> """<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128" fill="none"><title>逆向项目</title>
<defs><linearGradient id="reverse-blue" x1="29" y1="18" x2="100" y2="112" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#53D3FF"/><stop offset="0.48" stop-color="#2995F5"/><stop offset="1" stop-color="#2454D8"/></linearGradient></defs>
<g stroke="url(#reverse-blue)" stroke-width="3.8" stroke-linecap="round" stroke-linejoin="round"><path d="M77 16 H35 A11 11 0 0 0 24 27 V101 A11 11 0 0 0 35 112 H93 A11 11 0 0 0 104 101 V43 Z"/><path d="M77 16 V34 A9 9 0 0 0 86 43 H104"/><path d="M51 53L40 64L51 75 M77 53L88 64L77 75"/><path d="M68 51L60 77" stroke-width="3.4"/></g></svg>"""
    else -> null
}
