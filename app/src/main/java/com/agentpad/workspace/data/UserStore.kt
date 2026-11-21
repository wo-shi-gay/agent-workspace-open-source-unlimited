package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 用户系统（v2 后端）：APK 直连自有后端（官网域名与后端域名已分离）。
 * - 发码：POST /api/send_code.php  {"email"}                            → 200
 * - 注册：POST /api/register.php   {"username","email","code","password"} → {"code":200,"data":{"token","username"}}
 * - 登录：POST /api/login.php      {"username","password"}（可填邮箱）    → {"code":200,"data":{"token","username"}}
 * - 心跳：POST /api/heartbeat.php  header Authorization: Bearer <token>  → {"code":200,"data":{"active_count"}}
 * token 持久化在 SharedPreferences，重启不丢；后端 token 有 30 天有效期。
 *
 * InfinityFree JS 安全挑战处理：非浏览器客户端会被 aes.js 挑战页拦截。
 * 处理逻辑：响应为挑战页时，提取 3 段 hex（a=key, b=?, c=ciphertext），
 * AES 解密 c 得 cookie 值，设置 __test cookie 后重试原请求。
 */
object UserStore {
    private const val PREF = "user_store"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USERNAME = "user_username"

    /** 自有后端基地址（v2 后端，HTTPS，与官网域名分离；example.com 解析国内全运营商可达） */
    const val BASE_URL = "https://example.com"

