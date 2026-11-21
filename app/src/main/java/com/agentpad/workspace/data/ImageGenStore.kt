package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 生图（文生图）配置存储。
 *
 * 设计：与 ChatStore.apiProfiles 同一套 Prefs 思路，独立一份键，互不干扰。
 * 字段：
 *   enabled      总开关（关了就完全不暴露生图工具）
 *   baseUrl      OpenAI 兼容的 Base URL（如 https://api.openai.com/v1）
 *   apiKey       Key
 *   model        选定的生图模型
 *   exposeTool   是否把生图暴露给 AI 当工具（默认 true）
 *   size         默认尺寸：1024x1024 / 1024x1792 / 1792x1024
 *   saveDir      图片落盘目录（默认 filesDir/images）
 */
object ImageGenStore {

    private const val PREFS = "agent_imagegen"
    private const val K_ENABLED = "enabled"
    private const val K_URL = "base_url"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_EXPOSE = "expose_tool"
    private const val K_SIZE = "size"

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Config(
        val enabled: Boolean,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val exposeTool: Boolean,
        val size: String,
    ) {
        /** 三件套齐了才算「能生图」。 */
        val usable: Boolean get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    fun config(c: Context): Config {
        val p = prefs(c)
        return Config(
            enabled = p.getBoolean(K_ENABLED, false),
            baseUrl = p.getString(K_URL, "").orEmpty(),
            apiKey = p.getString(K_KEY, "").orEmpty(),
            model = p.getString(K_MODEL, "").orEmpty(),
            exposeTool = p.getBoolean(K_EXPOSE, true),
            size = p.getString(K_SIZE, "1024x1024").orEmpty().ifBlank { "1024x1024" },
        )
    }

    fun save(c: Context, cfg: Config) {
        prefs(c).edit()
            .putBoolean(K_ENABLED, cfg.enabled)
            .putString(K_URL, cfg.baseUrl.trim().trimEnd('/'))
            .putString(K_KEY, cfg.apiKey.trim())
            .putString(K_MODEL, cfg.model.trim())
            .putBoolean(K_EXPOSE, cfg.exposeTool)
            .putString(K_SIZE, cfg.size.trim().ifBlank { "1024x1024" })
            .apply()
    }

    /**
     * 生图落盘：**默认保存到 App 私有目录**（filesDir/images）。
     *
     * 语义（主人明确要求）：
     *   · 默认 = 私有。AI 生成完就落这里，聊天里显示、App 内相册浏览都用它。
     *   · 用户点「保存到相册」时才**复制一份**到公共 /storage/emulated/0/Pictures。
     * 这样既不会把手机相册塞满，也避免每次生图都往公共目录写。
     */
    fun dir(c: Context): File = File(c.filesDir, "images").apply { mkdirs() }

    fun fileFor(c: Context, name: String): File = File(dir(c), name)

    /** 公共相册目录：/storage/emulated/0/Pictures（只在用户点保存时用）。 */
    fun publicDir(): File = File("/storage/emulated/0/Pictures")

    /** 确保目录存在并可写（不存在就创建；写探针验证，别只信 canWrite）。 */
    fun ensureWritable(d: File): Boolean = runCatching {
        if (!d.isDirectory) d.mkdirs()
        if (!d.isDirectory) return@runCatching false
        val probe = File(d, ".ws_probe_" + System.nanoTime())
        probe.writeText("1")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * 把一张私有目录里的图「保存」到公共 Pictures（用户主动点击才调用）。
     * 返回 true = 已落到 Pictures；false = 无权限/失败。
     */
    fun exportToPublicPictures(src: File): Boolean = runCatching {
        if (!src.isFile) return@runCatching false
        val pub = publicDir()
        // 目录不存在就自动创建（父目录也建）
        if (!ensureWritable(pub)) return@runCatching false
        val dst = File(pub, src.name)
        src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
        dst.length() > 0
    }.getOrDefault(false)


    /** 从 OpenAI 兼容端点拉模型列表（走 /models）。 */
    fun fetchModels(baseUrl: String, key: String, timeoutMs: Int = 20000): Result<List<String>> = runCatching {
        val url = baseUrl.trim().trimEnd('/') + "/models"
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: " + text.take(200))
            val root = JSONObject(text)
            val arr = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }.sorted()
        } finally {
            conn.disconnect()
        }
    }
}
