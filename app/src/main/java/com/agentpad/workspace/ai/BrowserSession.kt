// 持久化浏览器会话 —— 「AI 后台渲染 + 随时交给用户」的核心。
//
// 【核心约束：WebView 永不搬家】
//   WebView 的渲染状态（WebGL context、JS 堆、滚动位置、表单已填内容、
//   验证码进度）全部绑定在 View 对象上。只要不 removeView / 不改父容器，
//   状态就永远不丢。反过来，任何一次 removeView 都会让 Chromium 收到
//   onWindowVisibilityChanged(INVISIBLE)，requestAnimationFrame 立刻停摆。
//
//   所以这里只做两件事：
//     1. attach(slot)  —— 把 WebView 挂进 ai_browser_slot，仅一次
//     2. 其余全是状态查询 + JS 读取
//   「藏 / 露」由 ChatActivity 用 bringToFront 调整 z 序完成，与这里无关。
//
// 【线程】
//   WebView 的所有操作必须在主线程。AI 工具跑在后台线程，
//   所以这里统一 post 到主线程再阻塞等结果。

package com.agentpad.workspace.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一次持久化浏览会话的可观测状态。
 *
 * @param alive      是否已创建
 * @param url        当前页面 URL（随重定向更新）
 * @param title      当前页面标题
 * @param loading    是否还在加载中
 * @param handed     控制权是否在用户手上
 * @param challenge  最近一次正文是否像人机验证页
 * @param textLength 最近一次抓到的正文长度
 */
data class BrowserState(
    val alive: Boolean,
    val url: String,
    val title: String,
    val loading: Boolean,
    val handed: Boolean,
    val challenge: Boolean,
    val textLength: Int,
)

object BrowserSession {

    private val main = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var slotContainer: FrameLayout? = null

    @Volatile private var currentUrl: String = ""
    @Volatile private var currentTitle: String = ""
    @Volatile private var loading: Boolean = false
    @Volatile private var progress: Int = 0
    @Volatile private var lastText: String = ""
    @Volatile private var handedToUser: Boolean = false
    @Volatile private var lastChallenge: Boolean = false

    /** 用户点了「交给 AI」后置位，AI 下次 poll 消费一次。 */
    @Volatile private var userReturned: Boolean = false
    @Volatile private var handoffReason: String = ""

    /** 主文档加载完成的信号（每次 load 重置）。 */
    @Volatile private var pageDoneLatch: CountDownLatch = CountDownLatch(0)

    /** 页面进度 / 标题变化时回调给 Activity 刷新顶栏。 */
    @Volatile var onUiStateChanged: (() -> Unit)? = null

    val isAlive: Boolean get() = webView != null

    fun state(): BrowserState = BrowserState(
        alive = webView != null,
        url = currentUrl,
        title = currentTitle,
        loading = loading,
        handed = handedToUser,
        challenge = lastChallenge,
        textLength = lastText.length,
    )

    /** 当前加载进度 0-100，给顶栏进度条用。 */
    fun progressPercent(): Int = progress

    /**
     * 把 WebView 挂进 slot。**必须主线程**，且只在首次真正创建 WebView。
     *
     * 幂等：如果 WebView 已存在但父容器变了（Activity 重建），
     * 会重新挂一次 —— 这是唯一允许的「搬家」场景，因为旧容器已经没了。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(slot: FrameLayout) {
        slotContainer = slot
        webView?.let { wv ->
            if (wv.parent !== slot) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                slot.addView(wv, frameParams())
            }
            return
        }
        val ctx: Context = slot.context
        val wv = WebView(ctx)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // UA 用内核真实值，不写死
            userAgentString = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("")
            // 安全：不允许读本地文件、不许 file:// 提权
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = false
        }
        wv.isVerticalScrollBarEnabled = false

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                url?.let { currentUrl = it }
                onUiStateChanged?.invoke()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                url?.let { currentUrl = it }
                currentTitle = view?.title.orEmpty()
                pageDoneLatch.countDown()
                onUiStateChanged?.invoke()
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?,
            ) {
                // 证书有问题直接断，绝不 proceed —— 防止中间人篡改内容喂给 AI
                handler?.cancel()
                loading = false
                pageDoneLatch.countDown()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?,
            ): Boolean {
                // 只放行 http/https，其余（intent://、market:// 等）一律拦掉
                val s = request?.url?.toString().orEmpty()
                return !(s.startsWith("http://") || s.startsWith("https://"))
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?,
            ): WebResourceResponse? = null
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                onUiStateChanged?.invoke()
            }

            // 弹窗一律吞掉，不让恶意页阻塞 UI 线程
            override fun onJsAlert(v: WebView?, u: String?, msg: String?, r: JsResult?): Boolean {
                r?.cancel(); return true
            }
            override fun onJsConfirm(v: WebView?, u: String?, msg: String?, r: JsResult?): Boolean {
                r?.cancel(); return true
            }
            override fun onJsPrompt(
                v: WebView?, u: String?, msg: String?, def: String?,
                r: android.webkit.JsPromptResult?,
            ): Boolean {
                r?.cancel(); return true
            }

            // 摄像头 / 麦克风 / 定位请求全部拒绝
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }
        }

        // 下载全部拦截，不落盘
        wv.setDownloadListener { _, _, _, _, _ -> }
        // 禁掉长按选中（用户接管时不想误触系统菜单）
        wv.setOnLongClickListener { false }

        slot.addView(wv, frameParams())
        webView = wv
    }

    private fun frameParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    /**
     * 加载 URL。**必须主线程**。
     * loadUrl 立即返回，是否加载完由 pollText 自己等。
     */
    fun load(url: String) {
        val wv = webView ?: return
        currentUrl = url
        currentTitle = ""
        lastText = ""
        lastChallenge = false
        userReturned = false
        handoffReason = ""
        handedToUser = false
        progress = 0
        pageDoneLatch = CountDownLatch(1)
        loading = true
        onUiStateChanged?.invoke()
        wv.loadUrl(url, browserHeaders(wv))
    }

