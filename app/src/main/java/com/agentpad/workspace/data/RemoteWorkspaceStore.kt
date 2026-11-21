// 远程工作区（SSH）的持久化存储。
//
// 数据结构用 data/ssh/SshClient.kt 里的类型（照抄 RikkaHub 3.1.1 的
// SshConnectionConfig / SshHostKey / SshAuthType），这里只负责存取。
//
// 【与 RikkaHub 的差异】
//   · 原版用 Room 存 workspaces 表，ssh_config 列是 JSON，
//     凭据字段用 SecretCipher（Android Keystore）加密。
//   · 我们用 SharedPreferences 存 JSON，凭据用 XOR 混淆 + Base64。
//     不是真加密，但避免明文裸奔。生产级应换 androidx.security:security-crypto。

package com.agentpad.workspace.data

import android.content.Context
import com.agentpad.workspace.data.ssh.SshAuthType
import com.agentpad.workspace.data.ssh.SshConnectionConfig
import com.agentpad.workspace.data.ssh.SshHostKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条远程工作区记录。
 *
 * [config] 里的 password / privateKey / passphrase 是**明文**，
 * 由 [RemoteWorkspaceStore] 在存盘时加密、读盘时解密。
 */
data class RemoteWorkspace(
    val id: String,
    val name: String,
    val config: SshConnectionConfig,
)

/**
 * 远程工作区存储。
 */
object RemoteWorkspaceStore {
    private const val PREFS = "remote_workspaces"
    private const val KEY_LIST = "list"

    fun list(context: Context): List<RemoteWorkspace> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getJSONObject(i).toWorkspace())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String): RemoteWorkspace? =
        list(context).firstOrNull { it.id == id }

    fun save(context: Context, ws: RemoteWorkspace) {
        val all = list(context).toMutableList()
        val idx = all.indexOfFirst { it.id == ws.id }
        if (idx >= 0) all[idx] = ws else all += ws
        write(context, all)
    }

    fun delete(context: Context, id: String) {
        write(context, list(context).filterNot { it.id == id })
    }

    /** 更新某条工作区的已信任主机公钥（首次连接确认后调用）。 */
    fun trustHostKey(context: Context, id: String, hostKey: SshHostKey) {
        val ws = find(context, id) ?: return
        save(context, ws.copy(config = ws.config.copy(hostKey = hostKey)))
    }

    /** 生成一个默认名字。 */
    fun defaultName(config: SshConnectionConfig): String =
        config.host.ifBlank { "远程工作区" }

    fun newId(): String = UUID.randomUUID().toString()

    private fun write(context: Context, all: List<RemoteWorkspace>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- JSON 序列化 ----

    private fun RemoteWorkspace.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", config.host)
        put("port", config.port)
        put("username", config.username)
        put("authType", config.authType.name)
        put("remoteDir", config.remoteDir)
        config.hostKey?.let {
            put("hostKeyType", it.type)
            put("hostKeyKey", it.key)
        }
        // 凭据：加密后再写
        config.password?.takeIf { it.isNotEmpty() }?.let {
            put("encPassword", obfuscate(it))
        }
        config.privateKey?.takeIf { it.isNotEmpty() }?.let {
            put("encPrivateKey", obfuscate(it))
        }
        config.passphrase?.takeIf { it.isNotEmpty() }?.let {
            put("encPassphrase", obfuscate(it))
        }
    }

    private fun JSONObject.toWorkspace(): RemoteWorkspace {
        val hkType = optString("hostKeyType").takeIf { it.isNotBlank() }
        val hkKey = optString("hostKeyKey").takeIf { it.isNotBlank() }
        return RemoteWorkspace(
            id = optString("id"),
            name = optString("name"),
            config = SshConnectionConfig(
                host = optString("host"),
                port = optInt("port", 22),
                username = optString("username"),
                authType = runCatching {
                    SshAuthType.valueOf(optString("authType"))
                }.getOrDefault(SshAuthType.PASSWORD),
                password = deobfuscate(optString("encPassword")).takeIf { it.isNotEmpty() },
                privateKey = deobfuscate(optString("encPrivateKey")).takeIf { it.isNotEmpty() },
                passphrase = deobfuscate(optString("encPassphrase")).takeIf { it.isNotEmpty() },
                remoteDir = optString("remoteDir"),
                hostKey = if (hkType != null && hkKey != null) SshHostKey(hkType, hkKey) else null,
            ),
        )
    }

    // ---- 凭据混淆（不是真加密，仅避免明文裸奔） ----

    private const val SALT = "AgentPad.SshWorkspace.v1"

    fun obfuscate(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = SALT.toByteArray()
        val bytes = plain.toByteArray()
        val out = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    fun deobfuscate(cipher: String?): String {
        if (cipher.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = android.util.Base64.decode(cipher, android.util.Base64.NO_WRAP)
            val key = SALT.toByteArray()
            val out = ByteArray(bytes.size) { i ->
                (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            String(out)
        }.getOrDefault("")
    }
}