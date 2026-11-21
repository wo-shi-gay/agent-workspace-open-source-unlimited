package com.agentpad.workspace.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 当前 AI 工作区内 APK 的扫描与系统安装入口。 */
object WorkspaceApkInstaller {
    data class ApkEntry(val file: File, val relativePath: String)

    fun scan(context: Context, sessionId: String): List<ApkEntry> {
        val root = ChatStore.ensureWorkspace(context, sessionId)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .map { file ->
                ApkEntry(file, runCatching { file.relativeTo(root).path }.getOrDefault(file.name))
            }
            .sortedBy { it.relativePath.lowercase() }
            .toList()
    }

    fun install(context: Context, file: File): Result<Unit> = runCatching {
        require(file.isFile && file.extension.equals("apk", true)) { "APK 文件不存在" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}