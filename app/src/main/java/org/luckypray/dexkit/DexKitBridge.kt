package org.luckypray.dexkit

import com.google.flatbuffers.FlatBufferBuilder
import org.luckypray.dexkit.schema.`-BatchClassMetaArrayHolder`
import org.luckypray.dexkit.schema.`-BatchFindClassUsingStrings`
import org.luckypray.dexkit.schema.`-BatchFindMethodUsingStrings`
import org.luckypray.dexkit.schema.`-BatchMethodMetaArrayHolder`
import org.luckypray.dexkit.schema.`-BatchUsingStringsMatcher`
import org.luckypray.dexkit.schema.`-StringMatchType`
import org.luckypray.dexkit.schema.`-StringMatcher`
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DexKit 原生桥 —— 包名 / 类名 / 方法名 / 注解**都必须与原版一致**，不能改。
 *
 * ⚠ 三个硬约束（实测 so 字符串表 + 读原版源码确认）：
 *
 *  1) **包名 + 类名**：JNI 符号规则是 `Java_<包名下划线化>_<类名>_<方法名>`。
 *     云端编出的 so 里导出的是：
 *         Java_org_luckypray_dexkit_DexKitBridge_nativeInitDexKit
 *         …（共 35 个，可 `grep -a Java_org_luckypray libdexkit-arm64-v8a.so`）
 *     所以必须是 `org.luckypray.dexkit.DexKitBridge`。
 *
 *  2) **必须是 object/companion 里的 @JvmStatic**：
 *     原版把 native 声明放在 `companion object` 里并加 `@JvmStatic`
 *     （原版 DexKitBridge.kt:682 起）。少一个 `@JvmStatic`，Kotlin 会把符号
 *     生成成 `DexKitBridge$Companion_nativeXxx`（或 object 的 `INSTANCEnativeXxx`），
 *     与 so 里对不上，一调就是 UnsatisfiedLinkError。
 *
 *     ⚠ 但**本类本身就是 `object`**，所以不能再套一层 `companion object` ——
 *       Kotlin 会直接报 "Modifier 'companion' is not applicable inside
 *       'standalone object'"（云构建 run 43752706917 就栽在这个错上）。
 *       正确做法：直接在 object 体内写 `@JvmStatic private external fun ...`。
 *
 *  3) **参数签名要对**：nativeInitDexKitByClassLoader 原版是
 *     `(ClassLoader, Boolean)`（loader + useMemoryDexFile）。
 *
 * 与原版的差别：原版 163 个 .kt（查询 DSL + flatbuffers 编解码）。
 * 这里只保留**不需要 flatbuffers 就能用**的那批（打开/统计/导出/释放）
 * 及查询方法的声明（调用前需另补编解码）。
 */
object DexKitBridge {

    // ============ 高层封装（Kotlin 便捷入口，非 JNI 符号） ============

    /** 打开 APK/dex 文件，返回句柄。 */
    fun open(path: String): Result<Long> = runCatching {
        val f = File(path)
        require(f.isFile) { "文件不存在：$path" }
        nativeInitDexKit(f.absolutePath).also { require(it != 0L) { "打开失败（返回 0）：$path" } }
    }

    /** 用一组 dex 字节打开（脱壳 dump 的 dex 走这个）。 */
    fun openDex(dexList: List<ByteArray>): Result<Long> = runCatching {
        require(dexList.isNotEmpty()) { "dex 列表为空" }
        // 同上：显式建数组，避免 Kotlin 推断出 uninferred T
        val arr: Array<ByteArray> = Array(dexList.size) { i -> dexList[i] }
        nativeInitDexKitByBytesArray(arr)
            .also { require(it != 0L) { "打开失败（返回 0）" } }
    }

    fun close(handle: Long) {
        runCatching { nativeRelease(handle) }
    }

    fun dexCount(handle: Long): Int = runCatching { nativeGetDexNum(handle) }.getOrDefault(0)

    /** 内部线程数（默认按 CPU 核数）。 */
    fun setThreadNum(handle: Long, n: Int) {
        runCatching { nativeSetThreadNum(handle, n.coerceAtLeast(1)) }
    }

    fun exportDex(handle: Long, outDir: String): Result<Unit> = runCatching {
        File(outDir).mkdirs()
        nativeExportDexFile(handle, outDir)
    }

    // ============ 查询：用字符串批量反查（A 方案，flatbuffers 编解码） ============
    //
    // 报文结构（schema/fbs/querys.fbs）：
    //   table BatchFindClassUsingStrings {
    //     search_packages: [string];
    //     exclude_packages: [string];
    //     ignore_packages_case: bool;
    //     in_classes: [int64];
    //     matchers: [BatchUsingStringsMatcher];   ← 每组一个
    //   }
    //   table BatchUsingStringsMatcher {
    //     union_key: string;          ← 这组的标识（我们用它当组名）
    //     using_strings: [StringMatcher];   ← 该组要匹配的字符串
    //   }
    // 返回：BatchClassMetaArrayHolder { items: [BatchClassMeta{union_key, classes:[ClassMeta]}] }
    //   —— 按 union_key 分组，正是我们要的「一组 → 一批类」。

