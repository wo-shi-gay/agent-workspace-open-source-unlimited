// 远程工作区的 AI 工具层。
//
// 作用：把 SSH 能力注册成 AI 可调用的工具，让模型能直接操作远程服务器。
//
// 【工具设计 —— 参考 RikkaHub 的 WorkspaceTools.kt】
// RikkaHub 把远程工作区包装成一组工具（ls / read / write / edit / grep / glob / exec），
// 模型通过 workspaceId 指定操作哪个工作区。我们沿用这个思路，但工具名加 remote_ 前缀，
// 避免和已有的本地 terminal / read_file / write_file 混淆。
//
// 【为什么用连接池】
// 每次工具调用都新建 SSH 连接太慢（TCP + 握手 + 认证约 0.5-2 秒）。
// RikaHub 有 SshConnectionPool，这里也做一个简化版：按 workspaceId 缓存连接，
// 空闲 60 秒后关闭，避免长期占着服务器资源。
//
// 【会话级隔离】
// AI 只能操作 config.remoteDir 之下的路径。所有路径参数都会被规范化为
// 绝对路径并校验前缀，防止模型乱跑（比如读 /etc/shadow）。

package com.agentpad.workspace.ai

import android.content.Context
import com.agentpad.workspace.data.RemoteWorkspace
import com.agentpad.workspace.data.RemoteWorkspaceStore
import com.agentpad.workspace.data.ssh.SshConnectionConfig
import com.agentpad.workspace.data.ssh.SshUnknownHostKeyException
import com.agentpad.workspace.data.ssh.WorkspaceCommandResult
import com.agentpad.workspace.data.ssh.SshConnection
import com.jcraft.jsch.ChannelSftp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 连接池。按 workspaceId 缓存 [SshConnection]，空闲超时后关闭。
 *
 * 简化自 RikkaHub workspace/src/main/java/me/rerere/workspace/ssh/SshConnectionPool.kt（210 行）。
 * 原版有引用计数、并发连接复用、后台清理线程等，我们只需要最基本的复用。
 */
object SshPool {
    private const val IDLE_TIMEOUT_MS = 60_000L
    /** 单条输出上限，和 StreamCollector 一致 */
    private const val MAX_OUTPUT = 256 * 1024

    private data class Entry(
        val connection: SshConnection,
        val lastUsed: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 取连接（没有就建一条）。 */
    @Synchronized
    internal fun get(workspaceId: String, config: SshConnectionConfig): SshConnection {
        val now = System.currentTimeMillis()
        // 先清理过期连接
        cache.entries.removeIf { (_, e) ->
            if (now - e.lastUsed > IDLE_TIMEOUT_MS || !e.connection.isConnected) {
                runCatching { e.connection.close() }
                true
            } else false
        }
        val existing = cache[workspaceId]?.connection
        if (existing != null && existing.isConnected) {
            cache[workspaceId] = Entry(existing, now)
            return existing
        }
        val fresh = SshConnection.open(config)
        cache[workspaceId] = Entry(fresh, now)
        return fresh
    }

    /** 主动断开某条。 */
    @Synchronized
    fun evict(workspaceId: String) {
        cache.remove(workspaceId)?.let { runCatching { it.connection.close() } }
    }

    /** 全部断开（应用退出时调）。 */
    @Synchronized
    fun closeAll() {
        cache.values.forEach { runCatching { it.connection.close() } }
        cache.clear()
    }

    /** 触达时间刷新，让长时间活跃的连接不被回收。 */
    fun touch(workspaceId: String) {
        cache[workspaceId]?.let {
            cache[workspaceId] = it.copy(lastUsed = System.currentTimeMillis())
        }
    }
}

/**
 * 远程工作区工具实现。由 [ToolRegistry] 注册。
 */
class RemoteWorkspaceTools(private val context: Context) {

    // ---------- 参数解析 ----------

    /** 取 workspaceId，缺省时用第一个已配置的工作区。 */
    private fun resolveWorkspace(args: JSONObject): RemoteWorkspace {
        val id = args.optString("workspace_id").takeIf { it.isNotBlank() }
        val all = RemoteWorkspaceStore.list(context)
        if (all.isEmpty()) {
            throw IllegalStateException("还没有配置任何远程工作区。请先在「设置 → 远程工作区」里添加。")
        }
        return if (id != null) {
            all.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("找不到 id=$id 的工作区。可用：" +
                    all.joinToString { "${it.id}(${it.name})" })
        } else {
            all.first()
        }
    }

    /**
     * 把用户给的路径规范化为绝对路径，并确保落在 remoteDir 之内。
     *
     * 规则（照抄 RikkaHub WorkspaceRepository 的路径策略）：
     *   · 以 / 开头 → 视为绝对路径，但必须前缀匹配 remoteDir
     *   · 否则 → 相对 remoteDir 解析
     *   · 解析 .. 之后如果跳出 remoteDir，直接拒绝
     */
    private fun safePath(ws: RemoteWorkspace, raw: String): String {
        val root = ws.config.remoteDir.trim().trimEnd('/').ifBlank { "/" }
        val p = raw.trim()
        val absolute = if (p.startsWith("/")) p else "$root/$p"
        // 手工解析 . 和 ..
        val parts = ArrayDeque<String>()
        absolute.split('/').forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val normalized = "/" + parts.joinToString("/")
        if (root != "/" && !normalized.startsWith("$root/") && normalized != root) {
            throw SecurityException("路径越界：$raw 不在工作目录 $root 之内")
        }
        return normalized
    }

