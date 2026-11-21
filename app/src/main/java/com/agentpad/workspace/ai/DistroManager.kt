package com.agentpad.workspace.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「设置 → 系统管理」的**终端执行器系统**管理器。
 *
 * ## 这个类是干什么的
 * 同一个 App 可以同时存在多个 rootfs（执行器系统），例如：
 *  - Debian slim（默认，首次启动自动装）
 *  - Alpine 3.19 minirootfs（本项目第一个内置的可选系统）
 *  - 用户自己填外部链接装的任意系统
 * 用户在这里切换「当前生效的那一个」，之后 AI 终端、部署服务、工具链
 *  全部改走新的 rootfs。
 *
 * ## 为什么不再复用 SandboxInstaller.SOURCES
 * 那套只描述「同一个 Debian 从哪下载」（下载源切换），不描述「装哪个系统」。
 *  本类是**多系统并存**，粒度不同，混在一起会让两个概念的 UI 都说不清。
 *
 * ## 存储布局（关键，别改）
 * ```
 * filesDir/sandbox/
 *   ├── rootfs/                     ← 当前生效的 rootfs（真实目录或软链）
 *   ├── systems/<id>/               ← 每个系统一个独立 rootfs 目录
 *   │     ├── debian-slim/
 *   │     └── alpine-3.19/
 *   └── prefs（SharedPreferences "sandbox_prefs"）
 *         key: active_system_id   = 当前生效系统 id
 *         key: systems_json      = 用户自建系统的元数据（JSON 数组）
 * ```
 * `rootfs` 用软链指向 `systems/<active>`，这样所有既有调用点
 * （SandboxRunner / TerminalService / DshService / DownloadEnvironmentActivity /
 *  EnvCatalog …）**一行都不用改**，自动跟着切换走。
 */
object DistroManager {

    const val PREF = "sandbox_prefs"
    private const val KEY_ACTIVE = "active_system_id"
    private const val KEY_SYSTEMS = "systems_json"

    /** 默认系统 id（Debian slim，首次启动自动安装的那个）。 */
    const val SYSTEM_DEBIAN = "debian-slim"

    /** 系统目录根：filesDir/sandbox/systems/ */
    fun systemsDir(context: Context): File =
        File(SandboxInstaller.sandboxDir(context), "systems").apply { mkdirs() }

    /** 某个系统的 rootfs 目录。 */
    fun systemDir(context: Context, id: String): File = File(systemsDir(context), id)

