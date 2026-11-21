package com.agentpad.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.ui.*

/**
 * 主题与配色页：主色调选择（7 色）+ 经典深色模式开关。
 * 改动 ThemeStore.palette 后，全局界面（含底部导航、聊天页）自动重组。
 */
class ThemeActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            AgentTheme {
                Content()
            }
        }
    }

    @Composable
    private fun Content() {
        var dark by remember { mutableStateOf(ThemeStore.isDark()) }
        var accentId by remember { mutableStateOf(ThemeStore.accentId()) }
        var follow by remember { mutableStateOf(ThemeStore.isFollowSystem()) }
        val supportsFollow = remember { ThemeStore.supportsFollowSystem() }

        SubPage("主题与配色", "全局生效，所有界面同步切换", onBack = { finish() }) {

            SectionLabel("界面布局")
            AgentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("旧款 UI", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text("开启后恢复原首页、底部导航与会话工具侧栏；关闭后使用会话首页和左侧会话管理。",
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    Switch(checked = ThemeStore.legacyUi.value,
                        onCheckedChange = { ThemeStore.setLegacyUi(this@ThemeActivity, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AColors.Accent))
                }
            }
            Spacer(Modifier.height(20.dp))

            // 跟随系统（仅 Android 10+ 显示；Android 8/9 无系统深色能力）
            if (supportsFollow) {
                SectionLabel("显示模式")
                AgentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                                .background(AColors.Accent.copy(alpha = .13f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DarkMode, contentDescription = null,
                                tint = AColors.Accent, modifier = Modifier.size(21.dp),
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Text("跟随系统深色", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            Text(
                                "随系统白天/黑夜自动切换",
                                fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Switch(
                            checked = follow,
                            onCheckedChange = { v ->
                                follow = v
                                ThemeStore.setFollowSystem(this@ThemeActivity, v)
                                dark = ThemeStore.isDark()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AColors.Accent,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                SectionLabel("显示模式")
            }

            // 深色模式
            AgentCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (dark) AColors.Violet.copy(alpha = .13f) else AColors.Amber.copy(alpha = .13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = null,
                            tint = if (dark) AColors.Violet else AColors.Amber,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text("深色模式", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                        Text(
                            when {
                                follow -> "已由系统控制"
                                dark -> "经典暗色 · 护眼省电"
                                else -> "明亮模式"
                            },
                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = dark,
                        enabled = !follow,
                        onCheckedChange = { v ->
                            dark = v
                            ThemeStore.setDark(this@ThemeActivity, v)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AColors.Accent,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 主色调
            SectionLabel("主色调")
            AgentCard(padding = 14.dp) {
                AccentTheme.entries.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { theme ->
                            val selected = theme.id == accentId
                            Box(Modifier.weight(1f)) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                                        .background(if (selected) AColors.AccentSoft else AColors.SurfaceAlt)
                                        .border(
                                            1.5.dp,
                                            if (selected) AColors.Accent else AColors.Line,
                                            RoundedCornerShape(ADimens.CardRadiusSmall),
                                        )
                                        .clickable {
                                            accentId = theme.id
                                            ThemeStore.selectAccent(this@ThemeActivity, theme)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(26.dp).clip(CircleShape)
                                            .background(Brush.linearGradient(listOf(Color(theme.main), Color(theme.deep)))),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) {
                                            Icon(Icons.Outlined.Check, contentDescription = null,
                                                tint = Color.White, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    Text(
                                        theme.label,
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) AColors.Accent else AColors.Ink,
                                        modifier = Modifier.padding(start = 10.dp),
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 预览
            SectionLabel("预览")
            AgentCard {
                Text("让 Agent 替你推进工作", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
                Text("这是正文示例，配色会随主题实时变化。",
                    fontSize = 13.sp, color = AColors.InkSoft, modifier = Modifier.padding(top = 6.dp), lineHeight = 19.sp)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag("已上架", AColors.Success, AColors.SuccessSoft)
                    Tag("待审核", AColors.Warning, AColors.WarningSoft)
                    Tag("已拒绝", AColors.Danger, AColors.DangerSoft)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("切换后立即生效，无需重启。", fontSize = 12.sp, color = AColors.Faint,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}