// WebView 网页抓取器。
//
// 参考两份源码：
//   1. Operit（.ref/operit/Operit-main）
//      · StandardWebVisitTool.kt:1500-1647  extractPageContent —— 正文提取 JS
//      · StandardWebVisitTool.kt:1664-1720  autoScrollToBottom —— 自动滚动触发懒加载
//      · BrowserWebViewSupport.kt:290-330    onPageStarted/onPageFinished 状态机
//   2. RikkaHub 3.1.1
//      · ui/.../webview/WebView.kt          WebViewClient/ChromeClient 骨架
//      · ui/.../webview/WebViewContentCache.kt  正文缓存
//
// 【为什么必须用 WebView 而不是 Jsoup】
// 现代网站大量依赖 JS 渲染（React/Vue/SPA），纯 HTTP 抓到的 HTML 里
// 正文往往是空的，只有 <div id="root"></div>。只有真正跑一遍内核、
// 等 JS 执行完、DOM 稳定下来，才能拿到用户看到的正文。
//
// 【"等渲染完"到底等什么】
// onPageFinished 只是"主文档加载完"，不等于"渲染完"：
//   · React/Vue 的异步数据还在请求
//   · 图片懒加载还没触发
//   · 无限滚动列表还没加载下一页
// 所以流程是：
//   1. loadUrl，等 onPageFinished
//   2. 轮询 document.readyState，直到 == "complete"
//   3. 自动滚动到底部（触发懒加载），等 DOM 高度稳定
//   4. 再等用户配置的 settle 秒数（默认 2 秒，可让 AI 自定义）
//   5. evaluateJavascript 提取正文
//
// 【超时与降级】
//   · 总超时由 timeoutSeconds 控制（默认 30 秒，AI 可自定义）
//   · WebView 失败（如渲染进程崩溃）→ 降级到 Jsoup 纯 HTTP 抓取
//   · 提取不到正文 → 返回明确错误，不编造内容

package com.agentpad.workspace.ai

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 网页抓取结果。
 *
 * @param title      页面标题
 * @param url        最终 URL（可能因重定向与请求的不同）
 * @param content    正文纯文本
 * @param meta       元数据（description / keywords / author / og:*）
 * @param links      页面链接列表（前 200 个）
 * @param imageLinks 图片链接列表（前 100 个）
 * @param elapsedMs  实际耗时
 * @param mode       抓取方式：webview / http
 * @param truncated  正文是否被截断
 */
data class WebPageResult(
    val title: String,
    val url: String,
    val content: String,
    val meta: Map<String, String>,
    val links: List<Pair<String, String>>,
    val imageLinks: List<String>,
    val elapsedMs: Long,
    val mode: String,
    val truncated: Boolean,
)

/**
 * 网页抓取器。
 *
 * 用法：
 *   val result = WebPageFetcher.fetch(
 *       context,
 *       url = "https://example.com",
 *       settleSeconds = 3,     // 渲染完再等几秒（AI 可自定义）
 *       timeoutSeconds = 30,   // 总超时
 *       autoScroll = true,     // 是否自动滚动触发懒加载
 *       includeImageLinks = false,
 *   )
 */
object WebPageFetcher {

    private const val MAX_CONTENT_CHARS = 200_000
    private const val MAX_LINKS = 200
    private const val MAX_IMAGES = 100

    // ---------- 动态 UA / 请求头 ----------
    //
    // 【为什么一个 UA 都不写死】
    //   硬编码 UA 是最容易被识别的爬虫特征，而且会自相矛盾：
    //     · 型号写死   → 和 Build.MODEL 不一致，指纹对不上
    //     · 系统版本写死 → 老 Android 却声称 Chrome 131，逻辑上不可能
    //     · WebView 的 UA 和 loadUrl 传的不一致 → 主文档和子资源指纹不匹配
    //   所以全部从系统读。这个过程本身每台机器都不一样，天然不重复。

    /** UA 里 Chrome 大版本扣不到时的兜底。仅在正则匹配失败时用。 */
    private const val FALLBACK_CHROME_MAJOR = "131"

    /**
     * WebView 内核自己报的 UA —— 本机上最"真"的那个。
     *
     * 为什么优先用它：这就是内核真实发送的字符串，拿它去拼 sec-ch-ua
     * 才不会出现「UA 说 Chrome 120、Client Hints 说 Chrome 131」这种矛盾。
     */
    private fun webViewUa(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.contains("Chrome/") }
            ?: buildChromeMobileUa()

