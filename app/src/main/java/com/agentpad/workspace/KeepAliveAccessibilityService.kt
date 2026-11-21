package com.agentpad.workspace

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.agentpad.workspace.data.AppLog

/**
 * 无障碍保活服务。
 *
 * ⚠ 2026-11-04 新增。**只做保活，不读界面、不模拟操作。**
 *
 * 原理：
 *   系统绑定无障碍服务后，会把这个进程按「服务提供者」对待，
 *   oom_adj 明显低于普通后台进程，常规后台清理不会杀它。
 *   免 root 场景下这是最稳定的保活方式之一。
 *
 * 与 [AdbKeepAliveService] 的关系：
 *   - ADB 保活：需要 Shizuku/ADB 授权，能执行 am 命令，功能强但门槛高
 *   - 无障碍保活：只需用户在系统设置里手动开启，门槛低、更稳
 *   两者可同时开启，互不冲突；本服务不做任何特权操作。
 *
 * 隐私：`canRetrieveWindowContent="false"`（见 res/xml/a11y_keepalive.xml），
 *       这里也**不消费**任何事件内容，`onAccessibilityEvent` 直接返回。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 检查无障碍服务是否已在系统设置里启用。
         *
         * 用 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判断，
         * 因为 `AccessibilityManager.getEnabledAccessibilityServiceList`
         * 在部分 ROM 上对未绑定服务返回空，检测不准。
         */
        fun isEnabled(context: Context): Boolean = runCatching {
            val expected = ComponentName(context, KeepAliveAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            enabled.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.packageName == expected.packageName && cn.className == expected.className
            }
        }.getOrDefault(false)

        /**
         * 打开系统无障碍设置页。
         *
         * Android 没有「直接开启某个无障碍服务」的公开 API（安全限制），
         * 只能把用户送到设置页，由用户手动勾选。这里尽量直接定位到本服务详情页，
         * 失败则退回无障碍列表页。
         */
        fun openSettings(context: Context) {
            // 优先：直接跳到本服务的详情页（部分 ROM 支持）
            val detail = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", componentFlatten(context))
                putExtra(":settings:show_fragment_args", componentFlatten(context))
            }
            val ok = runCatching { context.startActivity(detail); true }.getOrDefault(false)
            if (!ok) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { AppLog.w("A11y", "无法打开无障碍设置: ${it.message}") }
            }
        }

        private fun componentFlatten(context: Context): String =
            ComponentName(context, KeepAliveAccessibilityService::class.java).flattenToString()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 只保留「窗口状态变化」这一种事件，且实际不消费，纯粹为了保持绑定
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            // 明确不申请读取窗口内容的能力
            flags = flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
        }
        AppLog.i("A11y", "无障碍保活服务已连接")
    }

    /**
     * 不消费任何事件。
     *
     * 这是刻意的：我们只要「服务被系统绑定」这个状态带来的进程优先级，
     * 不关心用户在看什么界面。留空可以保证零隐私风险与零性能开销。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.i("A11y", "无障碍保活服务已断开（用户关闭或系统解绑）")
        return super.onUnbind(intent)
    }
}