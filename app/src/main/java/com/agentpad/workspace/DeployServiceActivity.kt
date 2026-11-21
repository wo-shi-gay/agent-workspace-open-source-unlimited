package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ai.DshService
import com.agentpad.workspace.ui.*
import kotlinx.coroutines.delay

/**
 * 部署服务 —— 给沙箱部署「可访问的服务」的入口页。
 *
 * 与「下载环境」的分工：
 *   · 下载环境：装工具链给 AI 用（Go / Node / NDK / 逆向链…），装完即完事
 *   · 部署服务：部署**长期运行、可对外访问**的服务，有运行状态 / 端口 / 配置
 *
 * ⚠ 当前状态：**骨架**。
 *   第一版尝试集成 Codex Web UI，卡在「codex CLI 无法创建会话」，
 *   已整体回滚（备份见工作区 _deployservice_codex_backup/）。
 *   具体踩坑与结论见《交接文档-2026-11-05-部署服务与Codex回滚.md》。
 *
 * 这一页保留三样东西，供下一个服务接入时直接复用：
 *   1. 服务卡片骨架（未部署 / 已部署 / 运行中 三态）
 *   2. 实时日志窗（可滚动、可清空、按关键字着色）
 *   3. 失败原因回传与「打开服务」快捷入口
 */
class DeployServiceActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val ctx = this@DeployServiceActivity

        // 骨架阶段只保留日志窗的自演示，不接任何真实服务
        var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
        val logScroll = rememberScrollState()
        val clip = LocalClipboardManager.current

        var status by remember { mutableStateOf(DshService.status(ctx)) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0) }

        DisposableEffect(Unit) {
            DshService.setLogListener { line ->
                logLines = (logLines + line).takeLast(400)
            }
            // 进页先刷一次已有日志
            if (logLines.isEmpty()) logLines = DshService.recentLog()
            onDispose { DshService.setLogListener(null) }
        }

        // 预热：进页就后台把 dsh 拉起来（已安装且未运行时），用户点按钮时往往已就绪
        LaunchedEffect(Unit) {
            val s0 = DshService.status(ctx)
            if (s0.installed && !s0.running) {
                Thread { DshService.start(ctx) }.start()
            }
        }

        // 定期刷新状态（运行中/停止后都要反映到卡片）
        LaunchedEffect(busy) {
            while (true) {
                status = DshService.status(ctx)
                delay(1500)
            }
        }

        // 新日志自动滚到底
        LaunchedEffect(logLines.size) {
            if (logLines.isNotEmpty()) logScroll.animateScrollTo(logScroll.maxValue)
        }

        SubPage(
            "部署服务",
            "给沙箱部署可访问的服务",
            onBack = { finish() },
        ) {

            // ---------- 服务列表 ----------
            SectionLabel("可用服务")
            Spacer(Modifier.height(2.dp))

            AgentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(
                        if (status.running) Icons.Outlined.PlayCircle else Icons.Outlined.Dns,
                        if (status.running) AColors.Success else AColors.Muted,
                    )
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(
                            "DeepSeek Harness",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink,
                        )
                        Text(
                            status.message,
                            fontSize = 12.sp, color = AColors.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (progress > 0 && progress < 100) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 主按钮：一键运行
                    ServiceAction(
                        text = when {
                            busy -> "处理中…"
                            !status.installed -> "下载并运行"
                            !status.running -> "启动服务"
                            else -> "打开"
                        },
                        primary = true,
                        enabled = !busy,
                    ) {
                        busy = true
                        Thread {
                            when {
                                !status.installed -> {
                                    val ok = DshService.install(ctx) { pct, _ ->
                                        progress = pct
                                    }
                                    if (ok) DshService.start(ctx)
                                }
                                !status.running -> DshService.start(ctx)
                            }
                            // 等就绪（dsh 首次启动要 30~40s 才打印 token，给足 180s）
                            val deadline = System.currentTimeMillis() + 180_000
                            while (System.currentTimeMillis() < deadline) {
                                val s = DshService.status(ctx)
                                if (s.running) {
                                    runOnUiThread {
                                        status = s
                                        busy = false
                                        progress = 0
                                        startActivity(
                                            android.content.Intent(
                                                ctx, DshWebActivity::class.java
                                            )
                                        )
                                    }
                                    return@Thread
                                }
                                Thread.sleep(400)
                            }
                            runOnUiThread {
                                status = DshService.status(ctx)
                                busy = false
                                progress = 0
                                logLines = DshService.recentLog()
                            }
                        }.start()
                    }

                    if (status.running) {
                        ServiceAction(text = "停止") {
                            DshService.stop()
                            status = DshService.status(ctx)
                        }
                    }
                }
            }

            // ---------- 实时日志（骨架保留）----------
            Spacer(Modifier.height(18.dp))
            SectionLabel("实时日志") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (logLines.isEmpty()) "空" else "${logLines.size} 行",
                        fontSize = 11.sp, color = AColors.Faint,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "复制",
                        fontSize = 12.sp, color = AColors.Accent,
                        modifier = Modifier
                            .clickable {
                                if (logLines.isNotEmpty()) {
                                    clip.setText(AnnotatedString(logLines.joinToString("\n")))
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "清空",
                        fontSize = 12.sp, color = AColors.Accent,
                        modifier = Modifier
                            .clickable { logLines = emptyList() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))

            AgentCard(
                background = Color(0xFF0D1117),
                border = AColors.LineSoft,
                padding = 0.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp)
                        .verticalScroll(logScroll)
                        .padding(12.dp),
                ) {
                    if (logLines.isEmpty()) {
                        Text(
                            "（部署服务运行后，这里显示实时输出）",
                            fontSize = 11.5.sp,
                            color = Color(0xFF6E7681),
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        logLines.forEach { line ->
                            Text(
                                line.ifBlank { " " },
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = logLineColor(line),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            // ---------- 说明 ----------
            Spacer(Modifier.height(18.dp))
            SectionLabel("说明")
            AgentCard(background = AColors.SurfaceAlt, border = AColors.LineSoft) {
                NoteRow("部署服务与「下载环境」分开：前者跑长期服务，后者装工具链")
                Spacer(Modifier.height(10.dp))
                NoteRow("服务只监听 127.0.0.1，局域网访问不到")
                Spacer(Modifier.height(10.dp))
                NoteRow("接入新服务时复用本页的三态卡片与日志窗，不要再写一套")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    /**
     * 日志行着色：
     *   错误/异常 → 红
     *   警告      → 黄
     *   成功/就绪 → 绿
     *   其它      → 灰
     */
    private fun logLineColor(line: String): Color {
        val l = line.lowercase()
        return when {
            l.contains("error") || l.contains("fail") || l.contains("exception") ||
                l.contains("cannot") || l.contains("not found") ->
                Color(0xFFFF7B72)
            l.contains("warn") -> Color(0xFFD29922)
            l.contains("ready") || l.contains("running") || l.contains("success") ->
                Color(0xFF3FB950)
            else -> Color(0xFF8B949E)
        }
    }

    @Composable
    private fun ServiceAction(
        text: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val bg = when {
            !enabled -> AColors.SurfaceAlt
            primary -> AColors.Accent
            else -> AColors.Surface
        }
        val fg = when {
            !enabled -> AColors.Faint
            primary -> Color.White
            else -> AColors.InkSoft
        }
        Box(
            Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(ADimens.ChipRadius))
                .background(bg)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(text, fontSize = 13.sp, color = fg, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun NoteRow(text: String) {
        Row(verticalAlignment = Alignment.Top) {
            Text("·", fontSize = 13.sp, color = AColors.Muted)
            Text(
                text, fontSize = 12.5.sp, color = AColors.InkSoft,
                lineHeight = 18.sp, modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}