    /** 当前生效的系统 id。 */
    fun activeId(context: Context): String {
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, SYSTEM_DEBIAN) ?: SYSTEM_DEBIAN
        return if (v.isBlank()) SYSTEM_DEBIAN else v
    }

    /** 当前生效系统的 rootfs 目录。 */
    fun activeDir(context: Context): File = systemDir(context, activeId(context))

    /**
     * 一个可安装/已安装的终端执行器系统。
     *
     * @param id         唯一 id，也是目录名（自建系统用 url 的 hash 派生）
     * @param label      显示名
     * @param desc       一句话说明
     * @param url        下载地址；内置系统写死，自建系统由用户填
     * @param kind       包格式：tar.gz / tar.xz / tar
     * @param builtin    是否 App 内置（不可删除）
     * @param pkgManager 包管理器，仅用于 UI 提示（apt / apk / 未知）
     */
    data class Distro(
        val id: String,
        val label: String,
        val desc: String,
        val url: String,
        val kind: String = "tar.gz",
        val builtin: Boolean = false,
        val pkgManager: String = "未知",
    )

    /** App 内置系统列表。新增内置系统只改这里。 */
    fun builtins(): List<Distro> = listOf(
        Distro(
            id = SYSTEM_DEBIAN,
            label = "Debian 12 slim（arm64）",
            desc = "默认执行器系统。debuerreotype 官方构建，约 35MB，自带 dpkg / apt-get。" +
                "AI 终端、工具链、部署服务都用它。",
            url = "https://gh-proxy.com/https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/stable/slim/oci/blobs/rootfs.tar.gz",
            kind = "tar.gz",
            builtin = true,
            pkgManager = "apt",
        ),
        Distro(
            id = "alpine-3.19",
            label = "Alpine 3.19 minirootfs（arm64）",
            desc = "阿里云镜像源。musl + busybox，约 3MB，包管理器 apk。" +
                "适合追求体积与启动速度的场景；注意部分依赖 glibc 的工具链不通用。",
            url = "https://mirrors.aliyun.com/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.0-aarch64.tar.gz",
            kind = "tar.gz",
            builtin = true,
            pkgManager = "apk",
        ),
    )

    /** 用户自建系统（从 prefs 读）。 */
    fun custom(context: Context): List<Distro> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SYSTEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                Distro(
                    id = id,
                    label = o.optString("label").ifBlank { id },
                    desc = o.optString("desc"),
                    url = o.optString("url"),
                    kind = o.optString("kind").ifBlank { "tar.gz" },
                    builtin = false,
                    pkgManager = o.optString("pkgManager").ifBlank { "未知" },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 全部系统：内置在前，自建在后。 */
    fun all(context: Context): List<Distro> = builtins() + custom(context)

    fun find(context: Context, id: String): Distro? = all(context).firstOrNull { it.id == id }

    /** 某个系统是否已安装（解压落地且里面有可用 shell）。 */
    fun isInstalled(context: Context, id: String): Boolean {
        val root = systemDir(context, id)
        if (!root.isDirectory) return false
        if (File(root, ".distro-installed").isFile.not()) return false
        // 探一个真实可执行 shell（软链不行，跟 SandboxInstaller.isHealthy 同口径）
        if (listOf("bin/sh", "bin/busybox", "usr/bin/dash", "bin/bash", "usr/bin/bash")
                .none { File(root, it).let { f -> f.isFile && f.canExecute() } }
        ) return false

        // ⚠ 2026-11-13 事故自愈：启动时的自动下载曾把 **Debian** 的内容
        //   灌进 alpine-3.19 目录（还打了 id=debian-slim 标记）。
        //   那种目录里没有 apk，却声称自己是 Alpine。
        //   判它「未安装」，好让用户重装，也避免 isHealthy 按 Alpine 查 apk 时
        //   永远不过 → 每次启动重下 35MB 的死循环。
        if (id.startsWith("alpine")) {
            val hasApk = File(root, "sbin/apk").isFile || File(root, "usr/bin/apk").isFile
            if (!hasApk) {
                android.util.Log.w(
                    "DistroManager",
                    "system $id 缺少 apk，判定为被 Debian 内容污染，按未安装处理（建议重装）",
                )
                return false
            }
        }
        return true
    }

    /** 当前生效系统是否已安装（决定主入口是「安装」还是「切换」）。 */
    fun isActiveInstalled(context: Context): Boolean = isInstalled(context, activeId(context))

    /** 是否有任意一个系统已安装（BootActivity 的 gate 用它）。 */
    fun anyInstalled(context: Context): Boolean =
        all(context).any { isInstalled(context, it.id) }

    /**
     * 切换到指定系统。
     *
     * ⚠ 2026-11-13 重大设计修正：**这里只改一个字符串，不做任何文件操作。**
     *
     * 原方案是维护 `sandbox/rootfs` 这个软链指向 `systems/<id>`，
     * 听着优雅，实际上是错的：
     *   • Android 的 `/data/user/0/` 是 F2FS/ext4 + SELinux 策略，
     *     `Files.createSymbolicLink` **必失败**（EPERM）；
     *   • 失败后走兜底 `target.copyRecursively(link)`，目标不存在时
     *     直接抛 NoSuchFileException，把 BootActivity 崩掉
     *     （线上实测：清除数据后连启动都进不去）；
     *   • 而且 rootfsDir() 每次被调都触发它，等于把崩溃点铺满所有路径。
     *
     * 现在的做法：rootfs 的真实位置永远是 `systems/<activeId>`，
     * `rootfsDir()` 只是个路径计算，切换 = 改 prefs 里的一个字符串。
     */
    fun setActive(context: Context, id: String) {
        val target = find(context, id) ?: error("未知系统：$id")
        if (!isInstalled(context, id)) error("「${target.label}」尚未安装")
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, SYSTEM_DEBIAN) == id) return
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /**
     * 旧版升级的数据抢救：把 `sandbox/rootfs` 实体目录搬进 `systems/debian-slim`。
     *
     * 只在**两种**情况下需要：
     *   ① 从旧版本升级上来，rootfs 还是实体目录（分片化之前的布局）
     *   ② 上一次迁移被中断，留下了搬了一半的目录
     *
     * ⚠ 这个函数**绝不能在启动路径上自动跑**：
     *   它做重 IO（几十 MB 拷贝），且目标目录可能不存在。
     *   由「系统管理」页面的用户主动触发，或 BootActivity 在确认
     *   "rootfs 是实体目录且非空" 之后调用，且必须整体包在 runCatching 里。
     *
     * @return true = 确实搬迁了；false = 无需搬迁
     */
    fun migrateLegacyRootfsIfNeeded(context: Context): Boolean {
        val sandbox = SandboxInstaller.sandboxDir(context)
        val legacyDir = File(sandbox, "rootfs")
        // 没有实体目录 / 已经是我们要的目标 → 不用搬
        if (!legacyDir.isDirectory) return false
        if (isSymlink(legacyDir)) {
            // 理论上不该存在（新方案不建软链），但保险起见清掉悬空链
            runCatching { legacyDir.delete() }
            return false
        }
        // 空目录直接删，别搬
        if (legacyDir.list()?.isEmpty() != false) {
            runCatching { legacyDir.deleteRecursively() }
            return false
        }
        val dest = systemDir(context, SYSTEM_DEBIAN)
        if (File(dest, ".distro-installed").isFile) {
            // 目标已经是有效的 Debian，旧的实体目录是残留，删掉腾地方
            runCatching { legacyDir.deleteRecursively() }
            return false
        }
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.deleteRecursively()
        val moved = runCatching { legacyDir.renameTo(dest) }.getOrDefault(false)
        if (!moved) {
            runCatching {
                legacyDir.copyRecursively(dest, overwrite = true)
                legacyDir.deleteRecursively()
            }.getOrElse { return false }
        }
        runCatching {
            File(dest, ".distro-installed")
                .writeText("migrated from legacy rootfs dir\n")
        }
        return true
    }

    private val lock = Any()

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 给 SandboxInstaller 的日志用的公开版判据。 */
    fun isLinkPublic(f: File): Boolean = isSymlink(f)

    /**
     * 安装一个系统（下载 → 解压到 systems/<id> → 打标 → 若不是首个则不动 rootfs）。
     *
     * @param onMessage 状态文案
     * @param onPercent 0..100
     */
    fun install(
        context: Context,
        distro: Distro,
        onMessage: (String) -> Unit = {},
        onPercent: (Int) -> Unit = {},
    ) {
        val sandbox = SandboxInstaller.sandboxDir(context)
        sandbox.mkdirs()
        val dest = systemDir(context, distro.id)
        // 已安装就跳过（要重装走 reinstall）
        if (isInstalled(context, distro.id)) {
            onMessage("${distro.label} 已安装")
            onPercent(100)
            return
        }
        dest.mkdirs()

        val cache = File(sandbox, "distro-download").apply { mkdirs() }
        val archive = File(cache, "${distro.id}.${distro.kind}")
        if (archive.exists()) archive.delete()

        onMessage("正在下载 ${distro.label}")
        onPercent(2)
        DistroDownloader.download(context, distro, archive) { p ->
            onPercent((2 + p * 0.86f).toInt().coerceIn(2, 98))
        }

        onMessage("正在解压 ${distro.label}")
        onPercent(98)
        DistroDownloader.extract(archive, dest, distro.kind) { p ->
            onPercent((98 + p * 0.02f).toInt().coerceIn(98, 100))
        }
        archive.delete()

        // Alpine minirootfs 不带 /etc/resolv.conf，init-sandbox.sh 会补；
        // 这里只做「安装成功」标记 + 权限兜底。
        listOf("bin/sh", "bin/busybox", "usr/bin/dash", "bin/bash").forEach { n ->
            File(dest, n).takeIf { it.isFile }?.setExecutable(true, false)
        }
        File(dest, ".distro-installed").writeText(
            "id=${distro.id}\nurl=${distro.url}\nkind=${distro.kind}\n"
        )
        // ⚠ 2026-11-13 事故：以前只写 .distro-installed，不写 .installed-v2。
        //   而 SandboxInstaller.isHealthy() 第一句就是查 .installed-v2，
        //   所以从「系统管理」装好的 Alpine **永远过不了健康检查**，
        //   表现为「装完了还是提示沙箱未就绪」「下载环境校验不过」。
        //   现在两个标记都写，Alpine 额外补 resolv.conf 和 apk repositories。
        File(dest, ".installed-v2").writeText("rootfs installed v2 (via SystemManage: ${distro.id})")

        // Alpine minirootfs 是「裸」的：无 /etc/apk/repositories、无 resolv.conf。
        // 不补 repositories 的话 apk add 直接报 "unable to select packages"。
        if (distro.id.startsWith("alpine")) {
            runCatching {
                val repos = File(dest, "etc/apk/repositories")
                repos.parentFile?.mkdirs()
                if (!repos.isFile || repos.length() == 0L) {
                    repos.writeText(
                        "https://mirrors.aliyun.com/alpine/v3.19/main\n" +
                            "https://mirrors.aliyun.com/alpine/v3.19/community\n"
                    )
                }
            }
            runCatching {
                // 默认 DNS = 腾讯云（119.29.29.29 / 1.12.12.12），阿里 223.5.5.5 仅作第三备。
                val r = File(dest, "etc/resolv.conf")
                r.parentFile?.mkdirs()
                if (!r.isFile || r.length() == 0L || !r.readText().contains("119.29.29.29")) {
                    r.writeText("nameserver 119.29.29.29\nnameserver 1.12.12.12\nnameserver 223.5.5.5\n")
                }
            }
        }
        onPercent(100)
        onMessage("${distro.label} 安装完成")

        // 第一个装好的系统 → 自动成为生效系统（只改一个字符串，不做文件操作）
        if (!anyOtherInstalled(context, distro.id)) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE, distro.id).apply()
        }
    }

    private fun anyOtherInstalled(context: Context, except: String): Boolean =
        all(context).any { it.id != except && isInstalled(context, it.id) }

    /** 重装（先删后装）。 */
    fun reinstall(
        context: Context,
        distro: Distro,
        onMessage: (String) -> Unit = {},
        onPercent: (Int) -> Unit = {},
    ) {
        val dest = systemDir(context, distro.id)
        val wasActive = activeId(context) == distro.id
        // 生效中的系统不能直接删：删了 rootfs 软链就悬空，AI 终端立刻全挂。
        // 先把它请回默认系统，再删。
        if (wasActive) {
            val fallback = all(context).firstOrNull {
                it.id != distro.id && isInstalled(context, it.id)
            }
            if (fallback != null) setActive(context, fallback.id)
        }
        if (dest.exists()) dest.deleteRecursively()
        install(context, distro, onMessage, onPercent)
    }

    /** 删除一个已安装系统（内置系统不允许删）。 */
    fun uninstall(context: Context, id: String) {
        val distro = find(context, id) ?: error("未知系统：$id")
        if (distro.builtin) error("内置系统不可删除")
        if (activeId(context) == id) {
            val fallback = all(context).firstOrNull {
                it.id != id && isInstalled(context, it.id)
            } ?: error("没有其它已安装系统可切换，先装一个再删")
            setActive(context, fallback.id)
        }
        systemDir(context, id).deleteRecursively()
        removeCustom(context, id)
    }

    /**
     * 从外部链接新增一个系统（不下载，只登记；安装由用户点「安装」触发）。
     * 这样列表里立刻可见，也能先看体积/格式再决定装不装。
     */
    fun addCustom(context: Context, label: String, url: String, desc: String = ""): Distro {
        val clean = url.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "链接必须以 http(s):// 开头" }
        val kind = when {
            clean.endsWith(".tar.xz") || clean.endsWith(".txz") -> "tar.xz"
            clean.endsWith(".tar") -> "tar"
            clean.endsWith(".tar.gz") || clean.endsWith(".tgz") -> "tar.gz"
            else -> "tar.gz"   // 默认按 gz 试，解不开会报错让用户改
        }
        val id = "custom-" + Integer.toHexString(clean.hashCode())
        val existing = custom(context)
        if (existing.none { it.id == id }) {
            val arr = JSONArray()
            existing.forEach { d ->
                arr.put(
                    JSONObject().apply {
                        put("id", d.id); put("label", d.label); put("desc", d.desc)
                        put("url", d.url); put("kind", d.kind); put("pkgManager", d.pkgManager)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", id)
                    put("label", label.ifBlank { urlFileName(clean) })
                    put("desc", desc)
                    put("url", clean)
                    put("kind", kind)
                    put("pkgManager", "未知")
                }
            )
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_SYSTEMS, arr.toString()).apply()
        }
        return Distro(id, label.ifBlank { urlFileName(clean) }, desc, clean, kind, false, "未知")
    }

    private fun removeCustom(context: Context, id: String) {
        val left = custom(context).filter { it.id != id }
        val arr = JSONArray()
        left.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("id", d.id); put("label", d.label); put("desc", d.desc)
                    put("url", d.url); put("kind", d.kind); put("pkgManager", d.pkgManager)
                }
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SYSTEMS, arr.toString()).apply()
    }

    private fun urlFileName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "自定义系统" }
}
