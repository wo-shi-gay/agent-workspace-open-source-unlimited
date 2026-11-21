package com.agentpad.workspace.data

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 4.7 数据迁移**导入器** —— 读取 [MigrationExporter] 产出的迁移包。
 *
 * 支持的两种输入：
 *   ① ZIP 压缩包：workspace-migration-4.6-xxx.zip
 *   ② 文件夹（铺开）：workspace-migration-4.6-xxx/ 目录
 *      两种包的内部结构完全一致，所以解析逻辑共用一套：
 *      前者读 zip entry 流，后者读真实文件，都用相对路径寻址。
 *
 * 包内结构（必须与 MigrationExporter 一致）：
 *   manifest.json          ← schemaVersion / 会话清单（含 mode）
 *   prefs/[name].json      ← 每个键编成 {t:类型, v:值}
 *   files/[子目录]/...     ← plugins / chat_bg / mcp-workspace
 *   workspaces/[id]/...    ← 每个会话的工作区（原样）
 *
 * 最关键的坑：
 *   agent_workspace_store 里的 session_workspace_[id] 存的是**绝对路径**，
 *   形如 /storage/emulated/0/Android/data/旧包名/files/workspaces/[id]。
 *   换包名后这个路径**必然失效**，所以导入时必须按新包名重写成：
 *     /storage/emulated/0/Android/data/新包名/files/workspaces/[id]
 *   否则所有会话都会指向一个不存在的旧目录 —— 这是迁移最容易漏的一处。
 *
 * 内存纪律：
 *   绝不 readBytes() 整个 zip 或整个 prefs。逐个 entry / 逐块流式处理，
 *   因为单是 agent_workspace_store.json 就可能上百 MB。
 */
object MigrationImporter {

    /** 与导出器配套的格式版本。 */
    const val SCHEMA_VERSION = 1

    private const val DIR_PREFS = "prefs"
    private const val DIR_FILES = "files"
    private const val DIR_WORKSPACES = "workspaces"
    private const val MANIFEST = "manifest.json"

    /** 进度回调（后台线程调用）。 */
    interface Progress {
        fun onMessage(text: String)
        fun onProgress(percent: Int)
    }

    /**
     * 导入前先「看一眼」包，用于在 UI 上显示摘要（会话数、大小、来源版本），
     * 让用户确认「这确实是我的包」再执行真正的导入。
     */
    data class Preview(
        val ok: Boolean,
        val message: String,
        /** 包的形态：zip / folder */
        val kind: String = "",
        val schemaVersion: Int = 0,
        val fromPackage: String = "",
        val fromVersionName: String = "",
        val exportedAtText: String = "",
        val sessionCount: Int = 0,
        val workspaceDirCount: Int = 0,
        /** 会话清单摘要（id / title / mode），用于 UI 展示项目类型分布 */
        val sessions: List<SessionInfo> = emptyList(),
        /** 需要导入的总字节数（估算） */
        val totalBytes: Long = 0L,
    ) {
        data class SessionInfo(val id: String, val title: String, val mode: String)
    }

    /** 导入结果。 */
    data class Result(
        val ok: Boolean,
        val message: String,
        val prefsRestored: Int = 0,
        val sessionsRestored: Int = 0,
        val workspaceDirsRestored: Int = 0,
        val filesDirsRestored: Int = 0,
        /** 被跳过/失败的条目说明（不致命，汇总给用户看） */
        val warnings: List<String> = emptyList(),
    )

    // ==================== 自动扫描 ====================

