package com.agentpad.workspace.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用市场数据层：列表 / 我的应用 / 通知 / 上传（multipart + 挑战处理）
 * 全部同步阻塞，调用方放子线程。
 */
object MarketStore {

    private val BASE get() = UserStore.BASE_URL

    /** InfinityFree JS 挑战：解密 __test cookie（与 UserStore 同款算法） */
    private fun solveChallenge(html: String): String? {
        return runCatching {
            val found = Regex("""toNumbers\("([0-9a-fA-F]{32})"\)""").findAll(html).map { it.groupValues[1] }.toList()
            if (found.size < 3) return null
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(found[0].hexToBytes(), "AES"),
                javax.crypto.spec.IvParameterSpec(found[1].hexToBytes()))
            cipher.doFinal(found[2].hexToBytes()).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun isChallenge(html: String) = html.contains("/aes.js") && html.contains("toNumbers")

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** GET（带挑战重试） */
    fun get(path: String): Pair<Int, String> {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }
        var code = conn.responseCode
        var text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            val r = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Cookie", "__test=$cv")
                setRequestProperty("Connection", "close")
            }
            code = r.responseCode
            text = (if (code in 200..299) r.inputStream else r.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            r.disconnect()
        }
        return Pair(code, text)
    }

    /** JSON POST（带挑战重试） */
    fun postJson(path: String, body: JSONObject): Pair<Int, String> {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        fun open(cookie: String?): HttpURLConnection =
            (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
            }
        val conn = open(null)
        conn.setFixedLengthStreamingMode(payload.size)
        conn.outputStream.use { it.write(payload) }
        var code = conn.responseCode
        var text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            val retry = open(cv)
            retry.setFixedLengthStreamingMode(payload.size)
            retry.outputStream.use { it.write(payload) }
            code = retry.responseCode
            text = (if (code in 200..299) retry.inputStream else retry.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            retry.disconnect()
        }
        return Pair(code, text)
    }

    /** multipart 上传（带挑战重试）：fields + apk 文件 + 可选图片数组 */
    fun uploadApp(
        token: String, appName: String, description: String,
        apkFile: File, imageFiles: List<File> = emptyList()
    ): Pair<Int, String> {
        val boundary = "----AgentPad${System.currentTimeMillis()}"
        val CRLF = "\r\n"

        fun buildBody(): ByteArray {
            val sb = StringBuilder()
            fun field(name: String, value: String) {
                sb.append("--$boundary").append(CRLF)
                sb.append("Content-Disposition: form-data; name=\"$name\"").append(CRLF).append(CRLF)
                sb.append(value).append(CRLF)
            }
            field("token", token)
            field("app_name", appName)
            field("description", description)
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            val apkHead = ("--$boundary$CRLF" +
                "Content-Disposition: form-data; name=\"apk\"; filename=\"${apkFile.name}\"$CRLF" +
                "Content-Type: application/vnd.android.package-archive$CRLF$CRLF").toByteArray(Charsets.UTF_8)
            val apkBytes = apkFile.readBytes()
            val imgParts = ArrayList<ByteArray>()
            imageFiles.take(4).forEach { img ->
                val ext = img.extension.lowercase().ifBlank { "png" }
                val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
                imgParts.add(("--$boundary$CRLF" +
                    "Content-Disposition: form-data; name=\"images[]\"; filename=\"${img.name}\"$CRLF" +
                    "Content-Type: $mime$CRLF$CRLF").toByteArray(Charsets.UTF_8))
                imgParts.add(img.readBytes())
                imgParts.add(CRLF.toByteArray())
            }
            val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
            var total = head.size + apkHead.size + apkBytes.size + tail.size
            imgParts.forEach { total += it.size }
            val out = ByteArray(total)
            var pos = 0
            fun put(b: ByteArray) { b.copyInto(out, pos); pos += b.size }
            put(head); put(apkHead); put(apkBytes); imgParts.forEach { put(it) }; put(tail)
            return out
        }

        fun send(cookie: String?): Pair<Int, String> {
            val body = buildBody()
            val conn = (URL(BASE + "/api/market_submit.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            return Pair(code, text)
        }

        var (code, text) = send(null)
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            val (c2, t2) = send(cv)
            return Pair(c2, t2)
        }
        return Pair(code, text)
    }

// ---------- 解析 ----------
    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("code", -1).put("msg", "服务器返回异常"))

    fun marketList(): Pair<String?, List<JSONObject>> {
        val (code, text) = get("/api/market_list.php?scope=market")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(null, list)
    }

    /** 插件市场列表（scope=plugins）。 */
    fun pluginList(): Pair<String?, List<JSONObject>> {
        val (code, text) = get("/kami/api/plugins")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(null, list)
    }

    /**
     * 上传插件包到市场（multipart）。
     * 字段：token / kind=plugin / plugin_name / description / plugin(文件) / images[]（可选）
     */
    fun uploadPlugin(
        token: String, name: String, description: String,
        pluginFile: File, imageFiles: List<File> = emptyList()
    ): Pair<Int, String> {
        val boundary = "----AgentPad${System.currentTimeMillis()}"
        val CRLF = "\r\n"
        fun buildBody(): ByteArray {
            val sb = StringBuilder()
            fun field(n: String, v: String) {
                sb.append("--$boundary").append(CRLF)
                sb.append("Content-Disposition: form-data; name=\"$n\"").append(CRLF).append(CRLF)
                sb.append(v).append(CRLF)
            }
            field("token", token)
            field("kind", "plugin")
            field("plugin_name", name)
            field("description", description)
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            val fileHead = ("--$boundary$CRLF" +
                "Content-Disposition: form-data; name=\"plugin\"; filename=\"${pluginFile.name}\"$CRLF" +
                "Content-Type: application/zip$CRLF$CRLF").toByteArray(Charsets.UTF_8)
            val fileBytes = pluginFile.readBytes()
            val imgParts = ArrayList<ByteArray>()
            imageFiles.take(4).forEach { img ->
                val ext = img.extension.lowercase().ifBlank { "png" }
                val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
                imgParts.add(("--$boundary$CRLF" +
                    "Content-Disposition: form-data; name=\"images[]\"; filename=\"${img.name}\"$CRLF" +
                    "Content-Type: $mime$CRLF$CRLF").toByteArray(Charsets.UTF_8))
                imgParts.add(img.readBytes())
                imgParts.add(CRLF.toByteArray())
            }
            val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
            var total = head.size + fileHead.size + fileBytes.size + tail.size
            imgParts.forEach { total += it.size }
            val out = ByteArray(total); var pos = 0
            fun put(b: ByteArray) { b.copyInto(out, pos); pos += b.size }
            put(head); put(fileHead); put(fileBytes); imgParts.forEach { put(it) }; put(tail)
            return out
        }
        fun send(cookie: String?): Pair<Int, String> {
            val body = buildBody()
            val conn = (URL(BASE + "/kami/api/plugins/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            return Pair(code, text)
        }
        val (code, text) = send(null)
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            return send(cv)
        }
        return Pair(code, text)
    }

    /** 下载插件包到本地文件（走挑战处理）。返回 null 表示成功，否则错误信息。 */
    fun downloadPlugin(appId: Int, dest: File): String? {
        return runCatching {
            fun open(cookie: String?): HttpURLConnection =
                (URL(BASE + "/kami/api/plugins/download?id=$appId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; instanceFollowRedirects = true
                    connectTimeout = 15000; readTimeout = 60000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Connection", "close")
                    cookie?.let { setRequestProperty("Cookie", "__test=$it") }
                }
            var conn = open(null)
            var code = conn.responseCode
            // 挑战页判断：读前若干字节看是否为 aes.js 挑战
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                val asText = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrDefault("")
                if (isChallenge(asText)) {
                    val cv = solveChallenge(asText)
                    if (cv != null) {
                        conn = open(cv)
                        code = conn.responseCode
                        if (code in 200..299) {
                            conn.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                            conn.disconnect()
                            return null
                        }
                    }
                    return "下载被挑战页拦截"
                }
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
                conn.disconnect()
                return null
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            "HTTP $code ${err.take(200)}"
        }.getOrElse { "下载失败：${it.message}" }
    }

    fun myApps(token: String): Pair<String?, List<JSONObject>> {
        // 走 Go 服务（kami-server），与插件市场同一后端，支持 JSON/Bearer 鉴权
        val (code, text) = get("/kami/api/market/mine?token=$token")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(null, list)
    }

    fun notifications(token: String, markRead: Boolean): Pair<String?, List<JSONObject>> {
        val path = "/api/market_list.php?scope=noti&token=$token" + if (markRead) "&read=1" else ""
        val (code, text) = get(path)
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(null, list)
    }

    /**
     * 全站公告（免登录）。
     *
     * ⚠ versionCode 必须传**当前安装包的真实版本号**。
     *   旧代码写死 `version_code=2`，导致服务端 SQL
     *   `WHERE target_version=0 OR target_version > 2`
     *   把所有 target_version>2 的公告全发给所有人 —— 包括已经是最新版的用户，
     *   于是强制更新公告会永远弹、永远拦着不让进主页。
     *
     * @param versionCode 当前 APK 的 versionCode（由调用方从 PackageManager 读）
     * @param all 是否取**全量历史公告**（「平台公告」列表页用）
     *   - false（默认）：保持原过滤 `target_version=0 OR target_version>vc`，
     *     只返回「比当前版本更新」的公告。启动页弹窗用这个，别改。
     *   - true：返回全部历史公告。设置页「平台公告」用这个。
     *
     *   ⚠ 2026-11-04 加 all 参数的原因（真机实测出来的 bug）：
     *     服务端那条 `target_version>vc` 过滤本来只是给「启动弹窗」用的，
     *     但「平台公告」页也复用同一个接口，于是已经在最新版的用户
     *     （vc=49，等于所有公告的 target_version）拿到空列表，界面显示「暂无公告」。
     *     同一台手机换 4.6（vc=46）却能列出 2 条 —— 现象就是「4.7 看不到公告」。
     *     服务端已支持 scope=all，客户端这里对应传 `all=true`。
     */
    fun announcements(versionCode: Int, all: Boolean = false): Pair<String?, List<JSONObject>> {
        val q = if (all) "&scope=all" else ""
        val (code, text) = get("/kami/api/announcements?version_code=$versionCode$q")
        if (code !in 200..299) return Pair("HTTP $code", emptyList())
        val resp = parse(text)
        if (resp.optInt("code") != 200) return Pair(resp.optString("msg", "加载失败"), emptyList())
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        return Pair(null, list)
    }

    /** 开发者管理操作：update（multipart 更新）/ unpublish（下架）/ republish（重新提交）
     *  unpublish / republish 走 Go 服务 /kami/api/market/manage（JSON body）。 */
    fun manageApp(token: String, action: String, appId: Int,
                  appName: String? = null, description: String? = null,
                  apkFile: File? = null, imageFiles: List<File> = emptyList()): Pair<Int, String> {
        if (action == "update" && apkFile != null) {
            return uploadMultipart("/api/market_manage.php", token, mapOf(
                "action" to action, "app_id" to appId.toString(),
                "app_name" to (appName ?: ""), "description" to (description ?: "")
            ), apkFile, imageFiles)
        }
        // 纯 JSON 操作（unpublish / republish）→ Go 后端
        val body = JSONObject().put("token", token).put("action", action).put("app_id", appId)
        return postJson("/kami/api/market/manage", body)
    }

    /** 通用 multipart（复用 uploadApp 的构建逻辑） */
    private fun uploadMultipart(path: String, token: String, fields: Map<String, String>,
                                apkFile: File, imageFiles: List<File>): Pair<Int, String> {
        val boundary = "----AgentPad${System.currentTimeMillis()}"
        val CRLF = "\r\n"
        fun buildBody(): ByteArray {
            val sb = StringBuilder()
            fun field(name: String, value: String) {
                sb.append("--$boundary").append(CRLF)
                sb.append("Content-Disposition: form-data; name=\"$name\"").append(CRLF).append(CRLF)
                sb.append(value).append(CRLF)
            }
            // ★ token 必须显式写入：服务端读的是 $_POST['token']，
            //   此前只写 fields（不含 token），导致更新时服务器报"未登录"。
            field("token", token)
            fields.forEach { (k, v) -> field(k, v) }
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            val apkHead = ("--$boundary$CRLF" +
                "Content-Disposition: form-data; name=\"apk\"; filename=\"${apkFile.name}\"$CRLF" +
                "Content-Type: application/vnd.android.package-archive$CRLF$CRLF").toByteArray(Charsets.UTF_8)
            val apkBytes = apkFile.readBytes()
            val imgParts = ArrayList<ByteArray>()
            imageFiles.take(4).forEach { img ->
                val ext = img.extension.lowercase().ifBlank { "png" }
                val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
                imgParts.add(("--$boundary$CRLF" +
                    "Content-Disposition: form-data; name=\"images[]\"; filename=\"${img.name}\"$CRLF" +
                    "Content-Type: $mime$CRLF$CRLF").toByteArray(Charsets.UTF_8))
                imgParts.add(img.readBytes())
                imgParts.add(CRLF.toByteArray())
            }
            val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
            var total = head.size + apkHead.size + apkBytes.size + tail.size
            imgParts.forEach { total += it.size }
            val out = ByteArray(total); var pos = 0
            fun put(b: ByteArray) { b.copyInto(out, pos); pos += b.size }
            put(head); put(apkHead); put(apkBytes); imgParts.forEach { put(it) }; put(tail)
            return out
        }
        fun send(cookie: String?): Pair<Int, String> {
            val body = buildBody()
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            return Pair(code, text)
        }
        val (code, text) = send(null)
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            return send(cv)
        }
        return Pair(code, text)
    }
}