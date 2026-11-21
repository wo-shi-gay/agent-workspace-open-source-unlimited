package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONObject

/**
 * 对话界面背景设置。
 *
 * 支持三种类型：
 *  1. **纯色** —— 单色填充（跟主题 paper 分开，可以只要背景变色）
 *  2. **静态图片** —— 用户从相册选的图片，铺满（Cover），可调模糊/压暗
 *  3. **动态背景** —— 内置几种免费的渐变/粒子动画，纯 Compose 绘制，
 *     不依赖任何第三方库，也不吃 GPU 特效（低端机不卡）
 *
 * 存储：全局一份配置（所有会话共用）。
 * 图片会复制到 App 私有目录，避免相册 uri 失效。
 */
object ChatBackgroundStore {

    private const val PREF = "chat_background_pref"
    private const val KEY_JSON = "config"
    const val TYPE_DEFAULT = "default"
    const val TYPE_COLOR = "color"
    const val TYPE_IMAGE = "image"
    const val TYPE_ANIMATED = "animated"
    /** 用户自备视频循环播放。 */
    const val TYPE_VIDEO = "video"

    data class Config(
        val type: String = TYPE_DEFAULT,
        /** 纯色模式的颜色（ARGB）。 */
        val color: Int = 0xFFF7F8FA.toInt(),
        /** 图片模式的本地文件绝对路径。 */
        val imagePath: String = "",
        /** 视频模式的本地文件绝对路径。 */
        val videoPath: String = "",
        /** 视频音量 0..100（背景一般应静音 = 0）。 */
        val videoVolume: Int = 0,
        /** 图片模糊半径（dp，0 = 不模糊）。 */
        val blur: Int = 0,
        /** 图片/动态背景的压暗程度 0..80（百分）。数值越大越暗，深色文字更清楚。 */
        val dim: Int = 0,
        /** 动态背景 id（见 AnimatedBackgrounds.ALL）。 */
        val animatedId: String = "aurora",
        /** 动态背景的播放速度倍率（0.5 = 慢，2 = 快）。 */
        val speed: Float = 1.0f,
        /** 是否对所有会话生效（否则只影响当前会话）。 */
        val global: Boolean = true,
    )

    fun load(context: Context): Config {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return Config()
        return runCatching {
            val o = JSONObject(raw)
            Config(
                type = o.optString("type", TYPE_DEFAULT),
                color = o.optInt("color", 0xFFF7F8FA.toInt()),
                imagePath = o.optString("imagePath", ""),
                videoPath = o.optString("videoPath", ""),
                videoVolume = o.optInt("videoVolume", 0),
                blur = o.optInt("blur", 0),
                dim = o.optInt("dim", 0),
                animatedId = o.optString("animatedId", "aurora"),
                speed = o.optDouble("speed", 1.0).toFloat(),
                global = o.optBoolean("global", true),
            )
        }.getOrDefault(Config())
    }

    fun save(context: Context, config: Config) {
        val o = JSONObject()
            .put("type", config.type)
            .put("color", config.color)
            .put("imagePath", config.imagePath)
            .put("videoPath", config.videoPath)
            .put("videoVolume", config.videoVolume)
            .put("blur", config.blur)
            .put("dim", config.dim)
            .put("animatedId", config.animatedId)
            .put("speed", config.speed.toDouble())
            .put("global", config.global)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, o.toString()).apply()
    }

    /** 把用户选的图片复制到私有目录，返回新路径；失败返回 null。 */
    fun importImage(context: Context, uri: android.net.Uri): String? = runCatching {
        val dir = java.io.File(context.filesDir, "chat_bg").apply { mkdirs() }
        val dest = java.io.File(dir, "bg_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { out -> ins.copyTo(out) }
        } ?: return null
        // 删掉旧图，避免占用空间
        dir.listFiles()?.forEach { if (it != dest && it.name.startsWith("bg_")) it.delete() }
        dest.absolutePath
    }.getOrNull()

    fun clearImage(context: Context) {
        runCatching {
            java.io.File(context.filesDir, "chat_bg").listFiles()?.forEach { it.delete() }
        }
    }

    /** 把用户选的视频复制到私有目录，返回新路径；失败返回 null。 */
    fun importVideo(context: Context, uri: android.net.Uri): String? = runCatching {
        val dir = java.io.File(context.filesDir, "chat_bg").apply { mkdirs() }
        // 保留原扩展名，VideoView 需要靠后缀判格式
        val ext = runCatching {
            context.contentResolver.getType(uri)?.let { mime ->
                when {
                    mime.contains("mp4") -> "mp4"
                    mime.contains("webm") -> "webm"
                    mime.contains("matroska") -> "mkv"
                    mime.contains("quicktime") -> "mov"
                    mime.contains("3gpp") -> "3gp"
                    else -> "mp4"
                }
            }
        }.getOrNull() ?: "mp4"
        val dest = java.io.File(dir, "bgvideo_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { out -> ins.copyTo(out) }
        } ?: return null
        dir.listFiles()?.forEach { if (it != dest && it.name.startsWith("bgvideo_")) it.delete() }
        dest.absolutePath
    }.getOrNull()

    fun clearVideo(context: Context) {
        runCatching {
            java.io.File(context.filesDir, "chat_bg")
                .listFiles()
                ?.filter { it.name.startsWith("bgvideo_") }
                ?.forEach { it.delete() }
        }
    }
}