package com.agentpad.workspace.ai

import android.content.Context
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

/**
 * DeepSeek Harness (dsh) 部署服务。
 *
 * 运行形态：把 node + @deepseek-ai/dsh 整包解压进 sandbox rootfs 的 /opt/dshenv，
 * 通过 init-sandbox.sh 在 proot 里启动常驻 web 服务。
 *
 * 启动命令（实测可用）：
 *   node /opt/dshenv/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
 *     --profile web --no-open --host 127.0.0.1 --port 3080
 *
 * stdout 打印：dsh web: http://127.0.0.1:3080/?token=<43位base64url>
 * 用该 URL 请求一次 → 303 + Set-Cookie: dsh-auth-* → 注入 WebView。
 */
object DshService {

    const val HOST = "127.0.0.1"
    const val DEFAULT_PORT = 3080

    /** rootfs 内的安装根（相对 proot 内路径） */
    private const val ENV_ROOT_IN_GUEST = "/opt/dshenv"
    private const val NODE_BIN_IN_GUEST = "$ENV_ROOT_IN_GUEST/bin/node"
    private const val DSH_BIN_IN_GUEST = "$ENV_ROOT_IN_GUEST/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

    /**
     * 下载源：**两段分包**。整包 724MB，超过网盘免登录 500MB 上限，必须拆两包。
     *
     *   part1 ≈ 288.9MB：bin/node + dsh 本体 + 常规依赖（含 run.sh）
     *   part2 ≈ 435.5MB：node_modules/@deepseek-ai/ 下所有子包（libreoffice-wasm 一个占 152MB）
     *
     * 两个 zip 都是 store（零压缩），解压到同一 rootfs 目录并**合并覆盖**。
     * 可直接填小飞机分享链接（FeijipanParser 自动解析直链），也可填直链。
     *
     * 实测：435MB 的分享**不需要登录转存**，file/redirect 直接 302 给直链 —— 
     * FeijipanParser 里"大文件(>300MB)需登录转存"的旧注释已作废。
     */
    const val BUNDLE_URL = "https://example.com"
    const val BUNDLE_SHA256 = "cefc9de44130d14e4f025c3223aac608ce15a94379218bad13602da424bfc818"
    const val BUNDLE_URL2 = "https://example.com"
    const val BUNDLE_SHA2562 = "962bba91e1b4b3ab59c0ed50ca35818b681c81ed6a424d0b9d19f92a0619b28e"

    /** V8 编译缓存目录（guest 内）：node 二次启动明显更快 */
    private const val GUEST_CACHE_DIR = "$ENV_ROOT_IN_GUEST/v8cache"

    /** 上次鉴权结果持久化：进程还活着时可跳过等 token */
    private const val PREF = "dsh_web"
    private const val K_URL = "launch_url"
    private const val K_COOKIE = "launch_cookie"
    private const val K_PORT = "launch_port"

    private val TOKEN_RE = Regex("""(http://127\.0\.0\.1:(\d+)/)\?token=([A-Za-z0-9_-]{43})""")
    private val STARTUP_RE = Regex("""dsh web:\s*(\S+)""")
    /** 实测值形如 dsh-auth-<43位>=v1.<base64>.<签名>；payload 可能含 = + / ，所以放宽到 [^;]+ */
    private val COOKIE_RE = Regex("""dsh-auth-[A-Za-z0-9_-]{43}=v1\.[^;]+""")

    // ---------------- 状态 ----------------

    data class Status(
        val installed: Boolean,
        val running: Boolean,
        val port: Int,
        val url: String,
        val message: String,
    )

    data class Launch(val port: Int, val cleanUrl: String, val cookie: String)

    @Volatile private var process: Process? = null
    @Volatile private var launch: Launch? = null
    @Volatile private var ready = false

    /** 启动时打印的带 token 链接：给「外部浏览器」用（token 仅在进程生命周期内有效） */
    @Volatile private var startupUrl: String? = null

