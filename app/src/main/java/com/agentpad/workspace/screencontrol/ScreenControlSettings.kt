package com.agentpad.workspace.screencontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object ScreenControlSettings {
    fun enabled(context: Context): Boolean = ScreenControlManager.isSupported &&
        context.getSharedPreferences("screen_control", Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("screen_control", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        ScreenControlManager.allowed.value = value && ScreenControlManager.isSupported
        if (!value) {
            ScreenControlNotificationService.stop(context)
            ScreenCaptureService.stop(context)
            ScreenControlManager.activeJob?.cancel()
            ScreenControlManager.setActing(false)
        }
    }

    fun serviceEnabled(context: Context): Boolean {
        val component = ComponentName(context, ScreenControlService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun openSettings(context: Context) {
        val component = ComponentName(context, ScreenControlService::class.java)
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.getOrElse {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
