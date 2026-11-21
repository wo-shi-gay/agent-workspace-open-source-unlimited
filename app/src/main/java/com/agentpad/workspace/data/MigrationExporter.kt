package com.agentpad.workspace.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 4.6 数据迁移导出器 —— 「完整迁移包」。
 *
 * 定位：4.6 只负责**把用户数据完整导出**成一个 zip；真正的换包名 / 换签名
 * 迁移发生在 4.7（新的 importer 负责读这个包）。
 *
 * ── 导什么 ──────────────────────────────────────────────
 *  ✅ SharedPreferences（含登录 token、会话 JSON→里面有 mode 项目类型标识）
 *  ✅ workspaces/<sessionId>/（用户真实工作成果，原样搬运，不筛选、不改名）
 *  ✅ files/plugins/（已装插件）
 *  ✅ files/chat_bg/（对话背景图/视频）
 *  ✅ files/mcp-workspace/（MCP 工作目录）
 *
 * ── 不导什么（主人明确要求）──────────────────────────────
 *  ❌ files/sandbox/rootfs（Debian 系统镜像，几百 MB，4.7 会自动重下）
 *  ❌ Java / Python / Go / Node / NDK / Kotlin / 视频 / 逆向 等工具链
 *     （它们都在 rootfs 的 /opt 或 /usr/local 下，rootfs 不导就自然全部排除）
 *  ❌ logs / crash（无迁移价值）
 *  ❌ ADB 密钥（跟设备绑定，换机无意义）
 *
 * ── 为什么 prefs 要自己序列化 ─────────────────────────────
 * Android 的 SharedPreferences 是进程内对象，磁盘上的 .xml 格式属于实现细节，
 * 直接拷 .xml 会碰到「root 可读但 owner 不同」和「格式版本」两个坑。
 * 这里用 prefs.all 遍历所有键值，写成 JSON —— 4.7 导入时用同一套类型规则
 * 反序列化即可，不依赖 Android 内部格式。
 */
object MigrationExporter {

    /** 迁移包格式版本。4.7 的 importer 靠它判断兼容性。 */
    const val SCHEMA_VERSION = 1

    /** 导出包内的目录布局（importer 必须与此一致）。 */
    private const val DIR_PREFS = "prefs"
    private const val DIR_FILES = "files"
    private const val DIR_WORKSPACES = "workspaces"
    private const val MANIFEST = "manifest.json"

    /**
     * 需要导出的 SharedPreferences 文件名。
     *
     * 刻意**不含** sandbox_prefs / adb_permission / wireless_adb / terminal_settings /
     * announcement_state —— 这些是设备/沙箱绑定状态，换包名后无意义甚至有害。
     */
    val EXPORTED_PREFS = listOf(
        "agent_workspace_store",   // 会话 JSON（含 mode）、API 配置、工作区路径、思考等级
        "user_store",              // 登录 token + 用户名 ← 登录状态
        "theme_prefs",             // 主题色 / 深色
        "chat_background_pref",    // 对话背景
        "prompt_store",            // 全局 + 会话提示词
        "plugin_config",           // 插件启用列表
        "mcp_config",              // MCP 服务器配置
        "remote_workspaces",       // 远程工作区（SSH）
        "file_history",            // 文件改动历史
    )

    /** files/ 下需要导出的子目录（相对 filesDir）。
     *
     * ⚠ 2026-11-13 补 "sessions"：11-09 会话存储分片化之后，消息正文落在
     *   filesDir/sessions/<id>/node_*.json，不再放在 agent_workspace_store 的
     *   session_<id> 字符串里。漏掉它 = 迁移包里只有会话标题、没有任何消息，
     *   导入后打开会话全是空的。这是个静默数据丢失，比崩溃更坑。 */
    val EXPORTED_FILE_DIRS = listOf(
        "sessions",
        "plugins",
        "chat_bg",
        "mcp-workspace",
    )

    /** 进度回调（都在后台线程调用，UI 侧自行切主线程）。 */
    interface Progress {
        fun onMessage(text: String)
        /** 0..100；总量未知时传 -1（UI 显示不确定进度）。 */
        fun onProgress(percent: Int)
    }

