package com.agentpad.workspace.ai

import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小飞机网盘分享链接 → 真实下载直链 解析器。
 *
 * 严格对照 NFD (netdisk-fast-download) FjTool.java 的实现：
 * - API 域名：api.feijipan.com（不是旧的 feejii.com！域名错了会被风控静默返回空列表）
 * - 加密：AES-128-ECB，密钥 "dingHao-disk-app"，PKCS5Padding，hex 大写
 * - 流程：1) VIP 预热（裸请求，无 header，且 UUID 必须固定）
 *        2) recommend/list（header0: Chrome/131 + Content-Length:0）
 *        3) file/redirect（文档头 Edge/135，no-redirect，Location 即直链）
 * - downloadId = enc("fileId|userId")，userId 可为 null（字符串 "null" 由服务端容错）
 */
object FeijipanParser {

    private const val AES_KEY = "dingHao-disk-app"
    private const val API = "https://api.feijipan.com/ws"

    /** header0: 用于 JSON API（recommend/list 等）。 */
    private val HEADER0 = arrayOf(
        "Accept-Encoding" to "gzip, deflate",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control" to "no-cache",
        "Connection" to "keep-alive",
        "Content-Length" to "0",
        "DNT" to "1",
        "Pragma" to "no-cache",
        "Referer" to "https://www.feijipan.com/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    /** 文档导航头：用于 file/redirect 下载请求。 */
    private val HEADER_DOC = arrayOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
        "Cache-Control" to "no-cache",
        "Connection" to "keep-alive",
        "DNT" to "1",
        "Pragma" to "no-cache",
        "Referer" to "https://www.feijix.com/",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0",
        "sec-ch-ua" to "\"Microsoft Edge\";v=\"135\", \"Not-A.Brand\";v=\"8\", \"Chromium\";v=\"135\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    /** 解析分享链接，返回 JSON：url/name/fileId。失败抛 IllegalStateException。 */
    fun resolve(shareUrl: String): String {
        val shareKey = shareUrl.trim().trimEnd('/').substringAfterLast('/')
        val files = listFiles(shareKey)
        if (files.length() == 0) error("分享内没有可下载的文件（shareKey=$shareKey）")
        val first = files.getJSONObject(0)
        return resolveFile(shareKey, first.getString("fileId"), first.optString("userId", "null"), first.optString("name"))
    }

    /** 列出分享内文件：recommend/list → 顶层 list[0]，fileIds/fileList。 */
    fun listFiles(shareKey: String): JSONArray {
        val uuid = fjUuid()
        val ts = enc(System.currentTimeMillis().toString())
        // 1) VIP 预热：固定同一 uuid，裸请求无 header
        runCatching { raw("$API/buy/vip/list?devType=6&devModel=Chrome&uuid=$uuid&extra=2&timestamp=$ts", null) }
        // 2) recommend/list
        val body = JSONObject(
            raw(
                "$API/recommend/list?devType=6&devModel=Chrome&uuid=$uuid&extra=2&timestamp=$ts&shareId=$shareKey&type=0&offset=1&limit=60",
                HEADER0
            )
        )
        if (body.optInt("code") != 200) error("recommend/list 异常: ${body.optString("msg")}")
        val arr = JSONArray()
        val list = body.optJSONArray("list") ?: return arr
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val userId = if (item.isNull("userId")) "null" else item.optString("userId", "null")
            item.optString("fileIds").takeIf { it.isNotBlank() }?.let { fid ->
                arr.put(JSONObject().put("fileId", fid).put("userId", userId)
                    .put("name", item.optString("fileName", "")).put("size", item.optLong("fileSize", -1)))
            }
            item.optJSONArray("fileList")?.let { files ->
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    if (f.optInt("fileType", 1) == 2) continue // 目录跳过
                    val fid = f.optString("fileId", f.optString("fileIds", ""))
                    if (fid.isBlank()) continue
                    arr.put(JSONObject().put("fileId", fid).put("userId", userId)
                        .put("name", f.optString("fileName", "")).put("size", f.optLong("fileSize", -1)))
                }
            }
        }
        return arr
    }

    /** 对指定 fileId 解析直链：file/redirect，手动跟 302 拿 Location。 */
    fun resolveFile(shareKey: String, fileId: String, userId: String = "null", name: String = ""): String {
        val uuid = fjUuid()
        val now = System.currentTimeMillis().toString()
        val ts2 = enc(now)
        val downloadId = enc("$fileId|$userId")
        // timestamp 与 auth 必须使用同一个毫秒时间戳
        val auth = enc("$fileId|$now")
        val conn = (URL(
            "$API/file/redirect?downloadId=$downloadId&enable=1&devType=6&uuid=$uuid&timestamp=$ts2&auth=$auth&shareId=$shareKey"
        ).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000; readTimeout = 30_000
            for ((k, v) in HEADER_DOC) setRequestProperty(k, v)
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location")
        if (location.isNullOrBlank()) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(200)
            conn.disconnect()
            error("未取得直链（HTTP $code）${err?.let { "：$it" } ?: ""}。分享可能失效或需要提取码。")
        }
        conn.disconnect()
        return JSONObject().put("url", location).put("name", name).put("fileId", fileId).toString()
    }

    // ---- 加密与工具 ----

    /** FjTool 的 fjUuid：64 字符集（含 - _），21 位。 */
    private fun fjUuid(): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-_"
        val rnd = SecureRandom()
        return buildString(21) { repeat(21) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    /** AES-128-ECB / PKCS5Padding，hex 大写。 */
    private fun enc(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    private fun raw(url: String, headers: Array<Pair<String, String>>?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000; readTimeout = 30_000
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}