    /**
     * 扫描本机常见的存放位置，找出所有「看起来像迁移包」的项。
     *
     * 为什么需要：SAF 的「选文件夹」在很多机型上限制重重（有的文件管理器
     * 直接把 Download 隐藏、有的返回的 document id 无法还原真实路径），
     * 用户根本选不到自己刚导出的那个文件夹。
     * 而 App 已经声明了 MANAGE_EXTERNAL_STORAGE，可以直接读这些目录，
     * 所以这里自己扫一遍，把候选列出来让用户点 —— 比让用户在一堆目录里
     * 翻找可靠得多。
     *
     * 扫描位置（按优先级）：
     *   1. /sdcard/Download/WorkspaceMigration/     ← 4.6 默认导出位置
     *   2. /sdcard/Download/                        ← 用户手动搬过来
     *   3. /sdcard/WorkspaceMigration/              ← 用户自己建的
     *   4. App 专属目录 migration/                  ← 导出时权限不足的兜底位置
     *        当前包（4.7）与旧包（4.6）都查，方便直接从旧包目录读
     *   5. 外部存储根目录下一层的 WorkspaceMigration
     *
     * 识别规则：
     *   - 目录且含 manifest.json                → 迁移文件夹
     *   - 文件且 .zip 且 zip 内有 manifest.json → 迁移压缩包
     *   只认这两种，其余一律忽略，避免列出无关文件干扰判断。
     */
    data class Found(val file: File, val isFolder: Boolean, val label: String, val hint: String)

    fun scanForPackages(context: Context): List<Found> {
        val candidates = LinkedHashSet<File>()

        fun addDir(dir: File?) {
            if (dir == null || !dir.isDirectory || !dir.canRead()) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory && File(f, MANIFEST).isFile) candidates.add(f)
                else if (f.isFile && f.name.endsWith(".zip", true) && zipHasManifest(f)) candidates.add(f)
            }
            // 目录本身也可能是迁移文件夹（用户直接选到它）
            if (File(dir, MANIFEST).isFile) candidates.add(dir)
        }

        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        // 1) Download/WorkspaceMigration/ + Download 本身
        addDir(File(dl, "WorkspaceMigration"))
        addDir(dl)
        // 2) 外部存储根的 WorkspaceMigration
        runCatching {
            val root = Environment.getExternalStorageDirectory()
            addDir(File(root, "WorkspaceMigration"))
        }
        // 3) 当前包与旧包的 App 专属 migration 目录
        for (pkg in listOf(context.packageName, "com.agentpad.newapp", "com.agentpad.workspace")) {
            addDir(File("/storage/emulated/0/Android/data/$pkg/files/migration"))
        }
        // 4) 当前包自己的 getExternalFilesDir 兜底（路径可能不是标准位置）
        runCatching { addDir(File(context.getExternalFilesDir(null) ?: return@runCatching, "migration")) }

        // 按修改时间倒序：最新的排在前面，用户通常刚导出就来导入
        return candidates
            .sortedByDescending { if (it.isDirectory) dirSize(it) else it.lastModified() }
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val isFolder = f.isDirectory
                Found(
                    file = f,
                    isFolder = isFolder,
                    label = f.name,
                    hint = buildString {
                        append(if (isFolder) "文件夹" else "压缩包")
                        append(" · ")
                        append(formatSize(if (isFolder) dirSize(f) else f.length()))
                        append(" · ")
                        append(parentShort(f))
                    },
                )
            }
    }

    /** 轻量校验：zip 里第一个条目之前先确认 manifest.json 存在，避免把随便的 zip 当迁移包。 */
    private fun zipHasManifest(zip: File): Boolean = runCatching {
        ZipFile(zip).use { zf -> zf.getEntry(MANIFEST) != null }
    }.getOrDefault(false)

    private fun parentShort(f: File): String {
        val p = f.parentFile?.absolutePath ?: return ""
        return p.replace("/storage/emulated/0/", "内部存储/")
    }

fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ==================== 杂项 ====================

    /**
     * 读包但不落盘。支持 zip 与文件夹两种输入。
     *
     * @param source 迁移包 zip 或迁移文件夹
     */
    fun preview(context: Context, source: File): Preview {
        return runCatching {
            when {
                source.isDirectory -> previewFolder(context, source)
                source.isFile && source.name.endsWith(".zip", true) -> previewZip(context, source)
                else -> Preview(false, "不认识的迁移包：需要 .zip 文件或迁移文件夹")
            }
        }.getOrElse { Preview(false, "读取失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun previewZip(context: Context, zip: File): Preview {
        ZipFile(zip).use { zf ->
            val manifestEntry = zf.getEntry(MANIFEST)
                ?: return Preview(false, "不是有效的迁移包：缺少 manifest.json")
            val manifest = zf.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            return buildPreview(zip.length(), "zip", manifest)
        }
    }

    private fun previewFolder(context: Context, dir: File): Preview {
        val mf = File(dir, MANIFEST)
        if (!mf.isFile) return Preview(false, "不是有效的迁移目录：缺少 manifest.json")
        val manifest = mf.readText()
        return buildPreview(dirSize(dir), "folder", manifest)
    }

    private fun buildPreview(bytes: Long, kind: String, manifestText: String): Preview {
        val m = runCatching { JSONObject(manifestText) }.getOrNull()
            ?: return Preview(false, "manifest.json 解析失败")
        val schema = m.optInt("schemaVersion", 0)
        if (schema <= 0) return Preview(false, "manifest 缺少 schemaVersion")
        if (schema > SCHEMA_VERSION) {
            return Preview(false, "包格式版本 $schema 高于本版本支持的 $SCHEMA_VERSION，请升级 App")
        }
        val arr = m.optJSONArray("sessions") ?: JSONArray()
        val sessions = ArrayList<Preview.SessionInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sessions.add(
                Preview.SessionInfo(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    mode = o.optString("mode", "chat"),
                )
            )
        }
        return Preview(
            ok = true,
            message = "迁移包可导入",
            kind = kind,
            schemaVersion = schema,
            fromPackage = m.optString("fromPackage"),
            fromVersionName = m.optString("fromVersionName"),
            exportedAtText = m.optString("exportedAtText"),
            sessionCount = sessions.size,
            workspaceDirCount = m.optInt("workspaceDirCount", 0),
            sessions = sessions,
            totalBytes = bytes,
        )
    }

    // ==================== 导入 ====================

    /**
     * 执行导入。
     *
     * ⚠ 必须在**后台线程**调用。
     *
     * 导入顺序（很重要）：
     *  1. prefs —— 先写，因为里面含会话 JSON 与工作区路径
     *  2. files/ —— 插件、背景等
     *  3. workspaces/ —— 最后搬大数据
     *  4. **重写 session_workspace_<id> 路径** —— 必须在 workspaces 落地之后，
     *     否则路径指向的目录还不存在
     *
     * @param overwrite true = 覆盖同名会话/文件；false = 跳过已存在的
     */
    fun import(
        context: Context,
        source: File,
        overwrite: Boolean = true,
        progress: Progress? = null,
    ): Result = runCatching {
        val preview = preview(context, source)
        if (!preview.ok) return Result(false, preview.message)

        val warnings = ArrayList<String>()
        var prefsRestored = 0
        var sessionsRestored = 0
        var filesDirsRestored = 0
        var workspaceDirsRestored = 0

        val totalBytes = preview.totalBytes.coerceAtLeast(1L)
        var doneBytes = 0L
        fun tick(delta: Long, label: String) {
            doneBytes += delta
            val p = ((doneBytes * 100) / totalBytes).toInt()
            if (p in 0..100) progress?.onProgress(p)
            if (label.isNotBlank()) progress?.onMessage(label)
        }

        if (source.isDirectory) {
            // ---------- 文件夹模式 ----------
            progress?.onMessage("导入设置与登录状态…")
            val prefsDir = File(source, DIR_PREFS)
            if (prefsDir.isDirectory) {
                prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                    val name = f.name.removeSuffix(".json")
                    if (restorePref(context, name, f.readText(), warnings)) prefsRestored++
                    tick(f.length(), "")
                }
            } else warnings += "包里没有 prefs/ 目录"

            val filesDir = File(source, DIR_FILES)
            if (filesDir.isDirectory) {
                progress?.onMessage("恢复插件 / 背景 / MCP 工作区…")
                filesDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                    val dst = File(context.filesDir, sub.name)
                    copyTree(sub, dst, overwrite) { tick(it, "") }
                    filesDirsRestored++
                }
            }

            val wsDir = File(source, DIR_WORKSPACES)
            if (wsDir.isDirectory) {
                progress?.onMessage("恢复会话工作区…")
                wsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { sub ->
                    val dst = File(workspaceRoot(context), sub.name)
                    copyTree(sub, dst, overwrite) { tick(it, "工作区 ${sub.name}") }
                    workspaceDirsRestored++
                }
            }
        } else {
            // ---------- ZIP 模式 ----------
            ZipFile(source).use { zf ->
                val entries = zf.entries().toList()

                // 1) prefs
                progress?.onMessage("导入设置与登录状态…")
                entries.filter { e ->
                    !e.isDirectory && e.name.startsWith("$DIR_PREFS/") && e.name.endsWith(".json")
                }.sortedBy { it.name }.forEach { e ->
                    val name = e.name.removePrefix("$DIR_PREFS/").removeSuffix(".json")
                    val text = zf.getInputStream(e).bufferedReader().use { it.readText() }
                    if (restorePref(context, name, text, warnings)) prefsRestored++
                    tick(e.size.coerceAtLeast(0), "")
                }

                // 2) files/
                progress?.onMessage("恢复插件 / 背景 / MCP 工作区…")
                val filesEntries = entries.filter {
                    !it.isDirectory && it.name.startsWith("$DIR_FILES/")
                }
                val filesTopDirs = filesEntries.mapNotNull {
                    it.name.removePrefix("$DIR_FILES/").split('/').firstOrNull()?.takeIf { s -> s.isNotBlank() }
                }.toSortedSet()
                filesEntries.sortedBy { it.name }.forEach { e ->
                    val rel = e.name.removePrefix("$DIR_FILES/")
                    if (rel.isBlank()) return@forEach
                    val dst = File(context.filesDir, rel)
                    writeZipEntry(context, zf, e, dst, overwrite) { tick(it, "") }
                }
                filesDirsRestored = filesTopDirs.size

                // 3) workspaces/
                progress?.onMessage("恢复会话工作区…")
                val wsEntries = entries.filter {
                    !it.isDirectory && it.name.startsWith("$DIR_WORKSPACES/")
                }
                wsEntries.sortedBy { it.name }.forEach { e ->
                    val rel = e.name.removePrefix("$DIR_WORKSPACES/")
                    if (rel.isBlank()) return@forEach
                    val dst = File(workspaceRoot(context), rel)
                    writeZipEntry(context, zf, e, dst, overwrite) { tick(it, "工作区 ${rel.substringBefore('/')}") }
                }
                workspaceDirsRestored = wsEntries.mapNotNull {
                    it.name.removePrefix("$DIR_WORKSPACES/").substringBefore('/').takeIf { s -> s.isNotBlank() }
                }.toSortedSet().size
            }
        }

        // 4) 重写工作区绝对路径 —— 换包名后旧路径必然失效
        progress?.onMessage("修正工作区路径…")
        val pathFixed = rewriteWorkspacePaths(context, warnings)

        // 5) 会话数（从导回的数据里数）
        sessionsRestored = preview.sessionCount

        progress?.onProgress(100)
        val warnText = if (warnings.isEmpty()) "" else "\n⚠ ${warnings.size} 条警告：" + warnings.take(3).joinToString("；")
        Result(
            ok = true,
            message = "导入完成：${sessionsRestored} 个会话、${workspaceDirsRestored} 个工作区、${prefsRestored} 组设置" +
                (if (pathFixed > 0) "、修正 $pathFixed 条路径" else "") + warnText,
            prefsRestored = prefsRestored,
            sessionsRestored = sessionsRestored,
            workspaceDirsRestored = workspaceDirsRestored,
            filesDirsRestored = filesDirsRestored,
            warnings = warnings,
        )
    }.getOrElse { e ->
        Result(false, "导入失败：${e.message ?: e.javaClass.simpleName}")
    }

    // ==================== prefs 还原 ====================

    /**
     * 把一个 prefs 下的 json 写回 SharedPreferences。
     *
     * 值格式是 {t: 类型, v: 值}，t 取值：s/i/l/f/b/ss/null。
     * 必须按类型写回，否则会出现「int 变 String」导致业务码读到错误配置。
     */
    private fun restorePref(
        context: Context,
        name: String,
        jsonText: String,
        warnings: MutableList<String>,
    ): Boolean {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull()
        if (obj == null) {
            warnings += "prefs/$name.json 解析失败，已跳过"
            return false
        }
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = sp.edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val enc = obj.optJSONObject(k) ?: continue
            val type = enc.optString("t")
            runCatching {
                when (type) {
                    "null" -> editor.remove(k)
                    "s" -> editor.putString(k, enc.optString("v"))
                    "i" -> editor.putInt(k, enc.optInt("v"))
                    "l" -> editor.putLong(k, enc.optLong("v"))
                    "f" -> editor.putFloat(k, enc.optDouble("v").toFloat())
                    "b" -> editor.putBoolean(k, enc.optBoolean("v"))
                    "ss" -> {
                        val arr = enc.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>(arr.length())
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(k, set)
                    }
                    else -> editor.putString(k, enc.optString("v"))   // 未知类型按字符串兜底
                }
            }.onFailure { warnings += "prefs/$name 的键 $k 写入失败：${it.message}" }
        }
        return runCatching { editor.commit() }.getOrDefault(false)
    }

    // ==================== 文件落地 ====================

    /** 工作区根目录（与 ChatStore.workspaceDir 的父目录一致）。 */
    private fun workspaceRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "workspaces").apply { mkdirs() }

    /** 把 zip 里的一个 entry 流式写到 dst；不整块读进内存。 */
    private fun writeZipEntry(
        context: Context,
        zf: ZipFile,
        entry: ZipEntry,
        dst: File,
        overwrite: Boolean,
        onBytes: (Long) -> Unit,
    ) {
        if (dst.exists() && !overwrite) {
            onBytes(entry.size.coerceAtLeast(0))
            return
        }
        dst.parentFile?.mkdirs()
        runCatching {
            zf.getInputStream(entry).use { input ->
                FileOutputStream(dst).use { output ->
                    pump(input, output) { n -> onBytes(n) }
                }
            }
        }
    }

    /** 递归复制目录（文件夹模式用）。 */
    private fun copyTree(src: File, dst: File, overwrite: Boolean, onBytes: (Long) -> Unit) {
        if (!src.exists()) return
        if (src.isFile) {
            if (dst.exists() && !overwrite) { onBytes(src.length()); return }
            dst.parentFile?.mkdirs()
            runCatching {
                src.inputStream().use { input ->
                    FileOutputStream(dst).use { output -> pump(input, output) { onBytes(it) } }
                }
            }
            return
        }
        dst.mkdirs()
        src.listFiles()?.forEach { child ->
            copyTree(child, File(dst, child.name), overwrite, onBytes)
        }
    }

    private fun pump(input: InputStream, output: FileOutputStream, onBytes: (Long) -> Unit): Long {
        val buf = ByteArray(64 * 1024)
        var n: Int
        var total = 0L
        while (input.read(buf).also { n = it } > 0) {
            output.write(buf, 0, n)
            total += n
            onBytes(n.toLong())
        }
        return total
    }

    // ==================== 路径修复（核心） ====================

    /**
     * 重写 `agent_workspace_store` 里所有 `session_workspace_<id>` 的值，
     * 让它们指向**当前包名**下的工作区目录。
     *
     * 为什么必须做：
     *   导出时这个键存的是旧包名下的绝对路径，例如
     *     /storage/emulated/0/Android/data/com.agentpad.workspace/files/workspaces/1791...
     *   4.7 换成新包名后，getExternalFilesDir(null) 变成
     *     /storage/emulated/0/Android/data/<新包名>/files
     *   旧路径下的目录在新 App 里**根本访问不到**（scoped storage 限制），
     *   于是每个会话都「打不开工作区」。
     *
     * @return 实际改写的键数量
     */
    private fun rewriteWorkspacePaths(context: Context, warnings: MutableList<String>): Int {
        val root = workspaceRoot(context)
        val sp = context.getSharedPreferences("agent_workspace_store", Context.MODE_PRIVATE)
        val prefix = "session_workspace_"
        val editor = sp.edit()
        var fixed = 0
        sp.all.forEach { (k, v) ->
            if (!k.startsWith(prefix)) return@forEach
            val id = k.removePrefix(prefix)
            val correct = ChatStore.workspaceDir(context, id).absolutePath
            val old = (v as? String).orEmpty()
            if (old != correct) {
                editor.putString(k, correct)
                fixed++
            }
        }
        if (fixed > 0) {
            runCatching { editor.commit() }
                .onFailure { warnings += "工作区路径改写失败：${it.message}" }
        }
        return fixed
    }

    // ==================== 杂项 ====================

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirSize(it) }
        return sum
    }
}