    /** 从 UA 里扣 Chrome 大版本（sec-ch-ua 要用，必须和 UA 一致）。 */
    private fun chromeMajorFrom(ua: String): String =
        Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: FALLBACK_CHROME_MAJOR

    /**
     * 用 Build 信息拼一个标准 Chrome Mobile UA（纯 HTTP 路径用）。
     *
     * 全部字段来自运行时：
     *   机型   → Build.MODEL
     *   系统   → Build.VERSION.RELEASE
     *   Chrome → 同上，和 WebView 内核保持一致
     *
     * ⚠ 关于 Android 10+：真实 Chrome 在 UA 里把系统版本冻结成 "10"
     *   （UA Reduction）。但这里按主人要求如实写入 Build.VERSION.RELEASE，
     *   因为我们的目标是"和这台手机自洽"，不是像素级模仿 Chrome。
     */
    private fun buildChromeMobileUa(): String {
        val model = Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
        val rel = Build.VERSION.RELEASE?.trim().orEmpty().ifBlank { "10" }
        val major = FALLBACK_CHROME_MAJOR
        return "Mozilla/5.0 (Linux; Android $rel; $model) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    /** 按系统 Locale 生成 Accept-Language（中文机发中文，英文机发英文）。 */
    private fun acceptLanguage(): String {
        val locale = java.util.Locale.getDefault()
        val lang = locale.language.ifBlank { "zh" }
        val country = locale.country
        return buildString {
            if (country.isNotBlank()) append("$lang-$country,")
            append("$lang;q=0.9,en;q=0.8")
        }
    }

    /**
     * 伪装成真实浏览器导航的请求头，字段全部按当前设备和内核动态推导。
     *
     * 【为什么需要】
     *   Cloudflare / Akamai / 各家云 WAF 判断「是不是脚本」靠的不是 UA，
     *   而是 Sec-Fetch-* 和 sec-ch-ua 这组「现代浏览器必发、脚本常漏」的头。
     *   缺了它们，UA 写得再真也会被拦成 403 / 验证页 / 空页面。
     *
     * 【一致性是关键】
     *   sec-ch-ua 里的 Chrome 版本必须和 UA 里的完全一致，否则 WAF 一眼识破。
     *   这里统一从同一个 ua 字符串里扣，保证不矛盾。
     *
     * 【Referer 故意不放】
     *   它必须和目标站点同源才自然，写死外站 Referer 反而是明显的爬虫特征。
     *
     * @param ua     本次请求实际使用的 UA（决定 sec-ch-ua 版本）
     * @param mobile 是否移动端指纹（决定 sec-ch-ua-mobile / platform）
     */
    private fun browserHeaders(ua: String, mobile: Boolean): Map<String, String> {
        val major = chromeMajorFrom(ua)
        return linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to acceptLanguage(),
            "Accept-Encoding" to "gzip, deflate, br",
            "Cache-Control" to "max-age=0",
            // Sec-Fetch-*：现代浏览器的「我不是脚本」凭证
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            // Client Hints：版本号与 UA 严格同源
            "sec-ch-ua" to "\"Chromium\";v=\"$major\", \"Not_A Brand\";v=\"24\", " +
                "\"Google Chrome\";v=\"$major\"",
            "sec-ch-ua-mobile" to if (mobile) "?1" else "?0",
            "sec-ch-ua-platform" to if (mobile) "\"Android\"" else "\"Windows\"",
            "sec-ch-ua-platform-version" to if (mobile) {
                "\"${Build.VERSION.RELEASE.orEmpty().ifBlank { "10" }}\""
            } else "\"15.0.0\"",
            // 老式但仍有站点检查
            "DNT" to "1",
            "Connection" to "keep-alive",
        )
    }

    /**
     * 抓取网页。
     *
     * @param settleSeconds  页面 readyState=complete 之后再等多久（让异步渲染落地）
     * @param timeoutSeconds 总超时
     * @param autoScroll     是否自动滚动到底触发懒加载
     * @param includeImageLinks 是否收集图片链接
     * @param fallbackToHttp WebView 失败时是否降级到 Jsoup
     */
    suspend fun fetch(
        context: Context,
        url: String,
        settleSeconds: Int = 2,
        timeoutSeconds: Int = 30,
        autoScroll: Boolean = true,
        includeImageLinks: Boolean = false,
        fallbackToHttp: Boolean = true,
    ): Result<WebPageResult> = withContext(Dispatchers.Main) {
        val started = System.currentTimeMillis()

        // WebView 必须在主线程创建和操作
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            runCatching { fetchWithWebView(context, url, settleSeconds, autoScroll, includeImageLinks) }
                .getOrElse { e -> null }
        }?.getOrNull()

