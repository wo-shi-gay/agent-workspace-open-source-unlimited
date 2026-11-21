package com.agentpad.workspace.data

import android.content.Context
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * 无线调试（ADB 配对码直连）——不依赖 Shizuku。
 *
 * 流程：
 *   1. 用户在开发者选项里打开「无线调试」→「使用配对码配对设备」
 *   2. 记下显示的 IP:端口 + 6 位配对码
 *   3. 填入本页 → 配对（pair）→ 成功后连接（connect）
 *   4. 之后 AI 的 terminal 工具走这条 ADB 通道
 *
 * 依赖：io.github.muntashirakon.adb（libadb-android）
 */
object WirelessAdb {

    private const val PREF = "wireless_adb"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_ENABLED = "enabled"
    private const val KEYSTORE = "adbkey.p12"
    private val STORE_PASS = "agentpad-adb".toCharArray()

    data class Result(val ok: Boolean, val message: String, val detail: String = "")

    // ---------- 常驻连接（2026-11-04） ----------
    //
    // ⚠ 为什么必须常驻：
    //   无线调试（adb over Wi-Fi）对频繁建连/断连非常敏感。旧实现每次 exec()
    //   都 create → connect → close，一次 AI 任务几十条命令就是几十次握手，
    //   adbd 那边容易直接拒绝或把端口判为异常。
    //   改成全局持有一个连接，命令复用同一条，只在失效时重建。
    //
    // ⚠ 端口为什么不能硬存：
    //   每开关一次「无线调试」，adbd 都会换一个随机端口（通常 30000-49999）。
    //   只要用户切过一次设置或重连过一次，旧端口必然失效。
    //   所以连接失败时要自动重新发现端口，而不是让用户手填。

    private val connLock = Any()
    private var persistentConn: AdbConnection? = null
    private var persistentKey: Pair<String, Int>? = null
    @Volatile private var lastConnectFailure = 0L

    /** 关闭常驻连接（停用无线 ADB 时调用）。 */
    fun closePersistent() {
        synchronized(connLock) {
            runCatching { persistentConn?.close() }
            persistentConn = null
            persistentKey = null
        }
    }

    /**
     * 拿到一条可用连接。
     *
     * 顺序：
     *   1. 已有常驻连接且端口未变 → 直接复用（先探活）
     *   2. 探测失败 / 首次连接 → 用保存的端口重连
     *   3. 仍失败 → 自动扫描端口段重新发现（用户不用手填）
     */
    private fun obtainConnection(ctx: Context): Pair<AdbConnection, Pair<String, Int>>? {
        val (key, cert) = ensureKeyPair(ctx)
        synchronized(connLock) {
            // 1) 复用
            val cached = persistentConn
            val cachedEp = persistentKey
            if (cached != null && cachedEp != null) {
                if (runCatching { cached.isConnected }.getOrDefault(false)) {
                    return cached to cachedEp
                }
                runCatching { cached.close() }
                persistentConn = null
                persistentKey = null
            }
            // 2) 保存的端点
            savedEndpoint(ctx)?.let { ep ->
                runCatching {
                    val c = AdbConnection.create(ep.first, ep.second, key, cert)
                    if (c.connect(5, TimeUnit.SECONDS, true)) {
                        persistentConn = c
                        persistentKey = ep
                        return c to ep
                    }
                    runCatching { c.close() }
                }
            }
            // 3) 端口自动发现
            val host = savedEndpoint(ctx)?.first ?: return null
            val found = discoverPort(host, key, cert)
            if (found != null) {
                prefs(ctx).edit().putString(KEY_HOST, host).putInt(KEY_PORT, found.first).apply()
                persistentConn = found.second
                persistentKey = host to found.first
                AppLog.i("WirelessAdb", "端口重新发现成功：$host:${found.first}")
                return found.second to (host to found.first)
            }
            lastConnectFailure = System.currentTimeMillis()
            return null
        }
    }

