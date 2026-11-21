package com.agentpad.workspace.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 逆向模式（mode="reverse"）：用户选一个已安装的 APK，把它复制 + 解压（zip）
 * 到该会话的独立工作区，供 AI 用逆向工具链分析。
 *
 * 只做「复制 + 解包」，不做反编译（反编译交给 AI 用 apktool/jadx 在沙箱里做）。
 */
object ReverseProject {

    /** 一个已安装的 APK 条目。 */
    data class InstalledApk(
        val label: String,
        val packageName: String,
        val apkPath: String,
        val sizeBytes: Long,
        val versionName: String,
        val isSystem: Boolean,
    )

    /** 扫描已安装应用（含系统应用，按是否有 APK 文件过滤）。 */
    fun installedApks(context: Context): List<InstalledApk> {
        val pm = context.packageManager
        val out = mutableListOf<InstalledApk>()
        val flags = PackageManager.GET_META_DATA
        val packages = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(flags)
        }.getOrDefault(emptyList())

        for (pi in packages) {
            val ai = pi.applicationInfo ?: continue
            val src = ai.publicSourceDir ?: continue
            if (src.isBlank()) continue
            val file = File(src)
            if (!file.isFile) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            out.add(
                InstalledApk(
                    label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName),
                    packageName = pi.packageName,
                    apkPath = src,
                    sizeBytes = file.length(),
                    versionName = runCatching { pi.versionName ?: "" }.getOrDefault(""),
                    isSystem = isSystem,
                )
            )
        }
        // 用户应用在前，其余按名称排序
        return out.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    /** 解压结果：工作区里落的目录名。 */
    data class ExtractResult(val outDir: File, val entryCount: Int)

    /**
     * 把 [apk] 复制并解压到会话工作区。
     *  - 先把原 APK 拷成 <workspace>/<name>.apk
     *  - 再解压到 <workspace>/<name>/（目录）
     * 进度回调 [onProgress]：(已处理字节, 总字节)。可被中断：返回 false 即取消。
     */
    fun importApk(
        context: Context,
        sessionId: String,
        apk: InstalledApk,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ExtractResult {
        val workspace = ChatStore.ensureWorkspace(context, sessionId)
        val baseName = sanitize(apk.label.ifBlank { apk.packageName })

        // 1) 拷贝原 APK
        val apkFile = File(File(workspace, "_apk").apply { mkdirs() }, "$baseName.apk")
        apkFile.delete()
        File(apk.apkPath).inputStream().use { input ->
            FileOutputStream(apkFile).use { out -> input.copyTo(out, 64 * 1024) }
        }
        // 2) 解压（进度按「解压后总字节」精确推进）
        val outDir = File(File(workspace, "_apk").apply { mkdirs() }, baseName)
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()

        val apkSize = apkFile.length().coerceAtLeast(1L)
        var entryCount = 0

        java.util.zip.ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList()
            val totalUncompressed = entries.filter { !it.isDirectory }.sumOf { it.size }.coerceAtLeast(1L)
            var written = 0L
            // 拷贝阶段 = 前 15%
            onProgress((apkSize * 0.15).toLong(), apkSize)

            val buf = ByteArray(64 * 1024)
            for (entry in entries) {
                val target = File(outDir, entry.name)
                // 防 zip 路径穿越
                if (!target.canonicalPath.startsWith(outDir.canonicalPath + File.separator) &&
                    target.canonicalPath != outDir.canonicalPath) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                            }
                        }
                    }
                }
                entryCount++
                // 15% + 85% * (written / totalUncompressed)
                val frac = 0.15 + 0.85 * (written.toDouble() / totalUncompressed)
                onProgress((frac * apkSize).toLong().coerceIn(0, apkSize), apkSize)
            }
        }
        onProgress(apkSize, apkSize)
        return ExtractResult(outDir, entryCount)
    }


    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "apk_${System.currentTimeMillis()}" }
}