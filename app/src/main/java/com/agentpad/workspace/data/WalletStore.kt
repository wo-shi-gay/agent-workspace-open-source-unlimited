package com.agentpad.workspace.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 钱包与卡密（kami 平台用户端 API）：
 * - GET  /kami/api/wallet          Bearer 用户token → {code:200,data:{balance,total}}
 * - POST /kami/api/redeem          {card, token}    → {code:200,data:{denom,balance}}
 *
 * 兑换走 body 传 token（服务端 handleRedeem 从 body 取，不读 Authorization 头）。
 * 网关响应统一为 {code, msg, data}；HTTP 层恒 200，业务码看 code 字段。
 */
object WalletStore {
    private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 卡密购买页（用户跳浏览器购买） */
    const val SHOP_URL = ChatStore.HUB_SHOP_URL

    data class Wallet(val balance: Double, val total: Double)

    private fun http(url: String, method: String, body: String?, bearer: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (body != null) doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Pair(code, text)
    }

    private fun parse(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("code", -1).put("msg", "服务器返回异常，请稍后再试"))

    /** 查询余额（同步，调用方放子线程）。失败抛 IllegalStateException(msg) */
    fun wallet(token: String): Wallet {
        val (_, text) = http("${UserStore.BASE_URL}/kami/api/wallet", "GET", null, token)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "查询失败"))
        val data = resp.optJSONObject("data") ?: JSONObject()
        return Wallet(data.optDouble("balance", 0.0), data.optDouble("total", 0.0))
    }

    /** 兑换卡密（同步，调用方放子线程）。成功返回兑换后余额。失败抛 IllegalStateException(msg) */
    fun redeem(token: String, card: String): Wallet {
        val body = JSONObject().put("card", card.trim()).put("token", token)
        val (_, text) = http("${UserStore.BASE_URL}/kami/api/redeem", "POST", body.toString(), null)
        val resp = parse(text)
        if (resp.optInt("code") != 200) error(resp.optString("msg", "兑换失败"))
        val data = resp.optJSONObject("data") ?: JSONObject()
        return Wallet(data.optDouble("balance", 0.0), 0.0)
    }
}