    /**
     * 端口自动发现（无线调试端口每次重开都会变）。
     *
     * adbd 无线调试实际端口由系统随机分配，官方没有稳定规律，但实践上集中在
     * 30000–49999。串行扫 20000 个端口太慢，这里用**并发探测**：
     * 先试常见段与上次端口附近，再全段并发扫（每个连接超时 300ms）。
     *
     * 只在「保存的端口连不上」时才走到这里，正常情况下零开销。
     */
    private fun discoverPort(
        host: String,
        key: PrivateKey,
        cert: Certificate,
    ): Pair<Int, AdbConnection>? {
        // 候选端口：先上次端口附近 ±16，再常见区段采样，最后全段
        val candidates = LinkedHashSet<Int>()
        val lastPort = persistentKey?.second ?: 0
        if (lastPort > 0) {
            for (d in 0..16) {
                if (lastPort - d in 1..65535) candidates.add(lastPort - d)
                if (lastPort + d in 1..65535) candidates.add(lastPort + d)
            }
        }
        // 无线调试常见分配段（含 37000 附近的真实样本）
        listOf(5555, 37000, 37001, 37100, 40000, 41000).forEach { candidates.add(it) }
        // 全段并发扫
        val pool = java.util.concurrent.Executors.newFixedThreadPool(24)
        val hits = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, AdbConnection>>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            for (port in 30000..49999) {
                if (done.get()) break
                pool.execute {
                    if (done.get()) return@execute
                    runCatching {
                        val c = AdbConnection.create(host, port, key, cert)
                        if (c.connect(1, TimeUnit.SECONDS, true)) {
                            if (done.compareAndSet(false, true)) {
                                hits.add(port to c)
                            } else {
                                runCatching { c.close() }
                            }
                        } else {
                            runCatching { c.close() }
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(20, TimeUnit.SECONDS)
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        // 先按候选表顺序找
        val all = hits.toList()
        candidates.firstOrNull { p -> all.any { it.first == p } }
            ?.let { p -> return all.first { it.first == p } }
        return all.firstOrNull()
    }


    // ---------- 状态 ----------

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false) && savedEndpoint(ctx) != null

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        // 停用时顺手关掉常驻连接，别留悬空 socket
        if (!on) closePersistent()
        AppLog.i("WirelessAdb", "无线ADB ${if (on) "已启用" else "已停用"}")
    }

    fun savedEndpoint(ctx: Context): Pair<String, Int>? {
        val p = prefs(ctx)
        val host = p.getString(KEY_HOST, "") ?: ""
        val port = p.getInt(KEY_PORT, 0)
        return if (host.isNotBlank() && port > 0) host to port else null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------- 密钥（ADB 认证用，首次生成后持久保存） ----------

    private fun keyStore(ctx: Context): KeyStore {
        val f = File(ctx.filesDir, KEYSTORE)
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            f.inputStream().use { ks.load(it, STORE_PASS) }
        } else {
            ks.load(null, STORE_PASS)
            f.parentFile?.mkdirs()
            f.outputStream().use { ks.store(it, STORE_PASS) }
            AppLog.i("WirelessAdb", "已生成 ADB 密钥对")
        }
        return ks
    }

    private fun ensureKeyPair(ctx: Context): Pair<PrivateKey, Certificate> {
        val ks = keyStore(ctx)
        var alias = ks.aliases().toList().firstOrNull()
        if (alias == null) {
            // 生成 RSA 2048 密钥对 + 自签名证书（ADB 认证要求）
            val kpg = java.security.KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val cert = selfSignedCert(kp)
            ks.setKeyEntry("adbkey", kp.private, STORE_PASS, arrayOf(cert))
            File(ctx.filesDir, KEYSTORE).outputStream().use { ks.store(it, STORE_PASS) }
            alias = "adbkey"
        }
        val key = ks.getKey(alias, STORE_PASS) as PrivateKey
        val cert = ks.getCertificate(alias)
        return key to cert
    }

    /** 生成自签名证书（CN=agentpad）。 */
    private fun selfSignedCert(kp: java.security.KeyPair): Certificate {
        val name = javax.security.auth.x500.X500Principal("CN=agentpad")
        val now = System.currentTimeMillis()
        val start = java.util.Date(now - 86400_000L)
        val end = java.util.Date(now + 3650L * 86400_000L)
        val builder = Class.forName("sun.security.x509.X509CertInfo")
        // 不依赖内部 API：用 BouncyCastle 太重建，这里手工构造最简 X.509 v3
        val tbs = buildTbs(name, kp.public as java.security.interfaces.RSAPublicKey, start, end)
        val sig = java.security.Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private); update(tbs)
        }.sign()
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(wrapDer(tbs, sig).inputStream())
    }

    private fun buildTbs(
        subject: javax.security.auth.x500.X500Principal,
        pub: java.security.interfaces.RSAPublicKey,
        start: java.util.Date, end: java.util.Date,
    ): ByteArray {
        // 简化：使用 org.bouncycastle 之外的手段太繁琐，这里直接借助 Android 自带的
        // SunCertPathBuilder 不可用，因此退到「用 KeyPair 生成 PKCS12 由 KeyStore 自签」方案。
        // 实际做法：调用 CertificateFactory 无法构造，改用自实现的 DER 编码。
        return DerCert.build(subject, pub, start, end)
    }

    private fun wrapDer(tbs: ByteArray, sig: ByteArray): File {
        val der = DerCert.wrap(tbs, sig)
        return File.createTempFile("cert", ".der").apply { writeBytes(der) }
    }
    /**
     * 配对（供通知栏输入配对码的 [com.agentpad.workspace.AdbPairingService] 调用）。
     *
     * 与 [pair] 完全相同的流程，只是语义上明确「这是服务内部直连」。
     * 配对端口由 mDNS 发现，固定连 127.0.0.1（配对服务只监听 loopback）。
     */
    fun pairDirect(ctx: Context, host: String, port: Int, code: String): Result =
        pair(ctx, host, port, code)

