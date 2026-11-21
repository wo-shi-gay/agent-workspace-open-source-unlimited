package com.agentpad.workspace.ai

import android.content.Context
import java.io.File

/**
 * 「下载环境」里所有资源卡片的**唯一真相源**。
 *
 * 之前卡片目录硬编码在 [com.agentpad.workspace.DownloadEnvironmentActivity] 里，
 * AI 看不到，只能瞎猜「有没有某工具链」，常常谎报缺失/存在。
 *
 * 现在目录集中在这里：UI 和 AI 工具（list_env_cards）都读它 —— 保证
 * AI 看到的清单和用户在「设置 → 下载环境」看到的**永远一致**。
 *
 * 只描述「是什么 / 装没装 / 装在哪」，不含下载逻辑（下载逻辑仍在 Activity，
 * 因为它要 Android 的 IO/进度 UI）。
 */
object EnvCatalog {

    /** 一张资源卡片的目录信息。 */
    data class Card(
        val id: String,
        /** 展示名（与 UI 卡片标题完全一致） */
        val title: String,
        /** 一句话说明 */
        val desc: String,
        /** 安装判据：返回 true 表示已部署到沙箱 */
        val installed: (Context) -> Boolean,
    )

    private fun rootfs(context: Context): File = SandboxInstaller.rootfsDir(context)

