package com.agentpad.workspace.ai

import android.content.Context
import android.content.pm.PackageManager
import com.android.apksig.ApkVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * APK 体检器（**纯进程内**，不用 aapt2 / 不进 proot / 不起 java）。
 *
 * ── 为什么必须单独一个 ──────────────────────────────────────────
 * 之前模型要看一个 APK 的信息，工具层只有「第二步」工具：
 *   dexkit_open（要先有正确路径）、apk_unzip（要目录）、smali_*（要反编译）
 * （历史上）于是它只能退回终端：
 *   find 找文件 → aapt2 dump badging → aapt2 dump xmltree → zipfile 数 entry
 *   → grep 权限 → apksigner verify → …… 一个信息一条命令，59 次调用换 4 条结论。
 *
 * 这里把「看一眼这个 APK」压成**一次调用**：
 *   apk_info → package / versionName / versionCode / minSdk / targetSdk
 *            / 权限清单 / 组件清单（含 exported、intent-filter）
 *            / 签名 v1+v2+v3 + 证书 DN + 证书 SHA-256
 *            / dex 清单（含每个体积）/ so 清单 / zip 结构统计 / APK SHA-256
 *
 * ── 两件事本来就必须原生做 ──────────────────────────────────────
 * 1) **二进制 AndroidManifest（AXML）解析**：这就是 aapt2 dump badging 干的事。
 *    自己解 string pool + start-element 属性，几百行，但一次调用就出结果。
 * 2) **签名校验**：apksig 的 ApkVerifier 在包里（sign 也在用同一个库），
 *    verify() 直接给 v1/v2/v3 与证书链，不用 apksigner。
 */
object ApkInspector {

    // ==================== 路径解析 ====================

    /** 解析结果：命中的文件 + 是怎么命中的（写进返回体，方便模型下次直接给对）。 */
    data class ResolveHit(val file: File, val how: String)

    /**
     * 把「模型随口给的一个路径」解析成真实存在的文件。
     *
     * ── 为什么必须做这一步（59 次调用的头号原因）──────────────────
     * 模型看到的世界是**沙盒（proot）里的路径**，原生工具跑在 App 进程里，
     * 两边根本不是同一套命名空间。init-sandbox.sh 里的 bind 是：
     *     -b $PROJECT_DIR:/workspace          （该 bind 已于 2026-11-17 移除，此表仅作历史说明；映射代码保留用于兼容老会话）
     *     -b $PREFIX/public:/public|/root|/home
     *     -b <realpath /sdcard>               （用户存储）
     * 所以模型给的 `/workspace/Workspace.apk` 在 App 侧**根本不存在**，
     * 它只能退回终端 `find /`、翻 `/proc/mounts`、翻 `no_backup` —— 12 次调用就没了。
     *
     * 解析顺序：
     *   1) 原样绝对路径（真的存在就最省事）
     *   2) **沙盒路径反映射**：/workspace、/sdcard、/public|/root|/home、rootfs 内路径
     *   3) 包名（含点、无斜杠、不以 .apk 结尾）→ PackageManager 查已安装 APK
     *   4) 裸文件名 → 常用根目录直查
     *   5) 浅层递归（≤3 层）
     * 返回里带 `how`，让模型知道下次该怎么给。
     */
    fun resolve(ctx: Context, raw: String?): ResolveHit? {
        val t = (raw ?: "").trim().removeSurrounding("\"").removeSurrounding("'")
        if (t.isBlank()) return null

        // 1) 原样
        hit(File(t))?.let { return ResolveHit(it, "exact") }

        // 2) 沙盒路径反映射（核心）
        for ((f, how) in sandboxCandidates(ctx, t)) {
            hit(f)?.let { return ResolveHit(it, how) }
        }

        // 3) 包名
        if (!t.contains("/") && t.contains(".") && !t.endsWith(".apk", true) &&
            t.matches(Regex("""[A-Za-z][\w.]*\.[A-Za-z][\w.]*"""))
        ) {
            byPackage(ctx, t)?.let { return ResolveHit(it, "package") }
        }

        // 4) 裸名字：在常用根目录里找
        val names = if (t.endsWith(".apk", true)) listOf(t) else listOf(t, "$t.apk")
        for (root in searchRoots(ctx)) {
            if (!root.isDirectory) continue
            for (n in names) {
                hit(File(root, n))?.let { return ResolveHit(it, "name-search:${root.absolutePath}") }
            }
        }
        // 5) 浅层递归找（最多 3 层，避免满盘扫）
        for (root in searchRoots(ctx)) {
            for (n in names) {
                walkFind(root, n, 0)?.let { return ResolveHit(it, "deep-search:${root.absolutePath}") }
            }
        }
        return null
    }

