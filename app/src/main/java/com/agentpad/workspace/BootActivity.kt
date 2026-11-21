package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.Activity
import com.agentpad.workspace.ai.SandboxInstaller
import com.agentpad.workspace.data.MarketStore
import org.json.JSONObject

/**
 * Startup gate. On first launch (or when the sandbox rootfs is damaged) it
 * downloads the Debian ARM64 (slim) rootfs, showing a live progress bar, then
 * jumps to the home screen. When the environment is already healthy it
 * immediately proceeds so returning users never see a delay.
 */
class BootActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var progressBar: ProgressBar
    private lateinit var messageView: TextView
    private lateinit var percentView: TextView
    private lateinit var sourceView: TextView
    private var starting = false
    private var announcementShown = false
    private var downloading = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // 2026-11-13：终端「装不好」排查时完全看不到现场（App 私有目录
        // shell 读不到），只能靠猜。现在每次启动都先落一份取证。
        runCatching { com.agentpad.workspace.ai.SandboxDiagnoser.describe(this) }

        // ⚠⚠ 启动路径的铁律：**任何异常都不许挡住用户进 App**。
        // 2026-11-13 线上事故：repointRootfs 抛 NoSuchFileException，
        // 直接崩在 BootActivity.onCreate，清除数据后连 App 都打不开。
        // 沙箱没装好只是「AI 终端不可用」，App 本身必须能开。
        val ready = runCatching {
            SandboxInstaller.status(this) == SandboxInstaller.SandboxStatus.READY
        }.getOrDefault(false)

        // ⚠⚠ 脚本刷新必须放在 READY 判断**之前**执行，不能只在 ensureInstalled 里做。
        //   READY 时下面直接 enterMain() 返回，ensureInstalled 根本不会被调，
        //   于是 assets 里更新过的 init-sandbox.sh 永远到不了 files/ 下 ——
        //   2026-11-13 就是这么错过一次 Alpine busybox 修复的。
        //   这个函数内部自带「版本号/内容比对」，没变就是两次 readBytes，开销可忽略。
        runCatching { SandboxInstaller.refreshInitScript(this) }

        if (ready) {
            enterMain()
            return
        }
        buildUi()
        startDownload()
    }

    private fun startDownload() {
        if (downloading) return
        downloading = true
        val progress = object : SandboxInstaller.Progress {
            override fun onMessage(text: String) { ui.post { messageView.text = text } }
            override fun onProgress(percent: Int) { ui.post { progressBar.progress = percent; percentView.text = "$percent%" } }
        }
        Thread {
            // 沙箱下载/校验失败（断网、镜像站不可达）也要放行进主页，绝不能卡死在启动页。
            runCatching { SandboxInstaller.ensureInstalled(this, progress) }
            downloading = false
            ui.post { enterMain() }
        }.start()
    }

    private fun sourceLabel(id: String): String =
        SandboxInstaller.SOURCES.firstOrNull { it.id == id }?.label ?: "自动（推荐）"

    private fun showSourcePicker() {
        val sources = SandboxInstaller.SOURCES
        val labels = sources.map { "${it.label}\n${it.desc}" }.toTypedArray()
        val current = SandboxInstaller.selectedSourceId(this)
        val checked = sources.indexOfFirst { it.id == current }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("选择系统镜像下载源")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val picked = sources[which].id
                SandboxInstaller.selectSource(this, picked)
                sourceView.text = "下载源：" + sources[which].label
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(247, 248, 250))
            setPadding(dp(32), 0, dp(32), 0)
        }
        val rocket = TextView(this).apply {
            text = "🚀"
            textSize = 44f
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "正在准备 Debian 运行环境"
            textSize = 20f
            setTextColor(Color.rgb(16, 19, 26))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        messageView = TextView(this).apply {
            text = "检测沙箱状态…"
            textSize = 14f
            setTextColor(Color.rgb(125, 132, 146))
            gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        percentView = TextView(this).apply {
            text = "0%"
            textSize = 13f
            setTextColor(Color.rgb(82, 103, 232))
            gravity = Gravity.CENTER
        }
        val bottomHint = TextView(this).apply {
            text = "首次使用需下载约 25MB 的 Debian ARM64 系统镜像，请保持网络连接"
            textSize = 12f
            setTextColor(Color.rgb(150, 156, 170))
            gravity = Gravity.CENTER
        }
        root.addView(rocket)
        root.addView(title, lpTop(dp(18)))
        root.addView(messageView, lpTop(dp(10)))
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(34); bottomMargin = dp(10) })
        root.addView(percentView)
        root.addView(bottomHint, lpTop(dp(44)))

        // 下载源选择：显示当前源，点击弹出切换
        sourceView = TextView(this).apply {
            text = "下载源：" + sourceLabel(SandboxInstaller.selectedSourceId(this@BootActivity))
            textSize = 13f
            setTextColor(Color.rgb(82, 103, 232))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { showSourcePicker() }
        }
        root.addView(sourceView, lpTop(dp(24)))

        val batteryButton = TextView(this).apply {
            text = "开启后台运行权限"
            textSize = 13f
            setTextColor(Color.rgb(82, 103, 232))
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { requestBatteryOptimization() }
        }
        root.addView(batteryButton, lpTop(dp(6)))
        setContentView(root)
        // Kick a gentle entrance.
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(420).start()
    }

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun enterMain() {
        if (starting) return
        if (!announcementShown) { announcementShown = true; checkAnnouncements(); return }
        starting = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun checkAnnouncements() {
        Thread {
            // 断网/域名解析失败时 announcements() 会抛异常。绝不能让它把启动流程卡死：
            // 公告只是锦上添花，失败必须照常进主页。
            // ⚠ 必须传当前安装包的真实 versionCode：服务端据此过滤
            //   `target_version > version_code` 的公告，写死会导致
            //   最新版用户也被强制更新公告拦住。
            val list = runCatching { MarketStore.announcements(currentVersionCode()).second }
                .getOrDefault(emptyList())
            val prefs = getSharedPreferences("announcement_state", MODE_PRIVATE)
            val seen = prefs.getStringSet("seen_ids", emptySet()) ?: emptySet()
            val item = list.firstOrNull { a ->
                val id = a.optString("id")
                val targetVersion = a.optInt("target_version", 0)
                val isForce = a.optInt("force_update") == 1
                // 客户端再兜一层：目标版本不高于当前版本 = 我已经是最新的，跳过
                val newerThanMe = targetVersion == 0 || targetVersion > currentVersionCode()
                newerThanMe && (isForce || !seen.contains(id))
            }
            ui.post {
                if (item == null) { announcementShown = true; enterMain(); return@post }
                runCatching { showAnnouncement(item, prefs, seen) }.onFailure {
                    announcementShown = true; enterMain()
                }
            }
        }.start()
        // 兜底：网络请求最长 45s（连接15s + 读取30s）。超时也必须放行进主页，不能卡在启动页。
        ui.postDelayed({
            if (!starting) {
                announcementShown = true
                enterMain()
            }
        }, 46_000)
    }

    /** 当前安装包的真实 versionCode。 */
    private fun currentVersionCode(): Int = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    private fun showAnnouncement(item: JSONObject, prefs: android.content.SharedPreferences, seen: Set<String>) {
        val force = item.optInt("force_update") == 1
        val title = item.optString("title", "公告")
        val body = item.optString("body")
        val builder = android.app.AlertDialog.Builder(this).setTitle(title).setMessage(body)
        val url = item.optString("download_url").trim()
        if (url.isNotEmpty()) builder.setPositiveButton("立即更新") { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            if (!force) prefs.edit().putStringSet("seen_ids", seen + item.optString("id")).apply()
            if (!force) enterMain()
        }
        if (!force) builder.setNegativeButton("稍后") { _, _ ->
            prefs.edit().putStringSet("seen_ids", seen + item.optString("id")).apply(); enterMain()
        }
        if (force) builder.setCancelable(false)
        builder.show()
    }

    private fun requestBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}