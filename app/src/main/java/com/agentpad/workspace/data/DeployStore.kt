package com.agentpad.workspace.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网站部署数据层（一人一站，双档位二选一）。
 *
 * 规则：
 *   - 每个用户只能选**一个**服务器档位，部署后即锁定
 *   - 只有删除站点后才能重新选择档位
 *   - 直连地址优先（performance 有独立 HTTPS 证书）
 *
 * 档位：
 *   normal      普通服务器：1GB/月 · 1MB/s · 主服务器容器
 *   performance 高性能服务器：10GB/月 · 5MB/s · 独立从机（⚠️ 可能不稳定，需备份）
 *
 * 全部同步阻塞，调用方放子线程。
 */
object DeployStore {

    private val BASE get() = UserStore.BASE_URL
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val HOST_NORMAL = "normal"
    const val HOST_PERFORMANCE = "performance"

    /** 站点信息 */
    data class Site(
        val host: String,
        val hostLabel: String,
        val slug: String,
        val siteName: String,
        val url: String,
        val urlGateway: String,
        val urlDirect: String,
        val status: String,
        val statusText: String,
        val suspendReason: String,
        val expiresAt: String,
        val daysLeft: Int,
        val trafficUsed: Long,
        val trafficQuota: Long,
        val trafficPercent: Double,
        val trafficText: String,
        val rateKbps: Int,
        val sideEffect: String,
        val createdAt: String,
        val lastDeployAt: String,
    ) {
        /** 优先使用的访问地址：直连 > 网关 */
        val preferredUrl: String
            get() = if (urlDirect.isNotBlank()) urlDirect else urlGateway
    }

    /** 档位预览（首次选择服务器时展示） */
    data class Slot(
        val host: String,
        val label: String,
        val quota: Long,
        val rateKbps: Int,
        val sideEffect: String,
        val trafficText: String,
    )

    /** 整体状态 */
    data class Overview(
        val hasSite: Boolean,
        val site: Site?,
        val slots: Map<String, Slot>,
    ) {
        /** 未部署才可自由选择服务器 */
        val canChoose: Boolean get() = !hasSite
        /** 当前锁定的档位 */
        val lockedHost: String? get() = site?.host
    }

    // ---------- 挑战处理（历史兼容） ----------

    private fun solveChallenge(html: String): String? = runCatching {
        val found = Regex("""toNumbers\("([0-9a-fA-F]{32})"\)""").findAll(html).map { it.groupValues[1] }.toList()
        if (found.size < 3) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(found[0].hexToBytes(), "AES"),
            javax.crypto.spec.IvParameterSpec(found[1].hexToBytes())
        )
        cipher.doFinal(found[2].hexToBytes()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun isChallenge(html: String) = html.contains("/aes.js") && html.contains("toNumbers")

    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }
            .getOrDefault(JSONObject().put("code", -1).put("msg", "服务器返回异常"))

    private fun readBody(conn: HttpURLConnection, code: Int): String =
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

    /** 统一的网络日志打点（低开销：字符串拼接 + 入队，无 IO）。 */
    private fun logNet(conn: HttpURLConnection, code: Int, startedAt: Long, note: String = "") {
        runCatching {
            AppLog.net(
                method = conn.requestMethod ?: "?",
                url = conn.url.toString(),
                code = code,
                costMs = System.currentTimeMillis() - startedAt,
                note = note,
            )
        }
    }

    // ---------- 网络层 ----------

