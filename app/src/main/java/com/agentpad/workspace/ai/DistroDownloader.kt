package com.agentpad.workspace.ai

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 系统镜像（rootfs）下载 + 解压。
 *
 * 为什么单独一个类而不塞进 [DistroManager]：
 *  下载要带进度回调和超时控制，解压要支持三种格式（tar.gz / tar.xz / tar），
 *  都是 200+ 行的重逻辑。管理器只负责「装哪个、装到哪」，这里是「怎么搬」。
 *
 * ## 格式支持
 *  - `tar.gz` / `tgz`：GZIPInputStream 套 tar
 *  - `tar.xz` / `txz`：**Android 没有 org.tukaani.xz**，用沙箱外的系统命令解不开，
 *    所以这条路只能靠 [extractTar] 的裸 tar 通道 —— xz 会直接报错提示用户换 gz 源。
 *    真需要 xz 的，让用户先用电脑转成 tar.gz 再贴链接。
 *  - `tar`：裸 tar
 *
 * ## 解压实现要点（照 SandboxInstaller.extractTarGz 的血泪）
 *  1. tar 字符串字段必须在**第一个 NUL** 处截断，不能 trimEnd —— 有些包的 name
 *     字段 NUL 后面还有垃圾字节（python 包实测 "2to3\0in/2to3"）。
 *  2. 软链（type '2'）必须真的建，建不动要退化拷贝，否则 Alpine/Debian 的
 *     `/bin/sh -> busybox`、`/usr/bin/sh -> dash` 全断，一进沙箱就 127。
 *  3. 硬链（type '1'）找同目录目标文件拷贝。
 *  4. 路径穿越防护：normalize() 后必须仍在 destination 内。
 *  5. 权限位 0x40（user exec）要还原，否则 rootfs 里的命令全都不可执行。
 */
object DistroDownloader {

