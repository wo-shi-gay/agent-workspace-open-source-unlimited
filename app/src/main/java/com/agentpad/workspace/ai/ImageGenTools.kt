package com.agentpad.workspace.ai

import android.content.Context
import com.agentpad.workspace.data.ImageGenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 生图工具（暴露给 AI）。只在「生图配置」启用且勾了「暴露为工具」时注册。
 *
 * generate_image  — 文生图，返回本地落盘路径（不返 base64，避免炸上下文）
 * read_image      — 识图：把本地图转 data URL 回给多模态模型看，
 *                   同时把 image_path 一起给出去，界面据此画缩略图
 * list_generated_images — 列生图历史（相册）
 */
object ImageGenTools {

    fun register(registry: ToolRegistry, ctx: Context) {

        // ---------- 文生图 ----------
        registry.register("generate_image") { args ->
            val prompt = args.optString("prompt").trim()
            if (prompt.isBlank()) {
                JSONObject().put("success", false).put("error", "缺少 prompt").toString()
            } else {
                val size = args.optString("size").takeIf { it.isNotBlank() }
                val n = args.optInt("n", 1)
                ImageGenService.generate(ctx, prompt, size, n).fold(
                    onSuccess = { list ->
                        val arr = JSONArray()
                        list.forEach { g ->
                            arr.put(JSONObject()
                                .put("path", g.file.absolutePath)
                                .put("name", g.file.name)
                                .put("bytes", g.bytes))
                        }
                        JSONObject()
                            .put("success", true)
                            .put("count", list.size)
                            .put("images", arr)
                            .put("note", "图片已保存到本机相册（设置 → 相册 可查看/下载）。不要回显 base64；需要给用户看就直接描述这张图。")
                            .toString()
                    },
                    onFailure = { e ->
                        JSONObject().put("success", false)
                            .put("error", e.message ?: "生图失败")
                            .toString()
                    },
                )
            }
        }

        // ---------- 识图（多模态）----------
        registry.register("read_image") { args ->
            val raw = args.optString("path").ifBlank { args.optString("image_path") }
            val f = resolveImage(ctx, raw)
            if (f == null) {
                JSONObject().put("success", false)
                    .put("error", "找不到图片：" + raw)
                    .put("hint", "可用绝对路径，或相册里的文件名；也可以先调 list_generated_images 看有什么")
                    .toString()
            } else {
                val dataUrl = ImageGenService.readImageAsDataUrl(f)
                if (dataUrl == null) {
                    JSONObject().put("success", false).put("error", "图片解码失败：" + f.name).toString()
                } else {
                    JSONObject()
                        .put("success", true)
                        .put("image_path", f.absolutePath)
                        .put("name", f.name)
                        .put("bytes", f.length())
                        // 这一项会被 ChatActivity 取出来当「图片内容」发给多模态模型
                        .put("_image_data_url", dataUrl)
                        .put("note", "图已附在你这轮请求里，你可以直接描述它的内容。回复里不要再贴 base64。")
                        .toString()
                }
            }
        }

        // ---------- 相册清单 ----------
        registry.register("list_generated_images") { args ->
            val limit = args.optInt("limit", 30).coerceIn(1, 200)
            val arr = JSONArray()
            ImageGenService.listImages(ctx).take(limit).forEach { f ->
                arr.put(JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("bytes", f.length())
                    .put("modified", f.lastModified()))
            }
            JSONObject().put("success", true).put("count", arr.length()).put("images", arr).toString()
        }
    }

    /** 解析图片：原样绝对路径 → 相册目录按名字找 → 工作区相对找。 */
    private fun resolveImage(ctx: Context, raw: String): File? {
        val t = raw.trim()
        if (t.isBlank()) return null
        File(t).takeIf { it.isFile }?.let { return it }
        File(ImageGenStore.dir(ctx), t).takeIf { it.isFile }?.let { return it }
        File(ImageGenStore.dir(ctx), t + ".png").takeIf { it.isFile }?.let { return it }
        // 相册里按名字模糊匹配
        return ImageGenService.listImages(ctx).firstOrNull { it.name.equals(t, true) }
    }

    // ================= 规格 =================

    fun spec(name: String): ToolSpec? = when (name) {
        "generate_image" -> ToolSpec(
            name,
            "Generate an image from a text prompt using the configured image model (OpenAI-compatible /images/generations). " +
                "The image is SAVED LOCALLY and also shown to the user as a thumbnail in the chat; the result gives you the local file paths. " +
                "Never echo base64. If the user later asks about the picture, use read_image to actually look at it.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"prompt\":{\"type\":\"string\",\"description\":\"What to draw. Be specific; include style/aspect hints.\"}," +
                "\"size\":{\"type\":\"string\",\"description\":\"Optional, e.g. 1024x1024 / 1024x1792 / 1792x1024\"}," +
                "\"n\":{\"type\":\"integer\",\"description\":\"How many images, 1-4. Default 1.\"}" +
                "},\"required\":[\"prompt\"]}",
        )

        "read_image" -> ToolSpec(
            name,
            "Look at a local image file (vision). The image is attached to this tool result so you can actually SEE it, and it is also shown " +
                "to the user as a thumbnail. Use it to describe, OCR, compare or criticise a generated image, or any picture in the gallery.",
            "{\"type\":\"object\",\"properties\":{" +
                "\"path\":{\"type\":\"string\",\"description\":\"Absolute path, or just a file name from the gallery\"}" +
                "},\"required\":[\"path\"]}",
        )

        "list_generated_images" -> ToolSpec(
            name,
            "List locally generated images (the gallery), newest first. Use it to find a picture the user refers to.",
            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"description\":\"Default 30\"}}}",
        )

        else -> null
    }
}
