package com.agentpad.workspace

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.FeijipanParser
import com.agentpad.workspace.ui.*
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * 下载环境（Compose 重构版）：每张卡片 = 一个可下载部署到沙箱的资源。
 * 下载/解压逻辑与原实现完全一致，仅 UI 改为统一设计系统。
 */
class DownloadEnvironmentActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    /** 每张卡片的运行时状态 */
    private class ItemState {
        var progress by mutableStateOf(-1)      // -1 未开始，0..100 进行中，100 完成
        var running by mutableStateOf(false)
        var status by mutableStateOf("")
        var installed by mutableStateOf(false)
        var failed by mutableStateOf(false)
    }

    private fun rootfsDir(): File =
        com.agentpad.workspace.ai.SandboxInstaller.rootfsDir(this)

    private inner class Resource(
        val id: String,
        val title: String,
        val desc: String,
        val shareUrl: String = "",
        /** 直链模式：shareUrl 就是可下载的 http(s) 地址，跳过网盘解析 */
        val direct: Boolean = false,
        /** 直链模式下的落盘文件名 */
        val archiveName: String = "",
        /**
         * 特殊补装标记：不走「解析链接 → 下载归档 → install(archive)」通用流程，
         * 直接调用 [com.agentpad.workspace.ai.SandboxInstaller] 里现成的补装入口。
         * 用于「系统镜像」「Python 运行时」——它们的下载/解压逻辑本来就在
         * SandboxInstaller 里（含多源回退、分块解压），不该在这里重写一份。
         */
        val special: String? = null,
        val isInstalled: () -> Boolean,
        /**
         * 安装逻辑。
         *
         * 2026-11-18 加 [report]：380MB 的 Gradle 工具链包解压要几十秒，
         * 期间卡片必须能显示「解压中 x/y」。
         *
         * 为什么不在 lambda 里直接调 setStatus：lambda 是在 [resources] 里
         * 创建的，而 setStatus 是 [runPipeline] 的局部函数 —— 两者作用域无关，
         * 直接引用会编译不过（历史教训：Unresolved reference 'setStatus'）。
         * 所以由 runPipeline 把回调传进来。
         */
        val install: ((File, (String) -> Unit) -> Unit)? = null,
    )

    private lateinit var resourceList: List<Resource>
    private lateinit var stateMap: MutableMap<String, ItemState>
    private val tick = mutableStateOf(0)

    private fun resources(): List<Resource> {
        // 卡片目录（title/desc/是否已安装）统一来自 EnvCatalog —— 与 AI 的
        // list_env_cards 工具同源，保证 AI 看到的清单和这里永远一致。
        val act = this
        val env = com.agentpad.workspace.ai.EnvCatalog.cards(this).associateBy { it.id }
        fun t(id: String) = env[id]?.title ?: id
        fun d(id: String) = env[id]?.desc ?: ""
        fun inst(id: String): () -> Boolean = {
            runCatching { env[id]?.installed?.invoke(act) == true }.getOrDefault(false)
        }
        return listOf(
        // ---- 基础设施补装（首次启动本来就会自动装，这里只给"当时跳过了"的人补）----
        Resource(
            id = "rootfs",
            title = t("rootfs"),
            desc = d("rootfs"),
            special = "rootfs",
            isInstalled = inst("rootfs")
        ),
        Resource(
            id = "python3",
            title = t("python3"),
            desc = d("python3"),
            special = "python3",
            isInstalled = inst("python3")
        ),
        Resource(
            id = "python316",
            title = t("python316"),
            desc = d("python316"),
            special = "python316",
            isInstalled = inst("python316")
        ),
        Resource(
            id = "python316slim",
            title = t("python316slim"),
            desc = d("python316slim"),
            special = "python316slim",
            isInstalled = inst("python316slim")
        ),
        Resource(
            id = "jre17",
            title = t("jre17"),
            desc = d("jre17"),
            shareUrl = "https://example.com",
            isInstalled = inst("jre17")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")
            val old = File(rootfs, "usr/local/jre17")
            if (old.exists()) deleteRecursive(old)
            extractTarGz(archive, File(rootfs, "usr/local"))
            val extracted = File(rootfs, "usr/local").listFiles { f -> f.isDirectory && f.name.startsWith("jdk-17") }
            if (extracted.isNullOrEmpty()) error("解压布局异常：未找到 jdk-17* 目录")
            if (extracted.size > 1) error("解压布局异常：发现多个 jdk-17* 目录")
            val src = extracted[0]
            val dst = File(rootfs, "usr/local/jre17")
            if (src.absolutePath != dst.absolutePath) { if (!src.renameTo(dst)) error("重命名 jdk-17* → jre17 失败") }
            val bin = File(dst, "bin")
            if (!File(bin, "java").isFile) error("解压布局异常：未找到 bin/java")
            val pathBin = File(rootfs, "usr/local/bin"); pathBin.mkdirs()
            listOf("java", "keytool").forEach { tool ->
                val link = File(pathBin, tool); link.delete()
                android.system.Os.symlink(File(bin, tool).absolutePath, link.absolutePath)
            }
        },
        Resource(
            id = "toolchain",
            title = t("toolchain"),
            desc = d("toolchain"),
            shareUrl = "https://example.com",
            isInstalled = inst("toolchain")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")
            val old = File(rootfs, "opt/toolchain-pack")
            if (old.exists()) deleteRecursive(old)
            extractTarGz(archive, File(rootfs, "opt"))
            val aapt2 = File(rootfs, "opt/toolchain-pack/jniLibs/libaapt2.so")
            if (aapt2.isFile) aapt2.setExecutable(true, false)
            val bin = File(rootfs, "usr/local/bin"); bin.mkdirs()
            val link = File(bin, "aapt2"); link.delete()
            android.system.Os.symlink(aapt2.absolutePath, link.absolutePath)
        },
        Resource(
            id = "toolchain_patch_v3",
            title = t("toolchain_patch_v3"),
            desc = d("toolchain_patch_v3"),
            shareUrl = "https://example.com",
            isInstalled = inst("toolchain_patch_v3")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "opt/toolchain-pack/compiler").isDirectory) error("请先安装「编译工具链」，再部署此补丁")
            extractTarGz(archive, File(rootfs, "opt"))
            val aapt2 = File(rootfs, "opt/toolchain-pack/jniLibs/libaapt2.so")
            if (aapt2.isFile) aapt2.setExecutable(true, false)
            val bin = File(rootfs, "usr/local/bin"); bin.mkdirs()
            val link = File(bin, "aapt2"); link.delete()
            android.system.Os.symlink(aapt2.absolutePath, link.absolutePath)
        },
        Resource(
            id = "go_env",
            title = t("go_env"),
            desc = d("go_env"),
            shareUrl = "https://example.com",
            isInstalled = inst("go_env")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")
            val tmp = File(rootfs, "tmp"); if (!tmp.isDirectory) tmp.mkdirs()
            val target = File(tmp, "go-env.tar.gz")
            if (target.exists()) target.delete()
            archive.copyTo(target, overwrite = true)
        },
        // ⚠ 2026-11-05 Node.js 卡片已移除（与 EnvCatalog 同步）。
        //   原因：这里只是把 tar.gz 丢进 /tmp，不解压不部署，
        //   用户以为装了其实 `node` 不存在，导致 Codex 启动莫名失败。
        //   Node 运行时改由「部署服务 → Codex」自己探测/解压管理。
        // ⚠ 2026-11-17 已删除 kotlin_compose 卡片（Kotlin + Compose 构建器）。
        //
        // 原因见 EnvCatalog.kt 同位置注释：那套手工 7 步流水线编不了真实工程。
        // 替代方案就是下面这两张 Gradle 卡片。
        //
        // ================================================================
        // Gradle 工具链 · 包 1（JDK + Gradle + SDK 原生件，能编纯 Java）
        // ================================================================
        // 与其它卡片的差别：这个包必须**先校验 SHA-256 再解压**。
        //   它是 380MB 异构二进制集合（JDK 的 .so + SDK 的 ELF），
        //   下载一旦被截断，解压出来是半个 JDK，报错会非常难查
        //   （java: not found / libjvm.so 损坏），而用户压缩包已删、没法重验。
        Resource(
            id = "gradle_toolchain",
            title = t("gradle_toolchain"),
            desc = d("gradle_toolchain"),
            shareUrl = com.agentpad.workspace.ai.ToolchainRelease.PKG1_URL,
            direct = true,
            archiveName = com.agentpad.workspace.ai.ToolchainRelease.PKG1_FILE,
            isInstalled = inst("gradle_toolchain")
        ) { archive, report ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")

            val want = com.agentpad.workspace.ai.ToolchainRelease.PKG1_SHA256
            report("正在校验完整性（${com.agentpad.workspace.ai.ToolchainRelease.PKG1_SIZE_TEXT}，约 1~2 秒）…")
            val got = sha256Of(archive)
            if (!got.equals(want, ignoreCase = true)) {
                error("包 1 校验失败（可能下载被截断）：\n实际 $got\n期望 $want")
            }
            report("校验通过，正在解压…")

            val opt = File(rootfs, "opt"); if (!opt.isDirectory) opt.mkdirs()
            val dest = File(opt, com.agentpad.workspace.ai.ToolchainRelease.PKG1_DIR)
            if (dest.exists()) deleteRecursive(dest)
            // 380MB 解压要几十秒，必须把进度回传到卡片上，否则用户以为卡死
            extractTarGz(archive, opt) { done, total ->
                if (total > 0) {
                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                    report("解压中 ${formatSize(done)} / ${formatSize(total)}（$pct%）")
                }
            }

            // tar 解出来没有可执行位，必须补回去，否则 java/aapt2 都是 Permission denied。
            val jdkDir = dest.listFiles { f -> f.isDirectory && f.name.startsWith("jdk-") }?.firstOrNull()
                ?: error("解压布局异常：未找到 jdk-* 目录")
            listOf("bin", "lib", "lib/jli").forEach { sub ->
                File(jdkDir, sub).listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
            }
            File(jdkDir, "bin/java").let {
                if (!it.isFile) error("解压布局异常：未找到 ${jdkDir.name}/bin/java")
                it.setExecutable(true, false)
            }
            listOf(
                "android-sdk/build-tools/36.0.0",
                "android-sdk/platform-tools",
                "gradle-9.6.0/bin",
            ).forEach { rel ->
                File(dest, rel).listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
            }
            File(dest, "verify.sh").takeIf { it.isFile }?.setExecutable(true, false)

            val pathBin = File(rootfs, "usr/local/bin"); pathBin.mkdirs()
            listOf(
                "java" to "${jdkDir.name}/bin/java",
                "javac" to "${jdkDir.name}/bin/javac",
                "gradle" to "gradle-9.6.0/bin/gradle",
                "aapt2" to "android-sdk/build-tools/36.0.0/aapt2",
                "zipalign" to "android-sdk/build-tools/36.0.0/zipalign",
                "apksigner" to "android-sdk/build-tools/36.0.0/apksigner",
                "d8" to "android-sdk/build-tools/36.0.0/d8",
            ).forEach { (name, rel) ->
                val src = File(dest, rel)
                if (src.isFile) {
                    val link = File(pathBin, name); link.delete()
                    runCatching { android.system.Os.symlink(src.absolutePath, link.absolutePath) }
                }
            }
        },
        // ================================================================
        // Gradle 工具链 · 包 2（离线依赖仓库，补 Kotlin/Compose）
        // ================================================================
        Resource(
            id = "gradle_m2",
            title = t("gradle_m2"),
            desc = d("gradle_m2"),
            shareUrl = com.agentpad.workspace.ai.ToolchainRelease.PKG2_URL,
            direct = true,
            archiveName = com.agentpad.workspace.ai.ToolchainRelease.PKG2_FILE,
            isInstalled = inst("gradle_m2")
        ) { archive, report ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")

            // 没有包 1 的话，装包 2 毫无意义（没有 JDK/Gradle/aapt2 去消费这些依赖）
            val pkg1 = File(rootfs, com.agentpad.workspace.ai.ToolchainRelease.PKG1_INSTALL_REL)
            if (!File(pkg1, "AGENTPAD-TOOLCHAIN.txt").isFile) {
                error("请先安装「Gradle 工具链」，本包只是依赖库，单独装无法使用")
            }

            val want = com.agentpad.workspace.ai.ToolchainRelease.PKG2_SHA256
            report("正在校验完整性（${com.agentpad.workspace.ai.ToolchainRelease.PKG2_SIZE_TEXT}，约 1~2 秒）…")
            val got = sha256Of(archive)
            if (!got.equals(want, ignoreCase = true)) {
                error("包 2 校验失败（可能下载被截断）：\n实际 $got\n期望 $want")
            }
            report("校验通过，正在解压…")

            // 关键：caches/ 必须落进 GRADLE_USER_HOME，否则 Gradle 根本不认这些依赖。
            //   /opt/gradle-home/caches/...   ← Gradle 读这里
            //   /opt/gradle-m2-offline/       ← 标记 + verify.sh（人看/校验用）
            val gh = File(rootfs, com.agentpad.workspace.ai.ToolchainRelease.GRADLE_USER_HOME_REL)
            if (!gh.isDirectory) gh.mkdirs()

            val staging = File(rootfs, "tmp/m2-stage")
            if (staging.exists()) deleteRecursive(staging)
            staging.mkdirs()
            extractTarGz(archive, staging)

            val staged = File(staging, com.agentpad.workspace.ai.ToolchainRelease.PKG2_DIR)
            val stagedCaches = File(staged, "caches")
            if (!stagedCaches.isDirectory) error("解压布局异常：未找到 caches/")

            val targetCaches = File(gh, "caches")
            if (targetCaches.exists()) deleteRecursive(targetCaches)
            if (!stagedCaches.renameTo(targetCaches)) {
                error("移动 caches/ 到 GRADLE_USER_HOME 失败")
            }

            val marker = File(rootfs, com.agentpad.workspace.ai.ToolchainRelease.PKG2_INSTALL_REL)
            if (marker.exists()) deleteRecursive(marker)
            marker.mkdirs()
            File(staged, ".agentpad").takeIf { it.isDirectory }
                ?.copyRecursively(File(marker, ".agentpad"), overwrite = true)
            File(staged, "AGENTPAD-M2-OFFLINE.txt").takeIf { it.isFile }
                ?.copyTo(File(marker, "AGENTPAD-M2-OFFLINE.txt"), overwrite = true)
            File(staged, "verify.sh").takeIf { it.isFile }?.let {
                it.copyTo(File(marker, "verify.sh"), overwrite = true)
                File(marker, "verify.sh").setExecutable(true, false)
            }
            // verify.sh 里查 "$C/caches"，放软链让它能找到真实缓存
            runCatching {
                android.system.Os.symlink(targetCaches.absolutePath, File(marker, "caches").absolutePath)
            }

            deleteRecursive(staging)
        },
        Resource(
            id = "video_buildchain",
            title = t("video_buildchain"),
            desc = d("video_buildchain"),
            shareUrl = "https://example.com",
            direct = true,
            archiveName = "video-buildchain-v1.0.0.tar.gz",
            isInstalled = inst("video_buildchain")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")
            // 直接解压到 /opt（包内顶层就是 video-buildchain/），解压后得到 /opt/video-buildchain
            val opt = File(rootfs, "opt"); if (!opt.isDirectory) opt.mkdirs()
            val old = File(opt, "video-buildchain")
            if (old.exists()) deleteRecursive(old)
            extractTarGz(archive, opt)
            val proj = File(opt, "video-buildchain/project")
            if (!proj.isDirectory) error("解压布局异常：未找到 video-buildchain/project")
            // 可执行权限 + ffmpeg
            File(proj, "build.sh").takeIf { it.isFile }?.setExecutable(true, false)
            File(proj, "render.py").takeIf { it.isFile }?.setExecutable(true, false)
            val ffdir = File(opt, "video-buildchain/pypkgs/imageio_ffmpeg/binaries")
            ffdir.listFiles { f -> f.isFile && f.name.startsWith("ffmpeg-") }?.forEach { it.setExecutable(true, false) }
        },
        Resource(
            id = "ndk",
            title = t("ndk"),
            desc = d("ndk"),
            shareUrl = "https://example.com",
            isInstalled = inst("ndk")
        ) { archive, _ ->
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/sh").isFile) error("沙箱尚未初始化：请先在对话中让 AI 执行一次终端命令，再回来部署")
            // 解压到 /opt（包内顶层就是 android-ndk-toolchain/），得到 /opt/android-ndk-toolchain
            val opt = File(rootfs, "opt"); if (!opt.isDirectory) opt.mkdirs()
            val old = File(opt, "android-ndk-toolchain")
            if (old.exists()) deleteRecursive(old)
            extractTarGz(archive, opt)
            val tc = File(opt, "android-ndk-toolchain")
            if (!File(tc, "bin/clang").isFile) error("解压布局异常：未找到 android-ndk-toolchain/bin/clang")
            // 复原可执行权限（tar 已带 mode，但保险起见再设一次）
            File(tc, "bin/clang").setExecutable(true, false)
            File(tc, "bin/clang++").setExecutable(true, false)
            File(tc, "bin/ld.lld").setExecutable(true, false)
            File(tc, "bin/ncc").takeIf { it.isFile }?.setExecutable(true, false)
            File(tc, "bin/ncc++").takeIf { it.isFile }?.setExecutable(true, false)
            File(tc, "qemu-x86_64-static").setExecutable(true, false)
            File(tc, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
            // 软链常用入口到 /usr/local/bin，方便直接敲
            val pathBin = File(rootfs, "usr/local/bin"); pathBin.mkdirs()
            listOf("clang", "clang++", "ld.lld", "llvm-ar", "llvm-strip", "llvm-objcopy", "ncc", "ncc++").forEach { name ->
                val src = File(tc, "bin/$name"); if (src.isFile) {
                    val link = File(pathBin, "ndk-$name"); link.delete()
                    runCatching { android.system.Os.symlink(src.absolutePath, link.absolutePath) }
                }
            }
        },
        Resource(
            id = "dexkit",
            title = t("dexkit"),
            desc = d("dexkit"),
            // 直链模式：官网固定路径，带 SHA-256 校验，不走网盘解析
            shareUrl = "https://example.com",
            direct = true,
            archiveName = "libdexkit-arm64-v8a.so",
            isInstalled = inst("dexkit"),
        ) { archive, _ ->
            // archive 就是下载下来的 .so（直链模式，未解压）
            // ① 放开上一次设的只读（否则 rename 会失败）
            com.agentpad.workspace.ai.DexKitLoader.prepareForUpdate(this)
            // ② 校验 SHA-256 → 原子落盘 → 置只读 + 父目录不可写
            val bytes = archive.readBytes()
            com.agentpad.workspace.ai.DexKitLoader
                .install(this, bytes, com.agentpad.workspace.ai.DexKitRelease.SHA256)
                .getOrThrow()
            // ③ 立刻加载，装完就能用（不用等 AI 再调一次）
            com.agentpad.workspace.ai.DexKitLoader.load(this).getOrThrow()
        },
    )
}

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        resourceList = resources()
        stateMap = resourceList.associate { it.id to ItemState() }.toMutableMap()
        resourceList.forEach { stateMap[it.id]!!.installed = runCatching { it.isInstalled() }.getOrDefault(false) }
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        SubPage("下载环境", "按需下载并部署运行组件到沙箱，供 AI 终端使用",
            onBack = { finish() }, scrollable = false) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(resourceList) { res -> ResourceCard(res) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    @Composable
    private fun ResourceCard(res: Resource) {
        val s = stateMap[res.id]!!
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(
                    if (s.installed) Icons.Outlined.CheckCircle else Icons.Outlined.CloudDownload,
                    if (s.installed) AColors.Success else AColors.Accent,
                )
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(res.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                    if (s.installed && s.progress < 0) {
                        Text("已安装 · 可重新下载覆盖", fontSize = 11.sp, color = AColors.Success,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            Text(res.desc, fontSize = 12.sp, color = AColors.Muted,
                modifier = Modifier.padding(top = 10.dp), lineHeight = 17.sp)

            if (s.progress >= 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (s.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (s.failed) AColors.Danger else AColors.Accent,
                    trackColor = AColors.LineSoft,
                )
                if (s.status.isNotBlank()) {
                    Text(s.status, fontSize = 11.sp,
                        color = if (s.failed) AColors.Danger else AColors.Muted,
                        modifier = Modifier.padding(top = 7.dp), lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            val label = when {
                s.running -> "下载中…"
                s.failed -> "重试"
                s.installed -> "重新下载"
                else -> "下载部署"
            }
            Box(
                Modifier.clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(if (s.running) AColors.Accent.copy(alpha = .5f) else AColors.Accent)
                    .clickable(enabled = !s.running) { startDownload(res, s) }
                    .fillMaxWidth().height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!s.running) Icon(Icons.Outlined.Download, contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(17.dp))
                    if (!s.running) Spacer(Modifier.width(7.dp))
                    Text(label, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    private fun startDownload(res: Resource, s: ItemState) {
        if (s.running) return
        s.running = true
        s.failed = false
        s.progress = 0
        s.status = "解析分享链接…"
        executor.execute { runPipeline(res, s) }
    }

    private fun runPipeline(res: Resource, s: ItemState) {
        fun ui(body: () -> Unit) = runOnUiThread(body)
        fun setBar(p: Int) = ui { if (p in 0..100) s.progress = p }
        fun setStatus(t: String) = ui { s.status = t }
        try {
            // ---- 特殊通道：系统镜像 / Python 运行时 ----
            // 这两样首次启动由 BootActivity → SandboxInstaller.ensureInstalled
            // 自动安装；断网会静默跳过（runCatching 放行），所以这里给补装入口。
            // 直接复用 SandboxInstaller 现成的多源回退 + 分块解压，不重写逻辑。
            if (res.special != null) {
                val progress = object : com.agentpad.workspace.ai.SandboxInstaller.Progress {
                    override fun onMessage(text: String) = setStatus(text)
                    override fun onProgress(percent: Int) = setBar(percent)
                }
                when (res.special) {
                    "rootfs" -> com.agentpad.workspace.ai.SandboxInstaller.reinstallRootfs(this, progress)
                    "python3" -> com.agentpad.workspace.ai.SandboxInstaller.reinstallPython(this, progress)
                    "python316" -> com.agentpad.workspace.ai.SandboxInstaller.reinstallPython316(this, progress)
                    "python316slim" -> com.agentpad.workspace.ai.SandboxInstaller.reinstallPython316Slim(this, progress)
                }
                val verified = runCatching { res.isInstalled() }.getOrDefault(false)
                if (!verified) {
                    // 2026-11-13 事故：这里只报「未找到目标文件/可执行」，
                    // 但到底是哪一项不过完全看不到，导致反复补装反复失败。
                    // 现在把现场写盘，用户截图即可定位。
                    runCatching { com.agentpad.workspace.ai.SandboxDiagnoser.describe(this@DownloadEnvironmentActivity) }
                    error("补装完成但校验未通过：未找到目标文件/可执行文件（现场已写入 Download/agentpad-diag.txt）")
                }
                setBar(100)
                setStatus("已部署 ✓")
                s.installed = true
                s.running = false
                ui { Toast.makeText(this, "${res.title}部署完成", Toast.LENGTH_SHORT).show() }
                return
            }

            val (direct, name) = if (res.direct) {
                // 直链模式：shareUrl 本身就是可下载地址，无需解析网盘
                setStatus("开始下载…")
                res.shareUrl to res.archiveName.ifBlank { "${res.id}.tar.gz" }
            } else {
                setStatus("解析分享链接…")
                val resolved = JSONObject(FeijipanParser.resolve(res.shareUrl))
                resolved.getString("url") to resolved.optString("name").ifBlank { "${res.id}.tar.gz" }
            }
            setBar(0)

            val cache = File(cacheDir, "download-env").apply { mkdirs() }
            val archive = File(cache, name)
            if (archive.exists()) archive.delete()

            download(direct, archive) { p, done, total ->
                setBar(p)
                if (total > 0) setStatus("下载中 ${formatSize(done)} / ${formatSize(total)}")
            }

            setStatus("正在部署到沙箱…")
            // special 通道在上面已 return，走到这里的卡片一定带 install。
            // 传 setStatus 进去，让耗时长的安装（380MB 解压）能把进度回报到卡片上。
            res.install?.invoke(archive, { text -> setStatus(text) })
                ?: error("该组件缺少安装逻辑（内部错误：未指定 install 或 special）")
            archive.delete()

            // 部署后按判据重新校验，避免「下载完成但判据不匹配 → 又显示未安装」
            val verified = runCatching { res.isInstalled() }.getOrDefault(false)
            if (!verified) error("部署完成但校验未通过：未找到目标文件，可能是安装判据与实际解压路径不一致")

            setBar(100)
            setStatus("已部署 ✓")
            s.installed = true
            s.running = false
            ui { Toast.makeText(this, "${res.title}部署完成", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            setStatus("失败：${e.message}")
            ui { s.failed = true; s.running = false }
        }
    }

    private fun download(urlString: String, target: File, onProgress: (Int, Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000; readTimeout = 300_000
            // 飞基盘直链需要 Referer；自家服务器直链给了也无害
            setRequestProperty("Referer", "https://www.feijipan.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "*/*")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            BufferedInputStream(input).use { buffered ->
                FileOutputStream(target, false).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var done = 0L; var lastPct = -1
                    while (buffered.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read); done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct, done, total) }
                        }
                    }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && target.length() != total) error("下载不完整：${target.length()}/${total} 字节")
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /**
     * tar.gz 解压。
     *
     * ⚠ 2026-11-18 逐条修掉真实 bug（主人要求「保证没有 bug」，所以每个修改都写了原因）：
     *
     *   ① **文件名在第一个 NUL 处截断，不是 trimEnd**。
     *      tar 的 name 字段是 100 字节定长、NUL 填充，但实测有些包（python-build-standalone）
     *      在 NUL 之后还有垃圾字节：`"2to3\0in/2to3"`。原来用 trimEnd('\u0000') 只砍尾部，
     *      中间的 NUL 会留在字符串里 → 生成带 \0 的非法路径，写入直接失败。
     *
     *   ② **路径穿越防护**。原实现 `File(targetDir, relative)` 完全信任 tar 里的名字，
     *      一个 `../../` 就能写到 rootfs 外面（比如覆盖 /system 下的东西）。
     *      自家包不会这么干，但这是解压器的基本纪律，不该靠"没人攻击"来保证安全。
     *
     *   ③ **buffer 从 8KB 提到 256KB**。380MB 的包用 8KB 缓冲区意味着十几万次系统调用，
     *      在手机上就是解压慢一倍。256KB 对内存没压力，速度明显提升。
     *
     *   ④ **补 LongLink（type 'L'/'K'）与 PAX（'x'/'g'）处理**。
     *      我们的包路径都很短（最长约 70 字符，远低于 100+155 的限制），
     *      理论上不会触发。但解压器不该在"换了打包方式"时静默产出错乱文件 ——
     *      所以按标准处理掉扩展头，反正代价只有几行。
     *
     *   ⑤ **可执行位判断的写法**。原代码 `mode and 0b001000000`，数值上等于 0x40（owner exec），
     *      **结果是对的**，但二进制字面量和 tar 规范的八进制 0100 对不上，读代码的人
     *      会以为搞错了。改成 0x40L 并注明来源。
     *
     * @param onProgress 已处理字节数的回调（用于 380MB 这种大包显示进度），可为 null
     */
    private fun extractTarGz(
        archive: File,
        targetDir: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        targetDir.mkdirs()
        val buffer = ByteArray(256 * 1024)
        val totalBytes = archive.length()
        var processed = 0L
        var lastPct = -1

        fun tick() {
            val cb = onProgress ?: return
            if (totalBytes <= 0) return
            val pct = (processed * 100 / totalBytes).toInt().coerceIn(0, 100)
            if (pct != lastPct) { lastPct = pct; cb(processed, totalBytes) }
        }

        GZIPInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024)).use { input ->
            val header = ByteArray(512)

            // PAX / GNU 扩展头携带的「下一条的真实值」
            var extName: String? = null
            var extSize: Long? = null
            var extLink: String? = null

            /** 定长字段解码：**在第一个 NUL 处停止**（见修正 ①） */
            fun field(bytes: ByteArray, from: Int, to: Int): String {
                var end = to
                for (i in from until to) if (bytes[i] == 0.toByte()) { end = i; break }
                return bytes.copyOfRange(from, end).decodeToString()
            }

            while (true) {
                var off = 0
                while (off < 512) {
                    val n = input.read(header, off, 512 - off)
                    if (n < 0) {
                        if (off == 0) return
                        throw IllegalStateException("tar 头不完整（解压包可能被截断）")
                    }
                    off += n
                }
                processed += 512
                // tar 结束标记：一个全零块（标准是两个，读到一个就可以收工）
                if (header.all { it == 0.toByte() }) return

                val type = header[156].toInt().toChar()
                var size = parseOctal(header, 124, 12)
                var name = field(header, 0, 100)
                val prefix = field(header, 345, 500)
                var linkName = field(header, 157, 257)

                // ---- 修正 ④：扩展头 ----
                if (type == 'x' || type == 'g') {
                    // PAX 扩展头：正文是一串 "len key=value\n" 记录
                    val data = ByteArray(size.toInt())
                    readExactly(input, data)
                    processed += size
                    skipPadding(input, size) { processed += it }
                    val text = data.decodeToString()
                    for (record in text.split('\n')) {
                        if (record.isBlank()) continue
                        // 每条记录开头是十进制长度 + 空格，跳过它
                        val sp = record.indexOf(' ')
                        if (sp < 0 || sp + 1 >= record.length) continue
                        val kv = record.substring(sp + 1)
                        when {
                            kv.startsWith("path=") -> extName = kv.removePrefix("path=")
                            kv.startsWith("linkpath=") -> extLink = kv.removePrefix("linkpath=")
                            kv.startsWith("size=") -> extSize = kv.removePrefix("size=").trim().toLongOrNull()
                        }
                    }
                    continue  // 扩展头本身不是文件，不落盘
                }
                if (type == 'L' || type == 'K') {
                    // GNU 长名字块：正文就是完整名字（可能超 100 字节）
                    val data = ByteArray(size.toInt())
                    readExactly(input, data)
                    processed += size
                    skipPadding(input, size) { processed += it }
                    val raw = data.decodeToString()
                    var end = raw.length
                    for (i in raw.indices) if (raw[i] == '\u0000') { end = i; break }
                    val v = raw.substring(0, end)
                    if (type == 'L') extName = v else extLink = v
                    continue
                }

                // 用扩展头的值覆盖（若存在）
                extName?.let { name = it }
                extSize?.let { size = it }
                extLink?.let { linkName = it }
                val hadLongName = extName != null
                extName = null; extSize = null; extLink = null

                val relative = (if (hadLongName || prefix.isBlank()) name else "$prefix/$name").trim()
                if (relative.isBlank()) {
                    skipExactly(input, size)
                    processed += size
                    skipPadding(input, size) { processed += it }
                    continue
                }

                // ---- 修正 ②：路径穿越防护 ----
                val outFile = File(targetDir, relative).normalize()
                val rootPath = targetDir.normalize().path
                if (outFile.path != rootPath && !outFile.path.startsWith(rootPath + File.separator)) {
                    throw IllegalStateException("压缩包含非法路径，已拒绝解压：$relative")
                }

                when (type) {
                    '5' -> outFile.mkdirs()

                    '1' -> {  // 硬链接：目标在同包内，能拷就拷
                        val src = File(targetDir, linkName).normalize()
                        if (src.isFile) {
                            outFile.parentFile?.mkdirs()
                            outFile.delete()
                            runCatching { src.copyTo(outFile, overwrite = true) }
                                .onFailure { error("硬链接复制失败：$relative") }
                        }
                    }

                    '2' -> {  // 符号链接：失败就退化成实体拷贝
                        outFile.parentFile?.mkdirs()
                        outFile.delete()
                        val created = runCatching {
                            android.system.Os.symlink(linkName, outFile.absolutePath)
                        }.isSuccess
                        if (!created) {
                            val src = File(targetDir, linkName).normalize()
                            if (src.isFile) runCatching { src.copyTo(outFile, overwrite = true) }
                        }
                    }

                    '0', '\u0000' -> {  // 普通文件
                        outFile.parentFile?.mkdirs()
                        if (outFile.isDirectory) outFile.deleteRecursively()
                        FileOutputStream(outFile, false).use { out ->
                            var left = size
                            while (left > 0) {
                                val want = minOf(buffer.size.toLong(), left).toInt()
                                val count = input.read(buffer, 0, want)
                                if (count < 0) throw IllegalStateException("tar 内容不完整：$relative")
                                out.write(buffer, 0, count)
                                left -= count
                                processed += count
                            }
                        }
                        outFile.setReadable(true, false)
                        // ---- 修正 ⑤：tar mode 的 owner-exec 位就是八进制 0100 = 0x40 ----
                        val mode = parseOctal(header, 100, 8)
                        if ((mode and 0x40L) != 0L) outFile.setExecutable(true, false)
                    }

                    else -> {  // 设备节点、FIFO 等：跳过（沙箱里用不到）
                        skipExactly(input, size)
                        processed += size
                    }
                }
                skipPadding(input, size) { processed += it }
                tick()
            }
        }
    }

    /** 精确读满 len 字节，读不满直接报错（truncated 包必须暴露出来）。 */
    private fun readExactly(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("tar 扩展头内容不完整")
            off += n
        }
    }

    /** 跳过 size 对应的 512 字节对齐填充。 */
    private inline fun skipPadding(input: java.io.InputStream, size: Long, onSkipped: (Long) -> Unit) {
        val pad = ((size + 511) / 512 * 512 - size)
        if (pad <= 0) return
        var left = pad
        val scratch = ByteArray(512)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(512L, left).toInt())
            if (n < 0) return
            left -= n
            onSkipped(n.toLong())
        }
    }

    /** 跳过 size 字节的正文（用于 type 不认识的条目）。 */
    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var left = size
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw IllegalStateException("tar 正文不完整（包可能被截断）")
            left -= n
        }
    }

    /**
     * 计算文件 SHA-256，返回小写十六进制。
     *
     * 为什么必须自己算而不是信任下载长度：
     *   HTTP 的 Content-Length 在加速代理被截断时经常"看起来完整"
     *   （代理自己返回 200 + 错误的长度），只有哈希能证明字节一致。
     *   380MB 在手机上算一次约 1~2 秒，可接受。
     */
    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val out = md.digest()
        val hex = StringBuilder(out.size * 2)
        for (b in out) {
            val v = b.toInt() and 0xFF
            if (v < 16) hex.append('0')
            hex.append(v.toString(16))
        }
        return hex.toString()
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        for (i in offset until offset + length) {
            val b = header[i].toInt().toChar()
            if (b == '\u0000' || b == ' ') break
            if (b in '0'..'7') result = result * 8 + (b - '0')
        }
        return result
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}