    fun tokenUrl(): String? = startupUrl

    private val log = ConcurrentLinkedDeque<String>()
    @Volatile private var listener: ((String) -> Unit)? = null

    // ---------------- 路径 ----------------

    /** 宿主机上的安装根目录，用于探活（与 guest 内 /opt/dshenv 对应） */
    private fun hostEnvRoot(context: Context): File =
        File(SandboxInstaller.rootfsDir(context), "opt/dshenv")

    private fun hostNode(context: Context): File =
        File(hostEnvRoot(context), "bin/node")

    private fun hostDshBin(context: Context): File =
        File(hostEnvRoot(context), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun installed(context: Context): Boolean =
        hostNode(context).isFile && hostDshBin(context).isFile

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun saveLaunch(context: Context, l: Launch) {
        prefs(context).edit()
            .putString(K_URL, l.cleanUrl)
            .putString(K_COOKIE, l.cookie)
            .putInt(K_PORT, l.port)
            .apply()
    }

    private fun savedLaunch(context: Context): Launch? {
        val p = prefs(context)
        val u = p.getString(K_URL, null) ?: return null
        val c = p.getString(K_COOKIE, null) ?: return null
        return Launch(p.getInt(K_PORT, DEFAULT_PORT), u, c)
    }

    /** 用已保存的 cookie 探活：GET / 带 cookie → 200 才算有效（dsh 侧 cookie 30 天有效） */
    private fun verifySaved(l: Launch): Boolean = runCatching {
        httpGet(l.cleanUrl.trimEnd('/') + "/", l.cookie).code == 200
    }.getOrDefault(false)

    fun status(context: Context): Status {
        val inst = installed(context)
        val l = launch
        val run = process?.isAlive == true && ready && l != null
        return Status(
            installed = inst,
            running = run,
            port = l?.port ?: DEFAULT_PORT,
            url = l?.cleanUrl ?: "http://$HOST:${DEFAULT_PORT}",
            message = when {
                !inst -> "未安装 dsh 运行环境"
                run -> "运行中 ${l!!.cleanUrl}"
                else -> "已安装，未运行"
            },
        )
    }

    // ---------------- 日志 ----------------

    fun setLogListener(cb: ((String) -> Unit)?) { listener = cb }
    fun recentLog(): List<String> = log.toList()
    fun clearLog() = log.clear()

    private fun emit(line: String) {
        val s = line.trimEnd()
        if (s.isEmpty()) return
        log.addLast(s)
        while (log.size > 400) log.pollFirst()
        listener?.invoke(s)
    }

    // ---------------- 安装 ----------------

    /**
     * 下载并解压**两段分包**到 rootfs/opt/dshenv。
     * 包内结构约定（zip 仅储存，store 模式）：
     *   part1: bin/node、run.sh、lib/node_modules/@deepseek-ai/dsh/{lib,package.json,...}
     *   part2: lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/...
     * 两包解压到同一目录合并，缺任一包 installed() 都为 false。
     */
    fun install(context: Context, onProgress: ((Int, String) -> Unit)? = null): Boolean {
        val root = hostEnvRoot(context)

        if (installed(context)) {
            emit("[dsh] 运行环境已存在，跳过下载")
            onProgress?.invoke(100, "已安装")
            return true
        }

        // 兼容修复：4.7.4 把 zip 内层的 dshenv/ 也解进来了，形成 root/dshenv/ 多一层。
        // 就地迁移，不重下 700MB。
        val nested = File(root, "dshenv")
        if (File(nested, "bin/node").isFile) {
            emit("[dsh] 检测到嵌套目录 opt/dshenv/dshenv，就地迁移…")
            nested.listFiles()?.forEach { f ->
                runCatching { f.renameTo(File(root, f.name)) }
            }
            nested.deleteRecursively()
            hostNode(context).apply { if (isFile) setExecutable(true, false) }
            if (installed(context)) {
                emit("[dsh] 迁移完成，无需重新下载")
                onProgress?.invoke(100, "安装完成")
                return true
            }
            emit("[dsh] 迁移后仍不完整，改走完整安装")
            root.deleteRecursively()
        }

        val a1 = File(context.cacheDir, "dshenv-part1.zip")
        val a2 = File(context.cacheDir, "dshenv-part2.zip")
        try {
            // ---- part1 ----
            if (!fetch(a1, BUNDLE_URL, BUNDLE_SHA256, "part1", 0, 42, onProgress)) return false

            onProgress?.invoke(43, "解压 part1…")
            root.deleteRecursively()
            val opt = root.parentFile ?: root
            val base1 = if (zipTopIsDshenv(a1)) opt else root
            base1.mkdirs()
            emit("[dsh] 解压 part1 → ${base1.absolutePath}")
            unzip(a1, base1) { p -> onProgress?.invoke(43 + p / 16, "解压 part1 $p%") }
            a1.delete()

            // ---- part2（合并覆盖到同一 root）----
            if (!fetch(a2, BUNDLE_URL2, BUNDLE_SHA2562, "part2", 50, 88, onProgress)) return false

            onProgress?.invoke(89, "解压 part2…")
            val base2 = if (zipTopIsDshenv(a2)) opt else root
            emit("[dsh] 解压 part2（合并覆盖）→ ${base2.absolutePath}")
            unzip(a2, base2) { p -> onProgress?.invoke(89 + p / 18, "解压 part2 $p%") }
            a2.delete()

            // 补执行位（私有目录拷贝常丢 x 位）
            hostNode(context).apply { if (isFile) setExecutable(true, false) }

            if (!installed(context)) {
                emit("[dsh] 解压后结构不符：缺 bin/node 或 lib/.../bin.js")
                return false
            }
            emit("[dsh] 安装完成（两包合并 OK）")
            onProgress?.invoke(100, "安装完成")
            return true
        } catch (e: Exception) {
            emit("[dsh] 安装异常：${e.message}")
            return false
        } finally {
            a1.delete()
            a2.delete()
        }
    }

    /**
     * 下载单包：支持小飞机分享链接（自动解析直链）或直链；带 SHA256 校验。
     * 进度 from..to 映射到 0..100 的 global 区间。
     */
    private fun fetch(
        dest: File,
        src: String,
        sha: String,
        label: String,
        from: Int,
        to: Int,
        onProgress: ((Int, String) -> Unit)?,
    ): Boolean {
        onProgress?.invoke(from, "下载 $label…")
        emit("[dsh] 下载 $label ← $src")
        download(src, dest) { p -> onProgress?.invoke(from + (to - from) * p / 100, "下载 $label $p%") }

        if (dest.length() <= 0L) {
            emit("[dsh] $label 下载为空")
            return false
        }
        if (sha.isNotEmpty()) {
            val got = sha256(dest)
            if (!got.equals(sha, ignoreCase = true)) {
                emit("[dsh] $label 校验失败\n  期望 $sha\n  实际 $got")
                return false
            }
            emit("[dsh] $label SHA256 通过（${dest.length()} B）")
        }
        return true
    }

    /** zip 内顶层是否带 `dshenv/` 目录（决定解压到 opt/ 还是 opt/dshenv/）。 */
    private fun zipTopIsDshenv(a: File): Boolean = runCatching {
        java.util.zip.ZipFile(a).use { zf ->
            zf.entries().asSequence().any { it.name.startsWith("dshenv/") }
        }
    }.getOrDefault(false)
    private fun unzip(archive: File, dest: File, onPct: (Int) -> Unit): Boolean {
        var ok = false
        java.util.zip.ZipFile(archive).use { zf ->
            val entries = zf.entries().toList()
            val total = entries.size.coerceAtLeast(1)
            var done = 0
            var lastPct = -1
            val destCanon = dest.canonicalPath
            for (e in entries) {
                val out = File(dest, e.name)
                // Zip Slip 防护
                if (!out.canonicalPath.startsWith(destCanon + File.separator) &&
                    out.canonicalPath != destCanon
                ) {
                    emit("[dsh] 跳过越界条目 ${e.name}")
                    continue
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins ->
                        out.outputStream().use { os -> ins.copyTo(os, 1 shl 16) }
                    }
                }
                done++
                val pct = (done * 100 / total)
                if (pct != lastPct) { lastPct = pct; onPct(pct) }
            }
            ok = true
        }
        return ok
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 下载一包。src 可以是：
     *   - 小飞机分享链接 https://example.com → FeijipanParser 解析成直链
     *   - 任何直链 http(s)://...
     * 直链带时间戳会过期，所以每次安装都重新解析。
     */
    private fun download(src: String, dest: File, onPct: (Int) -> Unit) {
        var url = src
        var referer: String? = null
        if (src.contains("feijipan.com/s/")) {
            val j = org.json.JSONObject(FeijipanParser.resolve(src))
            url = j.getString("url")
            referer = "https://www.feijipan.com/"
            emit("[dsh] 分享已解析：${url.take(80)}…")
        }

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )
        if (referer != null) c.setRequestProperty("Referer", referer)
        c.connect()
        val total = c.contentLengthLong
        emit("[dsh] HTTP ${c.responseCode} 长度 ${if (total > 0) "$total B" else "未知"}")
        c.inputStream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onPct(pct) }
                    }
                }
            }
        }
        c.disconnect()
    }

    // ---------------- 启停 ----------------

    fun start(context: Context, port: Int = DEFAULT_PORT): Boolean {
        val alive = process?.isAlive == true
        if (alive && ready) {
            emit("[dsh] 已在运行")
            return true
        }
        if (alive) {
            // 快路径：dsh 的 cookie 有 30 天有效期，进程还活着就直接复用，跳过等 token 的 30~40 秒
            val saved = savedLaunch(context)
            if (saved != null && verifySaved(saved)) {
                launch = saved
                ready = true
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie("http://$HOST:${saved.port}/", saved.cookie)
                    flush()
                }
                emit("[dsh] 复用上次 cookie（${saved.cleanUrl}），跳过等 token")
                return true
            }
            // 进程活着但未就绪且 cookie 已失效（token 无法复用）→ 必须重启换新 token
            emit("[dsh] 进程存活但未就绪，重启以获取新 token")
            runCatching { process?.destroyForcibly() }
            runCatching { process?.waitFor(3, TimeUnit.SECONDS) }
        }
        process = null
        ready = false
        launch = null

        if (!installed(context)) {
            emit("[dsh] 未安装，先安装")
            return false
        }

        val sandbox = SandboxInstaller.sandboxDir(context)
        val rootfs = SandboxInstaller.rootfsDir(context)
        val native = SandboxInstaller.nativeDir(context)
        val init = SandboxInstaller.initScript(context)
        val project = SandboxInstaller.projectDir(context)

        if (!init.isFile || !rootfs.isDirectory || !native.isDirectory) {
            emit("[dsh] 沙箱不完整：init-sandbox.sh / rootfs / native 缺失")
            return false
        }

        // --expose-internals：dsh 的 hmr 插件硬要求，否则启动时报
        //   hmr: Error: --expose-internals is required for HMR service
        // 并导致「1 required plugin did not activate」。
        // NODE_COMPILE_CACHE：把 V8 字节码缓存到磁盘，第二次启动不再重新编译 6 万模块 → 冷启动明显变快。
        val guestCmd = "cd /root && mkdir -p $GUEST_CACHE_DIR && export NODE_COMPILE_CACHE=$GUEST_CACHE_DIR && " +
            "exec $NODE_BIN_IN_GUEST --expose-internals $DSH_BIN_IN_GUEST " +
            "--profile web --no-open --host $HOST --port $port"

        val argv = listOf(
            "/system/bin/sh", init.absolutePath,
            sandbox.absolutePath, rootfs.absolutePath, native.absolutePath, project.absolutePath,
            "sh", "-c", guestCmd,
        )
        emit("[dsh] exec ${argv.joinToString(" ")}")

        val p = try {
            ProcessBuilder(argv)
                .directory(sandbox)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            emit("[dsh] 启动失败：${e.message}")
            return false
        }
        process = p

        Thread({
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        emit(raw)
                        if (!ready) {
                            val m = STARTUP_RE.find(raw)
                            val url = m?.groupValues?.get(1) ?: TOKEN_RE.find(raw)?.value
                            if (url != null) {
                                startupUrl = url
                                exchange(url, context)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            emit("[dsh] 进程输出结束")
        }, "dsh-stdout").apply { isDaemon = true }.start()

        return true
    }

    /**
     * token 换 cookie。对齐实测：
     *   1) GET ?token= → 303 + Location: ./ + Set-Cookie: dsh-auth-*
     *   2) GET /       → 200 才算就绪
     */
    private fun exchange(authUrl: String, context: Context) {
        val m = TOKEN_RE.find(authUrl)
        if (m == null) {
            emit("[dsh] 鉴权链接格式不符：$authUrl")
            return
        }
        val base = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: DEFAULT_PORT

        repeat(25) { attempt ->
            try {
                val first = httpGet(authUrl, null)
                if (first.code != 303 && first.code != 302) {
                    Thread.sleep(300)
                    return@repeat
                }
                // 注意：服务端下发的是小写 set-cookie，Android 的 headerFields 保留原大小写，
                // 所以这里必须忽略大小写、按原始 header 列表找，不能用 headers["Set-Cookie"]。
                val cookie = first.raw
                    .filter { it.first.equals("set-cookie", ignoreCase = true) }
                    .map { it.second.substringBefore(";").trim() }
                    .firstOrNull { COOKIE_RE.containsMatchIn(it) }
                if (cookie == null) {
                    emit(
                        "[dsh] 响应缺少 dsh-auth-* cookie（第 ${attempt + 1} 次）" +
                            " header=${first.raw.map { it.first }.take(8)}"
                    )
                    Thread.sleep(300)
                    return@repeat
                }
                val second = httpGet(base, cookie)
                if (second.code != 200) {
                    Thread.sleep(300)
                    return@repeat
                }

                val clean = base.trimEnd('/')
                launch = Launch(port, clean, cookie)
                ready = true
                saveLaunch(context, launch!!)

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie("http://$HOST:$port/", cookie)
                    flush()
                }
                emit("[dsh] READY $clean （cookie 已注入 WebView）")
                return
            } catch (e: Exception) {
                if (attempt == 0) emit("[dsh] 交换重试中：${e.message}")
                try { Thread.sleep(400) } catch (_: InterruptedException) { return }
            }
        }
        emit("[dsh] 25 次重试仍未就绪（token 已作废，再点启动会重启进程换新 token）")
    }

    private class Resp(
        val code: Int,
        val headers: Map<String, List<String>>,
        /** 原始 header 键值对（保留服务端下发的大小写），用于找 set-cookie */
        val raw: List<Pair<String, String>>,
    )

    private fun httpGet(url: String, cookie: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 2500
        c.readTimeout = 2500
        c.setRequestProperty("Cache-Control", "no-store")
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        try {
            val code = c.responseCode
            val all = c.headerFields
            val raw = ArrayList<Pair<String, String>>()
            for ((k, vs) in all) {
                if (k == null) continue
                for (v in vs) raw.add(k to v)
            }
            return Resp(code, all.filterKeys { it != null }, raw)
        } finally {
            c.disconnect()
        }
    }

    fun stop(): Boolean {
        val p = process ?: return false
        emit("[dsh] 停止")
        runCatching { p.destroy() }
        runCatching {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        ready = false
        launch = null
        return true
    }

    fun currentLaunch(): Launch? = launch
}
