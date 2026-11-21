package com.agentpad.workspace.ai

import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 解包 / 重打包 / 签名（纯 Java，原生层，不经 proot）。
 *
 * 这是「改完能装」的最后两环：
 *   ① 解包：把 APK 当 zip 拆开（不反编译资源，资源保持二进制原样）
 *   ② 重打包：把改过的 dex 塞回去，其余 entry **逐字节原样保留**
 *   ③ 签名：v1+v2 重签（apksig 官方库），否则装不上
 *
 * ⚠ 重要事实（别踩）：
 *   · 重签后的 APK 与原应用**签名不同**，无法覆盖安装原版；
 *     只能换包名重装，或把它当新应用分发。
 *   · 这里**不处理 resources.arsc / 二进制 XML**。
 *     只改 smali（代码逻辑）时不需要动资源，这条路是通的。
 *     要改 AndroidManifest / res 里的东西 → 必须过 aapt2（另一步）。
 *   · resources.arsc 必须用 STORED（不压缩）方式写回，否则 Android 12+
 *     校验会失败（installed 时报 "resources.arsc is not stored"）。
 */
object ApkRepack {

    /** 必须保持不压缩的 entry（Android 要求）。 */
    private val STORED_ENTRIES = setOf("resources.arsc")
    private val STORED_PREFIXES = listOf("lib/", "assets/dexopt/")

    // ================= ① 解包 =================

    /**
     * 解包 APK 到目录（纯 zip 解压，资源保持原样二进制）。
     * @return 解出来的 entry 数
     */
    fun unzip(apk: File, outDir: File): Result<Int> = runCatching {
        require(apk.isFile) { "APK 不存在：${apk.absolutePath}" }
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()
        var n = 0
        ZipFile(apk).use { zf ->
            val base = outDir.canonicalPath + File.separator
            zf.entries().toList().forEach { e ->
                val target = File(outDir, e.name)
                if (!target.canonicalPath.startsWith(base)) return@forEach
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins ->
                        target.outputStream().use { ins.copyTo(it) }
                    }
                    n++
                }
            }
        }
        n
    }

    /** 列出 APK 里的所有 dex entry（classes.dex / classes2.dex …）。 */
    fun dexEntries(apk: File): List<String> = runCatching {
        ZipFile(apk).use { zf ->
            zf.entries().toList()
                .map { it.name }
                .filter { it.matches(Regex("""classes\d*\.dex""")) }
                .sorted()
        }
    }.getOrDefault(emptyList())

    // ================= ② 重打包 =================

    /**
     * 用目录内容重新打一个 APK（zip）。
     *
     * @param srcDir    解包出来的目录
     * @param outApk    输出
     * @param replace   额外替换的 entry：entryName → 本地文件（用来塞回改好的 dex）
     */
    fun zip(srcDir: File, outApk: File, replace: Map<String, File> = emptyMap()): Result<Long> =
        runCatching {
            require(srcDir.isDirectory) { "目录不存在：${srcDir.absolutePath}" }
            outApk.parentFile?.mkdirs()
            if (outApk.exists()) outApk.delete()

            val replaced = replace.mapValues { it.value.readBytes() }
            ZipOutputStream(outApk.outputStream().buffered()).use { zos ->
                // 1) 先把 replace 里「目录中不存在」的 entry 写进去
                srcDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(srcDir).path.replace('\\', '/')
                    // 已被 replace 覆盖的跳过，稍后统一按 replace 写
                    writeEntry(zos, rel, replaced[rel] ?: f.readBytes())
                }
                // 2) replace 里新增的 entry（目录里原本没有的）
                replaced.forEach { (name, bytes) ->
                    if (!File(srcDir, name).isFile) writeEntry(zos, name, bytes)
                }
            }
            outApk.length()
        }

    private fun writeEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        val mustStore = name in STORED_ENTRIES || STORED_PREFIXES.any { name.startsWith(it) }
        if (mustStore) {
            // ⚠ resources.arsc / lib / dexopt 必须 STORED，否则 Android 12+ 安装失败
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc32(bytes)
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun crc32(bytes: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(bytes)
        return c.value
    }

    // ================= ③ 签名 =================

    /**
     * 用 keystore 重签 APK（v1 + v2 + v3）。
     *
     * @param keystore     .jks / .p12
     * @param storePass    库口令
     * @param alias        别名
     * @param keyPass      密钥口令（通常同 storePass）
     */
    fun sign(
        inApk: File,
        outApk: File,
        keystore: File,
        storePass: String,
        alias: String,
        keyPass: String,
    ): Result<Long> = runCatching {
        require(inApk.isFile) { "待签名 APK 不存在：${inApk.absolutePath}" }
        require(keystore.isFile) { "keystore 不存在：${keystore.absolutePath}" }
        outApk.parentFile?.mkdirs()
        if (outApk.exists()) outApk.delete()

        val ks = KeyStore.getInstance(keystore.extension?.lowercase()?.let {
            if (it == "p12" || it == "pfx") "PKCS12" else "JKS"
        } ?: "JKS").apply {
            keystore.inputStream().use { load(it, storePass.toCharArray()) }
        }
        val key = ks.getKey(alias, keyPass.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val signer = ApkSigner.SignerConfig.Builder(alias, key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(inApk)
            .setOutputApk(outApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)   // 可选；Android 9+ 用 v3 轮换密钥，保留无害
            .build()
            .sign()

        require(outApk.isFile && outApk.length() > 0) { "签名后没有产物" }
        outApk.length()
    }

    // ================= 便捷：一站式「改 dex → 重打包 → 签名」 =================

    /**
     * 把新的 dex 塞回原 APK 并重签。
     *
     * @param originalApk 原始 APK（提供所有非 dex 的资源 entry）
     * @param newDex      改好后重新汇编出来的 dex
     * @param dexEntryName 要替换的 entry 名，默认 "classes.dex"
     */
    fun replaceDexAndSign(
        originalApk: File,
        newDex: File,
        outApk: File,
        keystore: File,
        storePass: String,
        alias: String,
        keyPass: String,
        dexEntryName: String = "classes.dex",
        workDir: File,
    ): Result<Long> = runCatching {
        val unz = File(workDir, "unzip")
        unzip(originalApk, unz).getOrThrow()

        // 把新 dex 放到 workDir 里作为替换源
        val dexCopy = File(workDir, dexEntryName).apply {
            parentFile?.mkdirs()
            newDex.inputStream().use { i -> outputStream().use { i.copyTo(it) } }
        }

        val unsigned = File(workDir, "repacked-unsigned.apk")
        zip(unz, unsigned, mapOf(dexEntryName to dexCopy)).getOrThrow()

        sign(unsigned, outApk, keystore, storePass, alias, keyPass).getOrThrow()
    }
}