    private fun get(path: String, token: String): Pair<Int, String> {
        fun open(cookie: String?): HttpURLConnection =
            (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
            }
        var conn = open(null)
        val t0 = System.currentTimeMillis()
        var code = conn.responseCode
        var text = readBody(conn, code)
        logNet(conn, code, t0, "GET")
        conn.disconnect()
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            conn = open(cv)
            val t1 = System.currentTimeMillis()
            code = conn.responseCode
            text = readBody(conn, code)
            logNet(conn, code, t1, "GET retry")
            conn.disconnect()
        }
        return Pair(code, text)
    }

    private fun postJson(path: String, token: String, body: JSONObject): Pair<Int, String> {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        fun open(cookie: String?): HttpURLConnection =
            (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
            }
        var conn = open(null)
        conn.setFixedLengthStreamingMode(payload.size)
        conn.outputStream.use { it.write(payload) }
        val t0 = System.currentTimeMillis()
        var code = conn.responseCode
        var text = readBody(conn, code)
        logNet(conn, code, t0, "POST")
        conn.disconnect()
        if (isChallenge(text)) {
            val cv = solveChallenge(text) ?: return Pair(code, text)
            conn = open(cv)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val t1 = System.currentTimeMillis()
            code = conn.responseCode
            text = readBody(conn, code)
            logNet(conn, code, t1, "POST retry")
            conn.disconnect()
        }
        return Pair(code, text)
    }

    private fun uploadZip(token: String, host: String, siteName: String, zipFile: File): Pair<Int, String> {
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
            field("host", host)
            field("site_name", siteName)
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            val fileHead = ("--$boundary$CRLF" +
                "Content-Disposition: form-data; name=\"zip\"; filename=\"${zipFile.name}\"$CRLF" +
                "Content-Type: application/zip$CRLF$CRLF").toByteArray(Charsets.UTF_8)
            val fileBytes = zipFile.readBytes()
            val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
            val out = ByteArray(head.size + fileHead.size + fileBytes.size + tail.size)
            var pos = 0
            fun put(b: ByteArray) { b.copyInto(out, pos); pos += b.size }
            put(head); put(fileHead); put(fileBytes); put(tail)
            return out
        }

        fun send(cookie: String?): Pair<Int, String> {
            val body = buildBody()
            val conn = (URL(BASE + "/api/deploy_apply.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Connection", "close")
                cookie?.let { setRequestProperty("Cookie", "__test=$it") }
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val t0 = System.currentTimeMillis()
            val code = conn.responseCode
            val text = readBody(conn, code)
            logNet(conn, code, t0, "UPLOAD ${body.size / 1024}KB")
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

    // ---------- 解析 ----------

    private fun toSite(d: JSONObject) = Site(
        host = d.optString("host", HOST_NORMAL),
        hostLabel = d.optString("host_label", d.optString("label", "普通服务器")),
        slug = d.optString("slug"),
        siteName = d.optString("site_name"),
        url = d.optString("url"),
        urlGateway = d.optString("url_gateway", d.optString("url")),
        urlDirect = d.optString("url_direct", d.optString("url")),
        status = d.optString("status", "active"),
        statusText = d.optString("status_text", "正常"),
        suspendReason = d.optString("suspend_reason"),
        expiresAt = d.optString("expires_at"),
        daysLeft = d.optInt("days_left", 0),
        trafficUsed = d.optLong("traffic_used", 0),
        trafficQuota = d.optLong("traffic_quota", 1073741824),
        trafficPercent = d.optDouble("traffic_percent", 0.0),
        trafficText = d.optString("traffic_text"),
        rateKbps = d.optInt("rate_kbps", 1000),
        sideEffect = d.optString("side_effect"),
        createdAt = d.optString("created_at"),
        lastDeployAt = d.optString("last_deploy_at"),
    )

    private fun toSlot(host: String, d: JSONObject?): Slot {
        val defLabel = if (host == HOST_PERFORMANCE) "高性能服务器" else "普通服务器"
        if (d == null) return Slot(host, defLabel, 1073741824, 1000, "", "0 B / 1.00 GB")
        return Slot(
            host = host,
            label = d.optString("label", defLabel),
            quota = d.optLong("quota", d.optLong("traffic_quota", 1073741824)),
            rateKbps = d.optInt("rate_kbps", 1000),
            sideEffect = d.optString("side_effect"),
            trafficText = d.optString("traffic_text"),
        )
    }

    // ---------- 业务接口 ----------

    /** 查询站点状态 + 两档位预览 */
    fun status(token: String): Overview {
        val (code, text) = get("/api/deploy_status.php", token)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "查询失败（HTTP $code）"))
        val data = resp.optJSONObject("data") ?: JSONObject()

        val slotsObj = data.optJSONObject("slots")
        val slots = linkedMapOf<String, Slot>()
        slots[HOST_NORMAL] = toSlot(HOST_NORMAL, slotsObj?.optJSONObject(HOST_NORMAL))
        slots[HOST_PERFORMANCE] = toSlot(HOST_PERFORMANCE, slotsObj?.optJSONObject(HOST_PERFORMANCE))

        val has = data.optBoolean("has_site", false)
        return Overview(
            hasSite = has,
            site = if (has) toSite(data) else null,
            slots = slots,
        )
    }

    /** 部署（首次建站时选定档位；之后只能更新同档位内容） */
    fun deploy(token: String, host: String, siteName: String, zipFile: File): Site {
        val (code, text) = uploadZip(token, host, siteName, zipFile)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "部署失败（HTTP $code）"))
        return toSite(resp.optJSONObject("data") ?: JSONObject())
    }

    /** 续期（封顶 30 天） */
    fun renew(token: String): Site {
        val body = JSONObject().put("token", token)
        val (code, text) = postJson("/api/deploy_renew.php", token, body)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "续期失败（HTTP $code）"))
        return toSite(resp.optJSONObject("data") ?: JSONObject())
    }

    /** 删除站点（删除后可重新选服务器） */
    fun delete(token: String) {
        val body = JSONObject().put("token", token)
        val (code, text) = postJson("/api/deploy_delete.php", token, body)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "删除失败（HTTP $code）"))
    }
}