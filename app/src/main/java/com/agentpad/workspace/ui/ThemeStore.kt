package com.agentpad.workspace.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 全局主题系统：主色调 × 深色模式。
 *
 * 设计：AColors / Chat* 全部改成「动态读取 palette（一个 mutableStateOf）」，
 * 因此所有界面无需改动，切换主题后全局自动重组刷新。
 */

/** 可选主色调。 */
enum class AccentTheme(val id: String, val label: String, val main: Long, val deep: Long) {
    Indigo("indigo", "靛蓝", 0xFF5267E8, 0xFF3D50D6),
    Emerald("emerald", "翡翠", 0xFF10A37F, 0xFF0B7D60),
    Violet("violet", "紫罗兰", 0xFF7C5CF0, 0xFF6244D8),
    Rose("rose", "玫瑰", 0xFFE8508C, 0xFFC63A72),
    Amber("amber", "琥珀", 0xFFE8853D, 0xFFC96A28),
    Sky("sky", "天空", 0xFF2F9BE8, 0xFF1E7BC4),
    Graphite("graphite", "石墨", 0xFF5A6473, 0xFF434B58),
}

/** 一套完整色板。 */
data class APalette(
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val accentGhost: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val faint: Color,
    val paper: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val lineSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val violet: Color,
    val teal: Color,
    val pink: Color,
    val amber: Color,
    val codeBg: Color,
    val codeFg: Color,
    val codeMuted: Color,
    val inlineCodeBg: Color,
    val inlineCodeFg: Color,
    val isDark: Boolean,
)

object ThemeStore {

    private const val PREF = "theme_prefs"
    private const val KEY_ACCENT = "accent"
    private const val KEY_DARK = "dark"
    private const val KEY_FOLLOW = "follow_system"
    private const val KEY_LEGACY_UI = "legacy_ui"
    val legacyUi = mutableStateOf(false)

