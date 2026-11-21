package com.agentpad.workspace.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全局系统栏（状态栏 / 导航栏）主题化。
 *
 * 背景：本项目只有 ChatActivity 一手动了 window.statusBarColor，
 * 其余 Compose 界面全靠 themes.xml 里写死的浅色 @color/paper，
 * 深色模式下状态栏就露出白条。
 *
 * 而且 targetSdk=35 在 Android 15 上会强制边到边（edge-to-edge），
 * 此时 statusBarColor / navigationBarColor 会被系统忽略，
 * 真正露出来的是 **window 背景色**。所以必须四件套一起刷：
 *   1) statusBarColor          （Android ≤14 生效）
 *   2) navigationBarColor      （Android ≤14 生效）
 *   3) decorView 背景色         （Android 15 边到边兜底）
 *   4) 图标明暗 flag           （两代都生效）
 */

/** 从任意 Context 向上找到宿主的 Activity（ComposeView 的 context 常被 wrapper 包一层）。 */
fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 把当前主题应用到系统栏。可在任意线程/时机调用（Activity onResume、Compose SideEffect 都行）。
 *
 * @param transparentStatus true = 状态栏区域透明，让「对话背景」透上去。
 *        有自定义背景（纯色/图片/动态/视频）时必须为 true，
 *        否则状态栏会被刷成主题纸面色，和背景层之间出现一条突兀的白/黑色带。
 */
fun applySystemBars(activity: Activity?) {
    val window = activity?.window ?: return
    val p = ThemeStore.palette.value
    val bg = p.paper.toArgb()

    @Suppress("DEPRECATION")
    run {
        // 1) 传统系统栏底色（Android ≤14；Android 15 会被忽略，无害）
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    // 2) Android 15 强制边到边时，露出来的是 window 背景 → 一并刷成主题色
    window.decorView.setBackgroundColor(bg)

    // 3) 图标明暗：深底配浅图标，浅底配深图标
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = !p.isDark
        isAppearanceLightNavigationBars = !p.isDark
    }
}

/**
 * Compose 侧入口：订阅主题状态，切换深色/主色调时立即刷新系统栏。
 * AgentTheme 里已自动调用，界面无需各自处理。
 *
 * ⚠ 但「需要自定义背景（图片/视频铺到状态栏）」的页面会自己接管系统栏，
 *   此时这个副作用必须跳过，否则会在每次重组时把状态栏刷回纸面色，
 *   把页面刚设好的透明状态栏覆盖掉 —— 这正是「背景一直不透」的原因之一。
 */
@Composable
fun SystemBarsEffect() {
    val view = LocalView.current
    // 读取 palette 使其成为重组依赖：主题一变就重跑 SideEffect
    ThemeStore.palette.value
    SideEffect { applySystemBars(view.context.findActivity()) }
}
