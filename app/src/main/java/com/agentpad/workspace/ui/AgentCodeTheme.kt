// Ported from RikkaHub 3.1.1 (AGPL-3.0-only):
//   ui/theme/CodeColor.kt   —— Atom One Dark / Light 代码配色
//
// 本项目适配：字体不打包 JetBrains Mono（res/font 下没有 ttf），
// 统一用 FontFamily.Monospace，视觉与参考基本一致且不增加包体。

package com.agentpad.workspace.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import me.rerere.bettertext.highlight.HighlightTextColorPalette

/** 等宽字体：参考用 JetBrainsMono，这里用系统 Monospace（无字体资源可打包）。 */
val AgentMono: FontFamily = FontFamily.Monospace

// 照抄 CodeColor.kt:7-23
val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd),
    string = Color(0xFF98c379),
    number = Color(0xFFd19a66),
    comment = Color(0xFF5c6370),
    function = Color(0xFF61afef),
    operator = Color(0xFF56b6c2),
    punctuation = Color(0xFFabb2bf),
    className = Color(0xFFe5c07b),
    property = Color(0xFFe06c75),
    boolean = Color(0xFFd19a66),
    variable = Color(0xFFe06c75),
    tag = Color(0xFFe06c75),
    attrName = Color(0xFFd19a66),
    attrValue = Color(0xFF98c379),
    fallback = Color(0xFFabb2bf),
)

// 照抄 CodeColor.kt:26-42
val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFa626a4),
    string = Color(0xFF50a14f),
    number = Color(0xFFc18401),
    comment = Color(0xFF9ca0a4),
    function = Color(0xFF4078f2),
    operator = Color(0xFF0184bc),
    punctuation = Color(0xFF383a42),
    className = Color(0xFFc18401),
    property = Color(0xFFe45649),
    boolean = Color(0xFFc18401),
    variable = Color(0xFFe45649),
    tag = Color(0xFFe45649),
    attrName = Color(0xFFc18401),
    attrValue = Color(0xFF50a14f),
    fallback = Color(0xFF383a42),
)