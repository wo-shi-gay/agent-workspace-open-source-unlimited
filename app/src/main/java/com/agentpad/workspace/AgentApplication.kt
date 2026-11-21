package com.agentpad.workspace

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 日志中心最先启动（后台持续收集，与界面无关）
        runCatching { com.agentpad.workspace.data.AppLog.start(this) }
        // 恢复用户上次选择的主题（主色调 + 深色模式）
        runCatching { com.agentpad.workspace.ui.ThemeStore.load(this) }
        installCrashReporter()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var foregroundActivities = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                foregroundActivities++
                com.agentpad.workspace.screencontrol.ScreenControlManager.selfForeground = true
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                com.agentpad.workspace.screencontrol.ScreenControlManager.selfForeground = foregroundActivities > 0
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // 若用户此前启用了 MCP 服务，进程启动时自动拉起监听
        runCatching { com.agentpad.workspace.ai.McpServerManager.startIfEnabled(this) }
        // 通知常驻保活（默认开启，用户可在设置里关）：前台服务 + 常驻通知，
        // 不需要任何特殊权限，是「不申请无障碍」时最稳的保活手段。
        runCatching { KeepAliveNotificationService.startIfEnabled(this) }
        // ⚠ 2026-11-09：把旧 SharedPreferences 里的历史会话搬进分片目录（后台线程、幂等）。
        // 只跑一次，标记在 prefs 的 room_migrated_v1。升级用户不丢任何会话。
        runCatching { com.agentpad.workspace.data.ChatStore.migratePrefsToRoom(this) }
        // ⚠ 2026-11-15：内置技能包与 Operit 技能体系已整套删除，改为照抄 RikkaHub 3.2.0 的
        //   data/skills/（SkillManager + SkillValidator + SkillFrontmatterParser + BuiltinSkills）。
        //   skill 只有两个来源：① 用户在「插件与技能 → 技能」里手动导入（文件 / GitHub）；
        //   ② AI 用 create_skill 制作。启动时无需任何解压动作。
        // Sandbox setup (download + extract + integrity check) is handled by the
        // BootActivity launcher gate with a progress bar. Nothing blocking here.
    }

    /**
     * 系统白天/黑夜切换时回调（Android 10+ 才有意义）。
     * 仅在用户开启「跟随系统」时刷新色板，Android 8/9 上此回调不产生深色变化。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { com.agentpad.workspace.ui.ThemeStore.applySystemDark(this) }
    }

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // InterruptedIOException from aborted process readers is benign —
            // never treat it (or any IO error on background threads) as fatal.
            if (thread.name != "main" && throwable is java.io.IOException) {
                android.util.Log.w("AgentWorkspace", "background io error ignored on ${thread.name}", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            writeCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable) ?: Runtime.getRuntime().exit(10)
        }
    }

    private fun writeStartupError(error: Throwable) {
        writeText("startup_latest.txt", "startup initialization failed\n\n${error.stackTraceToString()}")
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        // 写入统一日志中心（后台可见、可导出）
        runCatching { com.agentpad.workspace.data.AppLog.crash(thread, throwable) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        writeText("crash_$stamp.txt", report(thread, throwable))
        writeText("latest.txt", report(thread, throwable))
    }

    private fun report(thread: Thread, throwable: Throwable) = buildString {
        appendLine("Workspace crash report")
        appendLine("time=${System.currentTimeMillis()}")
        appendLine("thread=${thread.name}")
        appendLine("package=$packageName")
        appendLine("version=${appVersion()}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine(throwable.stackTraceToString())
    }

    /** 从安装包读版本，避免这里写死导致与 build.gradle.kts 脱节。 */
    private fun appVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "unknown" }

    private fun writeText(name: String, content: String) {
        // Primary: /sdcard/Android/data/com.agentpad.workspace/ (user-requested,
        // browseable with any file manager). Fallback: app-internal crash dir.
        val primary = File(android.os.Environment.getExternalStorageDirectory(), "Android/data/$packageName")
        val internal = File(filesDir, "crash")
        listOf(primary, internal).forEach { directory ->
            runCatching {
                directory.mkdirs()
                File(directory, name).writeText(content, Charsets.UTF_8)
            }
        }
    }
}