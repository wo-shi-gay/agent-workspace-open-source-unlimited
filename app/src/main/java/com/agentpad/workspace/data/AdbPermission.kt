package com.agentpad.workspace.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * ADB 权限（Shizuku 通道）。
 *
 * 作用：
 *  - 通过 Shizuku 拿到 ADB 级（shell uid 2000）的系统权限
 *  - 开启后 AI 的 terminal 工具以 shell 身份执行命令（而非应用沙箱）
 *  - 提供 ADB 级后台保活能力（拉活自身 / 保活前台服务）
 *
 * 用户可在「设置 → ADB 权限」里随时开/关，关掉后立即回落到普通权限。
 */
object AdbPermission {

    private const val PREF = "adb_permission"
    private const val KEY_ENABLED = "adb_enabled"
    private const val KEY_BOOT_KEEPALIVE = "adb_boot_keepalive"

    const val REQUEST_CODE = 0x5A12

    /** ADB 权限是否已开启（用户开关 + Shizuku 授权都满足） */
    fun isEnabled(ctx: Context): Boolean {
        if (!prefEnabled(ctx)) return false
        return isGranted()
    }

    /** 用户开关（不代表已授权，仅表示用户希望开启） */
    fun prefEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setPrefEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        AppLog.i("AdbPermission", "用户${if (enabled) "开启" else "关闭"}ADB权限")
    }

    /** 开机保活开关 */
    fun bootKeepAlive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_BOOT_KEEPALIVE, true)

    fun setBootKeepAlive(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BOOT_KEEPALIVE, on).apply()
    }

    // ---------- Shizuku 状态 ----------

    /** Shizuku 服务是否在运行 */
    fun isShizukuAlive(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /** Shizuku 是否已安装 */
    fun isShizukuInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** 是否已授予 Shizuku 权限 */
    fun isGranted(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 当前 Shizuku 是否以 root 身份运行（否则为 ADB 身份） */
    fun isRootMode(): Boolean = runCatching {
        if (!isShizukuAlive()) return false
        Shizuku.getVersion() > 0 && false // 需通过 Shell 探测，见 ShellExec.isRoot()
    }.getOrDefault(false)

    /** 请求 Shizuku 授权（弹出系统级授权对话框） */
    fun requestPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                AppLog.w("AdbPermission", "请求授权失败：Shizuku 服务未运行")
                return
            }
            if (Shizuku.isPreV11()) {
                AppLog.w("AdbPermission", "Shizuku 版本过低（需 v11+）")
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
            AppLog.i("AdbPermission", "已发起 Shizuku 授权请求")
        }.onFailure {
            AppLog.e("AdbPermission", "请求授权异常", it)
        }
    }

    /** 状态摘要（给 UI 显示） */
    fun statusText(ctx: Context): String {
        if (!isShizukuInstalled(ctx)) return "未安装 Shizuku"
        if (!isShizukuAlive()) return "Shizuku 未运行"
        if (!isGranted()) return "未授权"
        return if (prefEnabled(ctx)) "已开启（ADB 权限）" else "已授权但未开启"
    }

    /** 详细状态（调试用） */
    fun statusDetail(ctx: Context): Map<String, Any> = mapOf(
        "installed" to isShizukuInstalled(ctx),
        "alive" to isShizukuAlive(),
        "granted" to isGranted(),
        "pref_enabled" to prefEnabled(ctx),
        "enabled" to isEnabled(ctx),
        "boot_keepalive" to bootKeepAlive(ctx),
        "shizuku_version" to runCatching { Shizuku.getVersion() }.getOrDefault(-1),
        "android" to Build.VERSION.SDK_INT,
    )
}