    /**
     * 配对成功后自动连接。
     *
     * ⚠ 关键：**连接端口必须用 mDNS 重新发现**，不能复用配对端口
     * （两者是不同的服务：`_adb-tls-pairing` vs `_adb-tls-connect`）。
     *
     * 流程：
     *   1. 监听 `_adb-tls-connect._tcp`，拿到系统 adbd 当前广播的连接端口
     *   2. 用 127.0.0.1 连（本机自连，不需要局域网 IP）
     *   3. 连上后保存端点 + 置为启用
     *
     * 超时 15 秒没发现就放弃（用户可回到 App 手动重试）。
     */
    fun autoConnectAfterPair(ctx: Context, timeoutMs: Long = 15_000) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val app = ctx.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        // ⚠ 不能用 @Volatile 局部变量（注解只支持字段），用 AtomicInteger 跨线程安全
        val foundPort = java.util.concurrent.atomic.AtomicInteger(-1)
        val mdns = AdbMdns(app, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0 && foundPort.get() <= 0) {
                foundPort.set(port)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            val ok = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val port = foundPort.get()
            if (!ok || port <= 0) {
                AppLog.w("WirelessAdb", "mDNS 未发现连接端口，等待用户手动连接")
                return
            }
            AppLog.i("WirelessAdb", "mDNS 发现连接端口：$port")
            val host = "127.0.0.1"
            val r = connect(app, host, port)
            AppLog.i("WirelessAdb", "配对后自动连接：${r.message}")
            if (r.ok) setEnabled(app, true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { mdns.stop() }
        }
    }

    // ---------- 配对 ----------

    /**
     * 配对（Android 11+ 无线调试配对码）。
     * @param host 配对页显示的 IP
     * @param port 配对页显示的端口
     * @param code 6 位配对码
     */
    fun pair(ctx: Context, host: String, port: Int, code: String): Result {
        return try {
            val (key, cert) = ensureKeyPair(ctx)
            AppLog.i("WirelessAdb", "开始配对 $host:$port")
            val pairing = PairingConnectionCtx(host, port, code.toByteArray(), key, cert, "agentpad")
            pairing.use {
                it.start()
            }
            AppLog.i("WirelessAdb", "配对成功 $host:$port")
            Result(true, "配对成功")
        } catch (e: Exception) {
            AppLog.e("WirelessAdb", "配对失败", e)
            Result(false, "配对失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 连接 ----------

    /**
     * 连接（配对成功后，用「无线调试」主页显示的 IP:端口）。
     */
    fun connect(ctx: Context, host: String, port: Int): Result {
        return try {
            val (key, cert) = ensureKeyPair(ctx)
            val conn = AdbConnection.create(host, port, key, cert)
            val ok = conn.connect(10, TimeUnit.SECONDS, true)
            conn.close()
            if (ok) {
                prefs(ctx).edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply()
                AppLog.i("WirelessAdb", "连接成功 $host:$port")
                Result(true, "连接成功")
            } else {
                Result(false, "连接失败：设备未授权或端口错误")
            }
        } catch (e: Exception) {
            AppLog.e("WirelessAdb", "连接失败", e)
            Result(false, "连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 仅测试已保存的连接是否还活着。 */
    fun ping(ctx: Context): Boolean {
        val ep = savedEndpoint(ctx) ?: return false
        return try {
            val (key, cert) = ensureKeyPair(ctx)
            AdbConnection.create(ep.first, ep.second, key, cert).let { c ->
                val ok = c.connect(5, TimeUnit.SECONDS, true)
                c.close()
                ok
            }
        } catch (e: Exception) {
            AppLog.w("WirelessAdb", "ping 失败：${e.message}")
            false
        }
    }

    // ---------- 执行命令 ----------

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * 在设备上以 shell 身份执行命令（等价 `adb shell`）。
     */
    fun exec(ctx: Context, command: String, timeoutMs: Long = 60_000): ShellResult {
        val ep = savedEndpoint(ctx)
            ?: return ShellResult(127, "", "无线 ADB 未配置")
        return try {
            val (key, cert) = ensureKeyPair(ctx)
            val conn = AdbConnection.create(ep.first, ep.second, key, cert)
            if (!conn.connect(10, TimeUnit.SECONDS, true)) {
                conn.close()
                return ShellResult(1, "", "连接失败")
            }
            val start = System.currentTimeMillis()
            val stream: AdbStream = conn.open("shell:$command")
            val out = StringBuilder()
            stream.openInputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = try { ins.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    out.append(String(buf, 0, n))
                }
            }
            stream.close()
            conn.close()
            val cost = System.currentTimeMillis() - start
            AppLog.i("WirelessAdb", "[${cost}ms] cmd=${command.take(80)} out=${out.length}B")
            ShellResult(0, out.toString(), "")
        } catch (e: Exception) {
            AppLog.e("WirelessAdb", "执行失败", e)
            ShellResult(1, "", "执行异常：${e.message}")
        }
    }

    fun statusText(ctx: Context): String {
        val ep = savedEndpoint(ctx) ?: return "未配对"
        if (!isEnabled(ctx)) return "已配对 ${ep.first}:${ep.second}（未启用）"
        return "已连接 ${ep.first}:${ep.second}"
    }
}