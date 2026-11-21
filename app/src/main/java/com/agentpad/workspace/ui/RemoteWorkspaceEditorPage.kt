// 远程工作区（SSH）编辑页。照抄 RikkaHub 3.1.1
//   ui/pages/extensions/workspace/WorkspaceSshEditorPage.kt（491 行）
//
// 【照抄的界面结构 —— 逐块对应原版行号】
//   Scaffold + TopAppBar（标题「新建/编辑远程工作区」+ 返回 + 保存勾）  94-106
//   LazyColumn，contentPadding 16/16/(top+8)/(bottom+16)，间距 16dp    109-120
//     ├─ CardGroup「基础信息」                                         121-134
//     ├─ CardGroup「连接」（主机/端口/用户名）                          136-171
//     ├─ CardGroup「认证」（密码 / 私钥 SegmentedButton）               173-251
//     ├─ CardGroup「工作目录」                                         253-270
//     ├─ CardGroup「安全」（主机公钥指纹）                              272-290
//     └─ ProbeSection：测试连接 + 结果卡片                              292-297
//   HostKeyDialog / 错误 AlertDialog                                   301-342
//
// 【本轮改动 —— 接上真实 SSH】
//   · 类型改用 data/ssh/SshClient.kt 里的 SshConnectionConfig（照抄 RikkaHub）
//   · 测试连接从「TCP 探测」换成真正的 SshConnection.probe()：
//     会走完整 SSH 握手，返回服务器出示的主机公钥、家目录、shell 路径、
//     远程目录是否存在 —— 和原版行为一致
//   · 首次连接：服务器出示的公钥与已保存的不一致时抛 SshUnknownHostKeyException，
//     弹 HostKeyDialog 让用户核对指纹后决定是否信任（原版 421-478 行）
//   · 保存时把确认过的公钥一并写进配置
//
// 【与原版的差异】
//   · HugeIcons → Material Icons
//   · stringResource → 中文字面量
//   · CustomColors.topBarColors → MaterialTheme.colorScheme.background
//   · LoadingIndicator → CircularProgressIndicator
//   · ViewModel → remember 状态
//   · 私钥「从文件导入」按钮：需要 Activity 提供 SAF，这里留了回调位

