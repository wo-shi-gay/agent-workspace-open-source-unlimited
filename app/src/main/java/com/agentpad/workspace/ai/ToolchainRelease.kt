package com.agentpad.workspace.ai

/**
 * 自建 Gradle 工具链两包的**唯一真相源**。
 *
 * 为什么单独一个文件：
 *   URL / SHA-256 / 版本号以前散在各处，升级一次要改三四个地方，
 *   而且卡片标题里写的体积和真实体积经常对不上（主人已经因为这种事
 *   当场对账过一次：「你不是说有300兆吗？」）。集中在这里，
 *   卡片描述里引用的数字全部由常量拼出来，改一处即可。
 *
 * 产物来源：独立仓库 `vxggm/agent-toolchain-arm64` 的 GitHub Actions
 * 云端构建，发布到 Release `toolchain-v<ver>`，与本 App 的发版完全分离。
 *
 * ⚠ 仓库已转为公开（accelerated 前缀只能访问公开资源）。
 */
object ToolchainRelease {

    /** 当前发布版本 */
    const val VERSION = "1.0.0"

    /** Release tag */
    const val TAG = "toolchain-v$VERSION"

    /**
     * 加速前缀。实测（2026-11-18，380MB 真实文件，各 5 秒）：
     *
     *   ghfast.top         3.26 MB/s   ✅ 两轮都成功，选它
     *   mirror.mikus.ink   0.58 MB/s   第二轮连接直接失败 ❌
     *   gh-proxy.com       0.02 MB/s   ❌
     *   ghproxy.net        0.06 MB/s   ❌
     *   直连 GitHub        完全不通     ❌
     *
     * 教训：小文件（2KB）探活测不出真实带宽，mirror.mikus.ink 探活 0.79s
     * 看着最快，下大文件直接崩。以后按真实体积测。
     */
    private const val MIRROR = "https://ghfast.top/"

    /** Release 资产直链的公共前半段 */
    private fun assetUrl(file: String): String =
        "${MIRROR}https://example.com"

    // ==================================================================
    // 包 1：JDK + Gradle + SDK 原生件 —— 能编纯 Java Android 工程
    // ==================================================================

    /** 包 1 解压后的顶层目录名 */
    const val PKG1_DIR = "gradle-toolchain-arm64"

    /** 包 1 文件名 */
    const val PKG1_FILE = "$PKG1_DIR-v$VERSION.tar.gz"

    /** 包 1 下载地址（已带加速前缀） */
    val PKG1_URL: String get() = assetUrl(PKG1_FILE)

    /** 包 1 的 SHA-256（由 Release 里的 .sha256 资产核对得来） */
    const val PKG1_SHA256 =
        "b2684cfd605710e3071f23258a1c1bc7b9d8c45afccd37fd3862787bbd522176"

    /** 包 1 压缩包字节数 */
    const val PKG1_BYTES = 398_732_920L

    /** 包 1 解压后字节数（云端实测 589,549,245） */
    const val PKG1_UNPACKED_BYTES = 589_549_245L

    // ==================================================================
    // 包 2：Gradle 离线依赖仓库 —— 叠加包 1 后能编 Kotlin / Compose
    // ==================================================================

    const val PKG2_DIR = "gradle-m2-offline"
    const val PKG2_FILE = "$PKG2_DIR-v$VERSION.tar.gz"
    val PKG2_URL: String get() = assetUrl(PKG2_FILE)

    const val PKG2_SHA256 =
        "a04790f4b3b44ae13cffc2c022197e6863e9593dc430eade978f312fa0bfd259"

    const val PKG2_BYTES = 462_420_582L

    // ==================================================================
    // 沙箱里的安装位置
    // ==================================================================

    /** 包 1 装到这里：<rootfs>/opt/gradle-toolchain-arm64 */
    const val PKG1_INSTALL_REL = "opt/$PKG1_DIR"

    /** 包 2 装到这里：<rootfs>/opt/gradle-m2-offline */
    const val PKG2_INSTALL_REL = "opt/$PKG2_DIR"

    /**
     * GRADLE_USER_HOME。
     *
     * 包 2 的 `caches/` 必须落在这个目录下，Gradle 才认得那些依赖。
     * 放在 /opt 下而不是 ~/.gradle，是因为：
     *   · rootfs 里 /root 可能被多系统共用，容易互相污染；
     *   · /opt 与包 1 同级，一次性 rm -rf 就能干净卸载。
     */
    const val GRADLE_USER_HOME_REL = "opt/gradle-home"

    // ------------------------------------------------------------------
    // 展示用字符串（卡片描述直接引用，保证数字不会写错）
    // ------------------------------------------------------------------

    fun mb(bytes: Long): String = "%.0f".format(bytes / 1_048_576.0)

    val PKG1_SIZE_TEXT: String get() = "${mb(PKG1_BYTES)}MB"
    val PKG2_SIZE_TEXT: String get() = "${mb(PKG2_BYTES)}MB"
    val PKG1_UNPACKED_TEXT: String get() = "${mb(PKG1_UNPACKED_BYTES)}MB"
}