    /**
     * 用字符串批量反查类。
     * @param groups key = 组名（拿回来对应用），value = 这组要匹配的字符串列表
     * @return 组名 → 该类名列表（dex 描述符，如 `Lcom/a/C;`）
     */
    fun batchFindClassesByStrings(handle: Long, groups: Map<String, List<String>>): Map<String, List<String>> =
        runCatching {
            val bytes = buildBatchClassQuery(groups)
            val res = nativeBatchFindClassUsingStrings(handle, bytes) ?: return emptyMap()
            parseBatchClassResult(res)
        }.getOrDefault(emptyMap())

    /**
     * 用字符串批量反查方法。
     * @return 组名 → 方法签名列表（`Lcom/a/C;->m()V` 形式由 native 侧给出）
     */
    fun batchFindMethodsByStrings(handle: Long, groups: Map<String, List<String>>): Map<String, List<String>> =
        runCatching {
            val bytes = buildBatchMethodQuery(groups)
            val res = nativeBatchFindMethodUsingStrings(handle, bytes) ?: return emptyMap()
            parseBatchMethodResult(res)
        }.getOrDefault(emptyMap())

    // ============ 私有：flatbuffers 报文构造 / 解析 ============

    /**
     * 构造 BatchFindClassUsingStrings 报文。
     *
     * 每组 → 一个 BatchUsingStringsMatcher{union_key=组名, using_strings=[StringMatcher...]}。
     * StringMatcher 用 Contains 匹配（DexKit 最常用的语义：类里"包含"这些字符串）。
     */
    private fun buildBatchClassQuery(groups: Map<String, List<String>>): ByteArray {
        val fbb = FlatBufferBuilder(1024)
        val matcherOffsets = IntArray(groups.size)
        groups.entries.forEachIndexed { i, (key, strings) ->
            val vec = `-BatchUsingStringsMatcher`.createUsingStringsVector(
                fbb,
                strings.map { s ->
                    `-StringMatcher`.createStringMatcher(
                        fbb,
                        fbb.createString(s),
                        `-StringMatchType`.Contains,
                        false,
                    )
                }.toIntArray(),
            )
            val keyOff = fbb.createString(key)
            matcherOffsets[i] = `-BatchUsingStringsMatcher`.createBatchUsingStringsMatcher(
                fbb,
                keyOff,
                vec,
            )
        }
        val matchersVec = `-BatchFindClassUsingStrings`.createMatchersVector(fbb, matcherOffsets)
        val root = `-BatchFindClassUsingStrings`.createBatchFindClassUsingStrings(fbb, 0, 0, false, 0, matchersVec)
        fbb.finish(root)
        return fbb.sizedByteArray()
    }

    /** 构造 BatchFindMethodUsingStrings 报文（结构同上，多一个 in_methods 字段）。 */
    private fun buildBatchMethodQuery(groups: Map<String, List<String>>): ByteArray {
        val fbb = FlatBufferBuilder(1024)
        val matcherOffsets = IntArray(groups.size)
        groups.entries.forEachIndexed { i, (key, strings) ->
            val vec = `-BatchUsingStringsMatcher`.createUsingStringsVector(
                fbb,
                strings.map { s ->
                    `-StringMatcher`.createStringMatcher(
                        fbb,
                        fbb.createString(s),
                        `-StringMatchType`.Contains,
                        false,
                    )
                }.toIntArray(),
            )
            matcherOffsets[i] = `-BatchUsingStringsMatcher`.createBatchUsingStringsMatcher(
                fbb,
                fbb.createString(key),
                vec,
            )
        }
        val matchersVec = `-BatchFindMethodUsingStrings`.createMatchersVector(fbb, matcherOffsets)
        val root = `-BatchFindMethodUsingStrings`.createBatchFindMethodUsingStrings(
            fbb, 0, 0, false, 0, 0, matchersVec,
        )
        fbb.finish(root)
        return fbb.sizedByteArray()
    }

    /** 解析返回：BatchClassMetaArrayHolder → 组名 → [类描述符]。 */
    private fun parseBatchClassResult(bytes: ByteArray): Map<String, List<String>> {
        val holder = `-BatchClassMetaArrayHolder`.getRootAsBatchClassMetaArrayHolder(
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
        )
        val out = linkedMapOf<String, List<String>>()
        for (i in 0 until holder.itemsLength) {
            val item = holder.items(i) ?: continue
            val key = item.unionKey ?: continue
            val list = ArrayList<String>(item.classesLength)
            for (j in 0 until item.classesLength) {
                val c = item.classes(j) ?: continue
                c.dexDescriptor?.let { list.add(it) }
            }
            out[key] = list
        }
        return out
    }