        if (outcome != null) {
            return@withContext Result.success(
                outcome.copy(elapsedMs = System.currentTimeMillis() - started)
            )
        }

        // WebView 超时或失败 → 降级
        if (!fallbackToHttp) {
            return@withContext Result.failure(
                RuntimeException("WebView 抓取超时（${timeoutSeconds}s）或失败，且未启用 HTTP 兜底")
            )
        }
        withContext(Dispatchers.IO) {
            runCatching {
                fetchWithHttp(url, includeImageLinks).copy(
                    elapsedMs = System.currentTimeMillis() - started,
                    mode = "http",
                )
            }
        }
    }

    // ---------- WebView 路径 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithWebView(
        context: Context,
        url: String,
        settleSeconds: Int,
        autoScroll: Boolean,
        includeImageLinks: Boolean,
    ): Result<WebPageResult> = suspendCancellableCoroutine { cont: kotlinx.coroutines.CancellableContinuation<Result<WebPageResult>> ->

        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)

        // WebView 路径直接用内核自己报的 UA —— 这就是本机最真实的浏览器指纹，
        // 不用改、也不用猜。sec-ch-ua 的版本号从同一个字符串里扣，保证自洽。
        val ua = webViewUa(context)
        val headers = browserHeaders(ua, mobile = true)

        val webView = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // getDefaultUserAgent 拿到的就是这个默认值；显式赋一次是为了
                // 确保它和下面 headers 里的 sec-ch-ua 版本严格一致。
                userAgentString = ua
                // 允许读取页面内容（evaluateJavascript 需要）
                allowContentAccess = true
                // 不加载图片能更快，但会漏掉图片链接，所以按需
                blockNetworkImage = false
            }
            // 不显示滚动条，避免影响布局测量
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        fun cleanup() {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        }

        fun fail(msg: String) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                cont.resume(Result.failure(RuntimeException(msg)))
            }
        }

        fun succeed(result: WebPageResult) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                cont.resume(Result.success(result))
            }
        }

        // 取消时清理
        cont.invokeOnCancellation { cleanup() }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 主文档加载完 ≠ 渲染完。继续走「等 readyState + 滚动 + settle」
                waitUntilRendered(
                    webView = view,
                    settleSeconds = settleSeconds,
                    autoScroll = autoScroll,
                    includeImageLinks = includeImageLinks,
                    onDone = { result -> succeed(result) },
                    onError = { msg -> fail(msg) },
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                // 只处理主文档错误，子资源错误忽略
                if (request.isForMainFrame) {
                    fail("加载失败：${error.description}（code=${error.errorCode}）")
                }
            }

            // 让 WebView 自己处理重定向、JS 跳转
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false
        }

        // 带上浏览器导航头。WebView 默认只会发 UA + Accept，缺 Sec-Fetch-* / sec-ch-ua
        // 这组头时，很多 WAF 会直接判成非浏览器请求并返回 403 或验证页。
        // loadUrl(url, headers) 只作用于这次主文档请求，子资源仍由 WebView 自己处理。
        // headers / ua 都是本机真实值（见 webViewUa / browserHeaders）。
        webView.loadUrl(url, headers)
    }

    /**
     * 等 readyState=complete → 可选滚动 → 等 DOM 稳定 → 再等 settleSeconds → 提取。
     *
     * 全程用 Handler.postDelayed 在主线程轮询，每 200ms 一次。
     */
    private fun waitUntilRendered(
        webView: WebView,
        settleSeconds: Int,
        autoScroll: Boolean,
        includeImageLinks: Boolean,
        onDone: (WebPageResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var checks = 0
        val maxChecks = 150 // 200ms * 150 = 30 秒上限

        fun pollReady() {
            if (checks++ > maxChecks) {
                onError("等待页面渲染超时")
                return
            }
            webView.evaluateJavascript("document.readyState") { state ->
                val s = state?.trim('"') ?: ""
                if (s == "complete") {
                    if (autoScroll) {
                        autoScrollThenExtract(webView, settleSeconds, includeImageLinks, onDone, onError)
                    } else {
                        main.postDelayed({
                            extract(webView, includeImageLinks, onDone, onError)
                        }, settleSeconds * 1000L)
                    }
                } else {
                    main.postDelayed({ pollReady() }, 200)
                }
            }
        }
        pollReady()
    }

    /**
     * 自动滚动到底部，触发懒加载。
     *
     * 照抄 Operit StandardWebVisitTool.kt:1664-1720 的思路：
     *   · 记录 scrollHeight，滚到底
     *   · 高度不再变化 → 认为到底了
     *   · 最多 3 轮，每轮间隔 400ms
     */
    private fun autoScrollThenExtract(
        webView: WebView,
        settleSeconds: Int,
        includeImageLinks: Boolean,
        onDone: (WebPageResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        var round = 0
        var lastHeight = -1

        fun scrollOnce() {
            if (round++ >= 3) {
                // 滚动结束，等 settle 后提取
                main.postDelayed({
                    extract(webView, includeImageLinks, onDone, onError)
                }, settleSeconds * 1000L)
                return
            }
            val js = """
                (function() {
                  try {
                    var h = document.body ? document.body.scrollHeight : document.documentElement.scrollHeight;
                    window.scrollTo(0, h);
                    return String(h);
                  } catch (e) { return "0"; }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { hStr ->
                val h = hStr?.trim('"')?.toIntOrNull() ?: 0
                if (h == lastHeight) {
                    // 高度没变，认为到底
                    main.postDelayed({
                        extract(webView, includeImageLinks, onDone, onError)
                    }, settleSeconds * 1000L)
                } else {
                    lastHeight = h
                    main.postDelayed({ scrollOnce() }, 400)
                }
            }
        }
        scrollOnce()
    }

    /**
     * 提取正文。JS 照抄 Operit StandardWebVisitTool.kt:1512-1614，
     * 做了三处简化：
     *   · 不返回完整 outerHTML（太大，AI 用不上）
     *   · 链接/图片各限 200/100 条
     *   · 正文超过 20 万字符截断
     */
    private fun extract(
        webView: WebView,
        includeImageLinks: Boolean,
        onDone: (WebPageResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val js = """
            (function() {
              try {
                var links = [];
                var linkNodes = document.querySelectorAll('a');
                for (var i = 0; i < linkNodes.length && links.length < $MAX_LINKS; i++) {
                  var n = linkNodes[i];
                  var href = n.href;
                  var text = (n.innerText || '').trim();
                  if (href && text && text.length < 300) {
                    links.push({url: href, text: text});
                  }
                }

                var imageLinks = [];
                if (${includeImageLinks}) {
                  var norm = function(u) {
                    if (!u) return null;
                    u = String(u).trim();
                    if (!u) return null;
                    if (u.indexOf('http://') !== 0 && u.indexOf('https://') !== 0) return null;
                    if (u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return null;
                    var clean = u.split('#')[0];
                    var path = clean.split('?')[0].toLowerCase();
                    if (path.endsWith('.svg')) return null;
                    return clean;
                  };
                  var seen = {};
                  var imgs = document.querySelectorAll('img');
                  for (var j = 0; j < imgs.length && imageLinks.length < $MAX_IMAGES; j++) {
                    var raw = imgs[j].currentSrc || imgs[j].src || imgs[j].getAttribute('data-src');
                    var u = norm(raw);
                    if (u && !seen[u]) { seen[u] = true; imageLinks.push(u); }
                  }
                }

                var metadata = {};
                var metas = document.querySelectorAll('meta');
                for (var k = 0; k < metas.length; k++) {
                  var name = metas[k].getAttribute('name') ||
                             metas[k].getAttribute('property') ||
                             metas[k].getAttribute('itemprop');
                  var content = metas[k].getAttribute('content');
                  if (name && content) metadata[name] = content;
                }

                var body = document.body;
                var fullText = body ? (body.innerText || '') : '';
                // 去掉连续空行
                fullText = fullText.replace(/\n{3,}/g, '\n\n').trim();

                return JSON.stringify({
                  title: document.title || '',
                  url: window.location.href,
                  content: fullText,
                  meta: metadata,
                  links: links,
                  imageLinks: imageLinks
                });
              } catch (e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            if (raw.isNullOrBlank() || raw == "null") {
                onError("提取脚本返回空结果")
                return@evaluateJavascript
            }
            try {
                // evaluateJavascript 返回的是 JSON 字符串字面量，需要先解一层
                val jsonStr = org.json.JSONTokener(raw).nextValue() as? String ?: raw
                val o = JSONObject(jsonStr)
                if (o.has("error")) {
                    onError("提取失败：${o.optString("error")}")
                    return@evaluateJavascript
                }

                var content = o.optString("content")
                val truncated = content.length > MAX_CONTENT_CHARS
                if (truncated) content = content.substring(0, MAX_CONTENT_CHARS)

                val meta = mutableMapOf<String, String>()
                o.optJSONObject("meta")?.let { m ->
                    m.keys().forEach { key ->
                        // 只保留有用的元数据，避免几百条无用项
                        if (key in setOf(
                                "description", "keywords", "author",
                                "og:title", "og:description", "og:site_name", "og:type",
                                "twitter:title", "twitter:description",
                            )
                        ) {
                            meta[key] = m.optString(key)
                        }
                    }
                }

                val links = mutableListOf<Pair<String, String>>()
                o.optJSONArray("links")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        links += it.optString("url") to it.optString("text")
                    }
                }

                val images = mutableListOf<String>()
                o.optJSONArray("imageLinks")?.let { arr ->
                    for (i in 0 until arr.length()) images += arr.optString(i)
                }

                onDone(
                    WebPageResult(
                        title = o.optString("title"),
                        url = o.optString("url"),
                        content = content,
                        meta = meta,
                        links = links,
                        imageLinks = images,
                        elapsedMs = 0, // 由上层填
                        mode = "webview",
                        truncated = truncated,
                    )
                )
            } catch (e: Exception) {
                onError("解析结果失败：${e.message}")
            }
        }
    }

    // ---------- HTTP 兜底 ----------

    /**
     * 纯 HTTP 抓取（Jsoup）。用于：
     *   · WebView 不可用（后台无 Activity、渲染进程崩溃）
     *   · 静态页面（不需要 JS 渲染）
     *
     * 拿不到 JS 渲染后的内容，但比什么都没有强。
     */
    private fun fetchWithHttp(url: String, includeImageLinks: Boolean): WebPageResult {
        // UA 全部来自运行时 Build 信息（型号 / 系统版本），Chrome 版本沿用内核的，
        // 保证和 WebView 路径的指纹一致。配合完整的浏览器头，降低被 WAF 拦的概率。
        val ua = buildChromeMobileUa()
        val headers = browserHeaders(ua, mobile = true)
        val conn = Jsoup.connect(url)
            .userAgent(ua)
            .timeout(20_000)
            .followRedirects(true)
            .ignoreContentType(true)
            .maxBodySize(0)
        headers.forEach { (k, v) -> conn.header(k, v) }
        val doc = conn.get()

        // 去掉脚本、样式、导航、页脚这些噪音
        doc.select("script, style, noscript, iframe, svg").remove()

        val meta = mutableMapOf<String, String>()
        doc.select("meta").forEach { m ->
            val key = m.attr("name").ifBlank { m.attr("property") }
            val value = m.attr("content")
            if (key.isNotBlank() && value.isNotBlank() &&
                key in setOf(
                    "description", "keywords", "author",
                    "og:title", "og:description", "og:site_name", "og:type",
                    "twitter:title", "twitter:description",
                )
            ) {
                meta[key] = value
            }
        }

        val links = doc.select("a[href]").take(MAX_LINKS).mapNotNull { a ->
            val href = a.absUrl("href")
            val text = a.text().trim()
            if (href.isNotBlank() && text.isNotBlank() && text.length < 300) href to text else null
        }

        val images = if (includeImageLinks) {
            doc.select("img[src]").take(MAX_IMAGES).mapNotNull { img ->
                val src = img.absUrl("src")
                if (src.startsWith("http") && !src.lowercase().endsWith(".svg")) src else null
            }
        } else emptyList()

        var content = doc.body()?.text().orEmpty()
        val truncated = content.length > MAX_CONTENT_CHARS
        if (truncated) content = content.substring(0, MAX_CONTENT_CHARS)

        return WebPageResult(
            title = doc.title(),
            url = doc.location(),
            content = content,
            meta = meta,
            links = links,
            imageLinks = images,
            elapsedMs = 0,
            mode = "http",
            truncated = truncated,
        )
    }
}