package com.agentpad.workspace.data

import android.content.Context
import java.io.File

/**
 * 视频项目：mode="video" 的会话会在工作区自动生成一个「视频项目」模板
 * （project.json / render.py / build.sh / PROMPT.md / AGENT.md / assets 字体），
 * AI 直接在上面改，用户在聊天侧边栏「预览视频 / 导出视频」。
 *
 * 产物固定为 out.mp4（预览与导出都找它）。
 */
object VideoProject {

    /** 产物文件名（固定，预览与导出都找它） */
    const val OUT = "out.mp4"
    /** 场景脚本 */
    const val PROJECT_JSON = "project.json"
    /** 中文字体（内置，原版工具链不带可用中文字体 → 中文会变方块） */
    const val FONT_NAME = "ZiTiQuanWeiJunHei-W1-2.ttf"

    fun outFile(context: Context, sessionId: String): File =
        File(ChatStore.ensureWorkspace(context, sessionId), OUT)

    /** 确保模板存在（已存在则不覆盖，避免抹掉用户/AI 的改动）。 */
    fun ensureTemplate(context: Context, sessionId: String) {
        val root = ChatStore.ensureWorkspace(context, sessionId)
        root.mkdirs()
        writeIfAbsent(File(root, PROJECT_JSON), projectJson())
        writeIfAbsent(File(root, "render.py"), renderPy())
        writeIfAbsent(File(root, "build.sh"), buildSh())
        writeIfAbsent(File(root, "PROMPT.md"), promptMd())
        writeIfAbsent(File(root, "AGENT.md"), agentMd())
        // 内置中文字体（项目内副本），保证中文不会渲染成方块
        val assetsDir = File(root, "assets").apply { mkdirs() }
        val fontFile = File(assetsDir, FONT_NAME)
        if (!fontFile.exists()) {
            runCatching {
                context.assets.open("video/$FONT_NAME").use { input ->
                    fontFile.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun writeIfAbsent(f: File, text: String) {
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------- 模板内容

    fun projectJson(): String = """
{
  "projectType": "video",
  "title": "视频项目",
  "width": 1280,
  "height": 720,
  "fps": 24,
  "output": "out.mp4",
  "font": "assets/$FONT_NAME",
  "scenes": [
    {
      "type": "title",
      "text": "从零生成视频",
      "subtitle": "视频项目 · 基座演示",
      "bg": "#0e1526",
      "fg": "#ffffff",
      "accent": "#5267E8",
      "duration": 3.0
    },
    {
      "type": "text",
      "text": "这是视频项目的基座。\nAI 只需修改 project.json 里的 scenes，再运行 build.sh 即可得到新的 out.mp4。",
      "bg": "#101725",
      "fg": "#e8eefc",
      "accent": "#5267E8",
      "footer": "视频项目",
      "duration": 4.0
    },
    {
      "type": "text",
      "text": "支持中文字体：全集黑 W1\n逐帧渲染 · H.264 编码 · 侧边栏可预览",
      "bg": "#141b2e",
      "fg": "#ffffff",
      "accent": "#5ad1a8",
      "footer": "视频项目",
      "duration": 3.5
    },
    {
      "type": "title",
      "text": "开始改动吧",
      "subtitle": "改 scenes → 重新渲染",
      "bg": "#0e1526",
      "fg": "#ffffff",
      "accent": "#ffb454",
      "duration": 2.5
    }
  ]
}
""".trimIndent()

    fun buildSh(): String = """#!/bin/bash
# 视频项目构建脚本：渲染 project.json -> out.mp4
# 依赖「视频工具链」（pypkgs + 字体），安装位置见 PROMPT.md
set -eu

PROJ="$(cd "$(dirname "$0")" && pwd)"

# 1) 定位渲染环境（按优先级）
if [ -n "${'$'}{VIDEO_BUILDCHAIN:-}" ] && [ -d "${'$'}{VIDEO_BUILDCHAIN}/pypkgs" ]; then
  BC="${'$'}VIDEO_BUILDCHAIN"
elif [ -d "/opt/video-buildchain/pypkgs" ]; then
  BC="/opt/video-buildchain"
else
  BC=""
fi

if [ -z "${'$'}BC" ]; then
  echo "======================================================"
  echo "[视频项目] 未检测到视频工具链（moviepy / ffmpeg / 中文字体）。"
  echo "请到 App 的『设置 → 下载环境』里下载并安装『视频工具链』，"
  echo "或运行视频工具链包里的 install.sh 完成安装。"
  echo "======================================================"
  exit 2
fi

# 2) 字体：项目自带优先，否则用工具链字体
FONT="${'$'}PROJ/assets/$FONT_NAME"
if [ ! -f "${'$'}FONT" ]; then
  FONT="${'$'}(ls "${'$'}BC"/fonts/*.ttf 2>/dev/null | head -1 || true)"
fi
export VIDEO_FONT="${'$'}{VIDEO_FONT:-${'$'}FONT}"
export PYTHONPATH="${'$'}BC/pypkgs${'$'}{PYTHONPATH:+:${'$'}PYTHONPATH}"

# 3) 渲染（逐帧流式，内存恒定）
echo "[视频项目] 使用工具链: ${'$'}BC"
echo "[视频项目] 字体: ${'$'}VIDEO_FONT"
exec python3 "${'$'}BC/project/render.py" "${'$'}PROJ" -o "${'$'}PROJ/out.mp4"
"""

    /** 与 _vd/render.py 同步的逐帧流式引擎。 */
    fun renderPy(): String = """
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# render.py — 视频项目渲染引擎（从零生成视频）
#
# 工作方式（逐帧流式，内存恒定）:
#   每一帧用 Pillow 画好后，直接把原始 RGB 字节通过管道喂给 ffmpeg。
#   ffmpeg 一边收一边编码，Python 端永远只持有一个帧缓冲。
#   内存峰值 ≈ 一帧大小（1280x720x3 ≈ 2.7MB），绝不会爆内存。
#
# 用法: PYTHONPATH=<buildchain>/pypkgs python3 render.py [project_dir] [-o out.mp4]
import os
import json
import glob
import shutil
import argparse
import subprocess
import time

from PIL import Image, ImageDraw, ImageFont

try:
    import imageio_ffmpeg
    FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
except Exception:
    FFMPEG = shutil.which("ffmpeg") or "ffmpeg"


def resolve_font(explicit=None):
    cands = []
    if explicit:
        cands.append(explicit)
    if os.environ.get("VIDEO_FONT"):
        cands.append(os.environ["VIDEO_FONT"])
    proj = os.path.dirname(os.path.abspath(__file__))
    cands += glob.glob(os.path.join(proj, "assets", "*.ttf"))
    cands += glob.glob(os.path.join(proj, "assets", "*.otf"))
    bc = os.environ.get("VIDEO_BUILDCHAIN", "/opt/video-buildchain")
    cands += glob.glob(os.path.join(bc, "fonts", "*.ttf"))
    for c in cands:
        if c and os.path.exists(c):
            return c
    raise SystemExit("[render] 找不到中文字体。请设置 VIDEO_FONT，或把字体放到项目 assets/ 下。")


def load_font(path, size):
    return ImageFont.truetype(path, size)


def wrap_text(draw, text, font, max_width):
    lines = []
    for raw in text.split("\n"):
        if not raw:
            lines.append("")
            continue
        cur = ""
        for ch in raw:
            if draw.textlength(cur + ch, font=font) <= max_width:
                cur += ch
            else:
                lines.append(cur)
                cur = ch
        lines.append(cur)
    return lines


def hex_to_rgb(s, default=(0, 0, 0)):
    if not s:
        return default
    s = s.lstrip("#")
    if len(s) == 3:
        s = "".join(c * 2 for c in s)
    try:
        return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))
    except Exception:
        return default


def draw_scene(scene, W, H, font_path, t_ratio=0.0):
    bg = hex_to_rgb(scene.get("bg", "#101725"), (16, 23, 37))
    fg = hex_to_rgb(scene.get("fg", "#ffffff"), (255, 255, 255))
    accent = hex_to_rgb(scene.get("accent", "#5267E8"), (82, 103, 232))
    img = Image.new("RGB", (W, H), bg)
    draw = ImageDraw.Draw(img)
    stype = scene.get("type", "text")
    text = scene.get("text", "")
    draw.rectangle([0, 0, W, 6], fill=accent)
    if stype == "title":
        size = int(scene.get("font_size", H * 0.11))
        font = load_font(font_path, size)
        lines = wrap_text(draw, text, font, int(W * 0.86))
        line_h = size * 1.35
        total = line_h * len(lines)
        y = (H - total) / 2
        for ln in lines:
            w = draw.textlength(ln, font=font)
            draw.text(((W - w) / 2, y), ln, font=font, fill=fg)
            y += line_h
        sub = scene.get("subtitle", "")
        if sub:
            sfont = load_font(font_path, int(size * 0.45))
            sw = draw.textlength(sub, font=sfont)
            draw.text(((W - sw) / 2, y + 10), sub, font=sfont, fill=accent)
    else:
        size = int(scene.get("font_size", H * 0.062))
        font = load_font(font_path, size)
        margin_x = int(W * 0.08)
        margin_y = int(H * 0.14)
        lines = wrap_text(draw, text, font, W - 2 * margin_x)
        line_h = size * 1.5
        y = margin_y
        for ln in lines:
            draw.text((margin_x, y), ln, font=font, fill=fg)
            y += line_h
            if y > H - margin_y:
                break
    wf = load_font(font_path, max(14, int(H * 0.032)))
    footer = scene.get("footer", "")
    if footer:
        fw = draw.textlength(footer, font=wf)
        draw.text((W - fw - int(W * 0.04), H - int(H * 0.07)), footer, font=wf, fill=accent)
    if t_ratio < 0.12:
        k = max(0.0, min(1.0, t_ratio / 0.12))
        img = Image.blend(Image.new("RGB", (W, H), (0, 0, 0)), img, k)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("project_dir", nargs="?", default=".")
    ap.add_argument("-o", "--output", default=None)
    ap.add_argument("--fps", type=int, default=None)
    ap.add_argument("--crf", type=int, default=23)
    ap.add_argument("--preset", default="veryfast")
    ap.add_argument("--no-fade", action="store_true")
    args = ap.parse_args()

    pdir = os.path.abspath(args.project_dir)
    pj = os.path.join(pdir, "project.json")
    if not os.path.exists(pj):
        raise SystemExit("[render] 未找到 " + pj)
    with open(pj, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    W = int(cfg.get("width", 1280))
    H = int(cfg.get("height", 720))
    fps = int(args.fps or cfg.get("fps", 24))
    scenes = cfg.get("scenes", [])
    if not scenes:
        raise SystemExit("[render] project.json 里 scenes 为空")

    font_path = resolve_font(cfg.get("font"))
    total_frames = sum(max(1, int(round(float(s.get("duration", 3.0)) * fps))) for s in scenes)
    print("[render] %dx%d @ %dfps, %d 个场景, 共 %d 帧" % (W, H, fps, len(scenes), total_frames))
    print("[render] 字体: " + font_path)
    print("[render] ffmpeg: " + FFMPEG)

    out = args.output or os.path.join(pdir, cfg.get("output", "out.mp4"))
    FADE = not args.no_fade

    cmd = [FFMPEG, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
           "-s", "%dx%d" % (W, H), "-r", str(fps), "-i", "-", "-an",
           "-c:v", "libx264", "-preset", args.preset, "-crf", str(args.crf),
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", out]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    written = 0
    t0 = time.time()
    try:
        for i, sc in enumerate(scenes):
            dur = float(sc.get("duration", 3.0))
            n = max(1, int(round(dur * fps)))
            print("[render] 场景 %d/%d: %s %ss -> %d 帧" % (i + 1, len(scenes), sc.get("type", "text"), dur, n))
            for fi in range(n):
                ratio = (fi / max(1, n)) if FADE else 0.0
                img = draw_scene(sc, W, H, font_path, ratio)
                proc.stdin.write(img.tobytes())
                written += 1
                if written % 48 == 0 or written == total_frames:
                    el = time.time() - t0
                    rate = written / el if el > 0 else 0
                    print("[render]   %d/%d 帧 (%.1f fps)" % (written, total_frames, rate))
    except BrokenPipeError:
        err = proc.stderr.read().decode("utf-8", "ignore") if proc.stderr else ""
        proc.wait()
        raise SystemExit("[render] ffmpeg 管道中断:\n" + err)
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass

    rc = proc.wait()
    if rc != 0:
        err = proc.stderr.read().decode("utf-8", "ignore") if proc.stderr else ""
        raise SystemExit("[render] ffmpeg 退出码 %d:\n%s" % (rc, err))

    size = os.path.getsize(out)
    print("[render] OK -> %s (%d bytes, %d 帧, 用时 %.1fs)" % (out, size, written, time.time() - t0))


if __name__ == "__main__":
    main()
"""

    fun promptMd(): String = """
# 视频项目 · 内置提示词（给 AI）

> 这份文件位于「视频项目」工作区里，AI 打开视频会话时应优先阅读它。

## 当前项目类型

**视频项目**（`projectType = "video"`）。这是一个「从零生成视频」的会话工作区：

- 场景脚本：`project.json`
- 渲染引擎：`render.py`（逐帧流式：每帧直接喂给 ffmpeg，内存恒定 ≈ 一个帧缓冲）
- 一键渲染：`build.sh`
- 产物：**`out.mp4`**（固定名字，侧边栏据此预览 / 导出）

## 渲染方法（核心：你教用户，也照这个做）

### 数据流（你只碰第 1 步）
`project.json`（**你写的场景脚本**）→ `render.py`（逐帧画图并喂给 ffmpeg）→ `out.mp4`（成片）。
**渲染逻辑在 render.py 里，你不用改、也不该改**；你只改 `project.json`。

### project.json 结构
```json
{
  "width": 1280, "height": 720, "fps": 24,
  "output": "out.mp4",
  "font": "assets/<中文字体>.ttf",
  "scenes": [ /* 每个元素是一个场景 */ ]
}
```
单个场景对象的字段：
- `type`：`"title"`（大标题 + 副标题，自动居中）或 `"text"`（正文，从左上角排）。
- `text`：主文本；用 `\n` 手动换行（超宽会自动折行）。
- `subtitle`：仅 `title` 用，副标题。
- `bg` / `fg` / `accent`：背景色 / 正文色 / 强调色，`#RRGGBB`（顶栏、副标题、页脚用 accent）。
- `footer`：右下角小字水印（可选）。
- `duration`：该场景显示**秒数**（数字，如 3.0）。
- `font_size`：可选，覆盖默认字号。

### 三种常见改法
1. **改文案**：改对应场景的 `text` / `subtitle`。
2. **加/删场景**：往 `scenes` 数组增删对象（复制一个再改最省事）。
3. **调样式/时长**：改 `bg/fg/accent/duration`；整片节奏改 `fps`（一般保持 24）。

### 渲染（怎么跑）
```bash
cd "<工作区绝对路径>"   # 就是系统提示「工作区指南」里给出的那个根目录
bash build.sh        # 产物固定写到工作区根目录下的 out.mp4
```
- `build.sh` 会**自动定位工具链**（默认 `/opt/video-buildchain`），你不用手动设环境变量。
- 渲染是**逐帧流式**：画一帧喂一帧给 ffmpeg，**内存恒定（≈一帧，几十 MB）**，不会爆内存、也不会慢到卡死。
- **一次把整段渲染完**，严禁：拆成很多小段反复渲染、为"提速"手写 ffmpeg 命令绕过 `render.py`、改 `out.mp4` 文件名。
- 想更快可以传参：`bash build.sh` 等价于 `python3 render.py . -o out.mp4`；需要时可加 `--fps 30`、`--crf 28`（更小体积）、`--preset ultrafast`（更快）。

### 渲染完成后
告诉用户：
> 「视频已渲染好。点聊天侧边栏（☰）的『预览视频』就能看效果，『导出视频』可以把成片导到手机下载目录。」

## 渲染耗时（预期，别乱猜）

1280×720 @ 24fps：每秒视频 24 帧，一帧几十毫秒，**几十秒的视频通常 10~60 秒**渲染完。
如果远超这个量级还没结束，多半是工具链/字体没装好——去看 `build.sh` 的报错，**不要盲目重复重试**。

## 工具链依赖（重要）

渲染依赖「**视频工具链**」（moviepy + ffmpeg + 中文字体），安装位置固定为
`/opt/video-buildchain`（也支持环境变量 `VIDEO_BUILDCHAIN` 指向别处）。

**如果工具链不存在**（例如 `import moviepy` 失败、`build.sh` 退出码为 2、找不到
`/opt/video-buildchain/pypkgs`）：

- **不要**尝试用 pip / apt 现场安装（本环境不适用，大概率失败）。
- **必须明确告诉用户**：
  > 「当前缺少视频工具链。请到 App 的『设置 → 下载环境』里下载并安装『视频工具链』，
  > 安装完成后我再继续渲染这个视频项目。」
- 说明后即停下，等工具链就绪再继续。

## 命令速查

```bash
# 渲染（推荐，build.sh 会自动定位工具链）
VIDEO_BUILDCHAIN=/opt/video-buildchain bash build.sh

# 手动调用渲染引擎
VIDEO_BUILDCHAIN=/opt/video-buildchain \
PYTHONPATH=/opt/video-buildchain/pypkgs \
python3 /opt/video-buildchain/project/render.py . -o out.mp4
```

## 中文字体

`$FONT_NAME`（全集黑 W1）。位置：

- 项目内：`assets/$FONT_NAME`
- 工具链：`/opt/video-buildchain/fonts/`

已内置该字体，中文不会渲染成方块。

## 扩展方向（用户要求时再做）

- 更多场景类型（图片、多栏、字幕条、进度动画）
- 配乐 / 旁白（ffmpeg 已支持 aac；可接入 TTS 生成音频再 mux）
- 转场 / 淡入淡出
- 导出多种分辨率或 GIF
""".trimIndent()

    fun agentMd(): String = """
# 视频项目 · 内置提示词

本项目是 App 的「**视频项目**」，用于**从零生成视频**。

## 项目结构

- `project.json` — 场景脚本（**改视频 = 改这里**）
- `render.py` — 渲染引擎（逐帧流式：Pillow 画一帧 → 立即喂给 ffmpeg，内存恒定）
- `build.sh` — 一键渲染
- `out.mp4` — **渲染产物（固定名字，侧边栏据此预览 / 导出）**
- `assets/$FONT_NAME` — 中文字体

## 如何改视频

1. 编辑 `project.json` 的 `scenes`（每项含 `type/text/subtitle/bg/fg/accent/footer/duration`）。
2. 运行渲染：`bash build.sh`
3. 产物写入项目根目录 **`out.mp4`**。

> ⚠️ **不要修改 `out.mp4` 这个文件名**，否则侧边栏预览和导出都会失效。
> ⚠️ 渲染是逐帧流式、一次整段完成；**不要**拆成多段反复渲染，也不要绕过 render.py 自己写 ffmpeg。

## 预览与导出

- 聊天侧边栏（☰）→「**预览视频**」：在 App 内直接播放 `out.mp4`，同一页可「导出视频」。
- 文件管理页的会话菜单也有「视频预览 / 导出」入口。

## 工具链依赖（重要）

渲染需要「**视频工具链**」（moviepy + ffmpeg + 中文字体），安装位置为
`/opt/video-buildchain`（或环境变量 `VIDEO_BUILDCHAIN` 指向的目录）。

**如果没有工具链**（`import moviepy` 失败 / `build.sh` 退出码为 2 / 找不到
`/opt/video-buildchain/pypkgs`）：

- 不要用 pip / apt 现场安装（本环境不适用）。
- **必须告诉用户**：
  > 「当前缺少视频工具链。请到 App 的『设置 → 下载环境』里下载并安装『视频工具链』，
  > 安装完成后我再继续渲染这个视频项目。」
- 说明后即停止，等工具链就绪。

## 命令

```bash
# 渲染（推荐，build.sh 会自动定位工具链）
VIDEO_BUILDCHAIN=/opt/video-buildchain bash build.sh

# 直接调用引擎
VIDEO_BUILDCHAIN=/opt/video-buildchain \\
PYTHONPATH=/opt/video-buildchain/pypkgs \\
python3 /opt/video-buildchain/project/render.py . -o out.mp4
```
""".trimIndent()
}