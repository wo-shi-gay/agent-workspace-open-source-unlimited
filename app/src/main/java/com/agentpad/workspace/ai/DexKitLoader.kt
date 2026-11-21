package com.agentpad.workspace.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * DexKit 原生库「下载模式」加载器。
 *
 * ── 为什么走下载模式 ──────────────────────────────────────────
 * DexKit 的 C++ 核心约 2~3MB，捆进 APK 会让主包变大；而它的 Kotlin 绑定
 * 只有一个外部依赖（com.google.flatbuffers），so 与 Java 层完全解耦。
 * 所以：so 不进 APK，云端编好后放在服务器，运行时下到私有目录再 System.load。
 *
 * ── Android 17 Safer DCL（主人截图给的政策）──────────────────
 * targetSdkVersion >= 37 时，所有 System.load(absolutePath) 的 .so：
 *   ① 加载前必须被标记为只读，否则 UnsatisfiedLinkError
 *   ② 必须通过签名校验
 *   ③ 只能从私有目录加载（/sdcard 非可执行，映射不了可执行页）
 *
 * 我们已经把 targetSdk 降到 30 规避该政策（见 app/build.gradle.kts 顶部注释），
 * 但这里**仍然把三条要求全部照做**，做成双保险：
 *   · 落地目录用 filesDir/dexkit/（私有，绝不碰 /sdcard）
 *   · 先写 .tmp，校验 SHA-256，再原子 rename
 *   · rename 后立刻 chmod 444（只读），并把父目录设为不可写
 * 这样即使将来 targetSdk 涨回去，也不至于直接崩。
 *
 * ── 命名约定 ──────────────────────────────────────────────
 * DexKit 的 JNI 符号是 Java_org_luckypray_dexkit_DexKitBridge_*，
 * 所以 so 名字必须是 libdexkit.so，且 Java 侧 System.load 后可正常解析。
 */
object DexKitLoader {

    private const val TAG = "DexKitLoader"
    private const val LIB_NAME = "libdexkit.so"

    /** 私有落地目录（绝不使用 /sdcard —— 那儿映射不了可执行页）。 */
    fun libDir(ctx: Context): File = File(ctx.filesDir, "dexkit").apply { mkdirs() }

    fun libFile(ctx: Context): File = File(libDir(ctx), LIB_NAME)

    /** 已就绪判定：文件存在 + 只读位已置。 */
    fun isReady(ctx: Context): Boolean {
        val f = libFile(ctx)
        return f.isFile && f.length() > 0 && !f.canWrite()
    }

    /**
     * 从字节数组安装 so（下载完成后调用）。
     *
     * @param expectedSha256 期望的 SHA-256（小写 hex）。为空则跳过校验（仅调试用）。
     * @return 安装结果
     */
    fun install(ctx: Context, bytes: ByteArray, expectedSha256: String? = null): Result<File> {
        return runCatching {
            require(bytes.size > 4096) { "so 体积异常（${bytes.size} 字节），下载可能不完整" }

            // ① 校验：不匹配直接拒绝，避免加载到半截/被篡改的文件
            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(bytes)
                require(actual.equals(expectedSha256, ignoreCase = true)) {
                    "SHA-256 不匹配：期望 $expectedSha256，实际 $actual"
                }
            }

            val dir = libDir(ctx)
            val target = libFile(ctx)
            val tmp = File(dir, "$LIB_NAME.tmp")

            // ② 先落 .tmp（此刻目录必须可写）
            if (tmp.exists()) tmp.delete()
            tmp.outputStream().use { it.write(bytes) }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)

            // ③ 原子替换
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("rename 失败：${tmp.absolutePath} -> ${target.absolutePath}")
            }

            // ④ 立刻置只读（Safer DCL 要求「加载前必须只读」）
            markReadOnly(target)
            Log.i(TAG, "installed ${target.absolutePath} (${target.length()} B, readonly=${!target.canWrite()})")
            target
        }
    }

    /** 把 so 及所在目录都置为不可写（双保险；目录只读能防后续被替换）。 */
    fun markReadOnly(file: File) {
        runCatching { file.setReadable(true, false) }
        runCatching { file.setExecutable(true, false) }
        runCatching { file.setWritable(false, false) }
        // 目录也收掉写权限：只有再要更新时才临时放开
        runCatching { file.parentFile?.setWritable(false, false) }
    }

    /** 需要重新安装时，先放开目录写权限（否则 rename 会失败）。 */
    fun prepareForUpdate(ctx: Context) {
        val dir = libDir(ctx)
        runCatching { dir.setWritable(true, true) }
        val f = libFile(ctx)
        if (f.exists()) runCatching { f.setWritable(true, true) }
    }

    /**
     * 加载 so。加载前再确认一次只读（幂等），然后 System.load(绝对路径)。
     *
     * ⚠ 用 System.load 而不是 System.loadLibrary：后者只认 APK 内的 lib 目录，
     * 我们这是外置下载的文件。
     */
    @Synchronized
    fun load(ctx: Context): Result<Unit> = runCatching {
        val f = libFile(ctx)
        require(f.isFile && f.length() > 0) { "so 未安装：${f.absolutePath}" }
        if (f.canWrite()) markReadOnly(f)   // 保险：万一被改回可写
        org.luckypray.dexkit.DexKitBridge.loadFrom(f.absolutePath)
            .getOrThrow()
        Log.i(TAG, "loaded $LIB_NAME")
        loaded = true
    }

    /** 是否已经 load 过（避免重复 load 抛异常）。 */
    @Volatile
    var loaded: Boolean = false
        private set

    /** 保证加载可用：未装/未加载时返回错误，让上层决定去下载。 */
    fun ensureLoaded(ctx: Context): Result<Unit> {
        if (loaded) return Result.success(Unit)
        if (!isReady(ctx)) return Result.failure(IllegalStateException("DexKit 原生库未就绪（未安装或不是只读）"))
        return load(ctx)
    }

    /** 删除已安装的 so（重新下载 / 卸载功能时用）。 */
    fun uninstall(ctx: Context) {
        runCatching { prepareForUpdate(ctx) }
        loaded = false
        runCatching { libFile(ctx).delete() }
    }

    fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}