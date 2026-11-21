package com.agentpad.workspace.ai

import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File

/**
 * dex ↔ smali 互转（原生库调用，不起 Java 进程、不过 proot）。
 *
 * ── 为什么这是「能改 APK」的分水岭 ──
 * DexKit 只能「定位」被混淆的类/方法，它不给文本、也不接受修改。
 * 要真正改逻辑必须有一个来回：
 *     dex   --baksmali-->  smali 文本   ← AI/人能读、能改
 *     smali --smali-->     dex         ← 改完编译回去
 *
 * ── API 来源（全部用 javap 核实过，不是凭记忆）──
 *   org.jf.baksmali.Baksmali
 *     static boolean disassembleDexFile(DexFile, File outDir, int jobs, BaksmaliOptions)
 *   org.jf.smali.Smali
 *     static boolean assemble(SmaliOptions, String... inputPaths)
 *   org.jf.dexlib2.DexFileFactory
 *     static DexBackedDexFile loadDexFile(File, Opcodes)
 *     static MultiDexContainer loadDexContainer(File, Opcodes)
 *     static void writeDexFile(String, DexFile)
 *   org.jf.dexlib2.Opcodes.forApi(int)
 */
object SmaliToolchain {

    /** 默认按 Android 11（API 30）生成 opcodes —— 与项目 targetSdk 一致。 */
    const val DEFAULT_API = 30

    // ================= dex → smali =================

    /**
     * 反汇编：dex（或 apk 里的第一个 dex）→ smali 文本目录。
     * @return 生成的 .smali 文件数
     */
    fun dexToSmali(dexOrApk: File, outDir: File, apiLevel: Int = DEFAULT_API): Result<Int> =
        runCatching {
            require(dexOrApk.isFile) { "输入不存在：${dexOrApk.absolutePath}" }
            if (outDir.exists()) outDir.deleteRecursively()
            outDir.mkdirs()

            val dex: DexFile = if (
                dexOrApk.name.endsWith(".apk", true) || dexOrApk.name.endsWith(".zip", true)
            ) {
                val container = DexFileFactory.loadDexContainer(dexOrApk, Opcodes.forApi(apiLevel))
                val entryName = container.dexEntryNames.firstOrNull()
                    ?: error("APK 里没有 dex：${dexOrApk.name}")
                container.getEntry(entryName)?.dexFile
                    ?: error("读取 dex entry 失败：$entryName")
            } else {
                DexFileFactory.loadDexFile(dexOrApk, Opcodes.forApi(apiLevel))
            }

            val opts = BaksmaliOptions().apply {
                this.apiLevel = apiLevel
                debugInfo = true          // 保留行号/局部变量名，便于读懂
                parameterRegisters = true
                sequentialLabels = false
                accessorComments = false
                implicitReferences = true
            }

            // ⚠ 反汇编同样降到 1 个 job：baksmali 的每个 job 都会缓存类定义与指令，
            //   并发反汇编 12MB 的 dex 也会把 384MB 堆吃光（与汇编同一个坑）。
            val jobs = 1
            val ok = Baksmali.disassembleDexFile(dex, outDir, jobs, opts)
            require(ok) { "baksmali 反汇编返回 false" }
            countSmali(outDir)
        }

    // ================= smali → dex =================

    /**
     * 汇编：smali 目录（或若干 .smali 文件）→ 一个 dex。
     * @return 生成的 dex 字节数
     */
    fun smaliToDex(inputs: List<File>, outDex: File, apiLevel: Int = DEFAULT_API): Result<Long> =
        runCatching {
            require(inputs.isNotEmpty()) { "没有输入" }
            inputs.forEach { require(it.exists()) { "输入不存在：${it.absolutePath}" } }
            outDex.parentFile?.mkdirs()
            if (outDex.exists()) outDex.delete()

            val opts = SmaliOptions().apply {
                this.apiLevel = apiLevel
                outputDexFile = outDex.absolutePath
                // ⚠ 必须 1！真机实测 OOM（2026-11-16）：
                //   java.lang.OutOfMemoryError: Failed to allocate a 32 byte allocation
                //   target footprint 402653184, growth limit 402653184   ← 384MB 堆
                //   原来用 availableProcessors()（8 线程）并行汇编，每个线程各持一套
                //   ANTLR 语法树 + DexBuilder 缓冲，内存 ×8；12MB 的 dex 单线程已要 300MB+。
                jobs = 1
                verboseErrors = true
            }

            // ⚠ 签名：assemble(SmaliOptions, String...) —— 必须显式建 Array<String>。
            //   直接写 `inputs.map { it.absolutePath }.toTypedArray()` 会让 Kotlin
            //   推断出 Array<T (uninferred)>，与 vararg String! 不匹配，
            //   编译器报 "None of the following candidates is applicable"
            //   （云构建 run 43849550307 就栽在这里）。
            val paths: Array<String> = Array(inputs.size) { i -> inputs[i].absolutePath }
            val ok = Smali.assemble(opts, *paths)
            require(ok) { "smali 汇编返回 false" }
            require(outDex.isFile && outDex.length() > 0) {
                "汇编后没有产出 dex：${outDex.absolutePath}"
            }
            outDex.length()
        }

    /** 便捷：单个 smali 目录 → dex。 */
    fun smaliDirToDex(smaliDir: File, outDex: File, apiLevel: Int = DEFAULT_API): Result<Long> =
        smaliToDex(listOf(smaliDir), outDex, apiLevel)

    // ================= 只读快路径（不经过文本） =================

