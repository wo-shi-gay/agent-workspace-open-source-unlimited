package com.agentpad.workspace

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentpad.workspace.ai.DshService
import com.agentpad.workspace.ui.*

/**
 * dsh web 内嵌页。
 *
 * 交互契约（用户定死）：
 *   · 默认：底部小窗（半屏，圆角，内嵌内核 WebView）
 *   · 点「放大」→ 全屏；全屏下点「还原」→ 回小窗
 *   · 全屏下连按两次系统返回 → 退出全屏
 *   · 单次返回 → WebView 走 goBack()，栈底才 finish()
 */
class DshWebActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val expanded = mutableStateOf(false)
    private val title = mutableStateOf("DeepSeek Harness")
    private val loadTick = mutableStateOf(0)

    /** 强制电脑 UI 模式：0=手机版，1024 / 1280 = 桌面基准宽度 */
    private val pcWidth = mutableStateOf(0)

    private fun prefs() = getSharedPreferences("dsh_web", MODE_PRIVATE)

    /** 全屏状态下第一次返回的时刻，用于判定「连按两次」 */
    private var firstBackAt = 0L
    private val doubleBackWindowMs = 2000L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pcWidth.value = prefs().getInt(KEY_PC_WIDTH, 0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (expanded.value) {
                    val now = System.currentTimeMillis()
                    if (now - firstBackAt <= doubleBackWindowMs) {
                        firstBackAt = 0L
                        expanded.value = false          // 连按两次 → 退全屏
                        applyExpanded(false)
                    } else {
                        firstBackAt = now               // 第一次返回 → 提示再按一次
                        android.widget.Toast.makeText(
                            this@DshWebActivity,
                            "再按一次退出全屏",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return
                }
                val wv = webView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()                          // 小窗态：正常浏览器返回
                } else {
                    finish()
                }
            }
        })

        setContent { AgentTheme { Content() } }
    }

    private fun applyExpanded(open: Boolean) {
        // 用 Compose 状态驱动，高度靠 animateFloat 过渡
        expanded.value = open
    }

    @Composable
    private fun Content() {
        val targetFraction = if (expanded.value) 1f else 0.52f
        val fraction by animateFloatAsState(targetFraction, label = "dsh-height")

        Box(Modifier.fillMaxSize().background(if (expanded.value) AColors.Paper else Color(0x66000000))) {

            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .align(Alignment.BottomCenter)
                    .then(
                        if (expanded.value) Modifier
                        else Modifier
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                            .background(AColors.Paper)
                    )
            ) {
                // ---- 顶栏 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(AColors.Surface)
                        .then(
                            if (expanded.value) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                            else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.weight(1f)
                    ) {
                        Column {
                            Text(
                                title.value,
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = AColors.Ink,
                                maxLines = 1,
                            )
                            Text(
                                DshService.status(this@DshWebActivity).let {
                                    if (it.running) it.url else it.message
                                },
                                fontSize = 10.5.sp, color = AColors.Faint, maxLines = 1,
                            )
                        }
                    }

                    // ---- 强制电脑 UI 模式：手机版 → PC1024 → PC1280 循环 ----
                    Text(
                        when (pcWidth.value) {
                            1024 -> "PC1024"
                            1280 -> "PC1280"
                            else -> "手机版"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (pcWidth.value > 0) AColors.Accent else AColors.InkSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { cyclePcWidth() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    // ---- 外部浏览器打开（带 token，系统浏览器没有我们注入的 cookie）----
                    Text(
                        "外部",
                        fontSize = 11.sp,
                        color = AColors.InkSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { openExternal() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "刷新",
                        tint = AColors.InkSoft,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { loadTick.value++ }
                            .padding(7.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = if (expanded.value) "还原" else "放大",
                        tint = if (expanded.value) AColors.Faint else AColors.Accent,
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { applyExpanded(!expanded.value) }
                            .padding(7.dp),
                    )
                }

                // ---- WebView ----
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(if (expanded.value) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                        .then(if (expanded.value) Modifier else Modifier.clip(RoundedCornerShape(ADimens.CardRadius)))
                        .background(Color.White),
                ) {
                    key(loadTick.value) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).also { wv ->
                                    webView = wv
                                    setupWebView(wv)
                                    wv.loadUrl(DshService.status(this@DshWebActivity).url)
                                }
                            },
                        )
                    }
                }

                // ---- 底部提示（仅小窗态）----
                if (!expanded.value) {
                    Text(
                        "双击放大 · 返回键回上一页",
                        fontSize = 10.5.sp, color = AColors.Faint,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 8.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            // 强制电脑 UI：UA 必须整体替换，只加后缀没用（站点按 Mobile 关键字判定）
            if (pcWidth.value > 0) {
                userAgentString = UA_DESKTOP
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = "$userAgentString AgentPad/1.0"
            }
        }
        // cookie 已在 DshService 注入，这里确保落盘
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
            flush()
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val v = view ?: return
                // 页面刚开始渲染就下发 viewport/指纹修正，避免先按手机排版再跳版
                injectRenderMode(v)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.title?.takeIf { it.isNotBlank() }?.let { title.value = it }
                injectRenderMode(view)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, t: String?) {
                if (!t.isNullOrBlank()) title.value = t
            }
        }
    }

    /** 用系统浏览器打开。必须用带 token 的启动链接：系统浏览器是独立 cookie 容器，
     *  拿不到我们注入 WebView 的 dsh-auth-*，直接用干净 URL 会 401。 */
    private fun openExternal() {
        val url = DshService.tokenUrl() ?: DshService.status(this@DshWebActivity).url
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            android.widget.Toast.makeText(
                this@DshWebActivity,
                "没有可用浏览器：${it.message}",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 手机版 → PC1024 → PC1280 循环。必须重建 WebView，UA 才会进请求头。 */
    private fun cyclePcWidth() {
        pcWidth.value = when (pcWidth.value) {
            0 -> 1024
            1024 -> 1280
            else -> 0
        }
        prefs().edit().putInt(KEY_PC_WIDTH, pcWidth.value).apply()
        loadTick.value++
    }

    /**
     * 强制电脑 UI 渲染 —— 五层一起上，不是只改请求头：
     *   1) UA 整体换成桌面 Chrome（不做后缀拼接，站点按 Mobile 关键字判定）
     *   2) viewport 钉成固定 1280px 宽 → WebView 按桌面版式排版后整体缩放适配屏幕
     *   3) CSS 兜底 html/body min-width:1280px，防站点用响应式把内容压回手机布局
     *   4) 抹掉触摸/平台指纹：maxTouchPoints=0、platform=Win32、userAgentData.mobile=false
     *   5) MutationObserver 盯住 <head>：站点自己把 viewport 改回去时立刻纠正
     */
    private fun injectRenderMode(wv: WebView) {
        val w = pcWidth.value
        if (w <= 0) {
            wv.evaluateJavascript(JS_PC_OFF, null)
            return
        }
        wv.evaluateJavascript(JS_PC_ON.replace("__WIDTH__", w.toString()), null)
    }

    private companion object {
        const val KEY_PC_WIDTH = "pc_width"

        const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val JS_PC_ON = """
            (function(){
              try{
                var W = __WIDTH__;
                function fit(){
                  var w = window.innerWidth || 0;
                  if(!w) return;
                  var k = w / W;
                  if(k > 1) k = 1;
                  // 用 transform 缩放：不参与布局计算，flex / 百分比宽度不会被压乱
                  // （zoom 会让 Chrome 的 flex 子项按缩放后宽度重算，导致文字被挤成竖排）
                  var h = document.documentElement;
                  h.style.width = W + 'px';
                  h.style.minWidth = W + 'px';
                  h.style.transformOrigin = '0 0';
                  h.style.transform = 'scale(' + k + ')';
                  h.style.minHeight = (100 / k) + 'vh';
                  if(document.body) document.body.style.minWidth = W + 'px';
                }
                window.__pc_fit = fit;
                function apply(){
                  var h = document.head || document.documentElement;
                  if(!h) return;
                  // viewport 保持 device-width，缩放交给 zoom 精确控制（改 meta 缩放 WebView 不会重排）
                  var m = document.querySelector('meta[name="viewport"]');
                  if(!m){ m = document.createElement('meta'); m.setAttribute('name','viewport'); h.appendChild(m); }
                  m.setAttribute('content','width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes');
                  var s = document.getElementById('__pc_force');
                  if(!s){ s = document.createElement('style'); s.id = '__pc_force'; h.appendChild(s); }
                  s.textContent = 'html,body{min-width:'+W+'px !important;}';
                  fit();
                }
                apply();
                try{ Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true}); }catch(e){}
                try{ Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;},configurable:true}); }catch(e){}
                try{ Object.defineProperty(navigator,'userAgentData',{get:function(){return {mobile:false,platform:'Windows',brands:[]};},configurable:true}); }catch(e){}
                if(!window.__pc_rs){
                  window.__pc_rs = true;
                  window.addEventListener('resize', function(){ if(window.__pc_fit) window.__pc_fit(); });
                  window.addEventListener('orientationchange', function(){ setTimeout(function(){ if(window.__pc_fit) window.__pc_fit(); }, 120); });
                }
                if(!window.__pc_obs){
                  var mo = new MutationObserver(function(){
                    var v = document.querySelector('meta[name="viewport"]');
                    var c = v ? (v.getAttribute('content')||'') : '';
                    if(!v || c.indexOf('device-width') < 0 || !document.getElementById('__pc_force')) apply();
                    else if(window.__pc_fit) window.__pc_fit();
                  });
                  mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
                  window.__pc_obs = mo;
                }
              }catch(e){}
            })();
        """.trimIndent()

        val JS_PC_OFF = """
            (function(){
              try{
                var m = document.querySelector('meta[name="viewport"]');
                if(m) m.setAttribute('content','width=device-width, initial-scale=1.0');
                var s = document.getElementById('__pc_force');
                if(s) s.remove();
                var h = document.documentElement;
                h.style.transform = '';
                h.style.transformOrigin = '';
                h.style.width = '';
                h.style.minWidth = '';
                h.style.minHeight = '';
                if(document.body) document.body.style.minWidth = '';
                window.__pc_fit = null;
                if(window.__pc_obs){ window.__pc_obs.disconnect(); window.__pc_obs = null; }
              }catch(e){}
            })();
        """.trimIndent()
    }

    override fun onDestroy() {
        runCatching {
            webView?.let {
                it.loadUrl("about:blank")
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        }
        webView = null
        super.onDestroy()
    }
}