    /** 和 WebPageFetcher 一致的浏览器导航头。 */
    private fun browserHeaders(wv: WebView): Map<String, String> {
        val ua = wv.settings.userAgentString.orEmpty()
        val major = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "131"
        return linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Chromium\";v=\"$major\", \"Not_A Brand\";v=\"24\", " +
                "\"Google Chrome\";v=\"$major\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
        )
    }

    /**
     * 等页面加载完（最多 [maxWaitMs]），读正文。
     *
     * AI 工具 `browser_poll` 的实现体，后台线程调用，
     * 内部切主线程 + 阻塞等待，对外同步。
     *
     * @return (正文, 是否像验证页)；会话不存在时 (null, false)
     */
    fun pollText(maxWaitMs: Long): Pair<String?, Boolean> {
        val wv = webView ?: return null to false
        runCatching { pageDoneLatch.await(maxWaitMs, TimeUnit.MILLISECONDS) }
        // 给 JS 一点时间把异步内容填进来
        Thread.sleep(POLL_SETTLE_MS)
        val text = readBodyText(wv) ?: return null to false
        lastText = text
        lastChallenge = looksLikeChallenge(text, currentTitle, currentUrl)
        return text to lastChallenge
    }

    /** 主线程同步读 document.body.innerText。 */
    private fun readBodyText(wv: WebView): String? {
        var out: String? = null
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                wv.evaluateJavascript(JS_EXTRACT_TEXT) { raw ->
                    out = decodeJsString(raw)
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        return if (latch.await(15, TimeUnit.SECONDS)) out else null
    }

    /** evaluateJavascript 返回值是 JSON 字面量，解一层。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            org.json.JSONTokener(raw).nextValue() as? String ?: ""
        }.getOrDefault("")
    }

    // ---------- 控制权状态（真正的「藏/露」由 ChatActivity 的 z 序完成）----------

    /** 标记控制权已交给用户。仅改状态，不动 View。 */
    fun markHandedToUser(reason: String) {
        handedToUser = true
        handoffReason = reason
    }

    /** 用户点了「交给 AI」：清标记，置事件位，AI 下次 poll 会知道。 */
    fun markReturnedToAgent() {
        handedToUser = false
        userReturned = true
        webView?.let {
            currentUrl = it.url.orEmpty()
            currentTitle = it.title.orEmpty()
        }
    }

    /** AI 侧消费「用户已交还」事件（读一次就清）。 */
    fun consumeUserReturn(): Boolean {
        val v = userReturned
        userReturned = false
        return v
    }

    fun handoffReasonText(): String = handoffReason

    /** 销毁会话。改为顺序严格：先脱离树，再 stopLoading，最后 destroy。 */
    fun destroy() {
        val wv = webView ?: return
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
        webView = null
        slotContainer = null
        currentUrl = ""
        currentTitle = ""
        lastText = ""
        loading = false
        progress = 0
        handedToUser = false
        lastChallenge = false
        userReturned = false
        handoffReason = ""
    }

    // ---------- 验证页识别 ----------

    fun looksLikeChallenge(text: String, title: String, url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("/cdn-cgi/challenge-platform")) return true
        if (u.contains("captcha")) return true
        if (u.contains("verify") && (u.contains("challenge") || u.contains("human"))) return true

        val t = title.lowercase()
        CHALLENGE_TITLE_HINTS.forEach { if (t.contains(it)) return true }

        val body = text.lowercase()
        CHALLENGE_BODY_HINTS.forEach { if (body.contains(it)) return true }

        if (text.length in 1..120) {
            val tiny = text.trim()
            if (tiny.contains("验证") || tiny.contains("稍候") || tiny.contains("等待")) return true
        }
        return false
    }

    private const val POLL_SETTLE_MS = 600L

    private val CHALLENGE_TITLE_HINTS = listOf(
        "just a moment", "attention required", "access denied",
        "安全验证", "人机验证", "验证中心", "正在验证",
    )

    private val CHALLENGE_BODY_HINTS = listOf(
        "verify you are human", "checking your browser",
        "checking if the site connection is secure",
        "enable javascript and cookies to continue", "unusual traffic",
        "请完成安全验证", "请完成下方验证", "拖动滑块", "点击完成验证",
        "请输入验证码", "人机身份验证", "访问过于频繁", "检测到异常流量",
        "cloudflare ray id", "cf-challenge", "ddos protection by",
    )

    private const val JS_EXTRACT_TEXT = """
        (function(){
          try {
            var b = document.body;
            if (!b) return '';
            var t = b.innerText || b.textContent || '';
            return t.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
          } catch (e) { return ''; }
        })();
    """
}