    /** 读 dex 再原样写出 —— 用来验证 dexlib2 链路通不通。 */
    fun rewriteDex(src: File, outDex: File, apiLevel: Int = DEFAULT_API): Result<Long> =
        runCatching {
            val dex = DexFileFactory.loadDexFile(src, Opcodes.forApi(apiLevel))
            outDex.parentFile?.mkdirs()
            DexFileFactory.writeDexFile(outDex.absolutePath, dex)
            outDex.length()
        }

    // ================= 低内存快路径：类级替换（避开全量汇编） =================
    //
    // ⚠ 为什么需要它（2026-11-16 真机 OOM 后加的）：
    //   `smaliToDex` 会把**整个** smali 树喂给 Smali.assemble，ANTLR 语法树
    //   + DexBuilder 缓冲加起来是 dex 体积的 20~40 倍。12MB 的 dex 就是
    //   300~500MB —— 超过 App 的 384MB 堆上限（除非 largeHeap 能挤出来），必炸。
    //
    //   但实际改 APK 时，99% 的情况只动**一个类**。所以正确做法是：
    //     ① 只编译改动的那几个 .smali → 得到一个小 dex（几十 KB，内存忽略不计）
    //     ② 用 dexlib2 把小 dex 里的类**替换**进原 dex，其余类原样搬
    //   第 ② 步的内存占用只有原 dex 的 3~5 倍（12MB → 40~60MB），能过。
    //
    //   这和 CAssistant 那类工具用 D8 做 dex 合并是同一个思路，只是我们用
    //   dexlib2 的 DexPool（更轻，不用背 R8 那 5MB 的编译器）。

    /**
     * 把 patchDex 里的类替换进 baseDex，输出 mergedDex。
     * 同名的类以 patch 为准；patch 里独有的类会追加进去。
     */
    fun mergePatchIntoBase(
        baseDex: File,
        patchDex: File,
        outDex: File,
        apiLevel: Int = DEFAULT_API,
    ): Result<Long> = runCatching {
        require(baseDex.isFile) { "基底 dex 不存在：${baseDex.absolutePath}" }
        require(patchDex.isFile) { "补丁 dex 不存在：${patchDex.absolutePath}" }
        val opcodes = Opcodes.forApi(apiLevel)
        val base = DexFileFactory.loadDexFile(baseDex, opcodes)
        val patch = DexFileFactory.loadDexFile(patchDex, opcodes)

        val patchMap = HashMap<String, ClassDef>(patch.classes.size * 2)
        patch.classes.forEach { patchMap[it.type] = it }

        val merged = ArrayList<ClassDef>(base.classes.size + patchMap.size)
        var replaced = 0
        base.classes.forEach { cls ->
            val repl = patchMap.remove(cls.type)
            if (repl != null) replaced++
            merged.add(repl ?: cls)
        }
        merged.addAll(patchMap.values)

        outDex.parentFile?.mkdirs()
        if (outDex.exists()) outDex.delete()
        DexPool.writeTo(outDex.absolutePath, ImmutableDexFile(opcodes, merged))
        require(outDex.isFile && outDex.length() > 0) { "合并后没有产出 dex" }
        outDex.length()
    }

    /**
     * **推荐路径**：只编译改动过的 .smali 文件，再合并回原 dex。
     *
     * @param baseDex    原始 dex（从 APK 里解出来的那个）
     * @param smaliFiles 改过的 .smali 文件（不要传整个目录！只传改动的那几个）
     * @param outDex     输出 dex
     * @return (dex 字节数, 被替换的类数)
     */
    fun patchClassesBySmali(
        baseDex: File,
        smaliFiles: List<File>,
        outDex: File,
        apiLevel: Int = DEFAULT_API,
    ): Result<Pair<Long, Int>> = runCatching {
        require(smaliFiles.isNotEmpty()) { "没有要编译的 smali 文件" }

        // ① 只编译这几个类 → 小 dex
        val patchDex = File(outDex.parentFile ?: File("."), "patch_${System.currentTimeMillis()}.dex")
        try {
            smaliToDex(smaliFiles, patchDex, apiLevel).getOrThrow()
            val patchClasses = runCatching {
                DexFileFactory.loadDexFile(patchDex, Opcodes.forApi(apiLevel)).classes.size
            }.getOrDefault(0)

            // ② 合并回原 dex
            val size = mergePatchIntoBase(baseDex, patchDex, outDex, apiLevel).getOrThrow()
            size to patchClasses
        } finally {
            runCatching { patchDex.delete() }
        }
    }

    // ================= 小工具 =================

    fun countSmali(dir: File): Int =
        dir.walkTopDown().count { it.isFile && it.name.endsWith(".smali") }

    /** 把 `Lcom/a/B;` 或 `com.a.B` 统一成 `Lcom/a/B;`。 */
    fun toDescriptor(className: String): String {
        val t = className.trim()
        return when {
            t.startsWith("L") && t.endsWith(";") -> t
            else -> "L" + t.replace('.', '/') + ";"
        }
    }

    /** 读某个类的 smali 文本。 */
    fun readSmali(smaliRoot: File, className: String): Result<String> = runCatching {
        val rel = toDescriptor(className).removePrefix("L").removeSuffix(";")
        val f = File(smaliRoot, "$rel.smali")
        require(f.isFile) { "找不到 $rel.smali（先 dexToSmali 反汇编）" }
        f.readText()
    }

    /** 写回某个类的 smali 文本（改完用这个落盘）。 */
    fun writeSmali(smaliRoot: File, className: String, text: String): Result<File> =
        runCatching {
            val rel = toDescriptor(className).removePrefix("L").removeSuffix(";")
            val f = File(smaliRoot, "$rel.smali")
            f.parentFile?.mkdirs()
            f.writeText(text)
            f
        }
}