    /** 伪装浏览器 UA（网关对非浏览器 UA 有检查） */
    private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // ---------- 本地持久化 ----------

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""
    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "") ?: ""
    fun isLoggedIn(context: Context): Boolean = token(context).isNotBlank()

    fun saveUser(context: Context, token: String, username: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun updateToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ---------- InfinityFree JS 挑战处理 ----------

    /** 最近一次挑战解出的 __test cookie 值（进程内缓存，避免重复解密） */
    @Volatile
    private var cachedTestCookie: String? = null

    /** 挑战页特征：aes.js 引用 */
    private const val CHALLENGE_MARKER = "/aes.js"

    /**
     * 从挑战页 HTML 提取 3 段 hex 并解密 __test cookie 值。
     * 页面结构：var a=toNumbers("HEX1"),b=toNumbers("HEX2"),c=toNumbers("HEX3");
     * 算法：slowAES.decrypt(c, 2, a, b) = AES-CBC，key=a, iv=b，密文 c。
     */
    private fun solveChallenge(html: String): String? {
        return try {
            val regex = Regex("""toNumbers\("([0-9a-fA-F]{32})"\)""")
            val found = regex.findAll(html).map { it.groupValues[1] }.toList()
            if (found.size < 3) return null
            val key = found[0].hexToBytes()
            val iv = found[1].hexToBytes()
            val cipherText = found[2].hexToBytes()
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            val plain = cipher.doFinal(cipherText)
            plain.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** 判断响应体是否为挑战页 */
    private fun isChallenge(html: String): Boolean = html.contains(CHALLENGE_MARKER) && html.contains("toNumbers")

    // ---------- 网络层（同步阻塞，调用方放子线程） ----------

    /**
     * 发起 POST JSON 请求；遇挑战页自动解密并重试一次。
     * authToken 非空时附带 Authorization: Bearer 头（v2 后端认证方式）。
     * 返回 Pair(状态码, 响应文本)。
     */
    fun post(context: Context, path: String, body: JSONObject, authToken: String? = null): Pair<Int, String> {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Connection", "close")
            authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            cachedTestCookie?.let { setRequestProperty("Cookie", "__test=$it") }
        }
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(payload.size)
        conn.outputStream.use { it.write(payload) }
        val t0 = System.currentTimeMillis()
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        runCatching { AppLog.net("POST", BASE_URL + path, code, System.currentTimeMillis() - t0) }
        conn.disconnect()

        // 挑战页：解密 cookie 后重试一次
        if (isChallenge(text)) {
            val cookieVal = solveChallenge(text) ?: return Pair(code, text)
            cachedTestCookie = cookieVal
            val retry = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Connection", "close")
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Cookie", "__test=$cookieVal")
            }
            retry.setFixedLengthStreamingMode(payload.size)
            retry.outputStream.use { it.write(payload) }
            val t1 = System.currentTimeMillis()
            val rcode = retry.responseCode
            val rtext = (if (rcode in 200..299) retry.inputStream else retry.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            runCatching { AppLog.net("POST", BASE_URL + path, rcode, System.currentTimeMillis() - t1, "retry") }
            retry.disconnect()
            return Pair(rcode, rtext)
        }
        return Pair(code, text)
    }

    // ---------- 业务接口 ----------

    /** 统一解析后端响应为 JSONObject；非法 JSON 时构造 error 对象 */
    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("error", "服务器返回异常，请稍后再试"))

    /** 找回密码验证码 */
    fun sendResetCode(context: Context, email: String): String? {
        val body = JSONObject().put("action", "send").put("email", email.trim())
        val (code, text) = post(context, "/api/reset_password.php", body)
        val resp = parse(text)
        if (code in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "验证码发送失败（HTTP $code）")
    }

    /** 使用邮箱验证码重置密码 */
    fun resetPassword(context: Context, email: String, code: String, password: String): String? {
        val body = JSONObject().put("action", "reset").put("email", email.trim()).put("code", code.trim()).put("password", password)
        val (http, text) = post(context, "/api/reset_password.php", body)
        val resp = parse(text)
        if (http in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", "密码重置失败（HTTP $http）")
    }

    /** 登录：成功返回 null 并保存 token */
    fun login(context: Context, username: String, password: String): String? {
        val body = JSONObject().put("username", username.trim()).put("password", password)
        val (code, text) = post(context, "/api/login.php", body)
        val resp = parse(text)
        if (code in 200..299 && resp.optInt("code") == 200) {
            val data = resp.optJSONObject("data") ?: JSONObject()
            saveUser(context, data.optString("token"), data.optString("username", username.trim()))
            return null
        }
        return resp.optString("msg", resp.optString("error", "登录失败（HTTP $code）"))
    }

    /** 心跳（在线检测）：Bearer header 认证（v2 后端）。
     *  返回 Pair(是否成功, 活跃用户数或错误信息)。
     *  调用方（HeartbeatManager）收到成功后才启动 50 秒下一次心跳计时。 */
    fun heartbeat(context: Context): Pair<Boolean, String> {
        // Bearer header + body token 双通道（部分主机丢弃 Authorization 头）
        val body = JSONObject().put("token", token(context))
        val (code, text) = post(context, "/api/heartbeat.php", body, token(context))
        val resp = parse(text)
        if (code in 200..299 && resp.optInt("code") == 200) {
            val count = resp.optJSONObject("data")?.optString("active_count") ?: "?"
            return Pair(true, count)
        }
        // token 失效：清本地登录态，引导重登
        if (resp.optInt("code") == 401 || resp.optInt("code") == 403) {
            logout(context)
        }
        return Pair(false, resp.optString("msg", "心跳失败（HTTP $code）"))
    }

    /** 发送注册验证码：成功返回 null */
    fun sendCode(context: Context, email: String): String? {
        val body = JSONObject().put("email", email.trim())
        val (code, text) = post(context, "/api/send_code.php", body)
        val resp = parse(text)
        if (code in 200..299 && resp.optInt("code") == 200) return null
        return resp.optString("msg", resp.optString("error", "验证码发送失败（HTTP $code）"))
    }

    /** 验证码注册（注册即登录）：后端校验 code 后创建用户，成功返回 null 并保存 token */
    fun registerWithCode(context: Context, username: String, email: String, code: String, password: String): String? {
        val body = JSONObject()
            .put("username", username.trim())
            .put("email", email.trim())
            .put("code", code.trim())
            .put("password", password)
        val (code2, text) = post(context, "/api/register.php", body)
        val resp = parse(text)
        if (code2 in 200..299 && resp.optInt("code") == 200) {
            val data = resp.optJSONObject("data") ?: JSONObject()
            saveUser(context, data.optString("token"), data.optString("username", username.trim()))
            return null
        }
        return resp.optString("msg", resp.optString("error", "注册失败（HTTP $code2）"))
    }
}