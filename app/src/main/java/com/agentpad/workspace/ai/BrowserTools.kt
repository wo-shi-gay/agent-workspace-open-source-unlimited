// AI 浏览器工具：让 AI 在后台渲染网页、轮询正文、撞上验证时交给用户。
//
// 四个工具：
//   browser_open    打开 URL（后台渲染，用户看不见）
//   browser_poll    轮询当前正文（可等加载完成），返回 challenge 标记
//   browser_handoff 把当前页面交给用户手动处理（弹窗让用户确认）
//   browser_close   关闭会话，回收 WebView
//
// 【为什么不复用 web_fetch】
//   web_fetch 是「一次性抓完就走」，每次都新建 WebView、抓完立刻销毁。
//   这里需要的是「持续存在的浏览器」：同一页面可以被反复读、
//   可以被用户接管、用户处理完 AI 还能接着读同一个页面。
//   两者的生命周期模型根本不同，所以独立成一套工具。

package com.agentpad.workspace.ai

import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

object BrowserTools {

    private val main = Handler(Looper.getMainLooper())

    /** 当前活跃的 ChatActivity，用来弹窗 + 切界面。由 Activity 自己在 onResume 时登记。 */
    @Volatile private var activity: Activity? = null

    fun bindActivity(a: Activity?) {
        activity = a
    }

    private fun ok(json: JSONObject): String = json.put("success", true).toString()
    private fun err(msg: String): String =
        JSONObject().put("success", false).put("error", msg).toString()

    // ---------------- browser_open ----------------

    fun open(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isEmpty()) return err("url 不能为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return err("url 必须以 http:// 或 https:// 开头")
        }
        val act = activity ?: return err("当前没有可用的对话界面，无法创建浏览器会话。请让用户先回到聊天页面。")

        // 已经在同一个 URL 上 → 直接复用，不重新加载（保住页面状态）
        if (BrowserSession.isAlive && BrowserSession.state().url == url) {
            return ok(JSONObject().apply {
                put("reused", true)
                put("note", "会话已存在于这个 URL，直接复用，页面状态保持不变。")
                put("url", url)
            })
        }

        val done = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        main.post {
            try {
                if (!BrowserSession.isAlive) {
                    // 需要 Activity 里的 slot 才能建 WebView。Activity 负责把它挂到布局上。
                    val opened = (act as? BrowserSlotProvider)?.ensureBrowserAttached() ?: false
                    if (!opened) {
                        failure.set("界面还没准备好浏览器容器")
                        return@post
                    }
                }
                BrowserSession.load(url)
            } catch (e: Throwable) {
                failure.set(e.message ?: e.javaClass.simpleName)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
            return err("创建会话超时")
        }
        failure.get()?.let { return err("打开失败：$it") }

        return ok(JSONObject().apply {
            put("url", url)
            put("note", "已在后台开始渲染。用 browser_poll 读取正文；" +
                "如果 poll 返回 challenge=true，可调用 browser_handoff 交给用户处理。")
        })
    }

    // ---------------- browser_poll ----------------

    fun poll(args: JSONObject): String {
        if (!BrowserSession.isAlive) {
            return err("没有活跃的浏览器会话，请先调用 browser_open。")
        }
        val waitSec = args.optInt("wait_seconds", 15).coerceIn(0, 120)
        val maxChars = args.optInt("max_chars", 20000).coerceIn(500, 100000)

        val st0 = BrowserSession.state()
        val polled = BrowserSession.pollText(waitSec * 1000L)
        val text: String = polled.first ?: return err("读取页面正文失败（会话可能已销毁）")
        val challenge: Boolean = polled.second

        val st = BrowserSession.state()
        var content = text
        val overLimit = content.length > maxChars
        if (overLimit) content = content.substring(0, maxChars)

        return ok(JSONObject().apply {
            put("url", st.url)
            put("title", st.title)
            put("loading", st.loading)
            put("content", content)
            put("content_length", text.length)
            put("truncated", overLimit)
            put("challenge", challenge)
            put("handed_to_user", st.handed)
            // 用户在上一次 poll 之后点了「交给 AI」→ 告诉 AI 可以接着读了
            put("user_returned", BrowserSession.consumeUserReturn())
            if (st0.url != st.url) put("redirected_from", st0.url)
            if (challenge) {
                put("challenge_hint",
                    "这一页看起来是人机验证 / 反爬拦截页。你可以调用 browser_handoff " +
                    "把页面交给用户手动完成，用户点「交给 AI」后你再 poll 就能读到处理后的内容。")
            }
            if (st.loading) {
                put("note", "页面还在加载，可以稍后再 poll 一次。")
            }
        })
    }

    // ---------------- browser_handoff ----------------

    /**
     * 弹窗问用户「要不要接手这个页面」，用户点接受才真正切界面。
     *
     * 这里刻意做成两步（先弹窗、再切），因为直接把用户界面抢过去太粗暴 ——
     * 用户可能正在看别的消息。
     */
    fun handoff(args: JSONObject): String {
        if (!BrowserSession.isAlive) {
            return err("没有活跃的浏览器会话，请先调用 browser_open。")
        }
        val act = activity ?: return err("当前没有可用的对话界面")
        val reason = args.optString("reason").trim()
            .ifBlank { "页面需要人工处理（可能是人机验证）" }

        val done = java.util.concurrent.CountDownLatch(1)
        var accepted: Boolean? = null
        main.post {
            try {
                val provider = act as? BrowserSlotProvider
                if (provider == null) {
                    accepted = false
                    return@post
                }
                provider.askUserToTakeOver(reason) { yes ->
                    accepted = yes
                    done.countDown()
                }
            } catch (e: Throwable) {
                accepted = false
                done.countDown()
            }
        }

        // 等用户点按钮，最长 180 秒（用户可能在忙）
        if (!done.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
            return err("等待用户响应超时（用户可能没看到弹窗）")
        }

        return if (accepted == true) {
            ok(JSONObject().apply {
                put("accepted", true)
                put("note", "用户已接手页面，正在手动处理。等用户点「交给 AI」后，" +
                    "再调用 browser_poll 就能读到处理后的内容。")
            })
        } else {
            ok(JSONObject().apply {
                put("accepted", false)
                put("note", "用户拒绝了接手。你可以换个来源（比如直接找页面 API、或用 web_fetch 抓静态版），" +
                    "或者就现有内容作答。")
            })
        }
    }

    // ---------------- browser_close ----------------

    fun close(args: JSONObject): String {
        val done = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching { BrowserSession.destroy() }
            done.countDown()
        }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return ok(JSONObject().put("note", "浏览器会话已关闭，WebView 已回收。"))
    }

    // ---------------- 注册 ----------------

    fun register(registry: ToolRegistry) {
        registry.register("browser_open") { args -> open(args) }
        registry.register("browser_poll") { args -> poll(args) }
        registry.register("browser_handoff") { args -> handoff(args) }
        registry.register("browser_close") { args -> close(args) }
    }
}

/**
 * ChatActivity 实现这个接口，给 BrowserTools 提供「界面能力」。
 *
 * 之所以用接口而不是直接依赖 ChatActivity，是为了让 ai 包不反向依赖
 * 具体的 Activity 实现，后面换成别的界面也不用改工具代码。
 */
interface BrowserSlotProvider {
    /** 确保 WebView 已挂到布局上（幂等）。返回是否成功。 */
    fun ensureBrowserAttached(): Boolean

    /** 弹窗问用户是否接手页面。回调在主线程。 */
    fun askUserToTakeOver(reason: String, onResult: (Boolean) -> Unit)
}