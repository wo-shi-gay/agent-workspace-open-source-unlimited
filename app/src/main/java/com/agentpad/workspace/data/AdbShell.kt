package com.agentpad.workspace.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ADB 级 Shell 执行器（经 Shizuku 以 shell uid 2000 身份运行）。
 *
 * Shizuku 13.1.5 的 API 包未公开 newProcess（14.x 才公开），
 * 因此这里用反射按「公开方法 → 私有方法 → 构造器」三级兜底。
 * 对外接口稳定，内部实现可随依赖版本替换。
 */
object AdbShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun available(ctx: Context): Boolean =
        AdbPermission.isEnabled(ctx) && AdbPermission.isShizukuAlive()

    /**
     * 以 ADB（shell）身份执行命令。
     * 输出重定向到临时文件再读取（Shizuku 进程通道不直接返回 stdout）。
     */
    fun exec(ctx: Context, command: String, timeoutMs: Long = 60_000): Result {
        if (!available(ctx)) return Result(127, "", "ADB 权限未开启（请在设置中开启）")

        val stamp = System.currentTimeMillis()
        val outFile = File(ctx.cacheDir, "adb_out_$stamp.txt")
        val errFile = File(ctx.cacheDir, "adb_err_$stamp.txt")
        val codeFile = File(ctx.cacheDir, "adb_code_$stamp.txt")

        return try {
            val wrapped =
                "($command) >'${outFile.absolutePath}' 2>'${errFile.absolutePath}'; " +
                    "echo \$? >'${codeFile.absolutePath}'"

            val start = System.currentTimeMillis()
            val ok = runShell(wrapped, timeoutMs)
            val cost = System.currentTimeMillis() - start

            val exit = try { codeFile.readText().trim().toInt() } catch (_: Exception) { if (ok) 0 else 1 }
            val stdout = try { outFile.readText() } catch (_: Exception) { "" }
            val stderr = try { errFile.readText() } catch (_: Exception) { "" }

            runCatching { outFile.delete(); errFile.delete(); codeFile.delete() }

            AppLog.i("AdbShell", "[${cost}ms] exit=$exit cmd=${command.take(100)}")
            Result(exit, stdout, stderr)
        } catch (e: Exception) {
            AppLog.e("AdbShell", "执行失败: ${command.take(80)}", e)
            runCatching { outFile.delete(); errFile.delete(); codeFile.delete() }
            Result(1, "", "ADB 执行异常：${e.message}")
        }
    }

    /** 启动进程并等待完成。 */
    private fun runShell(wrapped: String, timeoutMs: Long): Boolean {
        val process = newProcessCompat(arrayOf("sh", "-c", wrapped)) ?: return false
        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) runCatching { process.destroyForcibly() }
            finished
        } catch (_: Exception) {
            false
        }
    }

    /** 三级兜底获取 Shizuku 远程进程。 */
    private fun newProcessCompat(cmd: Array<String>): Process? {
        val cls = try { Class.forName("rikka.shizuku.Shizuku") } catch (_: Exception) { null }

        if (cls != null) {
            // 1) 公开静态方法
            try {
                val m = cls.getMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                (m.invoke(null, cmd, null, null) as? Process)?.let { return it }
            } catch (_: Exception) { }

            // 2) 私有静态方法
            try {
                val m = cls.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                (m.invoke(null, cmd, null, null) as? Process)?.let { return it }
            } catch (_: Exception) { }
        }

        // 3) ShizukuRemoteProcess 构造器（(String[], String[], String)）
        try {
            val rp = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
            for (ctor in rp.declaredConstructors) {
                if (ctor.parameterTypes.size != 3) continue
                ctor.isAccessible = true
                val inst = try { ctor.newInstance(cmd, null, null) } catch (_: Exception) { null }
                if (inst is Process) return inst
            }
        } catch (_: Exception) { }

        AppLog.w("AdbShell", "Shizuku.newProcess 不可用（请升级 Shizuku 到 14+）")
        return null
    }

    fun isRoot(ctx: Context): Boolean =
        runCatching { exec(ctx, "id -u", 5000).stdout.trim() == "0" }.getOrDefault(false)

    fun identity(ctx: Context): String = runCatching {
        val r = exec(ctx, "id", 5000)
        if (r.exitCode == 0) r.stdout.trim() else "unknown"
    }.getOrDefault("unknown")
}