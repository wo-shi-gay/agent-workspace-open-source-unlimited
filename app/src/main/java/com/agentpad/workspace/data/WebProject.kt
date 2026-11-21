package com.agentpad.workspace.data

import android.content.Context
import java.io.File

/**
 * 网页项目：mode="web" 的会话会在工作区自动生成一个 [ENTRY]（index.html）模板，
 * AI 直接改它，用户可在聊天侧边栏预览 / 导出。
 */
object WebProject {

    /** 入口文件名（固定，预览与导出都找它） */
    const val ENTRY = "index.html"

    fun appName(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }
            .getOrDefault("Workspace")

    /** 入口文件（不保证存在） */
    fun entryFile(context: Context, sessionId: String): File =
        File(ChatStore.ensureWorkspace(context, sessionId), ENTRY)

    /** 确保模板存在（已存在则不覆盖，避免抹掉用户/AI 的改动）。返回入口文件。 */
    fun ensureTemplate(context: Context, sessionId: String): File {
        val f = entryFile(context, sessionId)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(template(appName(context)), Charsets.UTF_8)
        }
        return f
    }

    /** 初始模板：屏幕正中大字显示 App 名 + 牛逼666。 */
    fun template(appName: String): String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>$appName</title>
<style>
  :root {
    --bg1: #5267E8;
    --bg2: #7C5CF0;
    --ink: #ffffff;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif;
    background: linear-gradient(135deg, var(--bg1), var(--bg2));
    color: var(--ink);
    overflow: hidden;
  }
  .stage {
    text-align: center;
    padding: 32px;
    animation: rise .8s cubic-bezier(.2,.8,.2,1) both;
  }
  .app-name {
    font-size: clamp(28px, 9vw, 72px);
    font-weight: 800;
    letter-spacing: .02em;
    line-height: 1.15;
    text-shadow: 0 6px 30px rgba(0,0,0,.22);
  }
  .badge {
    display: inline-block;
    margin-top: 22px;
    padding: 10px 26px;
    font-size: clamp(18px, 4.5vw, 30px);
    font-weight: 800;
    letter-spacing: .16em;
    border-radius: 999px;
    background: rgba(255,255,255,.16);
    border: 1.5px solid rgba(255,255,255,.38);
    backdrop-filter: blur(6px);
    text-shadow: 0 3px 16px rgba(0,0,0,.25);
  }
  .hint {
    margin-top: 42px;
    font-size: 13px;
    opacity: .62;
    letter-spacing: .06em;
  }
  @keyframes rise {
    from { opacity: 0; transform: translateY(26px) scale(.96); }
    to   { opacity: 1; transform: translateY(0) scale(1); }
  }
</style>
</head>
<body>
  <div class="stage">
    <div class="app-name">$appName</div>
    <div class="badge">牛逼666</div>
    <div class="hint">对 AI 说「把网页改成⋯⋯」即可实时修改</div>
  </div>
</body>
</html>
"""
}