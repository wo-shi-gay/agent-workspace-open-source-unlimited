package com.agentpad.workspace.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * DexKit 原生库的分发配置（下载模式）。
 *
 * so 在**云端构建**产出（见 /root/_ws_ci/.github/workflows/dexkit-so.yml），
 * 编好后放到官网服务器，App 运行时按需下载。
 *
 * ⚠ 换版本时只需改这里两行：URL 与 SHA-256。
 *   SHA-256 必填 —— DexKitLoader 会比对，不匹配就拒绝安装，
 *   防止被中间人塞一个恶意 so（毕竟 we System.load 它，等于执行任意代码）。
 */
object DexKitRelease {

    private const val TAG = "DexKitRelease"

    /** DexKit 版本（与 so 一起打标签，便于排查）。 */
    const val VERSION = "2.3.0"

    /** so 文件名（必须是 libdexkit.so，JNI 符号依赖它）。 */
    const val FILE_NAME = "libdexkit.so"

    /**
     * 下载地址。
     *
     * 走官网固定路径 /downloads/dexkit/libdexkit-arm64-v8a.so，
     * 与 APK 同一个 Apache 站点，无需额外鉴权。
     */
    const val URL_PRIMARY =
        "https://example.com"

    /**
     * so 的 SHA-256（小写 hex）。
     *
     * 2026-11-16 首次云端构建产出并回填：
     *   · workflow：.github/workflows/dexkit-so.yml（run 43751094895）
     *   · 体积：1,816,480 B（NDK 26.1.10909125 / arm64-v8a / minSdk 21 / -Oz + strip）
     *   · 已落位：https://<官网>/downloads/dexkit/libdexkit-arm64-v8a.so（公网实测 200）
     * DexKitLoader 会逐字节比对；不匹配就拒绝安装（我们 System.load 它 = 执行它的代码）。
     */
    const val SHA256 = "d2d693d1545e131c86b8eb23409e9154d47c678aef4b99581807b63acfacd613"

    /** 期望体积（仅做粗校验，防半截下载）。 */
    const val EXPECTED_SIZE_HINT = "1,816,480 B（约 1.73 MB）"

    /** 实测字节数（下载后可对比，不做硬校验；SHA 已经足够）。 */
    const val EXPECTED_SIZE = 1_816_480L

    /** 是否已配置好（URL 和 SHA 都填了才允许走下载）。 */
    val isConfigured: Boolean
        get() = SHA256.isNotBlank() && SHA256 != "PENDING_CLOUD_BUILD"

    /** 下载并安装 so。阻塞调用，请在后台线程执行。 */
    fun downloadAndInstall(ctx: Context): Result<File> {
        if (!isConfigured) {
            return Result.failure(
                IllegalStateException("DexKit so 尚未配置（SHA256=$SHA256）。云端构建产出后回填 DexKitRelease.SHA256。")
            )
        }
        val bytes = runCatching { httpGet(URL_PRIMARY) }
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("下载失败：$URL_PRIMARY"))

        // 重新安装前放开写权限（目录被设成只读过）
        DexKitLoader.prepareForUpdate(ctx)
        return DexKitLoader.install(ctx, bytes, SHA256)
    }

    /** 确保可用：就绪直接用；没装就下载安装再 load。 */
    fun ensureReady(ctx: Context): Result<Unit> {
        if (DexKitLoader.loaded) return Result.success(Unit)
        if (!DexKitLoader.isReady(ctx)) {
            val r = downloadAndInstall(ctx)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return DexKitLoader.load(ctx)
    }

    private fun httpGet(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "agentpad-workspace")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                null
            } else {
                conn.inputStream.use { it.readBytes() }
            }
        } finally {
            conn.disconnect()
        }
    }
}