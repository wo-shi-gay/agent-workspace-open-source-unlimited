// 网页工具：网页查看（含搜索）。
//
// 注册一个工具：
//   web_fetch    —— 用后台 WebView 内核渲染网页，等渲染完再提取正文
//
// 【为什么没有独立的 bing_search】
//   旧版有个 bing_search 工具，实测中文 query 会被截断（"张雪峰"→只搜"张"），
//   根因是 UTF-8 多字节在参数链路上被按字节截断，英文正常、中文必坏，属于
//   无法在应用层可靠规避的缺陷。而 web_fetch 是万能通道：直接访问
//   https://cn.bing.com/search?q=<关键词> 就能搜索，中文由 url 自动编码，
//   还能顺带拿到真实结果页的正文字，比包一层结构化解析更可靠。
//   所以删掉 bing_search，搜索统一走 web_fetch。
//
// 【设计要点】
//   · settle_seconds / timeout_seconds 由 AI 自定义 —— 主人明确要求
//     "就算没渲染完成，可以让 AI 自由自定义访问时长"
//   · url 里的中文自动做 percent-encoding（见 normalizeUrl），AI 直接传
//     中文关键词即可，不需要自己编码
//   · 返回结构化 JSON，正文超长会截断并标 truncated
//   · 明确区分"抓取失败"和"页面确实没内容"，不编造

package com.agentpad.workspace.ai

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网页工具实现。
 */
class WebTools(private val context: Context) {

    /**
     * web_fetch：渲染网页并提取正文。
     *
     * 参数：
     *   url                必填
     *   settle_seconds     渲染完再等几秒（默认 2，范围 0-30）
     *   timeout_seconds    总超时（默认 30，范围 5-120）
     *   auto_scroll        是否自动滚到底触发懒加载（默认 true）
     *   include_image_links 是否收集图片链接（默认 false）
     *   max_chars          正文最多返回多少字符（默认 30000）
     */
    fun fetch(args: JSONObject): String {
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isEmpty()) {
            return err("url 不能为空")
        }
        // 中文（以及任何非 ASCII 字符）在这里统一转成 percent-encoding。
        // 这样 AI 可以直接写 https://cn.bing.com/search?q=张雪峰，
        // 不用自己编码，也不会因为 URL 里有裸中文被服务端拒绝。
        val url = normalizeUrl(rawUrl)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return err("url 必须以 http:// 或 https:// 开头")
        }

        val settle = args.optInt("settle_seconds", 2).coerceIn(0, 30)
        val timeout = args.optInt("timeout_seconds", 30).coerceIn(5, 120)
        val autoScroll = args.optBoolean("auto_scroll", true)
        val includeImages = args.optBoolean("include_image_links", false)
        val maxChars = args.optInt("max_chars", 30_000).coerceIn(1_000, 200_000)

        val result = runBlocking {
            WebPageFetcher.fetch(
                context = context,
                url = url,
                settleSeconds = settle,
                timeoutSeconds = timeout,
                autoScroll = autoScroll,
                includeImageLinks = includeImages,
            )
        }

        return result.fold(
            onSuccess = { page ->
                var content = page.content
                val overLimit = content.length > maxChars
                if (overLimit) content = content.substring(0, maxChars)

                JSONObject().apply {
                    put("success", true)
                    put("mode", page.mode)
                    put("title", page.title)
                    put("url", page.url)
                    put("content", content)
                    put("content_length", page.content.length)
                    put("truncated", page.truncated || overLimit)
                    put("elapsed_ms", page.elapsedMs)
                    put("settle_seconds", settle)
                    // 元数据
                    if (page.meta.isNotEmpty()) {
                        put("meta", JSONObject(page.meta as Map<*, *>))
                    }
                    // 链接（只给 AI 前 50 条，避免占满上下文）
                    if (page.links.isNotEmpty()) {
                        put("link_count", page.links.size)
                        put("links", JSONArray().apply {
                            page.links.take(50).forEach { (u, t) ->
                                put(JSONObject().put("url", u).put("text", t))
                            }
                        })
                    }
                    if (page.imageLinks.isNotEmpty()) {
                        put("image_count", page.imageLinks.size)
                        put("image_links", JSONArray().apply {
                            page.imageLinks.take(30).forEach { put(it) }
                        })
                    }
                    if (content.isBlank()) {
                        put("warning", "页面渲染完成但正文为空。可能是纯图片页、需要登录，或反爬拦截。")
                    }
                }.toString()
            },
            onFailure = { e ->
                err("抓取失败：${e.message ?: e.javaClass.simpleName}")
            },
        )
    }

    /**
     * 把 URL 里的非 ASCII 字符（中文、日文、空格、全角符号等）转成
     * percent-encoding，让 https://cn.bing.com/search?q=张雪峰 这种写法可用。
     *
     * 规则：
     *   · 已经是 %XX 的部分原样保留，不会二次编码
     *   · 只处理 URL 里「不该出现裸非 ASCII」的位置，但为了简单可靠，
     *     对整串逐字符判断：ASCII 可打印字符原样保留，其余一律编码
     *   · 空格转 %20（不是 +），因为 path/query 都安全
     *
     * 为什么不用 URLEncoder.encode(整串)：那个会把 ":" "/" "?" "&" "="
     * 一起编码掉，URL 结构就废了。必须逐字符处理。
     */
    private fun normalizeUrl(raw: String): String {
        // 纯 ASCII 直接返回，零开销（绝大多数 URL 都是这种）
        if (raw.all { it.code in 0x21..0x7E }) return raw
        val sb = StringBuilder(raw.length + 32)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                // 已编码的 %XX 原样保留，避免 %25E5 这种二次编码
                c == '%' && i + 2 < raw.length &&
                    isHex(raw[i + 1]) && isHex(raw[i + 2]) -> {
                    sb.append(raw, i, i + 3); i += 3
                }
                // ASCII 可见字符 + 保留字符原样通过
                c.code in 0x21..0x7E -> { sb.append(c); i++ }
                // 空格 → %20
                c == ' ' -> { sb.append("%20"); i++ }
                // 其余全部按 UTF-8 字节 percent-encode
                else -> {
                    // 用代理对正确处理 emoji 等 4 字节字符
                    val end = if (Character.isHighSurrogate(c) && i + 1 < raw.length) i + 2 else i + 1
                    val bytes = raw.substring(i, end).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(HEX[(b.toInt() shr 4) and 0xF])
                        sb.append(HEX[b.toInt() and 0xF])
                    }
                    i = end
                }
            }
        }
        return sb.toString()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun err(msg: String): String = JSONObject().apply {
        put("success", false)
        put("error", msg)
    }.toString()

    companion object {
        /** 注册到 ToolRegistry。 */
        fun register(registry: ToolRegistry, context: Context) {
            val impl = WebTools(context)
            registry.register("web_fetch") { args -> impl.fetch(args) }
        }
    }
}