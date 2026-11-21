package com.agentpad.workspace

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*

/**
 * 登录 / 注册（Compose 重构版）。
 * 登录：用户名 + 密码；注册：用户名 + 邮箱 + 验证码 + 密码。
 */
class AuthActivity : ComponentActivity() {

    private val mode = mutableStateOf("login") // login | register | reset
    private val username = mutableStateOf("")
    private val email = mutableStateOf("")
    private val code = mutableStateOf("")
    private val password = mutableStateOf("")
    private val busy = mutableStateOf(false)
    private val status = mutableStateOf<String?>(null)
    private val statusIsError = mutableStateOf(true)
    private val cooldown = mutableStateOf(0)

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AgentTheme { Content() } }
    }

    @Composable
    private fun Content() {
        AgentPage {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxSize()) {
                TopBar(when (mode.value) { "login" -> "登录"; "register" -> "注册"; else -> "找回密码" }, "连接你的 Workspace 账号", 
                    onBack = { finish() })

                // 登录/注册切换；找回密码模式隐藏分段
                if (mode.value != "reset") Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                        .background(AColors.SurfaceAlt).padding(3.dp),
                ) {
                    listOf("login" to "登录", "register" to "注册").forEach { (key, label) ->
                        val active = mode.value == key
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                                .background(if (active) AColors.Surface else AColors.SurfaceAlt)
                                .clickable { if (!active) switchMode(key) }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) AColors.Accent else AColors.Muted)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                if (mode.value == "reset") {
                    AgentTextField(email.value, { email.value = it }, "注册邮箱", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { AgentTextField(code.value, { code.value = it }, "6 位验证码", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.height(56.dp).clip(RoundedCornerShape(ADimens.CardRadiusSmall)).background(if (cooldown.value > 0) AColors.SurfaceAlt else AColors.AccentSoft).clickable(enabled = cooldown.value == 0) { doSendResetCode() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(if (cooldown.value > 0) "${cooldown.value}s" else "发送验证码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (cooldown.value > 0) AColors.Muted else AColors.Accent)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    AgentTextField(password.value, { password.value = it }, "新密码（至少 6 位）", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("重置密码", loading = busy.value) { doResetPassword() }
                } else {
                AgentTextField(username.value, { username.value = it }, "用户名（或邮箱登录）")
                Spacer(Modifier.height(12.dp))

                if (mode.value == "register") {
                    AgentTextField(email.value, { email.value = it }, "邮箱",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            AgentTextField(code.value, { code.value = it }, "6 位邮箱验证码",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.height(56.dp).clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                                .background(if (cooldown.value > 0) AColors.SurfaceAlt else AColors.AccentSoft)
                                .clickable(enabled = cooldown.value == 0) { doSendCode() }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (cooldown.value > 0) "${cooldown.value}s" else "发送验证码",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (cooldown.value > 0) AColors.Muted else AColors.Accent,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                AgentTextField(password.value, { password.value = it }, "密码（至少 6 位）",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

                Spacer(Modifier.height(20.dp))
                PrimaryButton(if (mode.value == "login") "登录" else "完成注册", loading = busy.value) { doSubmit() }
                if (mode.value == "login") {
                    Text("忘记密码？", fontSize = 13.sp, color = AColors.Accent, modifier = Modifier.padding(top = 14.dp).clickable { mode.value = "reset"; status.value = null })
                }
                }

                status.value?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, fontSize = 13.sp,
                        color = if (statusIsError.value) AColors.Danger else AColors.Success,
                        lineHeight = 19.sp)
                }

                Spacer(Modifier.height(20.dp))
                Text("登录后可使用钱包、应用市场发布与通知功能",
                    fontSize = 12.sp, color = AColors.Faint, modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }

    private fun switchMode(m: String) {
        mode.value = m
        status.value = null
    }

    private fun showError(msg: String) { statusIsError.value = true; status.value = msg }
    private fun showInfo(msg: String) { statusIsError.value = false; status.value = msg }

    private fun doSendCode() {
        val mail = email.value.trim()
        if (mail.isEmpty()) { showError("请填写邮箱"); return }
        cooldown.value = 60
        startCooldown()
        showInfo("发送中…")
        Thread {
            val err = UserStore.sendCode(this, mail)
            runOnUiThread {
                if (err == null) showInfo("验证码已发送到邮箱，请查收后填写")
                else { cooldown.value = 0; showError(err) }
            }
        }.start()
    }

    private fun startCooldown() {
        val tick = object : Runnable {
            override fun run() {
                if (cooldown.value <= 0) return
                cooldown.value -= 1
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private fun doSendResetCode() {
        val mail = email.value.trim()
        if (mail.isEmpty()) { showError("请填写邮箱"); return }
        cooldown.value = 60; startCooldown(); showInfo("发送中…")
        Thread { val err = UserStore.sendResetCode(this, mail); runOnUiThread { if (err == null) showInfo("验证码已发送") else { cooldown.value = 0; showError(err) } } }.start()
    }

    private fun doResetPassword() {
        val mail = email.value.trim(); val c = code.value.trim(); val p = password.value
        if (mail.isEmpty() || c.length != 6) { showError("请填写邮箱和 6 位验证码"); return }
        if (p.length < 6) { showError("密码至少 6 位"); return }
        submitReset { UserStore.resetPassword(this, mail, c, p) }
    }

    private fun submitReset(action: () -> String?) {
        busy.value = true; status.value = null
        Thread { val err = action(); runOnUiThread { busy.value = false; if (err == null) { showInfo("密码已重置，请返回登录"); mode.value = "login" } else showError(err) } }.start()
    }

    private fun doSubmit() {
        if (mode.value == "reset") { doResetPassword(); return }
        val u = username.value.trim()
        val p = password.value
        if (u.isEmpty() || p.isEmpty()) { showError("请填写用户名和密码"); return }
        if (p.length < 6) { showError("密码至少 6 位"); return }
        if (mode.value == "register") {
            val mail = email.value.trim()
            val c = code.value.trim()
            if (mail.isEmpty()) { showError("请填写邮箱"); return }
            if (c.length != 6) { showError("请填写 6 位邮箱验证码"); return }
            submit { UserStore.registerWithCode(this, u, mail, c, p) }
        } else {
            submit { UserStore.login(this, u, p) }
        }
    }

    private fun submit(action: () -> String?) {
        busy.value = true
        status.value = null
        Thread {
            val err = action()
            runOnUiThread {
                busy.value = false
                if (err == null) {
                    Toast.makeText(this, "已登录：${UserStore.username(this)}", Toast.LENGTH_SHORT).show()
                    finish()
                } else showError(err)
            }
        }.start()
    }
}