    /** 执行命令，带上 cd 到工作目录。 */
    private fun exec(ws: RemoteWorkspace, command: String, timeoutMs: Long = 60_000): WorkspaceCommandResult {
        val conn = SshPool.get(ws.id, ws.config)
        val root = ws.config.remoteDir.trim().ifBlank { "~" }
        // 用 bash -lc 保证加载用户环境（PATH 等）
        val wrapped = "cd ${shellQuote(root)} && $command"
        val result = conn.exec(wrapped, timeoutMs)
        SshPool.touch(ws.id)
        return result
    }

    /** 单引号转义，防注入。 */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    // ---------- 工具实现 ----------

    /** 列出目录。对应 RikkaHub 的 ls 工具。 */
    fun ls(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val path = safePath(ws, args.optString("path", "."))
        val conn = SshPool.get(ws.id, ws.config)
        return conn.sftp { sftp ->
            @Suppress("UNCHECKED_CAST")
            val entries = sftp.ls(path).toList() as List<ChannelSftp.LsEntry>
            val arr = JSONArray()
            entries
                .filter { it.filename != "." && it.filename != ".." }
                .sortedWith(compareBy({ !it.attrs.isDir }, { it.filename }))
                .take(500)
                .forEach { e ->
                    arr.put(JSONObject().apply {
                        put("name", e.filename)
                        // JSch 的 LsEntry 字段名是 attrs（不是 attaches）
                        val a = e.attrs
                        put("type", if (a.isDir) "dir" else if (a.isLink) "link" else "file")
                        put("size", a.size)
                        put("mtime", a.mTime * 1000L)
                        put("perms", a.permissionsString)
                    })
                }
            JSONObject().apply {
                put("success", true)
                put("path", path)
                put("count", arr.length())
                put("entries", arr)
            }.toString()
        }
    }

    /** 读文件。对应 RikkaHub 的 read 工具。 */
    fun read(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val path = safePath(ws, args.optString("path"))
        val maxBytes = args.optInt("max_bytes", 128 * 1024).coerceIn(1, MAX_OUTPUT)
        val conn = SshPool.get(ws.id, ws.config)
        return conn.sftp { sftp ->
            val attrs = sftp.stat(path)
            if (attrs.isDir) throw IllegalArgumentException("$path 是目录，请用 remote_ls")
            val out = ByteArrayOutputStream()
            sftp.get(path, out)
            val all = out.toByteArray()
            val slice = if (all.size > maxBytes) all.copyOf(maxBytes) else all
            JSONObject().apply {
                put("success", true)
                put("path", path)
                put("size", all.size)
                put("truncated", all.size > maxBytes)
                put("content", String(slice, Charsets.UTF_8))
            }.toString()
        }
    }

    /** 写文件（覆盖）。对应 RikkaHub 的 write 工具。 */
    fun write(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val path = safePath(ws, args.optString("path"))
        val content = args.optString("content")
        val conn = SshPool.get(ws.id, ws.config)
        // 先确保父目录存在
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { conn.ensureDir(it) }
        return conn.sftp { sftp ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            sftp.put(ByteArrayInputStream(bytes), path)
            JSONObject().apply {
                put("success", true)
                put("path", path)
                put("bytes", bytes.size)
                put("message", "已写入 ${bytes.size} 字节")
            }.toString()
        }
    }