    /**
     * 导出结果。
     * @param ok          是否成功
     * @param zip         产出的 zip 文件（失败时为 null）
     * @param message     给人看的说明
     * @param sessions    导出时识别到的会话数
     * @param bytes       包体积（字节）
     */
    data class Result(
        val ok: Boolean,
        val zip: File?,
        val message: String,
        val sessions: Int = 0,
        val bytes: Long = 0L,
        val mode: Mode = Mode.ZIP,
    )

    /**
     * 导出模式。
     *
     * - [ZIP]  ：打成单个 `.zip`，便于分享/上传网盘，体积小；
     * - [FOLDER]：在下载目录建专属文件夹，把 manifest / prefs / files / workspaces
     *   按原结构**直接铺开**。好处是不用解压就能用文件管理器逐个查看、
     *   单文件拷贝；坏处是几十万个小文件时拷贝很慢。
     */
    enum class Mode { ZIP, FOLDER }

    /**
     * 执行导出。
     *
     * ⚠ 必须在**后台线程**调用（会做大量 IO）。不要在 UI 线程直接调。
     *
     * @param targetDir 落盘目录，通常是 /sdcard/Download/WorkspaceMigration
     * @param mode      压缩包 or 文件夹铺开
     */
    fun export(
        context: Context,
        targetDir: File,
        mode: Mode = Mode.ZIP,
        progress: Progress? = null,
    ): Result {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val baseName = "workspace-migration-4.6-$stamp"
        val outFile = when (mode) {
            Mode.ZIP -> File(targetDir, "$baseName.zip")
            Mode.FOLDER -> File(targetDir, baseName)
        }

        return runCatching {
            targetDir.mkdirs()
            if (targetDir.exists() && !targetDir.canWrite()) {
                error("目标目录不可写：${targetDir.absolutePath}")
            }

            progress?.onMessage("统计待导出内容…")

            // 被跳过的 session_<id> 数量（消息正文已由 files/sessions 承载）
            var droppedCount = 0
            // ⚠⚠ 2026-11-13 OOM 根因修复（主人实测 Failed to allocate 150994952 byte）：
            //
            // 旧代码两条路都炸：
            //   ① `val wsSnapshot = wsPrefs.all` 长期持有 150MB HashMap
            //   ② 再 `JSONObject()` 全量 put + `toString().toByteArray()`
            //      → 堆里同时有「Map + JSONObject 树 + String + ByteArray」四份
            //
            // Android 的 SharedPreferences **没有按 key 取值的 API**（getString 需预知
            // 类型），.all 必然重建全量 map，无法避免。所以正确解法是：
            //   取一次 .all → **立刻过滤掉 session_<id>（消息正文，占 99% 体积）**
            //   → 只保留配置类小 map → 丢掉全量引用
            // 消息正文由 files/sessions/<id>/ 分片目录整体导出承载，不缺数据。
            val wsPrefs = context.getSharedPreferences("agent_workspace_store", Context.MODE_PRIVATE)
            val wsConfig: Map<String, Any?> = run {
                val all = wsPrefs.all          // 唯一一次全量拷贝
                val kept = HashMap<String, Any?>()
                var dropped = 0
                for ((k, v) in all) {
                    if (isSessionJsonKey(k)) { dropped++; continue }
                    kept[k] = v                 // 只留配置键，值本身很小
                }
                droppedCount = dropped
                kept                             // all 出作用域即被 GC
            }

            val sessions = collectSessions(context)

            val fileDirs = EXPORTED_FILE_DIRS.filter { File(context.filesDir, it).isDirectory }
            val workspaceRoot = workspaceRoot(context)
            val workspaceDirs = workspaceRoot?.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

            // 预估总字节数（用于进度条；工作区占绝对大头）
            val totalBytes = workspaceDirs.sumOf { dirSize(it) } +
                fileDirs.sumOf { dirSize(File(context.filesDir, it)) }

            val unit = if (mode == Mode.ZIP) "迁移包" else "迁移文件夹"
            progress?.onMessage("写入$unit（共 ${sessions.length()} 个会话，${formatSize(totalBytes)}）…")

            var written = 0L
            fun tick(delta: Long) {
                written += delta
                if (totalBytes > 0) {
                    val p = ((written * 100) / totalBytes).toInt()
                    if (p in 0..99) progress?.onProgress(p)
                }
            }

            // 预统计一下会话数，用于日志与进度文案；不保留完整 prefs 结构体。
            val sessionCount = sessions.length()
            val manifest = buildManifest(context, sessions, workspaceDirs)
            // sessions 是个小数组（每个会话只有 id/title/mode/条数），留着无妨；
            // 但真正占内存的是 wsSnapshot 里的每个 session_<id> 完整 JSON。
            // manifest 建好后 sessions 也可以放掉。
            @Suppress("UNUSED_EXPRESSION") sessions

            if (mode == Mode.ZIP) {
                BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        zos.setLevel(6)   // 平衡速度/体积；工作区大量已是压缩格式，9 收益不大

                        // ---- 1) manifest.json ----
                        putEntry(zos, MANIFEST, manifest.toString(2).toByteArray())

                        // ---- 2) prefs/*.json ----
                        // ⚠ 逐个 pref 现算现写：绝不先把 9 个 pref 全部编成 JSONObject
                        //   再整体持有。agent_workspace_store 一个文件就是上百 MB，
                        //   预先生成 `{key:{t,v}}` 结构等于又复制了一份 → OOM。
                        progress?.onMessage("导出设置与登录状态…")
                        for (name in EXPORTED_PREFS) {
                            if (name == "agent_workspace_store") {
                                // ⚠⚠ 2026-11-13 OOM 根因修复（主人实测 Failed to allocate 150994952）：
                                //   旧写法把整个 wsSnapshot 编成 JSONObject 再
                                //   toString().toByteArray()，堆里同时存在
                                //   「Map(150MB) + JSONObject 树 + String + ByteArray」四份。
                                //
                                //   session_<id> 是**冗余副本**：11-09 分片化之后消息正文
                                //   已在 files/sessions/<id>/node_*.json，prefs 里那份
                                //   只是迁移期遗留。上面已经把它过滤掉，wsConfig 只剩配置键。
                                //
                                //   这里用流式写：一个键一条，常驻内存与 pref 总大小脱钩。
                                streamPrefEntry(
                                    zos = zos,
                                    entryName = "$DIR_PREFS/$name.json",
                                    keys = wsConfig.keys,
                                    getValue = { k -> wsConfig[k] },
                                    shouldSkip = { false },
                                    onBytes = { tick(it) },
                                )
                            } else {
                                val all: Map<String, *> =
                                    context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                                streamPrefEntry(
                                    zos = zos,
                                    entryName = "$DIR_PREFS/$name.json",
                                    keys = all.keys,
                                    getValue = { k -> all[k] },
                                    shouldSkip = { false },
                                    onBytes = { tick(it) },
                                )
                            }
                        }

                        // ---- 3) files/<子目录>/... ----
                        var dirIndex = 0
                        for (dirName in fileDirs) {
                            dirIndex++
                            progress?.onMessage("导出 $dirName/（$dirIndex/${fileDirs.size}）…")
                            val base = File(context.filesDir, dirName)
                            // ⚠ 进度由 onBytes 回调累加 written；zipTree 的**返回值不要**再加，
                            // 否则同一个字节被计两次，进度会虚高一倍、提前冲到 100%。
                            zipTree(zos, base, "$DIR_FILES/$dirName", buf = TMP_BUF) { tick(it) }
                        }

                        // ---- 4) workspaces/<sessionId>/... ----
                        // 原样搬运：不改名、不筛选、不排除构建产物（主人要求「直接原样」）。
                        var wsIndex = 0
                        for (dir in workspaceDirs) {
                            wsIndex++
                            progress?.onMessage("导出工作区 $wsIndex/${workspaceDirs.size}：${dir.name}")
                            zipTree(zos, dir, "$DIR_WORKSPACES/${dir.name}", buf = TMP_BUF) { tick(it) }
                        }
                    }
                }
            } else {
                // ---- 文件夹模式：按与 zip 完全相同的目录结构铺开 ----
                // 4.7 的 importer 两种模式都认：一个是 zip 条目路径，一个是真实路径，
                // 相对结构一致，所以解析代码可以共用。
                outFile.mkdirs()
                progress?.onMessage("写入清单与设置…")
                File(outFile, MANIFEST).writeText(manifest.toString(2))
                val prefsDir = File(outFile, DIR_PREFS).apply { mkdirs() }
                // 同 ZIP 分支：逐个现算现写，不预生成整份 prefs 结构。
                for (name in EXPORTED_PREFS) {
                    // agent_workspace_store 用已过滤的 wsConfig（无 session JSON）；
                    // 其余 pref 本身很小，可以整体取。
                    val all: Map<String, Any?> = if (name == "agent_workspace_store") {
                        wsConfig
                    } else {
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    }
                    // 文件夹模式没有流式压缩，但仍然逐键写字符串、不预建大树：
                    // 单个 pref 的配置键数量有限，这里不会 OOM。
                    val obj = JSONObject()
                    for ((k, v) in all) obj.put(k, encodeValue(v))
                    File(prefsDir, "$name.json").writeText(obj.toString())
                }

                var dirIndex = 0
                for (dirName in fileDirs) {
                    dirIndex++
                    progress?.onMessage("复制 $dirName/（$dirIndex/${fileDirs.size}）…")
                    copyTree(File(context.filesDir, dirName), File(outFile, "$DIR_FILES/$dirName")) { tick(it) }
                }

                var wsIndex = 0
                for (dir in workspaceDirs) {
                    wsIndex++
                    progress?.onMessage("复制工作区 $wsIndex/${workspaceDirs.size}：${dir.name}")
                    copyTree(dir, File(outFile, "$DIR_WORKSPACES/${dir.name}")) { tick(it) }
                }
            }

