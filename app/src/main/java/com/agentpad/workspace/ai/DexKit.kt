package com.agentpad.workspace.ai

import android.content.Context
import org.luckypray.dexkit.DexKitBridge

/**
 * DexKit 对外入口（原生层，不经 proot / 不起 Java 进程）。
 *
 * ── 为什么快 ──────────────────────────────────────────────
 * DexKit 是 C++ 实现，直接在 App 进程内解析 dex。对比原先走终端的路子：
 *     App → proot(Alpine/Debian) → java -jar apktool.jar → fork aapt2
 * proot 要 ptrace 拦截并翻译**每个**系统调用，这是最大头。
 * 原生调 so 完全没这些开销 —— 同一个 APK，这里是毫秒级。
 *
 * ── so 从哪来 ────────────────────────────────────────────
 * 「下载模式」：so 不进 APK，云端编好后放官网（见 [DexKitRelease]），
 * 运行时下到私有目录 → 校验 SHA-256 → 置只读 → System.load。
 *
 * ── 与 DexKit 原版的关系 ─────────────────────────────────
 * 原版是 163 个 .kt（查询 DSL + flatbuffers 编解码）。这里只包一层
 * 转发，真正声明 native 的是 [DexKitBridge]（包名/类名/方法名必须
 * 与 so 导出的 JNI 符号对齐，见那个类的注释）。
 *
 * 当前能力：打开 APK/dex、统计 dex 数、导出 dex、线程数。
 * 查询类接口（用字符串批量反查类/方法）需要 flatbuffers 编解码，
 * 在 [DexKitBridge] 里已声明但未接编解码，后续补。
 */
object DexKit {

    /** DexKit 是否已可用（so 已装 + 已加载）。 */
    fun isAvailable(ctx: Context): Boolean =
        DexKitLoader.loaded || (DexKitLoader.isReady(ctx) && DexKitLoader.load(ctx).isSuccess)

    /** 确保可用：就绪直接用；没装就下载安装再加载。阻塞，放后台线程。 */
    fun ensure(ctx: Context): Result<Unit> = DexKitRelease.ensureReady(ctx)

    // ---------------- 打开 / 关闭 ----------------

    /** 打开 APK 或单个 dex 文件，返回句柄。用完必须 [close]。 */
    fun openApk(path: String): Result<Long> = DexKitBridge.open(path)

    /** 打开一组 dex 字节（脱壳 dump 的 dex 走这个）。 */
    fun openDex(dexList: List<ByteArray>): Result<Long> = DexKitBridge.openDex(dexList)

    fun close(handle: Long) = DexKitBridge.close(handle)

    // ---------------- 元信息 / 导出 ----------------

    fun dexCount(handle: Long): Int = DexKitBridge.dexCount(handle)

    fun setThreadNum(handle: Long, n: Int) = DexKitBridge.setThreadNum(handle, n)

    fun exportDex(handle: Long, outDir: String): Result<Unit> = DexKitBridge.exportDex(handle, outDir)

    // ---------------- 查询：用字符串批量反查（DexKit 招牌能力） ----------------

    /** 用字符串批量反查类。key=组名，value=该组的字符串列表。 */
    fun batchFindClassesByStrings(handle: Long, groups: Map<String, List<String>>): Map<String, List<String>> =
        DexKitBridge.batchFindClassesByStrings(handle, groups)

    /** 用字符串批量反查方法。 */
    fun batchFindMethodsByStrings(handle: Long, groups: Map<String, List<String>>): Map<String, List<String>> =
        DexKitBridge.batchFindMethodsByStrings(handle, groups)

    // ---------------- 环境自检（给「下载环境」卡片 / 诊断用） ----------------

    /**
     * 一次性自检：so 是否就绪、能否加载、打开一个 dex 是否能读。
     * 返回可读的键值对，方便直接显示或塞进工具结果。
     */
    fun selfCheck(ctx: Context): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        out["lib_path"] = DexKitLoader.libFile(ctx).absolutePath
        out["lib_ready"] = DexKitLoader.isReady(ctx)
        out["lib_size"] = runCatching { DexKitLoader.libFile(ctx).length() }.getOrDefault(0L)
        out["configured"] = DexKitRelease.isConfigured
        out["expected_sha256"] = DexKitRelease.SHA256
        val load = ensure(ctx)
        out["load_ok"] = load.isSuccess
        out["load_error"] = load.exceptionOrNull()?.message.orEmpty()
        out["loaded"] = DexKitBridge.isLoaded
        return out
    }
}