    /** 全部卡片，顺序与「下载环境」页面一致。 */
    fun cards(context: Context): List<Card> = listOf(
        // ---- 基础设施（首次启动自动装，断网被跳过时可在这里补装）----
        Card(
            id = "rootfs",
            title = "系统镜像（Debian 12 ARM64）",
            desc = "沙箱底层 Linux 系统（proot + Debian rootfs，约 30MB）。App 首次启动会自动下载；" +
                "若当时断网被跳过、或提示「沙箱尚未初始化」，在这里补装即可修复（约 30MB）",
        ) { SandboxInstaller.isHealthy(it) },
        Card(
            id = "python3",
            title = "Python 3.12 运行时",
            desc = "预编译 CPython 3.12（aarch64，约 29MB），AI 终端里 python3 / pip3 的基础。" +
                "App 首次启动会自动下载；断网跳过或 `python3: not found` 时在这里补装。需先装系统镜像",
        ) {
            val root = rootfs(it)
            File(root, "usr/bin/python3").isFile ||
                File(root, "usr/local/python3/python/bin/python3").isFile
        },
        Card(
            id = "python316",
            title = "Python 3.16 尝鲜版（自建交叉编译）",
            desc = "我们自己在 GitHub Actions 上交叉编译的 CPython 3.16.0a2（aarch64，约 89MB）：" +
                "含 ssl / sqlite3 / zlib / bz2 / lzma / ctypes 与 include/（可编译 C 扩展）。" +
                "装到 usr/local/python316，**不替换** 默认的 python3（保持 3.12），用 /usr/bin/python3.16 调用。" +
                "注意：3.16 是开发版，PyPI 基本没有 cp316 的预编译 wheel，装三方二进制包会失败，" +
                "只适合尝鲜/跑纯 Python 脚本。需先装系统镜像",
        ) {
            val root = rootfs(it)
            File(root, "usr/local/python316/bin/python3.16").isFile ||
                File(root, "usr/local/python316/.installed").isFile
        },
        Card(
            id = "python316slim",
            title = "Python 3.16 精简版（11.9MB，推荐）",
            desc = "同一份自建 CPython 3.16.0a2，只做了 strip + 删静态库/测试/缓存：" +
                "**11.9MB**（解压 46MB），仍保留 include/（可编 C 扩展）、pkgconfig 与预装 pip。装到 usr/local/python316slim，" +
                "命令是 /usr/bin/python3.16slim，同样不替换默认 python3。3.16 是开发版，PyPI 无 cp316 wheel，三方二进制包装不上",
        ) {
            val root = rootfs(it)
            File(root, "usr/local/python316slim/bin/python3.16").isFile ||
                File(root, "usr/local/python316slim/.installed").isFile
        },
        Card(
            id = "jre17",
            title = "Java 17 运行时",
            desc = "Temurin JRE 17 (aarch64)，ecj / d8 / apksigner 等 Java 工具的运行环境（约 45MB）。建议先装这个再装工具链",
        ) { File(rootfs(it), "usr/local/jre17/bin/java").isFile },
        Card(
            id = "toolchain",
            title = "编译工具链",
            desc = "ecj / d8 / aapt2 / apksigner / android.jar，部署后 AI 可在终端内编译并打包 APK（约 42MB）",
        ) { File(rootfs(it), "opt/toolchain-pack/compiler").isDirectory },
        Card(
            id = "toolchain_patch_v3",
            title = "工具链补丁 v3（aapt2 成套）",
            desc = "aapt2 35.0.0 + android-35 android.jar 成套替换（修复 illegal map type / R.java missing），含 SELFCHECK.sh 自检（约 27MB）。需先装编译工具链",
        ) { File(rootfs(it), "opt/toolchain-pack/SELFCHECK.sh").isFile },
        Card(
            id = "go_env",
            title = "Go 环境",
            desc = "Go 1.22.2 (aarch64)，含完整 stdlib 源码与 gofmt，部署后 AI 可在终端内 go build / go run（约 38MB）",
        ) {
            File(rootfs(it), "usr/local/go/bin/go").isFile ||
                File(rootfs(it), "tmp/go-env.tar.gz").isFile
        },
        // ⚠ 2026-11-05 移除 Node.js 卡片。
        //
        // 原因（主人实测）：这张卡片只把 node-env.tar.gz 下载到 /tmp，
        // **不会自动解压部署**，需要 AI 手动执行解压命令才算装好。
        // 用户以为装完了，实际 `node` 根本不存在，
        // Codex 启动时才会莫名其妙失败。
        //
        // 现在 Node 运行时由「部署服务 → Codex」自己管理：
        // 离线包里已带 Node，或由 CodexService 按绝对路径探测 + 现场解压。
        // 所以这里不再暴露给用户，避免"装了却没用"的困惑。
        //
        // 如需恢复，原定义：
        //   id = "node_env", title = "Node.js 环境（含 PNPM）",
        //   installed = File(rootfs, "usr/local/node/node").isFile
        //            || File(rootfs, "tmp/node-env.tar.gz").isFile
        // ⚠ 2026-11-17 已删除「Kotlin + Compose 构建器」（原 id=kotlin_compose）。
        //
        // 删除原因：这套是手工拼的 7 步流水线（aapt2→ecj→kotlinc→d8→组装→签名），
        // 只能编一个写死的模板工程，任何带 build.gradle.kts 的真实工程都编不了 ——
        // 没有 Gradle、没有依赖解析、没有 AGP 的 manifest/资源合并。
        //
        // 替代方案：改用「Gradle 工具链」两包制（gradle-toolchain-arm64 +
        // gradle-m2-offline），走真正的 Gradle + AGP + Kotlin 插件，
        // 能编标准 Android 工程。产物由独立仓库 vxggm/agent-toolchain-arm64
        // 云端构建，与本项目发版完全分离。
        //
        // 如需恢复，原定义：
        //   Card(
        //       id = "kotlin_compose",
        //       title = "Kotlin + Compose 构建器",
        //       desc = "Kotlin 编译器 2.2.10 + Compose 编译器插件 + 52 个运行时依赖（约 74MB）",
        //   ) { File(rootfs(it), "opt/kotlin-toolchain/bin/kotlinc").isFile },
        Card(
            id = "gradle_toolchain",
            title = "Gradle 工具链（arm64 原生，可编 Java）",
            desc = "JDK 17 + Gradle 9.6.0 + build-tools 36.0.0(aarch64 原生) + android-37。" +
                "解压后 ${ToolchainRelease.PKG1_UNPACKED_TEXT}，压缩包 ${ToolchainRelease.PKG1_SIZE_TEXT}。" +
                "装了它 AI 能用真正的 Gradle 编**纯 Java 的 Android 工程**（不是只有 aapt2+d8 的手工流水线）。" +
                "全部二进制为 arm64 ELF，无需 qemu。装完可跑 opt/${ToolchainRelease.PKG1_DIR}/verify.sh 自检",
        ) { File(rootfs(it), "${ToolchainRelease.PKG1_INSTALL_REL}/AGENTPAD-TOOLCHAIN.txt").isFile },
        Card(
            id = "gradle_m2",
            title = "Gradle 离线依赖库（补 Kotlin/Compose，${ToolchainRelease.PKG2_SIZE_TEXT}）",
            desc = "AGP 9.4.0 + Kotlin 2.4.21 插件 + Compose BOM 2026.09.00 + androidx 全链。" +
                "**必须与上面的 Gradle 工具链一起用**：它只补依赖仓库，不含编译器。" +
                "两个都装好后，AI 才能编**带 build.gradle.kts 的真实 Kotlin/Compose 工程**。" +
                "含 aapt2？不含 —— aapt2 由 Gradle 工具链那包提供 arm64 版，这包是纯 JVM 依赖、跨架构安全",
        ) { File(rootfs(it), "${ToolchainRelease.PKG2_INSTALL_REL}/AGENTPAD-M2-OFFLINE.txt").isFile },
        Card(
            id = "video_buildchain",
            title = "视频工具链",
            desc = "moviepy + ffmpeg 7.0.2 + Pillow/numpy + 中文字体（内含「视频项目」模板）。装了它，「视频项目」会话里的 AI 才能在终端里渲染 out.mp4（约 47MB）",
        ) { File(rootfs(it), "opt/video-buildchain/pypkgs").isDirectory },
        Card(
            id = "ndk",
            title = "C/C++ 原生工具链（NDK）",
            desc = "Android NDK r29 clang 21 + qemu 转译 + Android sysroot，可在 ARM64 主机上编译 Android arm64-v8a 的 C/C++ 程序（.so / 可执行文件）。装了它，AI 才能编原生 UCI 引擎、native 库等（约 87MB）",
        ) {
            File(rootfs(it), "opt/android-ndk-toolchain/bin/clang").isFile ||
                File(rootfs(it), "opt/ndk-toolchain/bin/clang").isFile
        },
        Card(
            id = "dexkit",
            title = "DexKit 逆向引擎（1.73MB，推荐）",
            desc = "原生 C++ dex 解析引擎，**逆向功能就靠它**：按字符串反查被混淆的类/方法、" +
                "打开 APK/dex、导出明文 dex。装在 App 私有目录（不是 rootfs），下载后自动校验 SHA-256 并置为只读。" +
                "改 APK 逻辑（解包→smali→改→回编译→签名）**只需要这一个**，不用装 188MB 那套 apktool/jadx",
        ) { com.agentpad.workspace.ai.DexKitLoader.isReady(it) },
    )

    /** 已安装的卡片标题列表。 */
    fun installedTitles(context: Context): List<String> =
        cards(context).filter { runCatching { it.installed(context) }.getOrDefault(false) }.map { it.title }

    /** 卡片总数。 */
    fun total(context: Context): Int = cards(context).size
}