    /**
     * 沙盒路径 → App 侧真实文件（按 init-sandbox.sh 的 bind 表反着推）。
     *
     * /workspace/X  → 每一个会话工作区 + workspaces/current 下找 X
     *                 （不猜 sessionId：按「哪个真存在」来定，多会话也不会串）
     * /sdcard/X     → /storage/emulated/0/X
     * /public|/root|/home/X → filesDir/sandbox/public/X   （proot 里这三个是同一个目录）
     * /opt /usr /etc /data 等 → rootfs 内同名路径
     */
    fun sandboxCandidates(ctx: Context, raw: String): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        fun add(f: File?, how: String) { f?.let { if (!out.any { p -> p.first == it }) out.add(it to how) } }

        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val wsRoot = File(ext, "workspaces")

        when {
            raw.startsWith("/workspace/") -> {
                val rel = raw.removePrefix("/workspace/")
                // 1) 所有会话工作区（逆向模式 importApk 落点）
                runCatching { wsRoot.listFiles() }.getOrNull()?.forEach { add(File(it, rel), "sandbox:/workspace→${it.name}") }
                // 2) 沙盒默认 PROJECT_DIR
                add(File(wsRoot, "current/$rel"), "sandbox:/workspace→current")
                // 3) 由外向内也试一次（模型常把 /workspace 当成工作区根）
                add(File(rel), "sandbox:/workspace→pwd")
            }
            raw == "/workspace" -> {
                runCatching { wsRoot.listFiles() }.getOrNull()?.forEach { add(it, "sandbox:/workspace→${it.name}") }
            }
            raw.startsWith("/sdcard/") || raw.startsWith("/storage/self/primary/") -> {
                val rel = raw.removePrefix("/sdcard/").removePrefix("/storage/self/primary/")
                add(File("/storage/emulated/0", rel), "sandbox:/sdcard-mapped")
            }
            raw.startsWith("/public/") || raw.startsWith("/root/") || raw.startsWith("/home/") -> {
                val rel = raw.substringAfter('/')            // 去掉首段
                add(File(ctx.filesDir, "sandbox/public/$rel"), "sandbox:/public|root|home")
            }
            raw.startsWith("/") -> {
                // rootfs 内路径（/opt/xxx、/usr/xxx、/data/local/tmp 之类）
                runCatching {
                    val rootfs = SandboxInstaller.rootfsDir(ctx)
                    add(File(rootfs, raw.removePrefix("/")), "sandbox:rootfs")
                }
            }
        }
        return out
    }

    private fun hit(f: File): File? =
        runCatching { if (f.isFile && f.length() > 0) f.absoluteFile else null }.getOrNull()

    private fun byPackage(ctx: Context, pkg: String): File? = runCatching {
        val pi = if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0)
        File(pi.applicationInfo!!.publicSourceDir).takeIf { it.isFile }
    }.getOrNull()

    /** 只看这些根，不扫整个 /storage/emulated/0。 */
    private fun searchRoots(ctx: Context): List<File> {
        val out = ArrayList<File>()
        fun add(p: File?) { p?.let { if (!out.contains(it)) out.add(it) } }
        add(File("/storage/emulated/0/Download"))
        add(File("/storage/emulated/0/Download/revbuild"))
        add(File("/storage/emulated/0/Download/Operit"))
        add(ctx.getExternalFilesDir(null))
        // 所有会话工作区（逆向模式 importApk 就落在这里：workspaces/<sessionId>/X.apk）
        ctx.getExternalFilesDir(null)?.let { ext ->
            File(ext, "workspaces").listFiles()?.forEach { add(it) }
        }
        add(File(ctx.filesDir, "rev"))
        add(File("/storage/emulated/0"))
        return out
    }

    private fun walkFind(dir: File, name: String, depth: Int): File? {
        if (depth > 3) return null
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return null
        for (k in kids) {
            if (k.isFile && k.name.equals(name, ignoreCase = true) && k.length() > 0) return k
        }
        for (k in kids) {
            if (k.isDirectory && !k.name.startsWith(".") && k.name != "Android") {
                walkFind(k, name, depth + 1)?.let { return it }
            }
        }
        return null
    }

    // ==================== 体检 ====================

    /**
     * 一次性体检。返回结构（全部可缺省，缺了就说明那个环节失败）：
     * {
     *   success, file{path,size,sha256}, zip{...}, dex[...], libs[...],
     *   manifest{package,versionName,versionCode,minSdk,targetSdk,permissions[],
     *            components{activities[],services[],receivers[],providers[]},
     *            features[], application{...}, launcher},
     *   signature{v1,v2,v3,verified,certificates[]},
     *   warnings[], summary(一句话人话)
     * }
     */
    fun inspect(ctx: Context, apk: File, wantSha256: Boolean = true): JSONObject {
        val out = JSONObject().put("success", true)
        val warnings = JSONArray()

        out.put(
            "file", JSONObject()
                .put("path", apk.absolutePath)
                .put("name", apk.name)
                .put("size", apk.length())
                .put("size_mb", Math.round(apk.length() / 1048576.0 * 100) / 100.0)
        )
        if (wantSha256) {
            runCatching { sha256(apk) }
                .onSuccess { out.put("file", out.getJSONObject("file").put("sha256", it)) }
                .onFailure { warnings.put("sha256 计算失败：${it.message}") }
        }

        // ---- zip 结构 ----
        runCatching { zipStats(apk) }
            .onSuccess { out.put("zip", it) }
            .onFailure { warnings.put("zip 解析失败：${it.message}") }

        // ---- manifest（二进制 AXML）----
        runCatching {
            ZipFile(apk).use { zf ->
                val e = zf.getEntry("AndroidManifest.xml")
                    ?: throw IllegalStateException("没有 AndroidManifest.xml（不是 APK？）")
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                manifestSummary(parseAxml(bytes))
            }
        }.onSuccess { out.put("manifest", it) }
            .onFailure { warnings.put("manifest 解析失败：${it.message}") }

        // ---- 签名 ----
        runCatching { signature(apk) }
            .onSuccess { out.put("signature", it) }
            .onFailure { warnings.put("签名校验失败：${it.message}") }

        // ---- 一句话结论 ----
        val m = out.optJSONObject("manifest")
        val s = out.optJSONObject("signature")
        val dexN = out.optJSONObject("zip")?.optJSONArray("dex")?.length() ?: 0
        val comp = m?.optJSONObject("components")
        val compN = listOf("activities", "services", "receivers", "providers")
            .sumOf { k -> comp?.optJSONArray(k)?.length() ?: 0 }
        out.put(
            "summary",
            "${m?.optString("package").orEmpty()} ${m?.optString("versionName").orEmpty()}" +
                "（vc${m?.optString("versionCode").orEmpty()}）" +
                " minSdk=${m?.optString("minSdk").orEmpty()} targetSdk=${m?.optString("targetSdk").orEmpty()}" +
                " · dex=$dexN · 组件=$compN · 权限=${m?.optJSONArray("permissions")?.length() ?: 0}" +
                " · 签名 v1=${s?.optBoolean("v1") ?: false}/v2=${s?.optBoolean("v2") ?: false}" +
                "/v3=${s?.optBoolean("v3") ?: false}"
        )
        if (warnings.length() > 0) out.put("warnings", warnings)
        return out
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun zipStats(apk: File): JSONObject {
        val dex = JSONArray()
        val libs = JSONArray()
        var files = 0
        var dirs = 0
        var compressed = 0L
        var uncompressed = 0L
        var stored = 0
        var deflated = 0
        var arscStored = false
        var hasArsc = false
        var hasV1 = false

        ZipFile(apk).use { zf ->
            for (e in zf.entries()) {
                if (e.isDirectory) { dirs++; continue }
                files++
                compressed += e.compressedSize.coerceAtLeast(0)
                uncompressed += e.size.coerceAtLeast(0)
                val method = runCatching { e.method }.getOrDefault(-1)
                if (method == java.util.zip.ZipEntry.STORED) stored++ else deflated++
if (e.name == "resources.arsc") { hasArsc = true; arscStored = method == java.util.zip.ZipEntry.STORED }
                if (e.name.startsWith("META-INF/") && (e.name.endsWith(".SF") || e.name.endsWith(".RSA") || e.name.endsWith(".DSA")))
                    hasV1 = true
                if (e.name.matches(Regex("""classes\d*\.dex"""))) {
                    dex.put(JSONObject().put("name", e.name).put("size", e.size).put("compressed", e.compressedSize))
                }
                if (e.name.startsWith("lib/") && e.name.endsWith(".so")) {
                    // lib/<abi>/xxx.so
                    val abi = e.name.split('/').getOrNull(1).orEmpty()
                    libs.put(JSONObject().put("abi", abi).put("name", e.name.substringAfterLast('/')).put("size", e.size))
                }
            }
        }
        return JSONObject()
            .put("entries", files + dirs)
            .put("files", files)
            .put("dirs", dirs)
            .put("compressed_total", compressed)
            .put("uncompressed_total", uncompressed)
            .put("ratio", if (uncompressed > 0) Math.round(compressed * 1000.0 / uncompressed) / 1000.0 else 0.0)
            .put("stored_entries", stored)
            .put("deflated_entries", deflated)
            .put("has_resources_arsc", hasArsc)
            .put("resources_arsc_stored", arscStored)
            .put("has_v1_meta", hasV1)
            .put("dex", dex)
            .put("libs", libs)
            .put("note", "resources_arsc_stored=false 会让 Android 12+ 安装失败；has_v1_meta 不等于签名有效，真结论看 signature")
    }


    // ==================== 签名 ====================

    private fun signature(apk: File): JSONObject {
        val r = ApkVerifier.Builder(apk).build().verify()
        val certs = JSONArray()
        r.signerCertificates.orEmpty().forEach { c ->
            certs.put(
                JSONObject()
                    .put("subject", c.subjectDN.name)
                    .put("issuer", c.issuerDN.name)
                    .put("sha256", MessageDigest.getInstance("SHA-256")
                        .digest(c.encoded).joinToString("") { "%02x".format(it) })
                    .put("not_before", c.notBefore.toString())
                    .put("not_after", c.notAfter.toString())
                    .put("serial", c.serialNumber.toString(16))
            )
        }
        return JSONObject()
            .put("verified", r.isVerified)
            .put("v1", r.isVerifiedUsingV1Scheme)
            .put("v2", r.isVerifiedUsingV2Scheme)
            .put("v3", r.isVerifiedUsingV3Scheme)
            .put("certificates", certs)
            .put("errors", JSONArray(r.errors.orEmpty().map { it.toString() }))
            .put("warnings", JSONArray(r.warnings.orEmpty().map { it.toString() }))
    }

    // ==================== 二进制 AndroidManifest（AXML）====================

    class Tag(val name: String, val attrs: Map<String, String>, val depth: Int)

    /** 解 AXML 成扁平的 tag 列表（带层级深度）。任何异常都抛出去，由调用方记 warning。 */
    private fun parseAxml(data: ByteArray): List<Tag> {
        val out = ArrayList<Tag>()
        var strings: List<String> = emptyList()
        val stack = ArrayList<String>()
        var pos = 0

        fun u16(p: Int): Int = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
        fun i32(p: Int): Int = (data[p].toInt() and 0xFF) or
            ((data[p + 1].toInt() and 0xFF) shl 8) or
            ((data[p + 2].toInt() and 0xFF) shl 16) or
            ((data[p + 3].toInt() and 0xFF) shl 24)

        fun readStr8(p0: Int): String {
            var q = p0
            var a = data[q].toInt() and 0xFF; q++
            if (a and 0x80 != 0) q++          // 长字符串的长度占两字节，字符数用不到
            var n = data[q].toInt() and 0xFF; q++
            if (n and 0x80 != 0) { n = ((n and 0x7F) shl 8) or (data[q].toInt() and 0xFF); q++ }
            val end = (q + n).coerceAtMost(data.size)
            return String(data, q, (end - q).coerceAtLeast(0), Charsets.UTF_8)
        }

        fun readStr16(p0: Int): String {
            var q = p0
            var n = u16(q); q += 2
            if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(q); q += 2 }
            val bytes = n * 2
            val end = (q + bytes).coerceAtMost(data.size)
            return String(data, q, (end - q).coerceAtLeast(0), Charsets.UTF_16LE)
        }

        while (pos + 8 <= data.size) {
            val type = u16(pos)
            val headerSize = u16(pos + 2)
            val size = i32(pos + 4)
            if (size <= 0 || pos + size > data.size) break

            when (type) {
                // ---- string pool ----
                0x0001 -> {
                    val count = i32(pos + 8)
                    val flags = i32(pos + 16)
                    val stringsStart = i32(pos + 20)
                    val utf8 = (flags and 0x100) != 0
                    val base = pos + stringsStart
                    val list = ArrayList<String>(count)
                    for (i in 0 until count) {
                        val off = i32(pos + headerSize + i * 4)
                        val p = base + off
                        if (p < 0 || p >= data.size) { list.add(""); continue }
                        list.add(runCatching { if (utf8) readStr8(p) else readStr16(p) }.getOrDefault(""))
                    }
                    strings = list
                }
                // ---- start element ----
                0x0102 -> {
                    val nameIdx = i32(pos + 20)
                    val attrStart = u16(pos + 24)
                    val attrSize = u16(pos + 26)
                    val attrCount = u16(pos + 28)
                    val name = strings.getOrElse(nameIdx) { "" }
                    val attrs = LinkedHashMap<String, String>()
                    val stride = if (attrSize >= 20) attrSize else 20
                    for (i in 0 until attrCount) {
                        val ap = pos + headerSize + attrStart + i * stride
                        if (ap + 20 > data.size) break
                        val aNameIdx = i32(ap + 4)
                        val rawIdx = i32(ap + 8)
                        val dataType = data[ap + 15].toInt() and 0xFF
                        val vData = i32(ap + 16)
                        val aName = strings.getOrElse(aNameIdx) { "attr$i" }
                        val local = aName.substringAfterLast(':')
                        val value: String = when {
                            rawIdx >= 0 && rawIdx < strings.size -> strings[rawIdx]
                            dataType == 0x03 && vData >= 0 && vData < strings.size -> strings[vData]
                            dataType == 0x12 -> (vData != 0).toString()
                            dataType == 0x10 -> vData.toString()
                            dataType == 0x11 -> "0x%08x".format(vData)
                            dataType == 0x01 -> "@0x%08x".format(vData)
                            dataType == 0x02 -> "?0x%08x".format(vData)
                            else -> "0x%08x".format(vData)
                        }
                        attrs[local] = value
                    }
                    out.add(Tag(name, attrs, stack.size))
                    stack.add(name)
                }
                // ---- end element ----
                0x0103 -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> {}
            }
            pos += size
        }
        return out
    }


    /** org.json.JSONArray 没有 toList()，这里自己判重。 */
    private fun jsonArrayHas(arr: JSONArray, v: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == v) return true
        }
        return false
    }

    /** 把 tag 列表压成模型能直接用的 manifest 摘要。 */
    private fun manifestSummary(tags: List<Tag>): JSONObject {
        val out = JSONObject()
        val perms = JSONArray()
        val features = JSONArray()
        val act = JSONArray(); val svc = JSONArray(); val rcv = JSONArray(); val prv = JSONArray()
        val meta = JSONArray()
        var appJson: JSONObject? = null
        var launcher = ""
        var appDepth = -1

        // 记录每个组件最近的 intent-filter
        var curComp: JSONObject? = null
        var curFilter: JSONObject? = null
        // 非空初值：下面直接 .put()，用可空类型会编译不过（only safe calls allowed）
        var curCompArray: JSONArray = act

        for (t in tags) {
            when (t.name) {
                "manifest" -> {
                    out.put("package", t.attrs["package"].orEmpty())
                    out.put("versionName", t.attrs["versionName"].orEmpty())
                    out.put("versionCode", t.attrs["versionCode"].orEmpty())
                    t.attrs["compileSdkVersion"]?.let { out.put("compileSdk", it) }
                }
                "uses-sdk" -> {
                    out.put("minSdk", t.attrs["minSdkVersion"].orEmpty())
                    out.put("targetSdk", t.attrs["targetSdkVersion"].orEmpty())
                }
                "uses-permission", "uses-permission-sdk-23" ->
                    t.attrs["name"]?.let { if (!jsonArrayHas(perms, it)) perms.put(it) }
                "uses-feature" -> features.put(JSONObject()
                    .put("name", t.attrs["name"].orEmpty())
                    .put("required", t.attrs["required"] ?: "true"))
                "application" -> {
                    appDepth = t.depth
                    appJson = JSONObject().apply {
                        put("label", t.attrs["label"].orEmpty())
                        put("icon", t.attrs["icon"].orEmpty())
                        put("theme", t.attrs["theme"].orEmpty())
                        put("debuggable", t.attrs["debuggable"] ?: "false")
                        put("allowBackup", t.attrs["allowBackup"] ?: "true")
                        put("usesCleartextTraffic", t.attrs["usesCleartextTraffic"] ?: "false")
                        put("extractNativeLibs", t.attrs["extractNativeLibs"] ?: "true")
                        t.attrs["networkSecurityConfig"]?.let { put("networkSecurityConfig", it) }
                    }
                }
                "activity", "activity-alias", "service", "receiver", "provider" -> {
                    if (appDepth >= 0 && t.depth == appDepth + 1) {
                        val comp = JSONObject().apply {
                            put("name", t.attrs["name"].orEmpty())
                            put("exported", t.attrs["exported"] ?: "false")
                            t.attrs["permission"]?.let { put("permission", it) }
                            t.attrs["process"]?.let { put("process", it) }
                        }
                        curComp = comp
                        curCompArray = when (t.name) {
                            "activity", "activity-alias" -> act
                            "service" -> svc
                            "receiver" -> rcv
                            else -> prv
                        }
                        curCompArray.put(comp)
                        curFilter = null
                    }
                }
                "intent-filter" -> {
                    if (curComp != null && t.depth >= appDepth + 2) {
                        val f = JSONObject().put("priority", t.attrs["priority"] ?: "0")
                        val actions = JSONArray(); val cats = JSONArray(); val datas = JSONArray()
                        f.put("actions", actions).put("categories", cats).put("data", datas)
                        val arr = curComp!!.optJSONArray("intent_filters") ?: JSONArray().also {
                            curComp!!.put("intent_filters", it)
                        }
                        arr.put(f)
                        curFilter = f
                    }
                }
                "action" -> {
                    val n = t.attrs["name"].orEmpty()
                    curFilter?.optJSONArray("actions")?.put(n)
                    if (n == "android.intent.action.MAIN" && curComp != null) launcher = curComp!!.optString("name")
                }
                "category" -> curFilter?.optJSONArray("categories")?.put(t.attrs["name"].orEmpty())
                "data" -> curFilter?.optJSONArray("data")?.put(JSONObject().apply {
                    t.attrs["scheme"]?.let { put("scheme", it) }
                    t.attrs["host"]?.let { put("host", it) }
                    t.attrs["path"]?.let { put("path", it) }
                    t.attrs["pathPrefix"]?.let { put("pathPrefix", it) }
                    t.attrs["mimeType"]?.let { put("mimeType", it) }
                })
                "meta-data" -> if (t.depth == appDepth + 1) meta.put(JSONObject()
                    .put("name", t.attrs["name"].orEmpty())
                    .put("value", (t.attrs["value"] ?: t.attrs["resource"]).orEmpty()))
            }
        }

        out.put("permissions", perms)
        out.put("features", features)
        out.put("components", JSONObject()
            .put("activities", act)
            .put("services", svc)
            .put("receivers", rcv)
            .put("providers", prv))
        out.put("launcher", launcher)
        appJson?.let { out.put("application", it) }
        out.put("meta_data", meta)
        out.put(
            "note",
            "从二进制 AndroidManifest.xml 直接解析（等价 aapt2 dump badging/xmltree，" +
                "但没有 aapt2 的进程开销）。exported 未显式写时按 Android 默认规则是 false（有 intent-filter 的历史版本除外）。"
        )
        return out
    }
}
