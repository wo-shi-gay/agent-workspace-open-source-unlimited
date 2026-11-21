package com.agentpad.workspace.ai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 沙箱 rootfs 现场取证。
 *
 * 为什么需要：2026-11-13 线上出现「终端报 no executable shell found in rootfs」
 * 且「下载环境 → 系统镜像」反复补装校验不过。App 私有目录 shell 读不到
 * （/data/data 无权限），只能让 App 自己把现场写到一个能读的地方。
 *
 * 输出：/sdcard/Download/agentpad-diag.txt（可被文件管理器和助手直接读取）
 */
object SandboxDiagnoser {

    private const val TAG = "SandboxDiag"
    const val OUT_PATH = "/storage/emulated/0/Download/agentpad-diag.txt"

    fun describe(context: Context): String {
        val sb = StringBuilder()
        fun p(k: String, v: String) { sb.append(k).append(" = ").append(v).append('\n') }
        fun f(file: File): String = when {
            !file.exists() && !isLink(file) -> "MISSING"
            isLink(file) && !file.exists() -> "DANGLING_SYMLINK"
            file.isDirectory -> "dir"
            file.isFile -> "file(${file.length()}B,exec=${file.canExecute()})"
            else -> "other"
        }

        p("time", System.currentTimeMillis().toString())
        p("activeId", DistroManager.activeId(context))
        p("sandboxDir", SandboxInstaller.sandboxDir(context).absolutePath)
        p("nativeDir", SandboxInstaller.nativeDir(context).absolutePath)

        val sandbox = SandboxInstaller.sandboxDir(context)
        p("sandbox.list", sandbox.list()?.joinToString(",") ?: "null")

        // rootfs 本体：软链？指向哪？目标在不在？
        val link = File(sandbox, "rootfs")
        p("rootfs.path", link.absolutePath)
        p("rootfs.isSymlink", isLink(link).toString())
        p("rootfs.exists", link.exists().toString())
        p("rootfs.isDirectory", link.isDirectory.toString())
        val canonical = runCatching { link.canonicalFile }.getOrNull()
        p("rootfs.canonical", canonical?.absolutePath ?: "FAILED")
        p("rootfs.canonicalExists", canonical?.exists().toString())

        // systems/ 下到底有什么
        val systemsDir = DistroManager.systemsDir(context)
        p("systemsDir", systemsDir.absolutePath)
        p("systemsDir.list", systemsDir.list()?.joinToString(",") ?: "null")

        DistroManager.all(context).forEach { d ->
            val dir = DistroManager.systemDir(context, d.id)
            sb.append("--- system ${d.id} ---\n")
            p("  dir", dir.absolutePath)
            p("  exists", dir.exists().toString())
            p("  list(head)", dir.list()?.take(12)?.joinToString(",") ?: "null/empty")
            listOf(
                ".installed-v2", ".distro-installed",
                "bin/sh", "bin/busybox", "bin/dash",
                "usr/bin/sh", "usr/bin/dash", "usr/bin/bash", "bin/bash",
                "usr/bin/dpkg", "usr/bin/apt-get", "sbin/apk", "usr/bin/apk",
                "etc/resolv.conf",
            ).forEach { n -> p("  $n", f(File(dir, n))) }
            p("  isInstalled()", DistroManager.isInstalled(context, d.id).toString())
        }

        // 顺带看看有没有残留 salvage 目录占地方
        sandbox.listFiles { f -> f.isDirectory && f.name.startsWith("salvage-") }?.forEach {
            p("salvage", "${it.name} entries=${it.list()?.size ?: 0}")
        }

        p("isHealthy", SandboxInstaller.isHealthy(context).toString())
        p("status", SandboxInstaller.status(context).toString())

        // 脚本是否真的刷新到位：比对 files/ 下的副本与 APK asset 的字节
        val script = SandboxInstaller.initScript(context)
        p("initScript", script.absolutePath)
        p("initScript.exists", script.isFile.toString())
        p("initScript.size", if (script.isFile) script.length().toString() else "-")
        val verFile = File(script.parentFile, script.name + ".ver")
        p("initScript.ver", if (verFile.isFile) verFile.readText().trim() else "MISSING")
        p("initScript.hasBusyboxFix", runCatching {
            script.isFile && script.readText().contains("SHELL_APP")
        }.getOrDefault(false).toString())
        p("initScript.matchesAsset", runCatching {
            val a = context.assets.open("sandbox/init-sandbox.sh").use { it.readBytes() }
            val b = if (script.isFile) script.readBytes() else ByteArray(0)
            a.contentEquals(b)
        }.getOrDefault(false).toString())

        val text = sb.toString()
        Log.w(TAG, "diag:\n$text")
        runCatching {
            File(OUT_PATH).writeText(text)
        }
        return text
    }

    private fun isLink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}