    fun setLegacyUi(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LEGACY_UI, enabled).apply()
        legacyUi.value = enabled
    }

    /** 当前色板（Compose 状态；变化时所有读者自动重组）。 */
    val palette = mutableStateOf(build(AccentTheme.Indigo, false))

    fun accentId(): String = currentAccentId
    fun isDark(): Boolean = currentDark
    fun isFollowSystem(): Boolean = currentFollow

    /**
     * 是否支持「跟随系统深色」。
     * 系统级深色模式从 Android 10 (API 29) 才有；
     * minSdk=26 的 Android 8/9 没有这个能力，界面上不能显示该开关。
     */
    fun supportsFollowSystem(): Boolean = android.os.Build.VERSION.SDK_INT >= 29

    /** 当前系统是否处于深色（Android 10+ 才有意义，低版本恒为 false）。 */
    fun systemIsDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private var currentAccentId = AccentTheme.Indigo.id
    private var currentDark = false
    private var currentFollow = false

    /** 进程启动时从持久化恢复。 */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        legacyUi.value = p.getBoolean(KEY_LEGACY_UI, false)
        val id = p.getString(KEY_ACCENT, AccentTheme.Indigo.id) ?: AccentTheme.Indigo.id
        currentAccentId = id
        currentFollow = p.getBoolean(KEY_FOLLOW, false)
        // 跟随系统且系统支持时，以系统 uiMode 为准；否则用手动存的值。
        currentDark = if (currentFollow && supportsFollowSystem()) {
            systemIsDark(context)
        } else {
            p.getBoolean(KEY_DARK, false)
        }
        palette.value = build(byId(id), currentDark)
    }

    /** 切换「跟随系统深色」。低版本直接忽略（开关不会显示）。 */
    fun setFollowSystem(context: Context, follow: Boolean) {
        currentFollow = follow
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOLLOW, follow).apply()
        if (follow) applySystemDark(context)
    }

    /**
     * 系统白天/黑夜变化时调用（Application.onConfigurationChanged）。
     * 只在跟随开关打开时生效，不改变用户手动选择的主色调。
     */
    fun applySystemDark(context: Context) {
        if (!currentFollow || !supportsFollowSystem()) return
        val night = systemIsDark(context)
        if (night == currentDark) return   // 没变就跳过，避免无谓重组
        currentDark = night
        palette.value = build(byId(currentAccentId), night)
    }

    fun selectAccent(context: Context, theme: AccentTheme) {
        currentAccentId = theme.id
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCENT, theme.id).apply()
        palette.value = build(theme, currentDark)
    }

    fun setDark(context: Context, dark: Boolean) {
        currentDark = dark
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
        palette.value = build(byId(currentAccentId), dark)
    }

    private fun byId(id: String): AccentTheme =
        AccentTheme.entries.firstOrNull { it.id == id } ?: AccentTheme.Indigo

    private fun build(theme: AccentTheme, dark: Boolean): APalette {
        val main = Color(theme.main)
        val deep = Color(theme.deep)
        return if (!dark) {
            APalette(
                accent = main,
                accentDeep = deep,
                accentSoft = lerp(Color.White, main, 0.10f),
                accentGhost = lerp(Color.White, main, 0.05f),
                ink = Color(0xFF10131A),
                inkSoft = Color(0xFF3A4050),
                muted = Color(0xFF7D8492),
                faint = Color(0xFFA8AEBB),
                paper = Color(0xFFF6F7FA),
                surface = Color(0xFFFFFFFF),
                surfaceAlt = Color(0xFFFAFBFC),
                line = Color(0xFFE7E9EE),
                lineSoft = Color(0xFFF0F2F6),
                success = Color(0xFF19A974),
                successSoft = Color(0xFFE8F7F0),
                warning = Color(0xFFE8A33D),
                warningSoft = Color(0xFFFDF4E5),
                danger = Color(0xFFE5484D),
                dangerSoft = Color(0xFFFDECEC),
                violet = Color(0xFF7C5CF0),
                teal = Color(0xFF12A5A0),
                pink = Color(0xFFE8508C),
                amber = Color(0xFFEB9A2E),
                codeBg = Color(0xFF20232B),
                codeFg = Color(0xFFE6EAF2),
                codeMuted = Color(0xFF9BA3B4),
                inlineCodeBg = Color(0xFFEDEFF5),
                inlineCodeFg = Color(0xFFB03A6B),
                isDark = false,
            )
        } else {
            APalette(
                accent = main,
                accentDeep = lerp(main, Color.White, 0.35f),
                accentSoft = lerp(Color(0xFF171A21), main, 0.24f),
                accentGhost = lerp(Color(0xFF171A21), main, 0.12f),
                ink = Color(0xFFE8EBF2),
                inkSoft = Color(0xFFC2C8D6),
                muted = Color(0xFF8B93A6),
                faint = Color(0xFF666E80),
                paper = Color(0xFF0F1116),
                surface = Color(0xFF171A21),
                surfaceAlt = Color(0xFF1D2129),
                line = Color(0xFF2A2F3A),
                lineSoft = Color(0xFF22262F),
                success = Color(0xFF2FBF8F),
                successSoft = Color(0xFF16342A),
                warning = Color(0xFFE8A33D),
                warningSoft = Color(0xFF3A2E18),
                danger = Color(0xFFE5615F),
                dangerSoft = Color(0xFF3A1E20),
                violet = Color(0xFF9B7CF6),
                teal = Color(0xFF2BB8B1),
                pink = Color(0xFFEE6FA5),
                amber = Color(0xFFF0A64A),
                codeBg = Color(0xFF0B0D12),
                codeFg = Color(0xFFE6EAF2),
                codeMuted = Color(0xFF8B93A6),
                inlineCodeBg = Color(0xFF262A34),
                inlineCodeFg = Color(0xFFF08BB4),
                isDark = true,
            )
        }
    }
}
