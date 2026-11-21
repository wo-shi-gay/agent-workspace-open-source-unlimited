package com.agentpad.workspace.ai

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 聊天错误落盘。
 *
 * 为什么需要：以前流式断掉、chunk 解析失败、工具轮跑飞，都可能是
 * “什么都没显示就结束”，错误只存在内存里，重建 Activity 或进程被杀就消失。
 * 这里把 title/异常/堆栈追加到 filesDir/logs/chat_error.log，事后可直接看。
 *
 * 不做自动轮转（避免判断出错把用户日志删掉），需要时手动清。
 */
object ChatErrorLog {

    fun file(ctx: Context): File =
        File(File(ctx.filesDir, "logs").apply { mkdirs() }, "chat_error.log")

    fun record(ctx: Context, error: Throwable, tag: String = "") {
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val head = "[" + fmt.format(java.util.Date()) + "] " + tag +
                " " + error.javaClass.name + ": " + (error.message ?: "")
            file(ctx).appendText(head + "\n" + sw.toString().take(6000) + "\n\n")
        }
    }
}
