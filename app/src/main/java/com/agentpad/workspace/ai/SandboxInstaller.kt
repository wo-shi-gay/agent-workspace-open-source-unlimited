package com.agentpad.workspace.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object SandboxInstaller {
    // 沙箱系统镜像：Debian ARM64 slim（debuerreotype 官方构建）。
    // 主源走 gh-proxy；备用源走小飞机网盘（部分网络 gh-proxy 不通时回退）。
    private const val DEBIAN_SLIM_PROXY =
        "https://gh-proxy.com/https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/stable/slim/oci/blobs/rootfs.tar.gz"
    /** 小飞机网盘备用源（用户分享的 Debian ARM64 rootfs 镜像）。 */
    private const val DEBIAN_SLIM_FEIJI =
        "https://example.com"
    private val DISTRO_SOURCES = listOf(DEBIAN_SLIM_PROXY, DEBIAN_SLIM_FEIJI)

    /** 可选下载源：id / 显示名 / 描述 / 地址。 */
    data class DistroSource(val id: String, val label: String, val desc: String, val url: String)

    val SOURCES = listOf(
        DistroSource("auto", "自动（推荐）", "先试 gh-proxy，失败自动切换小飞机网盘", ""),
        DistroSource("ghproxy", "GitHub 加速（gh-proxy）", "海外/直连较快的网络", DEBIAN_SLIM_PROXY),
        DistroSource("feiji", "小飞机网盘", "国内网络更稳定，自动解析直链", DEBIAN_SLIM_FEIJI),
    )

    private const val PREF = "sandbox_prefs"
    private const val KEY_SOURCE = "distro_source"

    fun selectedSourceId(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SOURCE, "auto") ?: "auto"

    fun selectSource(context: Context, id: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, id).apply()
    }

    /** 根据偏好返回实际尝试顺序。auto = 全部；指定则只试该源。 */
    private fun sourcesFor(context: Context): List<String> {
        return when (selectedSourceId(context)) {
            "ghproxy" -> listOf(DEBIAN_SLIM_PROXY)
            "feiji" -> listOf(DEBIAN_SLIM_FEIJI)
            else -> DISTRO_SOURCES
        }
    }

    /** Prebuilt CPython 3.12 (python-build-standalone, aarch64 glibc, stripped:
     *  no bundled pip — `python3 -m ensurepip` can materialize it on demand).
     *  实测 gh-proxy 通道 ~830KB/s、29MB 下载 + 7s 解压 < 40s 总耗时。 */
    private const val PYTHON_BUNDLE =
        "https://gh-proxy.com/https://github.com/astral-sh/python-build-standalone/releases/download/20260901/cpython-3.12.14+20260901-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"

    /** Python 3.16 尝鲜版：我们自己交叉编译并挂在 GitHub Release 上的包。
     *  顶层结构是 ./bin ./lib ./include（不是 astral 的 python/ 前缀），
     *  因此单独装到 usr/local/python316，绝不覆盖默认 python3。 */
    private const val PYTHON316_BUNDLE =
        "https://example.com"

    private const val PYTHON316_DIR = "usr/local/python316"

    /** Python 3.16 精简版（strip 过，11.9MB，含 include/pkgconfig/pip）。
     *  与上面的 full 包并存安装，互不覆盖。 */
    private const val PYTHON316_SLIM_BUNDLE =
        "https://example.com"

    private const val PYTHON316_SLIM_DIR = "usr/local/python316slim"

    /** PREFIX: writable private dir passed into init-sandbox.sh. */
    fun sandboxDir(context: Context): File = File(context.filesDir, "sandbox")
    /** NATIVE_LIB_DIR == app's system-managed native library dir. The .so payloads
     *  ship as real app native libs under jniLibs, so Android places them in
     *  nativeLibraryDir where exec / mmap PROT_EXEC is permitted (files/ is
     *  often not). We never copy proot itself into files/. */
    fun nativeDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)
    /** ROOTFS_DIR: 当前生效系统的 rootfs 目录。
     *
     * ⚠ 2026-11-13 重大设计修正（清除数据后启动崩溃的根因）：
     *
     * 原方案让这里返回 `sandbox/rootfs` 并维护一个**软链**指向
     * `systems/<activeId>`。那是错的：
     *   • Android `/data/user/0/` 下 `Files.createSymbolicLink` 必然
     *     EPERM 失败（F2FS + SELinux 策略，不是权限没申请）；
     *   • 失败兜底走 `target.copyRecursively(link)`，目标不存在时抛
     *     NoSuchFileException，直接崩在 BootActivity.onCreate；
     *   • 清除数据后 systems/ 为空，这个路径 100% 触发 → 永远进不了 App。
     *
     * 现在改成**纯路径计算**：直接返回 `systems/<activeId>`。
     *   • 不做任何文件操作，不可能抛异常；
     *   · 切换系统只改 prefs 里的一个字符串，下一条命令自动走新 rootfs；
     *   · 所有既有调用点拿到的还是同一个 File 对象语义，一行都不用改。
     */
    fun rootfsDir(context: Context): File =
        DistroManager.systemDir(context, DistroManager.activeId(context))
    /** WRITABLE dir where init-sandbox.sh is staged (scripts only need to be
     *  readable, and files/ is writable; the proot binaries exec out of the
     *  system native dir, NOT from here). */
    fun initScript(context: Context): File = File(sandboxDir(context), "init-sandbox.sh")
    /** App-private external dir on /sdcard (FUSE always allows the owning app,
     *  no storage permission required, visible in the file manager). */
    fun externalDir(context: Context): File =
        context.getExternalFilesDir(null) ?: File(context.filesDir, "external")

    /** PROJECT_DIR = 会话工作区真实路径，原样 bind 进沙箱（不再改名成 /workspace）。 */
    fun projectDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "workspaces/current")

    enum class SandboxStatus { READY, NEEDS_DOWNLOAD, REPAIR_NEEDED }

    /** Callbacks surface work during a (possibly slow) first-time setup. */
    interface Progress {
        fun onMessage(text: String)
        fun onProgress(percent: Int)   // 0..100
    }

    /** True once rootfs is present and its real shell is actually executable.
     *  Old builds used to extract files without the exec bit (and skipped
     *  symlinks), so we verify a real binary (usr/bin/dash, not the sh
     *  symlink) can execute — otherwise the sandbox is repaired. */
    fun isHealthy(context: Context): Boolean {
        val root = rootfsDir(context)
        // 逐项记录失败原因：2026-11-13 线上「补装完成但校验不过」时
        // 完全看不出是哪一条判不过，只能靠猜。现在每条都留痕。
        fun fail(reason: String): Boolean {
            android.util.Log.w("SandboxInstaller", "isHealthy=false: $reason | root=${root.absolutePath} " +
                "isSymlink=${DistroManager.isLinkPublic(root)} canonical=${runCatching { root.canonicalPath }.getOrNull()}")
            return false
        }
        if (File(root, ".installed-v2").isFile.not()) return fail("missing .installed-v2")
        if (File(nativeDir(context), "libproot-xed.so").isFile.not()) return fail("missing libproot-xed.so")
        // 包管理器按「当前生效系统」分流：Debian 系查 dpkg/apt-get，
        // Alpine 查 apk。以前无脑查 dpkg，一装 Alpine 就判不健康 → 无限重装。
        val active = DistroManager.activeId(context)
        val alpine = active.startsWith("alpine")
        if (!alpine) {
            if (File(root, "usr/bin/dpkg").isFile.not()) return fail("missing usr/bin/dpkg")
            if (File(root, "usr/bin/apt-get").isFile.not()) return fail("missing usr/bin/apt-get")
        } else {
            if (File(root, "sbin/apk").isFile.not() && File(root, "usr/bin/apk").isFile.not()) return fail("missing apk")
        }
        if (probeExecutable(File(root, "usr/bin/dash")) ||
            probeExecutable(File(root, "usr/bin/bash")) ||
            probeExecutable(File(root, "bin/busybox"))
        ) return true
        return fail("no executable shell (dash/bash/busybox)")
    }

    private fun probeExecutable(file: File): Boolean {
        if (!file.isFile) return false
        val r = runCatching { file.canExecute() }.getOrDefault(false)
        return r
    }

    /** Lightweight health scan used before deciding to show a boot gate. */
    fun status(context: Context): SandboxStatus {
        if (isHealthy(context)) return SandboxStatus.READY
        val root = rootfsDir(context)
        val native = nativeDir(context)
        if (File(native, "libproot-xed.so").isFile) {
            return if (root.isDirectory && root.list() != null && root.list()!!.isNotEmpty()) SandboxStatus.REPAIR_NEEDED else SandboxStatus.NEEDS_DOWNLOAD
        }
        return SandboxStatus.NEEDS_DOWNLOAD
    }

    @Synchronized
    fun ensureInstalled(context: Context, progress: Progress? = null) {
        progress?.onMessage("准备环境目录")
        val base = sandboxDir(context)
        base.mkdirs()
        DistroManager.systemDir(context, DistroManager.activeId(context)).mkdirs()
        // ⚠⚠ 脚本刷新必须放在 ensureRootfs **之前**，且无条件执行。
        //   ensureRootfs 里第一句就是 `if (isHealthy()) return`，
        //   沙箱好着的时候根本走不到刷新脚本那一步 —— 这就是
        //   2026-11-13 「改了 init-sandbox.sh 但用户跑的还是旧的」根因。
        copyAsset(context, "sandbox/init-sandbox.sh", initScript(context), true)
        ensureRootfs(context, base, progress)
        ensurePythonRuntime(context, progress)
    }

    /**
     * 「设置 → 下载环境」的**系统镜像补装入口**。
     *
     * 为什么需要：首次启动时 [ensureInstalled] 是在 BootActivity 里被
     * runCatching 包着调的 —— 断网/镜像站不可达就直接放行进主页。用户之后
     * 想补装，界面上却没有对应卡片，只能干瞪眼。这里把 [ensureRootfs]
     * 原样暴露出来，供卡片复用（不复制任何下载/解压逻辑）。
     */
    @Synchronized
    fun reinstallRootfs(context: Context, progress: Progress? = null) {
        val base = sandboxDir(context)
        base.mkdirs()
        DistroManager.systemDir(context, DistroManager.activeId(context)).mkdirs()
        copyAsset(context, "sandbox/init-sandbox.sh", initScript(context), true)
        ensureRootfs(context, base, progress)
    }

    /**
     * 「设置 → 下载环境」的 **Python 运行时补装入口**。
     *
     * 同上的理由：断网时 Python 会被静默跳过，用户没有补装入口。
     * 这里原样复用 [ensurePythonRuntime]（直链 python-build-standalone →
     * 失败回退 apt TUNA 源两条通道都保留）。
     */
    @Synchronized
    fun reinstallPython(context: Context, progress: Progress? = null) {
        sandboxDir(context).mkdirs()
        DistroManager.systemDir(context, DistroManager.activeId(context)).mkdirs()
        copyAsset(context, "sandbox/init-sandbox.sh", initScript(context), true)
        ensurePythonRuntime(context, progress)
    }

    /**
     * 「下载环境 → Python 3.16 尝鲜版」的安装入口（与 3.12 **完全独立**）。
     *
     * 为什么必须单独一条路，而不是复用 [ensurePythonRuntime]：
     *  • 我们这个自建包顶层是 `./bin ./lib ./include`，没有 astral 的 `python/` 前缀，
     *    复用旧的 target 布局会解到 `usr/local/python3/python/...` 找不到解释器；
     *  • 装到 `usr/local/python316`，只注册 `/usr/bin/python3.16`，
     *    **绝不覆盖** 默认 `python3`（3.12 仍是 AI 终端默认解释器）；
     *  • 解压复用 [extractPythonBundle]（同一套 PAX / symlink 兜底逻辑）。
     */
    @Synchronized
    fun reinstallPython316(context: Context, progress: Progress? = null) =
        installPython316Bundle(context, progress, PYTHON316_BUNDLE, PYTHON316_DIR, "python3.16")

    /** Python 3.16 精简版（本地 strip 过的 11MB 包，同样挂在 GitHub Release 上分发）。 */
    @Synchronized
    fun reinstallPython316Slim(context: Context, progress: Progress? = null) =
        installPython316Bundle(context, progress, PYTHON316_SLIM_BUNDLE, PYTHON316_SLIM_DIR, "python3.16slim")

    /**
     * 3.16 系列包的统一安装路径（两个包共用，只有 URL / 目录 / 命令名不同）。
     *
     * 要点：
     *  • 自建包顶层是 `./bin ./lib ./include`（无 astral 的 `python/` 前缀），
     *    直接解到独立目录，不套用 3.12 的 `python/` 布局；
     *  • 各自独立前缀，**均不覆盖** 默认 `python3`；
     *  • 解压复用 [extractPythonBundle]（PAX / symlink 兜底）。
     */
    private fun installPython316Bundle(
        context: Context,
        progress: Progress?,
        url: String,
        dirName: String,
        commandName: String,
    ) {
        sandboxDir(context).mkdirs()
        DistroManager.systemDir(context, DistroManager.activeId(context)).mkdirs()
        copyAsset(context, "sandbox/init-sandbox.sh", initScript(context), true)

        val root = rootfsDir(context)
        val dest = File(root, dirName)
        dest.mkdirs()

        progress?.onMessage("下载 $commandName 运行时")
        val archive = File(sandboxDir(context), "$commandName-bundle.tar.gz")
        downloadFile(url, archive, progress)

        progress?.onMessage("解压 $commandName 到 $dirName")
        extractPythonBundle(archive, dest, progress)
        runCatching { archive.delete() }

        val bin = listOf("python3.16", "python3", "python")
            .map { File(dest, "bin/$it") }
            .firstOrNull { it.isFile && it.length() > 0 }
            ?: throw IllegalStateException("解压后未找到 $dirName/bin/python3（包结构异常）")
        bin.setExecutable(true, false)

        // 只挂自己的命令名，默认 python3 保持不变
        val link = File(root, "usr/bin/$commandName")
        link.parentFile?.mkdirs()
        val linked = runCatching {
            if (link.exists()) link.delete()
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                java.nio.file.Paths.get("/$dirName/bin/${bin.name}"),
            )
            true
        }.getOrDefault(false)
        if (!linked) {
            link.writeText("#!/bin/sh\nexec /$dirName/bin/${bin.name} \"${'$'}@\"\n")
            link.setExecutable(true, false)
        }

        // ---- 软链补齐（软链优先，失败退化成启动脚本）----
        // 包内 bin/python3、bin/python 必须指向真实解释器；沙箱 FS 不支持软链时也能用。
        linkOrScript(File(dest, "bin/python3"), bin.name)
        linkOrScript(File(dest, "bin/python"), bin.name)

        // pip 入口：包里带 pip3 就直接链，没带就退化成 `python -m pip`
        val pipSuffix = commandName.removePrefix("python")   // 3.16 / 3.16slim
        val pipInPkg = File(dest, "bin/pip3")
        if (pipInPkg.isFile) {
            linkOrScript(File(root, "usr/bin/pip$pipSuffix"), "/$dirName/bin/pip3")
        } else {
            linkOrScript(
                File(root, "usr/bin/pip$pipSuffix"),
                "",
                "#!/bin/sh\nexec /$dirName/bin/${bin.name} -m pip \"${'$'}@\"\n",
            )
        }

        File(dest, ".installed").writeText("$commandName (aarch64, custom cross build)")
        progress?.onMessage("$commandName 就绪：/usr/bin/$commandName（pip：/usr/bin/pip$pipSuffix）")
    }

    /**
     * 创建软链；失败（沙箱 FS 不允许 symlink / 目标不可写）则写一个启动脚本兜底。
     * @param target 软链目标（相对名或绝对路径）
     * @param fallbackScript 自定义兜底脚本；为 null 时用同目录同名执行
     */
    private fun linkOrScript(link: File, target: String, fallbackScript: String? = null) {
        // 自指保护：包里解释器本身就叫 python3 时，绝不能删掉自己再建自指软链
        if (link.name == target) return
        link.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val linked = runCatching {
            if (link.exists()) link.delete()
            java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
            true
        }.getOrDefault(false)
        if (linked) return
        val body = fallbackScript
            ?: "#!/bin/sh\nexec \"${'$'}(dirname \"${'$'}0\")/$target\" \"${'$'}@\"\n"
        runCatching {
            link.writeText(body)
            link.setExecutable(true, false)
        }
    }

    /** Debian trixie apt sources pointed at TUNA over plain HTTP. slim rootfs
     *  has no ca-certificates, so https mirrors would fail; http works and is
     *  still ~10x faster than deb.debian.org direct on mainland networks
     *  (实测官方源直连 ~100KB/s). */
    private const val DEBIAN_CN_SOURCES = """
Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/debian
Suites: stable stable-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

Types: deb
URIs: http://mirrors.tuna.tsinghua.edu.cn/debian-security
Suites: stable-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp
"""

    /** Bootstrap python3 into the SHARED rootfs on first boot. Channel 1 (fast):
     *  prebuilt python-build-standalone tarball via gh-proxy, extracted to
     *  usr/local/python3 (its bin is on PATH via init-sandbox.sh). Channel 2
     *  (fallback): apt from TUNA Debian mirror. Skipped if python3 exists. */
    private fun ensurePythonRuntime(context: Context, progress: Progress?) {
        val root = rootfsDir(context)
        if (File(root, "usr/bin/python3").isFile) return
        val target = File(root, "usr/local/python3")
        if (File(target, "python/bin/python3").isFile) {
            // Earlier direct-channel install: link bin into place and done.
            linkPythonIntoUsrBin(root, target)
            return
        }
        val channelErrors = mutableListOf<String>()
        progress?.onMessage("下载 Python 运行时（约 29MB）")
        // Channel 1: prebuilt tarball with download progress.
        runCatching {
            val archive = File(sandboxDir(context), "python-bundle.tar.gz")
            downloadFile(PYTHON_BUNDLE, archive, progress)
            progress?.onMessage("正在解压 Python 运行时")
            target.mkdirs()
            extractPythonBundle(archive, target, progress)
            archive.delete()
            // The tarball's top-level dir is `python/` -> target/python/bin/python3.
            val realBin = File(target, "python/bin/python3")
            if (!realBin.isFile) throw IllegalStateException("解压布局异常：未找到 python/bin/python3")
            realBin.setExecutable(true, false)
            linkPythonIntoUsrBin(root, target)
            File(target, ".installed").writeText("python3 direct install")
            progress?.onMessage("Python 运行时就绪")
            return
        }.onFailure { error ->
            channelErrors += "直链: ${error.message}"
            progress?.onMessage("直链安装失败，改用 apt…")
        }
        // Channel 2: apt fallback over TUNA http mirror.
        runCatching {
            val sources = File(root, "etc/apt/sources.list.d/debian.sources")
            if (sources.isFile) sources.writeText(DEBIAN_CN_SOURCES.trim() + "\n")
            val (exit, tail) = runInSandbox(
                context,
                "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y --no-install-recommends python3"
            )
            if (exit != 0 || File(root, "usr/bin/python3").isFile.not()) {
                throw IllegalStateException("apt exit=$exit: ${tail.takeLast(300).replace(Regex("\u001B\\[[0-9;]*m"), "").trim()}")
            }
            progress?.onMessage("Python 运行时就绪（apt）")
        }.onFailure { error ->
            channelErrors += "apt: ${error.message}"
            progress?.onMessage("Python 安装失败。${channelErrors.joinToString("；")}（可稍后在沙箱内手动重试）")
        }
    }

    /** Expose the direct-channel python on the standard PATH for every
     *  sandbox session (usr/bin symlinks into the bundle layout). */
    private fun linkPythonIntoUsrBin(root: File, target: File) {
        runCatching {
            val bin = File(root, "usr/bin")
            bin.mkdirs()
            listOf("python3", "python3.12", "pip3").forEach { name ->
                val link = File(bin, name)
                if (!link.exists()) {
                    java.nio.file.Files.createSymbolicLink(
                        link.toPath(),
                        java.nio.file.Path.of("/usr/local/python3/python/bin/$name")
                    )
                }
            }
        }
    }

    /** Run a command inside the proot sandbox using the exact same launch
     *  layout as SandboxRunner (via init-sandbox.sh). Returns exit code and
     *  the tail of combined output (for error reporting). */
    private fun runInSandbox(context: Context, command: String): Pair<Int, String> {
        val process = ProcessBuilder(
            listOf(
                "/system/bin/sh",
                initScript(context).absolutePath,
                sandboxDir(context).absolutePath,
                rootfsDir(context).absolutePath,
                nativeDir(context).absolutePath,
                projectDir(context).absolutePath,
                "sh", "-c", command
            )
        ).directory(sandboxDir(context)).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            try { process.inputStream.bufferedReader().useLines { it.forEach { line -> output.appendLine(line) } } }
            catch (_: Exception) { }
        }
        reader.start()
        val exit = process.waitFor()
        reader.join(1_000)
        return exit to output.toString()
    }

    private fun ensureRootfs(context: Context, base: File, progress: Progress?) {
        val root = rootfsDir(context)
        if (isHealthy(context)) { progress?.onProgress(100); progress?.onMessage("沙箱已就绪"); return }

        // ⚠⚠ 2026-11-13 线上事故根因（主人指出的）：启动时的自动下载
        // **只认 Debian 这一个包**，但解压目标用的是 activeDir()。
        // 用户一旦在「系统管理」切到 Alpine，这里就把 Debian 的内容
        // 灌进 systems/alpine-3.19/，还打上 id=debian-slim 的标记
        // → isHealthy 按 Alpine 查 apk 永远不过
        // → 每次启动都重下 35MB，系统永远修不好，表现为「终端怎么也装不好」。
        //
        // 新规则：自动下载**只能装 debian-slim**，绝不擅自往别的系统里灌 Debian。
        val active = DistroManager.activeId(context)
        if (active != DistroManager.SYSTEM_DEBIAN) {
            val prefs = context.getSharedPreferences(DistroManager.PREF, Context.MODE_PRIVATE)
            if (DistroManager.isInstalled(context, DistroManager.SYSTEM_DEBIAN)) {
                // Debian 已装好 → 回退到它，不下载
                progress?.onMessage("当前系统未就绪，回退到 Debian")
                runCatching { DistroManager.setActive(context, DistroManager.SYSTEM_DEBIAN) }
                if (isHealthy(context)) {
                    progress?.onProgress(100)
                    progress?.onMessage("沙箱已就绪（Debian）")
                    return
                }
            } else {
                // Debian 也没装 → 把生效系统改回 debian-slim，再走下面的正常下载
                progress?.onMessage("当前系统未安装，改为安装默认系统（Debian）")
                prefs.edit().putString("active_system_id", DistroManager.SYSTEM_DEBIAN).apply()
            }
        }

        progress?.onMessage("沙箱缺失或损坏，正在安装系统镜像")
        // 装到 debian-slim。上面的闸已经保证 active == debian-slim，
        // 但这里**仍然写死**：自动下载只可能装 Debian，写死比依赖状态更安全。
        val dest = DistroManager.systemDir(context, DistroManager.SYSTEM_DEBIAN)

        // ⚠ 安全闸（2026-11-13 线上事故后加）：绝不能无条件 deleteRecursively()。
        //   以前 isHealthy 一旦误判（比如软链没建好、判据路径写错），这里就把
        //   已装好的 35MB rootfs 连同一整套工具链 / Python 全部删掉重下，
        //   表现为「重启就要重新下载」「下载环境全部失效」。
        //   现在的规则：目录里已经有实质内容（bin/ + 若干文件）时，
        //   **先备份再重装**，并且只有真的下不动才恢复备份。
        val salvage = File(base, "salvage-${System.currentTimeMillis()}")
        val hasContent = dest.isDirectory &&
            (File(dest, "bin/sh").isFile || File(dest, "bin/busybox").isFile)
        if (hasContent) {
            progress?.onMessage("检测到已有系统内容，先备份再重装")
            runCatching { if (salvage.exists()) salvage.deleteRecursively() }
            val moved = runCatching { dest.renameTo(salvage) }.getOrDefault(false)
            if (!moved) {
                // rename 失败（跨卷/占用）→ 拷贝一份当保险，原件留着
                runCatching { dest.copyRecursively(salvage, overwrite = true) }
            }
        } else {
            runCatching { if (dest.exists()) dest.deleteRecursively() }
        }
        dest.mkdirs()
        val archive = File(base, "debian-rootfs-arm64.tar.gz")
        archive.delete()
        try {
            downloadWithFallback(context, archive, progress)
            progress?.onMessage("正在解压系统镜像")
            progress?.onProgress(8)
            extractTarGz(archive, dest, progress)
            archive.delete()
        } catch (e: Exception) {
            // 重装失败 → 把备份搬回来，绝不让用户落到「什么都没装」的状态
            if (salvage.isDirectory) {
                progress?.onMessage("重装失败，恢复原有系统（${e.message}）")
                runCatching { dest.deleteRecursively() }
                runCatching { salvage.renameTo(dest) }
                    .onFailure { runCatching { salvage.copyRecursively(dest, overwrite = true) } }
            }
            throw e
        }
        // 装成功了，备份没用了
        runCatching { if (salvage.exists()) salvage.deleteRecursively() }
        File(dest, ".installed-v2").writeText("rootfs installed v2")
        File(dest, ".distro-installed").writeText("id=${DistroManager.SYSTEM_DEBIAN}\nvia=SandboxInstaller\n")
        progress?.onProgress(100)
        progress?.onMessage("环境就绪")
    }

    private fun downloadWithFallback(context: Context, target: File, progress: Progress?) {
        var lastError: Exception? = null
        var index = 0
        val sources = sourcesFor(context)
        for (source in sources) {
            index++
            try {
                if (source.contains("feijipan.com/s/")) {
                    progress?.onMessage("正在从小飞机网盘解析系统镜像")
                    val resolved = org.json.JSONObject(FeijipanParser.resolve(source))
                    val directUrl = resolved.getString("url")
                    progress?.onMessage("正在从小飞机网盘下载 Debian 系统镜像")
                    downloadFile(directUrl, target, progress, referer = "https://www.feijipan.com/")
                } else {
                    progress?.onMessage(if (index == 1 && sources.size == 1) "正在下载 Debian 12 (arm64) 系统镜像" else "正在从 GitHub 加速源下载")
                    downloadFile(source, target, progress)
                }
                if (target.isFile && target.length() > 10_000_000) return
                lastError = IllegalStateException("unexpected small download")
                target.delete()
            } catch (error: Exception) {
                lastError = error
                target.delete()
            }
        }
        throw IllegalStateException("failed to download Debian rootfs: ${lastError?.message}")
    }

    private fun downloadFile(urlString: String, target: File, progress: Progress?, referer: String? = null) {
        target.parentFile?.mkdirs()
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
            referer?.let { setRequestProperty("Referer", it) }
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code for $urlString")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readCount: Int
                    var transferred = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { readCount = it } != -1) {
                        output.write(buffer, 0, readCount)
                        transferred += readCount
                        if (total > 0) {
                            val pct = (8 + (transferred * 90 / total)).toInt().coerceIn(8, 98)
                            if (pct != lastPct) { lastPct = pct; progress?.onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 脚本版本号。**每次改动 assets/sandbox/init-sandbox.sh 都必须 +1**，
     * 否则老用户机器上的旧脚本永远不会被刷新（见 copyAsset 的说明）。
     *
     * 版本记录：
     *   1 = 初版
     *   2 = Alpine busybox 修复（SHELL_APP）
     *   3 = 主机名 agent-workspace + ASCII banner + Alpine repositories/os-release
     *   4 = 修复 mktemp 落到 /data/local 导致 Permission denied（TMPDIR 全套指向 PREFIX/tmp）
     *       + 系统名改为从 rootfs 读 os-release（之前读宿主的，永远显示 Linux）
     */
    private const val INIT_SCRIPT_VERSION = 7   // 7: 去掉 /workspace 虚拟挂载

    /** 强制刷新 init-sandbox.sh 到 files/ 下。
     *
     * 由 [com.agentpad.workspace.BootActivity] 在启动时无条件调用 —— 不能只在
     * ensureInstalled 里做，因为沙箱健康时那条路根本不会被执行到。 */
    fun refreshInitScript(context: Context) {
        copyAsset(context, "sandbox/init-sandbox.sh", initScript(context), true)
    }

    /**
     * 把 asset 里的脚本放到 files/ 下，必要时**强制刷新**。
     *
     * ⚠⚠ 2026-11-13 血的教训：原来的判断是
     *     `if (target.exists() && target.length() > 0) return`
     *   意思是「文件在就永不更新」。而 isHealthy() 为 true 时 ensureRootfs
     *   会提前 return，连这个函数都不会被调到 —— 结果我改了
     *   init-sandbox.sh 修 Alpine 的 busybox bug，主人装上新包后
     *   跑的还是旧脚本，`-c: applet not found` 照崩。
     *
     *   现在改成按**版本号 + 内容**判断：
     *     • 旁边记一个 `<target>.ver`，内容是本脚本的版本号；
     *     • 版本号不符 → 无条件覆盖（哪怕文件存在）；
     *     • 版本号相同但 asset 内容变了（比如热修复没来得及改版本号）
     *       → 也覆盖，靠内容比对兜底。
     */
    private fun copyAsset(context: Context, assetPath: String, target: File, executable: Boolean) {
        target.parentFile?.mkdirs()
        val verFile = File(target.parentFile, target.name + ".ver")

        val needCopy = runCatching {
            if (!target.isFile || target.length() == 0L) return@runCatching true
            val recorded = if (verFile.isFile) verFile.readText().trim().toIntOrNull() else null
            if (recorded != INIT_SCRIPT_VERSION) return@runCatching true
            // 版本号一致，再比一次内容，防止「改了脚本忘改版本号」
            val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
            val diskBytes = target.readBytes()
            !assetBytes.contentEquals(diskBytes)
        }.getOrDefault(true)

        if (!needCopy) return

        // 先写临时文件再原子替换：避免写一半时正好被别的进程 exec
        val tmp = File(target.parentFile, target.name + ".tmp")
        context.assets.open(assetPath).use { input -> FileOutputStream(tmp).use { output -> input.copyTo(output) } }
        tmp.setReadable(true, false)
        if (executable) {
            tmp.setExecutable(true, false)
            // Hard chmod fallback: Java setExecutable sometimes does not persist
            // as a real exec bit on all filesystems. Force 0755 via the system
            // shell so ProcessBuilder can exec it (and SELinux sees exec).
            tmp.setWritable(true, false)
            try {
                val sh = ProcessBuilder("/system/bin/sh", "-c", "chmod 755 '${tmp.absolutePath}'").redirectErrorStream(true).start()
                sh.waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // ignore; Java mode bits above usually suffice on app private dirs
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // renameTo 失败就退回直接拷
            context.assets.open(assetPath).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            tmp.delete()
            if (executable) {
                target.setExecutable(true, false)
                target.setWritable(true, false)
            }
        }
        target.setReadable(true, false)
        verFile.writeText(INIT_SCRIPT_VERSION.toString())
    }

    private fun extractTarGz(archive: File, destination: File, progress: Progress?) {
        val totalSize = archive.length()
        var written = 0L
        var lastPct = -1
        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { input ->
            val header = ByteArray(512)
            while (true) {
                readFully(input, header) ?: break
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).decodeToString().trimEnd('\u0000')
                val prefix = header.copyOfRange(345, 500).decodeToString().trimEnd('\u0000')
                val relative = if (prefix.isBlank()) name else "$prefix/$name"
                val size = parseOctal(header, 124, 12)
                val mode = parseOctal(header, 100, 8) // tar file mode, e.g. 0o100755
                val type = header[156].toInt().toChar()
                val linkName = header.copyOfRange(157, 257).decodeToString().trimEnd('\u0000')
                // normalize() (string-level) instead of canonicalFile(): the
                // canonical form resolves symlinks via the FS and throws
                // "Invalid file path" on some devices for the deep
                // rootfs/usr/local/python3/... paths. String normalization is
                // enough for the traversal check below.
                val target = File(destination, relative).normalize()
                require(target.path == destination.path || target.path.startsWith(destination.path + File.separator)) { "invalid archive path" }
                when (type) {
                    '5' -> target.mkdirs()
                    '2' -> { // symbolic link (Debian ships /bin/sh, /usr/bin/sh etc.)
                        if (!target.parentFile?.exists()!!) target.parentFile?.mkdirs()
                        try { if (target.exists()) target.delete(); java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName)) } catch (_: Exception) { /* some devices disallow symlinks */ }
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val count = copyExactly(input, output, size)
                            written += count
                        }
                        target.setReadable(true, false)
                        // user execute bit (0o100 octal) == 0x40
                        if ((mode and 0x40L) != 0L) target.setExecutable(true, false)
                    }
                    else -> skipExactly(input, size)
                }
                val padding = (512 - (size % 512)) % 512
                skipExactly(input, padding)
                if (totalSize > 0) {
                    val pct = (98 + (written * 2 / totalSize)).toInt().coerceIn(98, 100)
                    if (pct != lastPct) { lastPct = pct; progress?.onProgress(pct) }
                }
            }
        }
    }

    /** Extract the python-build-standalone bundle into the rootfs. Unlike
     *  extractTarGz this is tuned for the bundle: skips terminfo/man (thousands
     *  of symlink entries, runtime-irrelevant), falls back to a hard copy when
     *  the FS rejects symlinks, and REPORTS write failures with the exact path
     *  instead of swallowing them — silent half-extracts surfaced later as
     *  "Invalid file path". */
    private fun extractPythonBundle(archive: File, destination: File, progress: Progress?) {
        val totalSize = archive.length()
        var written = 0L
        var lastPct = -1
        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { input ->
            val header = ByteArray(512)
            var entryIndex = 0
            var extendedName: String? = null
            var extendedSize: Long? = null
            var extendedLink: String? = null
            while (true) {
                readFully(input, header) ?: break
                if (header.all { it == 0.toByte() }) break
                val headerType = header[156].toInt().toChar()
                // PAX/GNU header handling: python-build-standalone tarballs are PAX
                // format — type 'x' blocks carry the REAL name/size for the next
                // entry. Without this, extension data gets written as garbage
                // files and the whole stream desyncs ("Invalid file path" etc.).
                // Supported keys: path, size, linkpath. Everything else ignored.
                if (headerType == 'x' || headerType == 'g' || headerType == 'L' || headerType == 'K') {
                    val extSize = parseOctal(header, 124, 12)
                    val rawData = ByteArray(extSize.toInt())
                    readFully(input, rawData)
                    skipExactly(input, (512 - (extSize % 512)) % 512)
                    if (headerType == 'L' || headerType == 'K') {
                        extendedName = String(rawData).trimEnd('\u0000')
                    } else if (headerType == 'x') {
                        // Robustness: split records on BOTH '\n' (spec) and
                        // '\0' (some writers pad lines with NULs instead).
                        String(rawData).split("\n", "\u0000").forEach { segment ->
                            val line = segment.trim('\u0000')
                            if (line.isBlank()) return@forEach
                            val sp = line.indexOf(' ')
                            if (sp > 0) {
                                val key = line.substring(0, sp)
                                val value = line.substring(sp + 1).replace("\u0000", "").trim()
                                when (key) {
                                    "path" -> extendedName = value
                                    "size" -> extendedSize = value.toLongOrNull()
                                    "linkpath" -> extendedLink = value
                                }
                            }
                        }
                    }
                    continue
                }
                // Tar name fields are NUL-terminated; some archives in the wild have
                // garbage AFTER the terminator. Cut at the FIRST NUL — trimEnd
                // alone can't remove mid-field junk (field-verified: the python
                // bundle's first entry carries "2to3\0in/2to3" in its name).
                fun tarString(bytes: ByteArray, start: Int, len: Int): String {
                    var end = start
                    val stop = start + len
                    while (end < stop && bytes[end] != 0.toByte()) end++
                    return bytes.copyOfRange(start, end).decodeToString()
                }
                val name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                val relative = extendedName ?: (if (prefix.isBlank()) name else "$prefix/$name")
                val size = extendedSize ?: parseOctal(header, 124, 12)
                val mode = parseOctal(header, 100, 8)
                val type = headerType
                val linkName = extendedLink ?: tarString(header, 157, 100)
                extendedName = null; extendedSize = null; extendedLink = null
                // Skip bulky runtime-irrelevant trees (symlink storm + docs).
                if (relative.startsWith("python/share/terminfo") ||
                    relative.startsWith("python/share/man")) {
                    if (type != '5') {
                        skipExactly(input, size)
                        skipExactly(input, (512 - (size % 512)) % 512)
                    }
                    continue
                }
                val target = File(destination, relative).normalize()
                require(target.path == destination.path || target.path.startsWith(destination.path + File.separator)) { "invalid archive path" }
                when (type) {
                    '5' -> if (!target.isDirectory) target.mkdirs()
                    '2' -> {
                        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                        try { if (target.exists()) target.delete() } catch (_: Exception) {}
                        try {
                            java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                        } catch (e: Exception) {
                            // FS may reject symlinks; a hard copy keeps python working.
                            val copied = runCatching {
                                val linkTarget = File(target.parentFile, linkName)
                                if (linkTarget.isFile) { linkTarget.copyTo(target, overwrite = true); true } else false
                            }.getOrDefault(false)
                            if (!copied) throw IllegalStateException("symlink $relative -> $linkName: ${e.message}")
                        }
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.let { parent ->
                            if (!parent.isDirectory && !parent.mkdirs()) throw IllegalStateException("mkdir failed: ${parent.path}")
                        }
                        try {
                            FileOutputStream(target).use { output -> copyExactly(input, output, size) }
                        } catch (e: Exception) {
                            throw IllegalStateException("write $relative: ${e.message}", e)
                        }
                        written += size
                        target.setReadable(true, false)
                        if ((mode and 0x40L) != 0L) target.setExecutable(true, false)
                    }
                    else -> skipExactly(input, size)
                }
                val padding = (512 - (size % 512)) % 512
                skipExactly(input, padding)
                entryIndex++
                if (totalSize > 0 && entryIndex % 200 == 0) {
                    val pct = (98 + (written * 2 / totalSize)).toInt().coerceIn(98, 100)
                    if (pct != lastPct) { lastPct = pct; progress?.onProgress(pct) }
                }
            }
        }
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long =
        bytes.copyOfRange(offset, offset + length).decodeToString().trim('\u0000', ' ').ifBlank { "0" }.trim().toLong(8)

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): ByteArray? {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return if (offset == 0) null else throw IllegalStateException("truncated tar")
            offset += count
        }
        return buffer
    }

    private fun copyExactly(input: java.io.InputStream, output: FileOutputStream, size: Long): Long {
        var left = size
        val buffer = ByteArray(8192)
        var written = 0L
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (count < 0) throw IllegalStateException("truncated tar entry")
            output.write(buffer, 0, count)
            left -= count
            written += count
        }
        return written
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) { var left = size; while (left > 0) { val skipped = input.skip(left); if (skipped <= 0) { if (input.read() < 0) throw IllegalStateException("truncated tar"); left-- } else left -= skipped } }
}