package com.agentpad.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agentpad.workspace.data.RemoteWorkspace
import com.agentpad.workspace.data.RemoteWorkspaceStore
import com.agentpad.workspace.data.ssh.SshAuthType
import com.agentpad.workspace.data.ssh.SshConnection
import com.agentpad.workspace.data.ssh.SshConnectionConfig
import com.agentpad.workspace.data.ssh.SshHostKey
import com.agentpad.workspace.data.ssh.SshHostKeyChangedException
import com.agentpad.workspace.data.ssh.SshProbeResult
import com.agentpad.workspace.data.ssh.SshUnknownHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程工作区编辑页。
 *
 * @param workspaceId 传 null 表示新建，传 id 表示编辑
 * @param onBack      返回
 * @param onSaved     保存成功后回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteWorkspaceEditorPage(
    workspaceId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val existing = remember(workspaceId) {
        workspaceId?.let { RemoteWorkspaceStore.find(context, it) }
    }
    val isNew = existing == null

    // 表单状态（原版用 ViewModel 的 SshWorkspaceDraft，这里直接用几个 state）
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.config?.host ?: "") }
    var port by remember { mutableStateOf((existing?.config?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.config?.username ?: "") }
    var authType by remember { mutableStateOf(existing?.config?.authType ?: SshAuthType.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var remoteDir by remember { mutableStateOf(existing?.config?.remoteDir ?: "") }
    var hostKey by remember { mutableStateOf(existing?.config?.hostKey) }

    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<SshProbeResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var canSaveAnyway by remember { mutableStateOf(false) }
    // 待确认的主机公钥（原版 pendingHostKey）
    var pendingHostKey by remember { mutableStateOf<SshHostKey?>(null) }
    var pendingChangedFrom by remember { mutableStateOf<SshHostKey?>(null) }

    val editable = !busy

    /** 用当前表单拼出配置。凭据留空时沿用已保存的。 */
    fun buildConfig(): SshConnectionConfig = SshConnectionConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: 22,
        username = username.trim(),
        authType = authType,
        password = password.takeIf { it.isNotEmpty() } ?: existing?.config?.password,
        privateKey = privateKey.takeIf { it.isNotEmpty() } ?: existing?.config?.privateKey,
        passphrase = passphrase.takeIf { it.isNotEmpty() } ?: existing?.config?.passphrase,
        remoteDir = remoteDir.trim(),
        hostKey = hostKey,
    )

    /** 测试连接：走真实 SSH 握手（原版 vm.testConnection）。 */
    fun testConnection() {
        val cfg = buildConfig()
        if (cfg.host.isBlank()) { errorMessage = "请填写主机地址"; return }
        if (cfg.username.isBlank()) { errorMessage = "请填写用户名"; return }
        testing = true
        busy = true
        probeResult = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { SshConnection.probe(cfg) }
                probeResult = result
                // 首次连接：把服务器出示的公钥记下来，等用户确认
                if (hostKey == null) {
                    pendingHostKey = result.hostKey
                }
            } catch (e: SshUnknownHostKeyException) {
                // 服务器出示的公钥没被信任 —— 原版就是在这里弹 HostKeyDialog
                pendingHostKey = e.hostKey
            } catch (e: SshHostKeyChangedException) {
                // 公钥变了，可能是重装也可能是中间人 —— 红色警告
                pendingChangedFrom = e.expected
                pendingHostKey = e.actual
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
                canSaveAnyway = true
            } finally {
                testing = false
                busy = false
            }
        }
    }

    /** 保存（原版 vm.save）。 */
    fun save() {
        val cfg = buildConfig()
        if (cfg.host.isBlank()) { errorMessage = "请填写主机地址"; return }
        if (cfg.username.isBlank()) { errorMessage = "请填写用户名"; return }
        val ws = RemoteWorkspace(
            id = existing?.id ?: RemoteWorkspaceStore.newId(),
            name = name.ifBlank { "${cfg.host}:${cfg.port}" },
            config = cfg,
        )
        RemoteWorkspaceStore.save(context, ws)
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建远程工作区" else "编辑远程工作区") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Outlined.Check, "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 基础信息（原版 121-134）----
            item {
                CardGroup(title = { Text("基础信息") }) {
                    formItem(headlineContent = { Text("名称") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("例如：生产服务器") },
                            singleLine = true,
                        )
                    }
                }
            }

            // ---- 连接（原版 136-171）----
            item {
                CardGroup(title = { Text("连接") }) {
                    formItem(headlineContent = { Text("主机地址") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = host,
                            onValueChange = { host = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("192.168.1.100 或 example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    formItem(headlineContent = { Text("端口") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = port,
                            onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    formItem(headlineContent = { Text("用户名") }) {
                        OutlinedTextField(
                            enabled = editable,
                            value = username,
                            onValueChange = { username = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("root") },
                            singleLine = true,
                        )
                    }
                }
            }

            // ---- 认证（原版 173-251）----
            item {
                CardGroup(title = { Text("认证") }) {
                    formItem(headlineContent = { Text("认证方式") }) {
                        val options = listOf(
                            SshAuthType.PASSWORD to "密码",
                            SshAuthType.PRIVATE_KEY to "私钥",
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (type, label) ->
                                SegmentedButton(
                                    enabled = editable,
                                    selected = authType == type,
                                    onClick = { authType = type },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(label) }
                            }
                        }
                    }
                    when (authType) {
                        SshAuthType.PASSWORD -> formItem(headlineContent = { Text("密码") }) {
                            SecretTextField(
                                enabled = editable,
                                value = password,
                                onValueChange = { password = it },
                                placeholder = if (!isNew && existing?.config?.password != null) {
                                    "已保存，留空则沿用"
                                } else null,
                            )
                        }

                        SshAuthType.PRIVATE_KEY -> {
                            formItem(
                                headlineContent = { Text("私钥") },
                                supportingContent = { Text("粘贴 OpenSSH 私钥内容") },
                            ) {
                                OutlinedTextField(
                                    enabled = editable,
                                    value = privateKey,
                                    onValueChange = { privateKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            if (!isNew && existing?.config?.privateKey != null) {
                                                "已保存，留空则沿用"
                                            } else {
                                                "-----BEGIN OPENSSH PRIVATE KEY-----"
                                            }
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                OutlinedButton(
                                    enabled = editable,
                                    onClick = { /* SAF 文件选择由宿主 Activity 提供 */ },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        Icons.Outlined.FileOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text("从文件导入")
                                }
                            }
                            formItem(headlineContent = { Text("私钥口令") }) {
                                SecretTextField(
                                    enabled = editable,
                                    value = passphrase,
                                    onValueChange = { passphrase = it },
                                    placeholder = "私钥有口令时填写",
                                )
                            }
                        }
                    }
                }
            }

            // ---- 工作目录（原版 253-270）----
            item {
                CardGroup(title = { Text("工作目录") }) {
                    formItem(
                        headlineContent = { Text("远程目录") },
                        supportingContent = { Text("AI 读写文件的根目录，绝对路径") },
                    ) {
                        OutlinedTextField(
                            enabled = editable,
                            value = remoteDir,
                            onValueChange = { remoteDir = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("/home/user/project") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                }
            }

            // ---- 安全（原版 272-290）----
            item {
                CardGroup(title = { Text("安全") }) {
                    item(
                        leadingContent = { CardGroupIcon(Icons.Outlined.Fingerprint) },
                        headlineContent = { Text("主机公钥") },
                        supportingContent = {
                            val hk = hostKey
                            if (hk == null) {
                                Text("尚未确认（首次测试连接时会提示确认）")
                            } else {
                                Text(
                                    text = "${hk.type}\n${hk.fingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                }
            }

            // ---- 测试连接（原版 292-297 / 370-419）----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { testConnection() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                        }
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (testing) "连接中…" else "测试连接")
                    }
                    probeResult?.let { result ->
                        CardGroup(title = { Text("已连接") }) {
                            item(
                                leadingContent = { CardGroupIcon(Icons.Outlined.Dns) },
                                headlineContent = { Text("Shell") },
                                supportingContent = { Text(result.shell) },
                            )
                            item(
                                leadingContent = { CardGroupIcon(Icons.Outlined.Folder) },
                                headlineContent = { Text("工作目录") },
                                supportingContent = {
                                    Text(
                                        when {
                                            remoteDir.isBlank() -> "使用家目录 ${result.homeDir}"
                                            result.remoteDirExists -> remoteDir
                                            else -> "目录不存在：$remoteDir"
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 主机公钥确认（原版 421-478）----
    pendingHostKey?.let { pending ->
        val changed = pendingChangedFrom != null
        AlertDialog(
            onDismissRequest = {
                pendingHostKey = null
                pendingChangedFrom = null
            },
            icon = { Icon(Icons.Outlined.VpnKey, contentDescription = null) },
            title = { Text(if (changed) "主机公钥已变更！" else "确认主机公钥") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (changed) {
                            "警告：${host}:${port} 出示的公钥与之前记录的不一致。\n" +
                                "可能是服务器重装过，也可能是中间人攻击。请核对后再决定。"
                        } else {
                            "首次连接到 ${host}:${port}，请核对服务器指纹："
                        }
                    )
                    if (changed) {
                        Text(
                            text = "原指纹：\n${pendingChangedFrom?.fingerprint}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = "新指纹：\n${pending.type}\n${pending.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(
                        "可在服务器上执行验证：\nssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                if (changed) {
                    Button(
                        onClick = {
                            hostKey = pending
                            pendingHostKey = null
                            pendingChangedFrom = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("信任新公钥") }
                } else {
                    TextButton(onClick = {
                        hostKey = pending
                        pendingHostKey = null
                    }) { Text("信任并继续") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingHostKey = null
                    pendingChangedFrom = null
                }) { Text("取消") }
            },
        )
    }

    // ---- 错误提示（原版 310-342）----
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null; canSaveAnyway = false },
            title = { Text(if (canSaveAnyway) "连接失败" else "出错了") },
            text = { Text(if (canSaveAnyway) "$msg\n\n仍然可以保存配置，稍后再试。" else msg) },
            confirmButton = {
                if (canSaveAnyway) {
                    TextButton(onClick = {
                        errorMessage = null
                        canSaveAnyway = false
                        save()
                    }) { Text("仍然保存") }
                } else {
                    TextButton(onClick = { errorMessage = null }) { Text("确定") }
                }
            },
            dismissButton = if (canSaveAnyway) {
                { TextButton(onClick = { errorMessage = null; canSaveAnyway = false }) { Text("取消") } }
            } else null,
        )
    }
}

/** 密码/私钥口令输入框，带显示切换。照抄原版 SecretTextField（346-368 行）。 */
@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示",
                )
            }
        },
    )
}