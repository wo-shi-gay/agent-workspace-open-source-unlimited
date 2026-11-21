package com.agentpad.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.MarketStore
import com.agentpad.workspace.data.UserStore
import com.agentpad.workspace.ui.*
import org.json.JSONObject
import java.io.File

/**
 * 应用市场（Compose 重构版）：市场列表 / 我的应用（上传、管理）。
 */
class MarketActivity : ComponentActivity() {

    private val tab = mutableStateOf("market") // market | plugins | mine
    private val loading = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)
    private val list = mutableStateOf<List<JSONObject>>(emptyList())
    private val uploading = mutableStateOf(false)

    private val uploadDialog = mutableStateOf(false)
    private val updateTarget = mutableStateOf<JSONObject?>(null)
    /** 必须是 Compose 状态，否则选完文件 UI 不刷新（提交时仍读到空）。 */
    private val pendingApkUri = mutableStateOf<Uri?>(null)

    private val apkPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingApkUri.value = uri
            Toast.makeText(this, "已选择 APK", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            AgentTheme {
                Content()
                if (uploadDialog.value) UploadOrUpdateDialog(null)
                updateTarget.value?.let { UploadOrUpdateDialog(it) }
            }
        }
        load()
    }

    private fun load() {
        loading.value = true
        error.value = null
        Thread {
            val (err, l) = when (tab.value) {
                "mine" -> MarketStore.myApps(UserStore.token(this))
                "plugins" -> MarketStore.pluginList()
                else -> MarketStore.marketList()
            }
            runOnUiThread { error.value = err; list.value = l; loading.value = false }
        }.start()
    }

    private fun switchTab(t: String) { tab.value = t; load() }

    @Composable
    private fun Content() {
        val loggedIn = remember { UserStore.isLoggedIn(this) }
        SubPage("应用市场", "浏览、下载 AI 构建的应用，或发布你的作品",
            onBack = { finish() }, scrollable = false) {
            // 分段标签
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(AColors.SurfaceAlt).padding(3.dp),
            ) {
                listOf("market" to "应用", "plugins" to "插件", "mine" to "我的").forEach { (key, label) ->
                    val active = tab.value == key
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (active) AColors.Surface else AColors.SurfaceAlt)
                            .clickable { switchTab(key) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) AColors.Accent else AColors.Muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            when {
                tab.value == "mine" && !loggedIn ->
                    EmptyState(Icons.Outlined.Storefront, "请先登录", "登录后可发布与管理你的应用")
                loading.value ->
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = AColors.Accent)
                    }
                error.value != null ->
                    EmptyState(Icons.Outlined.Storefront, "加载失败", error.value)
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tab.value == "mine") {
                        item {
                            AgentCard(padding = 15.dp, onClick = {
                                pendingApkUri.value = null; uploadDialog.value = true
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBox(Icons.Outlined.CloudUpload, AColors.Accent)
                                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                                        Text("上传应用到市场", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Accent)
                                        Text("选择 AI 构建的 APK，填写名称与简介，提交审核",
                                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                        item {
                            AgentCard(padding = 15.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBox(Icons.Outlined.Extension, AColors.Violet)
                                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                                        Text("发布插件", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Violet)
                                        Text("不用手动上传 —— 直接对 AI 说「把这个插件发布到市场」，AI 会自动打包并提交审核",
                                            fontSize = 12.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (list.value.isEmpty()) {
                        item {
                            EmptyState(
                                if (tab.value == "mine") Icons.Outlined.Upload
                                else if (tab.value == "plugins") Icons.Outlined.Extension
                                else Icons.Outlined.Storefront,
                                when (tab.value) {
                                    "mine" -> "还没有发布过内容"
                                    "plugins" -> "市场还没有插件"
                                    else -> "市场还没有应用"
                                },
                                when (tab.value) {
                                    "mine" -> "点上方发布你的第一个作品"
                                    "plugins" -> "在「我的」里发布插件，或让 AI 用 create_plugin 造一个"
                                    else -> "抢个首发的机会来了"
                                })
                        }
                    }
                    items(list.value) { app ->
                        when (tab.value) {
                            "mine" -> MyAppCard(app)
                            "plugins" -> PluginCard(app)
                            else -> MarketAppCard(app)
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    private val installState = mutableStateOf<Pair<Int, String>?>(null)   // (appId, status文字)

    /** 市场插件卡片：一键安装（下载 → 解包 → 注册工具）。 */
    @Composable
    private fun PluginCard(app: JSONObject) {
        val appId = app.optInt("id")
        val st = installState.value
        val installing = st?.first == appId
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                        .background(AColors.Violet.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null,
                        tint = AColors.Violet, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(app.optString("app_name"), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = AColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("作者 ${app.optString("author")} · ${"%.1f".format(app.optInt("apk_size") / 1048576.0)}MB · ${app.optInt("downloads")} 次安装",
                        fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text(app.optString("description"), fontSize = 13.sp, color = AColors.InkSoft,
                modifier = Modifier.padding(top = 8.dp), lineHeight = 19.sp, maxLines = 4,
                overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.padding(top = 12.dp).clip(RoundedCornerShape(ADimens.ChipRadius))
                    .background(if (installing) AColors.Accent.copy(alpha = .4f) else AColors.Accent)
                    .clickable(enabled = !installing) { installPluginFromMarket(appId, app.optString("app_name")) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (installing) Icons.Outlined.Download else Icons.Outlined.Extension,
                    contentDescription = null, tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (installing) (st?.second ?: "安装中…") else "一键安装",
                    fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }

    /** 一键安装市场插件：下载包 → PluginStore 解包安装 → 刷新列表。 */
    private fun installPluginFromMarket(appId: Int, name: String) {
        if (installState.value?.first == appId) return
        installState.value = appId to "下载中…"
        Thread {
            val tmp = File(cacheDir, "market_plugin_$appId.plugin")
            val err = MarketStore.downloadPlugin(appId, tmp)
            if (err != null) {
                runOnUiThread {
                    installState.value = null
                    Toast.makeText(this, "安装失败：$err", Toast.LENGTH_LONG).show()
                }
                tmp.delete()
                return@Thread
            }
            val (ok, msg, manifest) = com.agentpad.workspace.data.PluginStore.installFromZip(this, tmp)
            tmp.delete()
            runOnUiThread {
                installState.value = null
                Toast.makeText(this, if (ok) "已安装插件「${manifest?.name ?: name}」，AI 现在就能用它" else msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @Composable
    private fun MarketAppCard(app: JSONObject) {
        AgentCard {
            Text(app.optString("app_name"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
            Text("作者 ${app.optString("author")} · ${"%.1f".format(app.optInt("apk_size") / 1048576.0)}MB · ${app.optInt("downloads")} 次下载",
                fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 5.dp))
            Text(app.optString("description"), fontSize = 13.sp, color = AColors.InkSoft,
                modifier = Modifier.padding(top = 8.dp), lineHeight = 19.sp, maxLines = 4,
                overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.padding(top = 12.dp).clip(RoundedCornerShape(ADimens.ChipRadius))
                    .background(AColors.AccentSoft).clickable {
                        val id = app.optInt("id")
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(UserStore.BASE_URL + "/api/market_download.php?id=$id")))
                    }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(16.dp))
                Text("下载 APK", fontSize = 13.sp, color = AColors.Accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    @Composable
    private fun MyAppCard(app: JSONObject) {
        val st = app.optInt("status")
        val (statusText, statusColor, statusBg) = when (st) {
            0 -> Triple("待审核", AColors.Warning, AColors.WarningSoft)
            1 -> Triple("已上架", AColors.Success, AColors.SuccessSoft)
            2 -> Triple("已拒绝", AColors.Danger, AColors.DangerSoft)
            9 -> Triple("已下架", AColors.Muted, AColors.SurfaceAlt)
            else -> Triple("?", AColors.Muted, AColors.SurfaceAlt)
        }
        AgentCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.optString("app_name"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = AColors.Ink, modifier = Modifier.weight(1f, false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Tag(statusText, statusColor, statusBg)
            }
            Text("下载 ${app.optInt("downloads")} 次 · ${app.optString("created_at", "").take(16)}",
                fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 6.dp))
            val note = app.optString("review_note")
            if (note.isNotBlank()) {
                Text("原因：$note", fontSize = 12.sp, color = AColors.Danger, modifier = Modifier.padding(top = 7.dp))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip("更新版本", AColors.Accent) { pendingApkUri.value = null; updateTarget.value = app }
                if (st == 1 || st == 0) MiniChip("下架", AColors.Warning) { confirmManage(app, "unpublish") }
                if (st == 9 || st == 2) MiniChip("重新提交审核", AColors.Success) { confirmManage(app, "republish") }
            }
        }
    }

    @Composable
    private fun MiniChip(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(ADimens.ChipRadius)).background(color.copy(alpha = 0.1f))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun UploadOrUpdateDialog(app: JSONObject?) {
        val isUpdate = app != null
        var name by remember { mutableStateOf(app?.optString("app_name") ?: "") }
        var desc by remember { mutableStateOf(app?.optString("description") ?: "") }
        val picked = pendingApkUri.value

        AlertDialog(
            onDismissRequest = { uploadDialog.value = false; updateTarget.value = null },
            title = { Text(if (isUpdate) "更新「${app!!.optString("app_name")}」" else "上传应用到市场", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    AgentTextField(name, { name = it }, if (isUpdate) "应用名" else "应用名（1-60 字）")
                    Spacer(Modifier.height(10.dp))
                    AgentTextField(desc, { desc = it }, "简介（1-2000 字）", singleLine = false)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(AColors.SurfaceAlt).clickable { apkPicker.launch(arrayOf("*/*")) }
                            .padding(14.dp),
                    ) {
                        Text(
                            if (picked == null) "📄 点此选择 APK 文件" else "✓ 已选择：${queryApkName(picked)}",
                            fontSize = 14.sp, color = if (picked == null) AColors.Muted else AColors.Success,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val n = name.trim(); val d = desc.trim()
                    val uri = picked
                    if (n.isEmpty() || d.isEmpty()) { Toast.makeText(this, "请填写完整", Toast.LENGTH_SHORT).show(); return@TextButton }
                    if (uri == null) { Toast.makeText(this, "请先选择 APK 文件", Toast.LENGTH_SHORT).show(); return@TextButton }
                    uploadDialog.value = false; updateTarget.value = null
                    doUpload(n, d, uri, if (isUpdate) app!!.optInt("id") else null)
                }) { Text(if (isUpdate) "提交更新" else "提交审核") }
            },
            dismissButton = {
                TextButton({ uploadDialog.value = false; updateTarget.value = null }) { Text("取消") }
            },
        )
    }

    private fun confirmManage(app: JSONObject, action: String) {
        val title = if (action == "unpublish") "下架应用" else "重新提交"
        val msg = if (action == "unpublish")
            "确定下架「${app.optString("app_name")}」？市场将不再展示，可随时重新提交审核。"
        else "将「${app.optString("app_name")}」重新提交审核？"
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(if (action == "unpublish") "下架" else "提交") { _, _ -> doManage(action, app.optInt("id")) }
            .setNegativeButton("取消", null).show()
    }

    private fun queryApkName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) "${c.getString(0)}（${c.getLong(1) / 1048576.0}MB）" else uri.toString()
        } ?: uri.toString()
    }.getOrNull() ?: uri.toString()

    private fun doUpload(name: String, desc: String, uri: Uri, appId: Int?) {
        if (uploading.value) return
        // 先确认登录态：token 为空时直接提示，不要发空 token 出去被服务器打回"未登录"
        if (!UserStore.isLoggedIn(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("请先登录")
                .setMessage("发布/更新应用需要登录账号。请先到「设置 → 登录 / 注册」登录后再试。")
                .setPositiveButton("去登录") { _, _ ->
                    startActivity(android.content.Intent(this, AuthActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        uploading.value = true
        Toast.makeText(this, "读取文件中…", Toast.LENGTH_SHORT).show()
        val tag = if (appId == null) "upload" else "update"
        val cache = File(cacheDir, "${tag}_${System.currentTimeMillis()}.apk")
        Thread {
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    cache.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!ok || !cache.isFile || cache.length() == 0L) {
                uploading.value = false
                runOnUiThread { Toast.makeText(this, "读取 APK 失败", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            if (cache.length() > 30L * 1048576) {
                cache.delete(); uploading.value = false
                runOnUiThread { Toast.makeText(this, "APK 超过 30MB", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val (code, text) = if (appId == null)
                MarketStore.uploadApp(UserStore.token(this), name, desc, cache)
            else
                MarketStore.manageApp(UserStore.token(this), "update", appId, name, desc, cache)
            uploading.value = false
            cache.delete()
            runOnUiThread {
                val resp = runCatching { JSONObject(text) }.getOrNull()
                if (resp != null && resp.optInt("code") == 200) {
                    Toast.makeText(this, resp.optString("msg", "提交成功"), Toast.LENGTH_LONG).show()
                    switchTab("mine")
                } else {
                    val msg = resp?.optString("msg") ?: "上传失败（HTTP $code）"
                    val detail = resp?.optString("detail") ?: ""
                    val shown = if (detail.isNotBlank()) "$msg：$detail" else msg
                    val hint = if (msg.contains("凭证")) "\n\n请检查 AI 是否完成了构建凭证注入（构建 APK 后应执行 TOKEN_OK 那步）。" else ""
                    android.app.AlertDialog.Builder(this).setTitle("上传失败")
                        .setMessage(shown + hint).setPositiveButton("好", null).show()
                }
            }
        }.start()
    }

    private fun doManage(action: String, appId: Int) {
        if (!UserStore.isLoggedIn(this)) { Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val (code, text) = MarketStore.manageApp(UserStore.token(this), action, appId)
            runOnUiThread {
                val resp = runCatching { JSONObject(text) }.getOrNull()
                val msg = resp?.optString("msg") ?: "操作失败（HTTP $code）"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (resp?.optInt("code") == 200) load()
            }
        }.start()
    }
}