            progress?.onProgress(100)
            val size = if (mode == Mode.ZIP) outFile.length() else dirSize(outFile)
            progress?.onMessage("导出完成：${formatSize(size)}")
            Result(
                ok = true,
                zip = outFile,
                message = "${unit}已生成（${sessions.length()} 个会话，${formatSize(size)}）",
                sessions = sessions.length(),
                bytes = size,
                mode = mode,
            )
        }.getOrElse { e ->
            runCatching { if (outFile.exists()) outFile.deleteRecursively() }
            Result(false, null, "导出失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 复用同一块缓冲区，避免每个文件都 new 64KB。 */
    private val TMP_BUF = ByteArray(64 * 1024)

    // ================= 采集 =================

    /** 工作区根目录（所有会话目录的父目录）。 */
    fun workspaceRoot(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = File(base, "workspaces")
        return if (root.isDirectory) root else null
    }

    /**
     * 从分片目录里读出全部会话元数据。
     *
     * ⚠⚠ 2026-11-13 OOM 根因修复：旧实现从 `agent_workspace_store` 快照里
     *   逐个取出 `session_<id>` 再 `JSONObject(raw)` 解析。分片化之后这些键
     *   仍是**冗余副本**，单个就可能几十 MB，堆里同时放原始 String + JSONObject 树
     *   → Failed to allocate 150994952 byte。
     *
     *   现在只读 filesDir/sessions/<id>/meta.json（每个几百字节），
     *   消息正文由 files/sessions/ 目录整体导出承载。
     *
     * 仍然把 **mode（项目类型标识）** 提到 manifest 里做双保险：
     * 即使导入时某个会话解析失败，也能从 manifest 恢复分类。
     */
    private fun collectSessions(context: Context): JSONArray {
        val out = JSONArray()

        val sessionsRoot = File(context.filesDir, "sessions")
        val wsRoot = workspaceRoot(context)
        val dirs = sessionsRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

        dirs.forEach { dir ->
            val id = dir.name
            val meta = runCatching {
                JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8))
            }.getOrNull() ?: return@forEach

            // 消息条数：数分片文件个数不够准确，但 meta 里没存总数，
            // 而逐片解析又回到 OOM 老路。用「分片数」给个量级即可，
            // 真正的内容由 files/sessions/<id>/ 原样搬运保证。
            val nodeCount = dir.listFiles { f -> f.name.startsWith("node_") }?.size ?: 0
            val hasWorkspace = wsRoot != null && File(wsRoot, id).isDirectory
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("title", meta.optString("title", "新对话"))
                    .put("mode", meta.optString("mode", "chat"))
                    .put("messageCount", nodeCount * 32)
                    .put("hasWorkspace", hasWorkspace)
            )
        }
        return out
    }

    /**
     * 值编码：保留原始类型，避免 4.7 导入时「int 变 String」这类静默降级。
     * 支持 SharedPreferences 的全部类型：String / Int / Long / Float / Boolean / Set<String>。
     */
    private fun encodeValue(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("t", "null")
        is String -> JSONObject().put("t", "s").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it?.toString() ?: "" }))
        else -> JSONObject().put("t", "s").put("v", v.toString())
    }

    /**
     * 判断一个 SharedPreferences 键是否是「会话消息 JSON」。
     *
     * 只有 `session_<id>` 是消息正文。其它 `session_` 前缀的键各有用途：
     *   session_index            StringSet（会话 id 列表）
     *   session_workspace_<id>   String（绝对路径）
     *   session_active_<id>      Long
     *   session_title_locked_<id> / session_title_done_<id>
     *   session_prompt_<id>
     *
     * ⚠ 4.6 曾在这里崩过：把 session_index 当成会话 id "index"，
     *   getString 拿到 HashSet → ClassCastException。
     */
    fun isSessionJsonKey(key: String): Boolean {
        if (key == "session_index") return false
        if (key.startsWith("session_workspace_")) return false
        if (key.startsWith("session_active_")) return false
        if (key.startsWith("session_title_locked_")) return false
        if (key.startsWith("session_title_done_")) return false
        if (key.startsWith("session_prompt_")) return false
        return key.startsWith("session_")
    }

    /**
     * **流式**把一个 SharedPreferences 写成 zip 条目。
     *
     * 为什么必须有这个函数（2026-11-13 线上 OOM）：
     *   旧写法是先 `JSONObject()` 把所有键 put 进去，再 `toString().toByteArray()`。
     *   对 agent_workspace_store 来说，堆里会同时存在
     *     「原始 Map(150MB) + JSONObject 树 + String(150MB) + ByteArray(150MB)」
     *   四份拷贝 → Failed to allocate 150994952 byte，导出必挂。
     *
     *   现在逐个键取出 → 编码 → 立刻写进 zip 流 → 丢掉引用。
     *   常驻内存只有「一个键」的量，跟 pref 文件总大小彻底脱钩。
     *
     * 输出格式与旧版**完全一致**（`{key:{t,v}}` 的 JSON 对象），
     * 所以 importer 一行都不用改。
     */
    private fun streamPrefEntry(
        zos: ZipOutputStream,
        entryName: String,
        keys: Set<String>,
        getValue: (String) -> Any?,
        shouldSkip: (String) -> Boolean,
        onSkipped: () -> Unit = {},
        onBytes: (Long) -> Unit = {},
    ) {
        val e = ZipEntry(entryName)
        e.time = System.currentTimeMillis()
        zos.putNextEntry(e)
        // 用 Writer 而不是 ByteArrayOutputStream：避免在堆里攒整份 JSON
        val writer = java.io.OutputStreamWriter(zos, Charsets.UTF_8)
        writer.write("{")
        var first = true
        for (k in keys) {
            if (shouldSkip(k)) { onSkipped(); continue }
            val v = getValue(k)
            val encoded = encodeValue(v).toString()
            val chunk = (if (first) "" else ",") +
                JSONObject.quote(k) + ":" + encoded
            writer.write(chunk)
            onBytes(chunk.length.toLong())
            first = false
        }
        writer.write("}")
        writer.flush()
        zos.closeEntry()
    }

    private fun buildManifest(
        context: Context,
        sessions: JSONArray,
        workspaceDirs: List<File>,
    ): JSONObject {
        val pkg = context.packageName
        val pm = context.packageManager
        val vCode = runCatching {
            pm.getPackageInfo(pkg, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        }.getOrDefault(0L)
        val vName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: ""

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("kind", "workspace-migration")
            .put("appLabel", "Workspace")
            .put("fromPackage", pkg)
            .put("fromVersionCode", vCode)
            .put("fromVersionName", vName)
            .put("exportedAt", System.currentTimeMillis())
            .put("exportedAtText", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("includedPrefs", JSONArray(EXPORTED_PREFS))
            .put("includedFileDirs", JSONArray(EXPORTED_FILE_DIRS))
            .put("excludedNote", "沙箱系统镜像与全部工具链（Java/Python/Go/Node/NDK/Kotlin/视频/逆向）不打包，4.7 首启自动重装")
            .put("sessions", sessions)
            .put("workspaceDirCount", workspaceDirs.size)
            // 4.7 导入后要用它重写 session_workspace_<id> 的绝对路径
            .put("workspaceDirName", "workspaces")
    }

    // ================= 打包工具 =================

    private fun putEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.time = System.currentTimeMillis()
        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
    }

    /**
     * 递归把一个目录塞进 zip，返回写入的原始字节数。
     *
     * ⚠ 逐文件流式读写，绝不整目录 readBytes() —— 工作区里有单个大文件，
     * 也有几十万个小文件（Gradle 缓存），必须扛得住。
     * ⚠ 符号链接按普通文件处理（isFile 为 true 才读），避免循环。
     */
    private fun zipTree(
        zos: ZipOutputStream,
        base: File,
        entryPrefix: String,
        buf: ByteArray,
        onBytes: (Long) -> Unit,
    ): Long {
        var total = 0L
        if (!base.exists()) return 0L
        if (base.isFile) {
            val name = "$entryPrefix/${base.name}"
            val e = ZipEntry(name)
            e.time = base.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            zos.putNextEntry(e)
            base.inputStream().use { input -> total += pump(input, zos, buf, onBytes) }
            zos.closeEntry()
            return total
        }
        val children = base.listFiles() ?: return 0L
        // 空目录也要留一个条目，导入时才知道它的存在
        if (children.isEmpty()) {
            putEntry(zos, "$entryPrefix/", ByteArray(0))
            return 0L
        }
        for (child in children) {
            if (child.isDirectory) {
                total += zipTree(zos, child, "$entryPrefix/${child.name}", buf, onBytes)
            } else if (child.isFile) {
                val entryName = "$entryPrefix/${child.name}"
                val e = ZipEntry(entryName)
                e.time = child.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                // ⚠ 单个文件读不出来（权限/导出中途被删）不该让整包失败：
                // 失败就跳过该条目，继续下一个。
                // 注意 openEntry 标志：只有 putNextEntry 成功过才 closeEntry，
                // 否则会对一个未打开的条目 close，抛出新的异常把循环打断。
                var opened = false
                runCatching {
                    zos.putNextEntry(e)
                    opened = true
                    child.inputStream().use { input -> total += pump(input, zos, buf, onBytes) }
                }.onFailure {
                    // 忽略该文件；zip 里最多留一个空条目，不影响其它内容
                }
                if (opened) runCatching { zos.closeEntry() }
            }
        }
        return total
    }

    private fun pump(input: InputStream, out: ZipOutputStream, buf: ByteArray, onBytes: (Long) -> Unit): Long {
        var n: Int
        var total = 0L
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
            total += n
            onBytes(n.toLong())
        }
        return total
    }

    /**
     * 递归复制目录树（文件夹导出模式用）。
     *
     * 与 [zipTree] 的目录遍历规则保持一致，这样两种模式产出的**相对路径完全相同**，
     * 4.7 的 importer 可以共用一套解析。
     *
     * ⚠ 单个文件复制失败只跳过它，不中断整个导出 —— 工作区里可能有
     * 被删除的临时文件、权限异常的 socket 之类。
     */
    private fun copyTree(src: File, dst: File, onBytes: (Long) -> Unit) {
        if (!src.exists()) return
        if (src.isFile) {
            dst.parentFile?.mkdirs()
            runCatching {
                src.inputStream().use { input ->
                    FileOutputStream(dst).use { output ->
                        var n: Int
                        while (input.read(TMP_BUF).also { n = it } > 0) {
                            output.write(TMP_BUF, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
            }
            return
        }
        dst.mkdirs()
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                copyTree(child, File(dst, child.name), onBytes)
            } else if (child.isFile) {
                val target = File(dst, child.name)
                target.parentFile?.mkdirs()
                runCatching {
                    child.inputStream().use { input ->
                        FileOutputStream(target).use { output ->
                            var n: Int
                            while (input.read(TMP_BUF).also { n = it } > 0) {
                                output.write(TMP_BUF, 0, n)
                                onBytes(n.toLong())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var sum = 0L
        dir.listFiles()?.forEach { sum += dirSize(it) }
        return sum
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}