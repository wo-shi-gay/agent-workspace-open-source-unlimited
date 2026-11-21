package com.agentpad.workspace.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 论坛数据层：板块 / 帖子 / 回复 / 发帖回帖。
 * 读取走 GET（免登录），写入走 POST（UserStore 的鉴权通道，带 token）。
 * 全部同步阻塞，调用方放子线程。
 */
object ForumStore {

    private val BASE get() = UserStore.BASE_URL

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** GET（论坛接口在自有服务器，无 InfinityFree 挑战页，直接请求） */
    private fun get(path: String): Pair<Int, String> {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Pair(code, text)
    }

    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("code", -1).put("msg", "服务器返回异常"))

    private fun toList(arr: JSONArray?): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return list
    }

    // ---------- 读取 ----------

    fun boards(): Pair<String?, List<JSONObject>> {
        val (code, text) = get("/api/forum_list.php?scope=boards")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        return Pair(null, toList(resp.optJSONObject("data")?.optJSONArray("list")))
    }

    fun posts(boardId: Int): Pair<String?, List<JSONObject>> {
        val q = if (boardId > 0) "&board_id=$boardId" else ""
        val (code, text) = get("/api/forum_list.php?scope=posts$q")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        return Pair(null, toList(resp.optJSONObject("data")?.optJSONArray("list")))
    }

    /** 帖子详情：返回 Pair(错误, Pair(帖子, 回复列表) | null) */
    fun postDetail(id: Int): Pair<String?, Pair<JSONObject, List<JSONObject>>?> {
        val (code, text) = get("/api/forum_list.php?scope=post&id=$id")
        if (code !in 200..299) return Pair("HTTP $code", null)
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), null)
        val data = resp.optJSONObject("data") ?: return Pair("数据异常", null)
        val post = data.optJSONObject("post") ?: return Pair("数据异常", null)
        return Pair(null, Pair(post, toList(data.optJSONArray("replies"))))
    }

    /** 当前用户论坛身份（0 普通 / 1 版主 / 2 管理员），未登录或失败返回 -1 */
    fun myRole(context: android.content.Context): Int {
        val token = UserStore.token(context)
        if (token.isBlank()) return -1
        val (code, text) = get("/api/forum_list.php?scope=me&token=$token")
        if (code !in 200..299) return -1
        val resp = parse(text)
        if (resp.optInt("code") != 200) return -1
        return resp.optJSONObject("data")?.optInt("forum_role", 0) ?: 0
    }

    // ---------- 写入（需登录） ----------

    private val CRLF = "\r\n"

    /** 通用 multipart 发送（含可选附件），带 50MB 客户端预检 */
    private fun sendMultipart(
        context: android.content.Context,
        fields: Map<String, String>,
        attach: java.io.File?,
    ): String? {
        if (attach != null && attach.length() > 50L * 1048576) return "附件超过 50MB 上限"
        val token = UserStore.token(context)
        val boundary = "----AgentPadForum${System.currentTimeMillis()}"
        val body = run {
            val head = StringBuilder()
            fun field(name: String, value: String) {
                head.append("--$boundary").append(CRLF)
                head.append("Content-Disposition: form-data; name=\"$name\"").append(CRLF).append(CRLF)
                head.append(value).append(CRLF)
            }
            field("token", token)
            fields.forEach { (k, v) -> field(k, v) }
            val headBytes = head.toString().toByteArray(Charsets.UTF_8)
            val parts = ArrayList<ByteArray>()
            parts.add(headBytes)
            if (attach != null && attach.isFile) {
                parts.add(("--$boundary$CRLF" +
                    "Content-Disposition: form-data; name=\"attach\"; filename=\"${attach.name}\"$CRLF" +
                    "Content-Type: application/octet-stream$CRLF$CRLF").toByteArray(Charsets.UTF_8))
                parts.add(attach.readBytes())
                parts.add(CRLF.toByteArray(Charsets.UTF_8))
            }
            parts.add("--$boundary--$CRLF".toByteArray(Charsets.UTF_8))
            var total = 0; parts.forEach { total += it.size }
            val out = ByteArray(total); var pos = 0
            parts.forEach { it.copyInto(out, pos); pos += it.size }
            out
        }
        val conn = (URL(BASE + "/api/forum_action.php").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
            connectTimeout = 20000; readTimeout = 180000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Connection", "close")
            setRequestProperty("Authorization", "Bearer $token")
            setFixedLengthStreamingMode(body.size)
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        val resp = parse(text)
        if (code in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "操作失败（HTTP $code）")
    }

    /** 发帖（可带附件）：成功返回 null */
    fun createPost(context: android.content.Context, boardId: Int, title: String, content: String, attach: java.io.File? = null): String? {
        if (attach != null) {
            return sendMultipart(context, mapOf(
                "action" to "post", "board_id" to boardId.toString(),
                "title" to title, "content" to content), attach)
        }
        val body = JSONObject()
            .put("action", "post").put("board_id", boardId)
            .put("title", title).put("content", content)
            .put("token", UserStore.token(context))
        val (http, text) = UserStore.post(context, "/api/forum_action.php", body, UserStore.token(context))
        val resp = parse(text)
        if (http in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "发布失败（HTTP $http）")
    }

    /** 回帖（可带附件）：成功返回 null */
    fun reply(context: android.content.Context, postId: Int, content: String, attach: java.io.File? = null): String? {
        if (attach != null) {
            return sendMultipart(context, mapOf(
                "action" to "reply", "post_id" to postId.toString(),
                "content" to content), attach)
        }
        val body = JSONObject()
            .put("action", "reply").put("post_id", postId)
            .put("content", content)
            .put("token", UserStore.token(context))
        val (http, text) = UserStore.post(context, "/api/forum_action.php", body, UserStore.token(context))
        val resp = parse(text)
        if (http in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "回复失败（HTTP $http）")
    }

    /** 删除帖子 / 回复 / 置顶 / 加精 */
    fun action(context: android.content.Context, action: String, idName: String, id: Int, value: Int? = null): String? {
        val body = JSONObject().put("action", action).put(idName, id).put("token", UserStore.token(context))
        if (value != null) body.put("value", value)
        val (http, text) = UserStore.post(context, "/api/forum_action.php", body, UserStore.token(context))
        val resp = parse(text)
        if (http in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "操作失败（HTTP $http）")
    }
}