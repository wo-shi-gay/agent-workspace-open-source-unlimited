package com.agentpad.workspace.ai

import android.content.Context
import android.util.Base64
import com.agentpad.workspace.data.ImageGenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 生图服务（OpenAI 兼容 /images/generations）。
 *
 * 关键点：
 *  1. 不管是 b64_json 还是 url 返回，**必须落盘**到 ImageGenStore.dir()，
 *     因为 AI 可能不回复、链接会过期、用户也可能只是想回看。
 *  2. 返回值只给本地路径与尺寸，**不把 base64 反给模型**（会炸上下文）。
 *  3. 识图需求走 readImageAsDataUrl()：压缩到合理边长再 base64。
 */
object ImageGenService {

    data class Generated(val file: File, val bytes: Int)

    /** 单次生图。同步阻塞，调用方放后台线程。 */
    fun generate(
        ctx: Context,
        prompt: String,
        sizeOverride: String? = null,
        n: Int = 1,
    ): Result<List<Generated>> = runCatching {
        val cfg = ImageGenStore.config(ctx)
        require(cfg.usable) { "生图未配置或未启用（设置 → 生图配置）" }
        require(prompt.isNotBlank()) { "prompt 不能为空" }

        val size = sizeOverride?.takeIf { it.isNotBlank() } ?: cfg.size
        val body = JSONObject()
            .put("model", cfg.model)
            .put("prompt", prompt)
            .put("n", n.coerceIn(1, 4))
            .put("size", size)

        val url = cfg.baseUrl.trimEnd('/') + "/images/generations"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000   // 生图慢，给 5 分钟
            setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP " + code + ": " + text.take(300))

            val root = JSONObject(text)
            // 网关可能 200 包着业务错误
            if (!root.has("data") && root.has("error")) {
                error("接口报错：" + root.optJSONObject("error")?.optString("message").orEmpty())
            }
            val arr = root.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<Generated>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val b64 = o.optString("b64_json").ifBlank { o.optString("b64") }
                val f = if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val nf = ImageGenStore.fileFor(ctx, newName("png"))
                    nf.writeBytes(bytes)
                    nf
                } else {
                    val u = o.optString("url")
                    require(u.isNotBlank()) { "返回里既没有 b64_json 也没有 url" }
                    val nf = ImageGenStore.fileFor(ctx, newName("png"))
                    download(u, nf)
                    nf
                }
                out.add(Generated(f, f.length().toInt()))
            }
            check(out.isNotEmpty()) { "接口未返回图片" }
            out
        } finally {
            conn.disconnect()
        }
    }

    private fun newName(ext: String): String =
        "img_" + System.currentTimeMillis() + "_" + (1000..9999).random() + "." + ext

    private fun download(u: String, dst: File) {
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 120_000
        }
        try {
            check(c.responseCode in 200..299) { "下载图片失败 HTTP " + c.responseCode }
            c.inputStream.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    /** 把本地图片转成 data URL（供识图 AI 使用）。长边超 maxSide 会先缩放。 */
    fun readImageAsDataUrl(file: File, maxSide: Int = 1024, quality: Int = 85): String? = runCatching {
        val raw = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val long = maxOf(raw.width, raw.height)
        val bmp = if (long > maxSide) {
            val k = maxSide.toFloat() / long
            android.graphics.Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true)
        } else raw
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        "data:image/jpeg;base64," + b64
    }.getOrNull()


    /**
     * 从工具返回 JSON 里取出识图用的 data URL（read_image 才会带）。
     * 取出来之后要把 base64 从落盘文本里剥掉，否则历史里会塞进一整张图。
     */
    fun extractDataUrl(toolResult: String): String? = runCatching {
        JSONObject(toolResult).optString("_image_data_url").takeIf { it.startsWith("data:image/") }
    }.getOrNull()

    /** 去掉 base64 后的工具结果（给落盘/历史用）。 */
    fun stripDataUrl(toolResult: String): String = runCatching {
        val o = JSONObject(toolResult)
        if (!o.has("_image_data_url")) return toolResult
        o.remove("_image_data_url")
        o.put("image_attached", true)
        o.toString()
    }.getOrDefault(toolResult)

    /** 构造带图（多模态）的 tool 消息；无图则返回 null。 */
    fun toolMessageWithImage(toolCallId: String, name: String, toolResult: String): JSONObject? {
        val du = extractDataUrl(toolResult) ?: return null
        val text = stripDataUrl(toolResult)
        val arr = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", du)),
            )
        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCallId)
            .put("name", name)
            .put("content", arr)
    }

    /** 相册：按时间倒序列出本地所有图片。 */
    fun listImages(ctx: Context): List<File> =
        (ImageGenStore.dir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
}