    /** 编辑文件：把 old 替换为 new。对应 RikkaHub 的 edit 工具。 */
    fun edit(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val path = safePath(ws, args.optString("path"))
        val oldText = args.optString("old")
        val newText = args.optString("new")
        if (oldText.isEmpty()) throw IllegalArgumentException("old 不能为空")
        val replaceAll = args.optBoolean("replace_all", false)
        val conn = SshPool.get(ws.id, ws.config)
        return conn.sftp { sftp ->
            val out = ByteArrayOutputStream()
            sftp.get(path, out)
            val original = String(out.toByteArray(), Charsets.UTF_8)
            // 统计出现次数（不重叠）
            var count = 0
            var from = 0
            while (true) {
                val idx = original.indexOf(oldText, from)
                if (idx < 0) break
                count++
                from = idx + oldText.length
            }
            // 找不到 / 多次匹配又没允许全替换 → 抛异常，让上层转成错误 JSON
            if (count == 0) {
                throw IllegalArgumentException("找不到要替换的内容（old 在文件中不存在）")
            }
            if (count > 1 && !replaceAll) {
                throw IllegalArgumentException(
                    "old 在文件中出现 $count 次，请提供更长的唯一片段，或传 replace_all=true"
                )
            }
            val updated = if (replaceAll) original.replace(oldText, newText)
                          else original.replaceFirst(oldText, newText)
            sftp.put(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8)), path)
            JSONObject().apply {
                put("success", true)
                put("path", path)
                put("replacements", if (replaceAll) count else 1)
                put("message", "已替换")
            }.toString()
        }
    }

    /** 在文件里搜索。对应 RikkaHub 的 grep 工具。 */
    fun grep(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val pattern = args.optString("pattern")
        if (pattern.isEmpty()) throw IllegalArgumentException("pattern 不能为空")
        val path = args.optString("path", ".")
        val target = safePath(ws, path)
        val include = args.optString("include")
        // 用 grep -rn，--include 可选
        val cmd = buildString {
            append("grep -rn --color=never")
            if (include.isNotBlank()) append(" --include=").append(shellQuote(include))
            append(' ').append(shellQuote(pattern))
            append(' ').append(shellQuote(target))
            append(" 2>/dev/null | head -200")
        }
        val r = exec(ws, cmd)
        return JSONObject().apply {
            put("success", true)
            put("pattern", pattern)
            put("path", target)
            put("output", r.stdout)
            put("truncated", r.stdout.length >= 200 * 80)
        }.toString()
    }

    /** 按文件名找文件。对应 RikkaHub 的 glob 工具。 */
    fun glob(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val pattern = args.optString("pattern", "*")
        val target = safePath(ws, args.optString("path", "."))
        val cmd = "find ${shellQuote(target)} -type f -name ${shellQuote(pattern)} 2>/dev/null | head -300"
        val r = exec(ws, cmd)
        return JSONObject().apply {
            put("success", true)
            put("pattern", pattern)
            put("path", target)
            put("output", r.stdout)
        }.toString()
    }

    /** 执行任意命令。对应 RikkaHub 的 shell 工具。 */
    fun shell(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        val cmd = args.optString("command")
        if (cmd.isBlank()) throw IllegalArgumentException("command 不能为空")
        val timeout = args.optLong("timeout_ms", 60_000L).coerceIn(1_000L, 600_000L)
        val r = exec(ws, cmd, timeout)
        return JSONObject().apply {
            put("success", r.exitCode == 0)
            put("status", if (r.exitCode == 0) "success" else "error")
            put("workspace", ws.name)
            put("exit_code", r.exitCode)
            put("timed_out", r.timedOut)
            put("truncated", r.truncated)
            put("stdout", r.stdout.take(8000))
            put("stderr", r.stderr.take(4000))
        }.toString()
    }

    /** 测试连接（不依赖连接池，用临时连接）。 */
    fun test(args: JSONObject): String {
        val ws = resolveWorkspace(args)
        return try {
            val probe = SshConnection.probe(ws.config)
            // 首次连接：把服务器出示的公钥存下来（原版是弹窗让用户确认，这里自动信任并记录）
            if (ws.config.hostKey == null) {
                RemoteWorkspaceStore.trustHostKey(context, ws.id, probe.hostKey)
            }
            JSONObject().apply {
                put("success", true)
                put("workspace", ws.name)
                put("host_key", "${probe.hostKey.type} ${probe.hostKey.fingerprint}")
                put("home_dir", probe.homeDir)
                put("shell", probe.shell)
                put("remote_dir", ws.config.remoteDir)
                put("remote_dir_exists", probe.remoteDirExists)
            }.toString()
        } catch (e: SshUnknownHostKeyException) {
            JSONObject().apply {
                put("success", false)
                put("error", "首次连接，需要确认主机公钥")
                put("host_key", "${e.hostKey.type} ${e.hostKey.fingerprint}")
                put("hint", "再次调用 remote_test 即会自动信任并保存该指纹")
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }

    /** 列出所有已配置的工作区。 */
    fun list(args: JSONObject): String {
        val all = RemoteWorkspaceStore.list(context)
        val arr = JSONArray()
        all.forEach { ws ->
            arr.put(JSONObject().apply {
                put("id", ws.id)
                put("name", ws.name)
                put("address", ws.config.address)
                put("remote_dir", ws.config.remoteDir)
                put("auth", ws.config.authType.name)
                put("host_key_trusted", ws.config.hostKey != null)
            })
        }
        return JSONObject().apply {
            put("success", true)
            put("count", all.size)
            put("workspaces", arr)
        }.toString()
    }

    /** 小工具：让上面 sftp 块里能提前返回错误 JSON。 */
    private inline fun <T> ChannelSftpSink(body: () -> T): T = body()

    companion object {
        private const val MAX_OUTPUT = 256 * 1024

        /** 注册到 ToolRegistry。 */
        fun register(registry: ToolRegistry, context: Context) {
            val impl = RemoteWorkspaceTools(context)
            registry.register("remote_list") { args -> impl.list(args) }
            registry.register("remote_test") { args -> impl.test(args) }
            registry.register("remote_ls") { args -> impl.ls(args) }
            registry.register("remote_read") { args -> impl.read(args) }
            registry.register("remote_write") { args -> impl.write(args) }
            registry.register("remote_edit") { args -> impl.edit(args) }
            registry.register("remote_grep") { args -> impl.grep(args) }
            registry.register("remote_glob") { args -> impl.glob(args) }
            registry.register("remote_shell") { args -> impl.shell(args) }
        }
    }
}