    /** 解析返回：BatchMethodMetaArrayHolder → 组名 → [方法描述符]。 */
    private fun parseBatchMethodResult(bytes: ByteArray): Map<String, List<String>> {
        val holder = `-BatchMethodMetaArrayHolder`.getRootAsBatchMethodMetaArrayHolder(
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
        )
        val out = linkedMapOf<String, List<String>>()
        for (i in 0 until holder.itemsLength) {
            val item = holder.items(i) ?: continue
            val key = item.unionKey ?: continue
            val list = ArrayList<String>(item.methodsLength)
            for (j in 0 until item.methodsLength) {
                val m = item.methods(j) ?: continue
                m.dexDescriptor?.let { list.add(it) }
            }
            out[key] = list
        }
        return out
    }

    // ============ 加载入口 ============
    //
    // ⚠ 一定要用 System.load(绝对路径) 而不是 System.loadLibrary("dexkit")：
    //   loadLibrary 只在 APK 的 nativeLibraryDir 里找，而我们的 so 是
    //   「下载模式」——落在私有目录 filesDir/dexkit/ 下。

    /** 是否已经加载过（避免重复 load）。 */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /** 加载指定路径的 so。幂等。 */
    @Synchronized
    fun loadFrom(path: String): Result<Unit> = runCatching {
        if (isLoaded) return Result.success(Unit)
        val f = File(path)
        require(f.isFile && f.length() > 0) { "so 不存在或为空：$path" }
        System.load(f.absolutePath)
        isLoaded = true
    }

    // ============ JNI 声明区 ============
    //
    // ⚠ 这里**不能**再套一层 `companion object`：
    //   本类已经是 `object`（单例），Kotlin 会报
    //     "Modifier 'companion' is not applicable inside 'standalone object'"
    //   （上一次云构建 run 43752706917 就是栽在这个编译错误上）
    //
    //   object 内的方法引用形式与 companion 一致（DexKitBridge.nativeXxx()），
    //   但符号名生成规则**不同**：
    //     · object 内 @JvmStatic  → Java_org_luckypray_dexkit_DexKitBridge_nativeXxx ✅
    //     · object 内不加 @JvmStatic → Java_org_luckypray_dexkit_DexKitBridge_INSTANCEnativeXxx ❌
    //   所以 @JvmStatic 一个都不能少（so 里是对着这个名字找的）。

    @JvmStatic
    private external fun nativeInitDexKit(apkPath: String): Long

    @JvmStatic
    private external fun nativeInitDexKitByBytesArray(dexBytesArray: Array<ByteArray>): Long

    /** 原版签名就是 (ClassLoader, Boolean)。 */
    @JvmStatic
    private external fun nativeInitDexKitByClassLoader(
        loader: ClassLoader,
        useMemoryDexFile: Boolean,
    ): Long

    @JvmStatic
    private external fun nativeSetThreadNum(nativePtr: Long, threadNum: Int)

    @JvmStatic
    private external fun nativeSetMaxConcurrentQueries(nativePtr: Long, maxConcurrentQueries: Int)

    @JvmStatic
    private external fun nativeInitFullCache(nativePtr: Long)

    @JvmStatic
    private external fun nativeGetDexNum(nativePtr: Long): Int

    @JvmStatic
    private external fun nativeRelease(nativePtr: Long)

    @JvmStatic
    private external fun nativeExportDexFile(nativePtr: Long, outDir: String)

    // ---- 以下收/返 [B，需 flatbuffers 构造/解析报文，属预留 ----

    @JvmStatic
    private external fun nativeBatchFindClassUsingStrings(nativePtr: Long, bytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeBatchFindMethodUsingStrings(nativePtr: Long, bytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeFindClass(nativePtr: Long, bytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeFindMethod(nativePtr: Long, bytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeFindField(nativePtr: Long, bytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeGetClassData(nativePtr: Long, dexDescriptor: String): ByteArray?

    @JvmStatic
    private external fun nativeGetMethodData(nativePtr: Long, dexDescriptor: String): ByteArray?

    @JvmStatic
    private external fun nativeGetFieldData(nativePtr: Long, dexDescriptor: String): ByteArray?

    @JvmStatic
    private external fun nativeGetClassByIds(nativePtr: Long, ids: LongArray): ByteArray

    @JvmStatic
    private external fun nativeGetMethodByIds(nativePtr: Long, ids: LongArray): ByteArray

    @JvmStatic
    private external fun nativeGetFieldByIds(nativePtr: Long, ids: LongArray): ByteArray
}