    /** 下载，progress 0..100。支持小飞机分享链接（自动解析直链）。 */
    fun download(context: Context, distro: DistroManager.Distro, target: File, progress: (Int) -> Unit) {
        var url = distro.url
        var referer: String? = null
        // 小飞机分享页 → 先解析成直链（跟 DownloadEnvironmentActivity 同一条路）
        if (url.contains("feijipan.com/s/")) {
            progress(1)
            val resolved = org.json.JSONObject(FeijipanParser.resolve(url))
            url = resolved.getString("url")
            referer = "https://www.feijipan.com/"
        }
        target.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 600_000      // 大文件 + 慢网络，给 10 分钟
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "*/*")
            referer?.let { setRequestProperty("Referer", it) }
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code：$url")
            val total = conn.contentLengthLong
            var last = -1
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { buffered ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(128 * 1024)
                        var read: Int
                        var done = 0L
                        while (buffered.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) { last = pct; progress(pct) }
                            }
                        }
                    }
                }
            }
            // 完整性校验：content-length 对不上就是被截断了（弱网常见）
            if (total > 0 && target.length() != total) {
                throw IllegalStateException("下载不完整：${target.length()}/$total 字节")
            }
            if (target.length() < 1024) throw IllegalStateException("文件过小（${target.length()} 字节），不是有效的系统镜像")
        } finally {
            conn.disconnect()
        }
    }

    /** 按 kind 解压到 destination。 */
    fun extract(archive: File, destination: File, kind: String, progress: (Int) -> Unit = {}) {
        destination.mkdirs()
        when (kind) {
            "tar.gz" -> {
                GZIPInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024)).use {
                    extractTar(it, destination, progress)
                }
            }
            "tar" -> {
                BufferedInputStream(archive.inputStream(), 256 * 1024).use {
                    extractTar(it, destination, progress)
                }
            }
            "tar.xz" -> throw IllegalStateException(
                "暂不支持 .tar.xz：Android 无内置 xz 解压。请改用 .tar.gz 链接，" +
                    "或先在电脑上 `xz -d` 转成 tar.gz 再贴链接。"
            )
            else -> throw IllegalStateException("未知包格式：$kind")
        }
    }

    /**
     * 裸 tar 流解压（PAX/GNU 扩展头一并处理）。
     *
     * 与 SandboxInstaller 的两份实现相比，这份**必须**处理 PAX：
     * 官方 Alpine/Debian rootfs 是 PAX 格式，不解析 'x'/'g'/'L'/'K' 头会把
     * 扩展数据当成普通文件写进去，整个流立即错位。
     */
    private fun extractTar(input: java.io.InputStream, destination: File, progress: (Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var entries = 0L
        var lastPct = -1
        var extName: String? = null
        var extSize: Long? = null
        var extLink: String? = null

        fun field(b: ByteArray, start: Int, len: Int): String {
            var end = start
            val stop = minOf(start + len, b.size)
            while (end < stop && b[end] != 0.toByte()) end++
            return b.copyOfRange(start, end).decodeToString()
        }

        while (true) {
            val header = readFully(input, 512) ?: break
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()

            // ---- PAX / GNU 扩展头 ----
            // ⚠ 局部变量绝不能叫 extSize：会遮蔽外层的 var extSize，
            //   往里写就变成「给 val 赋值」，编译直接 VAL_REASSIGNMENT。
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                val payloadLen = parseOctal(header, 124, 12)
                val raw = readFully(input, payloadLen.toInt()) ?: break
                skip(input, ((512 - (payloadLen % 512)) % 512))
                if (type == 'L') extName = String(raw).trimEnd('\u0000')
                else if (type == 'K') extLink = String(raw).trimEnd('\u0000')
                else {
                    String(raw).split("\n", "\u0000").forEach { seg ->
                        val line = seg.trim('\u0000')
                        if (line.isBlank()) return@forEach
                        val sp = line.indexOf(' ')
                        if (sp > 0) {
                            when (line.substring(0, sp)) {
                                "path" -> extName = line.substring(sp + 1).replace("\u0000", "").trim()
                                "size" -> extSize = line.substring(sp + 1).replace("\u0000", "").trim().toLongOrNull()
                                "linkpath" -> extLink = line.substring(sp + 1).replace("\u0000", "").trim()
                            }
                        }
                    }
                }
                continue
            }

            val name = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val relative = (extName ?: if (prefix.isBlank()) name else "$prefix/$name").trim()
            val size = extSize ?: parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8)
            val linkName = extLink ?: field(header, 157, 100)
            extName = null; extSize = null; extLink = null

            if (relative.isNotEmpty() && relative != "." && relative != "./") {
                // 路径穿越防护
                val target = File(destination, relative).normalize()
                val destPath = destination.path
                require(
                    target.path == destPath || target.path.startsWith(destPath + File.separator)
                ) { "非法归档路径：$relative" }

                when (type) {
                    '5' -> if (!target.isDirectory) target.mkdirs()
                    '2' -> {   // 符号链接 —— rootfs 的生命线，必须建
                        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        runCatching { if (target.exists()) target.delete() }
                        val ok = runCatching {
                            java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                            true
                        }.getOrDefault(false)
                        if (!ok) {
                            // 设备不让建软链：退化成拷贝目标文件内容（能跑就行）
                            val src = File(target.parentFile, linkName)
                            if (src.isFile) src.copyTo(target, overwrite = true)
                        }
                    }
                    '1' -> {   // 硬链接
                        val src = File(target.parentFile, linkName)
                        if (src.isFile) { target.delete(); src.copyTo(target, overwrite = true) }
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        FileOutputStream(target).use { out ->
                            var left = size
                            while (left > 0) {
                                val want = minOf(buf.size.toLong(), left).toInt()
                                val read = input.read(buf, 0, want)
                                if (read < 0) throw IllegalStateException("归档被截断：$relative")
                                out.write(buf, 0, read)
                                left -= read
                            }
                        }
                        target.setReadable(true, false)
                        if ((mode and 0x40L) != 0L) target.setExecutable(true, false)
                    }
                    else -> skip(input, size)   // '3'/'4'/'6'/'7' 等，rootfs 用不到
                }
                entries++
            } else if (type != '5') {
                skip(input, size)
            }

            skip(input, (512 - (size % 512)) % 512)
            // rootfs 文件数上万，按条目数推进度（没有可靠的总字节数）
            if (entries % 500 == 0L) {
                val pct = (entries / 40).coerceAtMost(99L).toInt()
                if (pct != lastPct) { lastPct = pct; progress(pct) }
            }
        }
        progress(100)
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long =
        bytes.copyOfRange(offset, offset + length).decodeToString()
            .trim('\u0000', ' ').ifBlank { "0" }.trim().toLong(8)

    private fun readFully(input: java.io.InputStream, count: Int): ByteArray? {
        val b = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(b, off, count - off)
            if (n < 0) return if (off == 0) null else throw IllegalStateException("truncated tar")
            off += n
        }
        return b
    }

    private fun skip(input: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            if (input.read() < 0) throw IllegalStateException("truncated tar")
            left--
        }
    }
}