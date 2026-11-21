package com.agentpad.workspace

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.data.ChatStore.apiProfiles
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Operit-style API config: a profile list (default profile is built-in and
 * crippled — URL/key hidden, only model picker), create/rename/delete for
 * user profiles, and one active profile used by every chat request.
 */
class ApiConfigActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F4EF"))
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; setTextColor(Color.parseColor("#10131A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = getDrawable(R.drawable.bg_panel)
            setOnClickListener { finish() }
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            addView(TextView(this@ApiConfigActivity).apply { text = "API 配置"; textSize = 21f; setTextColor(Color.parseColor("#10131A")); setTypeface(typeface, 1) })
            addView(TextView(this@ApiConfigActivity).apply { text = "管理你的模型服务配置"; textSize = 12f; setTextColor(Color.parseColor("#7D8492")) })
        })
        root.addView(header)

        status = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#8C929E")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
        root.addView(listContainer)

        root.addView(TextView(this).apply {
            text = "＋ 新建配置"; textSize = 14f; setTypeface(typeface, 1)
            setTextColor(Color.parseColor("#FFFFFF")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
            background = getDrawable(R.drawable.bg_accent)
            setOnClickListener { promptNewProfile() }
        })
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "思考过程回传上下文"; textSize = 14f; setTypeface(typeface, 1)
            setTextColor(Color.parseColor("#10131A")); setPadding(0, dp(22), 0, dp(4))
        })
        val reasonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = getDrawable(R.drawable.bg_soft) }
        reasonRow.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#7D8492"))
            text = "将 AI 的思考内容加入后续请求上下文（思考过程始终本地保存）"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply { isChecked = ChatStore.reasoningInContext(this@ApiConfigActivity) }
        sw.setOnCheckedChangeListener { _, checked ->
            ChatStore.saveReasoningInContext(this@ApiConfigActivity, checked)
            status.text = if (checked) "已开启：思考回传上下文" else "已关闭：思考仅本地保存"
        }
        reasonRow.addView(sw)
        root.addView(reasonRow)

        // ---- 多子智能体代理协调 ----
        root.addView(TextView(this).apply {
            text = "多子智能体代理协调"; textSize = 14f; setTypeface(typeface, 1)
            setTextColor(Color.parseColor("#10131A")); setPadding(0, dp(20), 0, dp(4))
        })
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = getDrawable(R.drawable.bg_soft)
        }
        subRow.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#7D8492"))
            text = "开启后 AI 可把互不依赖的子任务并行派发给最多 ${com.agentpad.workspace.ai.SubAgentRunner.MAX_CONCURRENCY} 个子代理：" +
                "子代理各自带完整工具链独立跑完，只把结论摘要回传，中间过程不占主对话上下文。" +
                "关闭时 subagent_run / subagent_parallel 两个工具对模型完全不可见（不是看到后拒绝）。" +
                "改完下一轮对话生效。"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val subSw = Switch(this).apply { isChecked = ChatStore.subAgentsEnabled(this@ApiConfigActivity) }
        subSw.setOnCheckedChangeListener { _, checked ->
            ChatStore.saveSubAgentsEnabled(this@ApiConfigActivity, checked)
            status.text = if (checked) "已开启：多子智能体代理协调（下一轮对话生效）" else "已关闭：多子智能体代理协调"
        }
        subRow.addView(subSw)
        root.addView(subRow)

        setContentView(scrollView(root))
        renderProfiles()
    }

    /** 悬浮窗权限需用户在系统设置里授予；未授予时对话逻辑不受影响，只是不显示胶囊。 */
    private fun requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun scrollView(content: LinearLayout): View {
        val sv = android.widget.ScrollView(this)
        sv.layoutParams = ViewGroup.LayoutParams(-1, -1)
        sv.addView(content)
        sv.setBackgroundColor(Color.parseColor("#F6F4EF"))
        return sv
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderProfiles() {
        listContainer.removeAllViews()
        val activeId = ChatStore.activeProfileId(this)
        apiProfiles(this).forEach { profile ->
            listContainer.addView(profileCard(profile, activeId))
        }
    }

    private fun profileCard(profile: JSONObject, activeId: String): View {
        val id = profile.optString("id")
        val isBuiltin = profile.optBoolean("builtin", false)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(if (id == activeId) R.drawable.bg_accent_soft else R.drawable.bg_soft)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = profile.optString("name"); textSize = 15f; setTypeface(typeface, 1)
            setTextColor(Color.parseColor("#10131A"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (id == activeId) {
            titleRow.addView(TextView(this).apply {
                text = "✓ 使用中"; textSize = 11f; setTextColor(Color.parseColor("#3E7B4F")); setTypeface(typeface, 1)
            })
        }
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            // ⚠ 2026-11-10：只剩「官方模型」一个内置渠道，免费渠道已下架，
            // 文案不能再写「内置免费服务」，否则用户会误以为还是免费的。
            text = when {
                profile.optBoolean("hub", false) -> "官方模型（按余额计费） · ${profile.optString("model")}"
                isBuiltin -> "内置渠道 · 模型：${profile.optString("model")}"
                else -> "${profile.optString("model")} · ${profile.optString("url")}"
            }
            textSize = 11f; setTextColor(Color.parseColor("#7D8492")); setPadding(0, dp(4), 0, 0)
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun chip(text: String, action: () -> Unit): TextView = TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(Color.parseColor("#10131A")); setTypeface(typeface, 1)
            gravity = Gravity.CENTER; background = getDrawable(R.drawable.bg_panel)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(30)).apply { marginEnd = dp(8) }
            setOnClickListener { action() }
        }
        if (id != activeId) btnRow.addView(chip("使用") {
            ChatStore.setActiveProfileId(this@ApiConfigActivity, id)
            status.text = "已切换到「${profile.optString("name")}」"
            renderProfiles()
        })
        if (profile.optBoolean("hub", false)) {
            btnRow.addView(chip("选模型") { pickHubModel(profile) })
            btnRow.addView(chip("💰 充值") { startActivity(android.content.Intent(this@ApiConfigActivity, WalletActivity::class.java)) })
        } else {
            btnRow.addView(chip(if (isBuiltin) "选模型" else "编辑") {
                if (isBuiltin) pickModelForDefault(profile) else editProfile(profile)
            })
        }
        if (!isBuiltin) btnRow.addView(chip("删除") {
            AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除「${profile.optString("name")}」？")
                .setPositiveButton("删除") { _, _ ->
                    ChatStore.deleteProfile(this@ApiConfigActivity, id)
                    status.text = "已删除"
                    renderProfiles()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        card.addView(btnRow)
        return card
    }

    private fun promptNewProfile() {
        val input = EditText(this).apply { hint = "配置名称" }
        AlertDialog.Builder(this)
            .setTitle("新建配置")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { status.text = "必须填写配置名称"; return@setPositiveButton }
                val newId = ChatStore.createProfile(this@ApiConfigActivity, name)
                ChatStore.setActiveProfileId(this@ApiConfigActivity, newId)
                status.text = "已创建「$name」，请补全配置信息"
                renderProfiles()
                // Jump straight into editing the fresh profile.
                apiProfiles(this).firstOrNull { it.optString("id") == newId }?.let { editProfile(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editProfile(profile: JSONObject) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: String, value: String, password: Boolean = false): EditText {
            container.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(Color.parseColor("#7D8492")); setPadding(0, dp(10), 0, dp(2)) })
            return EditText(this).apply {
                setText(value); textSize = 14f; setSingleLine(true)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = getDrawable(R.drawable.bg_soft)
            }
        }
        val nameField = field("配置名称", profile.optString("name"))
        container.addView(nameField)
        val urlField = field("Base URL", profile.optString("url"))
        container.addView(urlField)
        val keyField = field("API Key", profile.optString("key"), password = true)
        container.addView(keyField)
        val modelField = field("模型", profile.optString("model"))
        container.addView(modelField)
        val fetchBtn = TextView(this).apply {
            text = "获取模型列表"; textSize = 13f; setTextColor(Color.parseColor("#3E6FA8")); setPadding(0, dp(10), 0, 0)
            setOnClickListener { fetchModels(urlField.text.toString().trim().trimEnd('/'), keyField.text.toString().trim()) { model -> modelField.setText(model) } }
        }
        container.addView(fetchBtn)

        AlertDialog.Builder(this)
            .setTitle("编辑配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                ChatStore.updateProfile(
                    this,
                    profile.optString("id"),
                    nameField.text.toString(),
                    urlField.text.toString(),
                    keyField.text.toString(),
                    modelField.text.toString()
                )
                status.text = "配置已保存"
                renderProfiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** The built-in default profile is crippled: only the model is pickable.
     *  default2 additionally uses a FIXED model list (no /models fetch). */
    /** 官方模型：从 kami 网关拉 /models（含三档售价），点选即保存。 */
    private fun pickHubModel(profile: JSONObject) {
        val token = com.agentpad.workspace.data.UserStore.token(this)
        if (token.isBlank()) { status.text = "官方模型需要先登录（计费绑定账号）"; return }
        status.text = "获取官方模型列表…"
        ChatStore.fetchHubModels(this, token) { models, err ->
            if (err != null || models.isEmpty()) {
                status.text = "获取失败：${err ?: "没有可用模型"}"
                return@fetchHubModels
            }
            // 列表项带售价（/models 返回 in/out/cache_price_per_1m），重新拉一次带价展示
            status.text = ""
            Thread {
                val priced = runCatching {
                    val c = (java.net.URL("${ChatStore.HUB_BASE}/models").openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val dataArray = json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data")
                    // ⚠ 2026-11-10：kami 网关的 /models **只返回输入价和输出价**，
// 没有 cache_price_per_1m。所以列表项也只写这两档，
// 不要在界面上留「缓存价」的位置，避免出现空白或 ¥0 误导用户。
                    buildList {
                        if (dataArray != null) for (i in 0 until dataArray.length()) {
                            val o = dataArray.optJSONObject(i) ?: continue
                            val name = o.optString("id")
                            if (name.isBlank()) continue
                            val ip = o.optDouble("in_price_per_1m", 0.0)
                            val op = o.optDouble("out_price_per_1m", 0.0)
                            val price = when {
                                ip > 0 && op > 0 -> "   （输入¥%g / 输出¥%g，每百万 token）".format(ip, op)
                                ip > 0 -> "   （输入¥%g，每百万 token）".format(ip)
                                op > 0 -> "   （输出¥%g，每百万 token）".format(op)
                                else -> ""   // 网关没给价，就只显示模型名，不写 ¥0
                            }
                            add(name + price)
                        }
                    }
                }.getOrElse { models }
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("选择官方模型（按用量计费）")
                        .setItems(priced.toTypedArray()) { _, i ->
                            val name = models.getOrNull(i) ?: priced[i].substringBefore("（").trim()
                            ChatStore.saveHubModel(this, name)
                            ChatStore.updateProfile(this, profile.optString("id"), profile.optString("name"), profile.optString("url"), profile.optString("key"), name)
                            status.text = "已选官方模型：$name"
                            renderProfiles()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }.start()
        }
    }

    private fun pickModelForDefault(profile: JSONObject) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        container.addView(TextView(this).apply {
            text = "内置免费服务（地址与密钥已内置，不可修改）"; textSize = 11f; setTextColor(Color.parseColor("#7D8492")); setPadding(0, 0, 0, dp(8))
        })
        val fixed = profile.optJSONArray("fixedModels")
        val modelField = EditText(this).apply {
            setText(profile.optString("model")); textSize = 14f; setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = getDrawable(R.drawable.bg_soft)
        }
        container.addView(modelField)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                ChatStore.updateProfile(
                    this, profile.optString("id"),
                    profile.optString("name"),
                    profile.optString("url"),
                    profile.optString("key"),
                    modelField.text.toString().trim()
                )
                status.text = "模型已更新"
                renderProfiles()
            }
            .setNegativeButton("取消", null)
            .show()
        if (fixed != null && fixed.length() > 0) {
            // Fixed list: tap-to-apply chips instead of a network fetch.
            val listRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
            for (i in 0 until fixed.length()) {
                val m = fixed.optString(i)
                listRow.addView(TextView(this).apply {
                    text = if (m == profile.optString("model")) "✓ $m" else m
                    textSize = 14f
                    setTextColor(Color.parseColor(if (m == profile.optString("model")) "#3E7B4F" else "#10131A"))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        modelField.setText(m)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    }
                })
            }
            // Insert chips above the free-text field is fiddly post-show; the
            // list renders below it — still one-tap apply, which is the point.
            container.addView(listRow)
        } else {
            fetchModels(profile.optString("url"), profile.optString("key")) { model -> modelField.setText(model) }
        }
    }

    private fun fetchModels(baseUrl: String, key: String, onPicked: (String) -> Unit) {
        if (baseUrl.isBlank() || key.isBlank()) { status.text = "该配置缺少 Base URL 或 Key"; return }
        status.text = "获取模型列表…"
        Thread {
            runCatching {
                val c = (URL("$baseUrl/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer $key")
                }
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val data = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data")
                buildList { if (data != null) for (i in 0 until data.length()) data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add) }
            }.onSuccess { models ->
                runOnUiThread {
                    status.text = ""
                    if (models.isEmpty()) { status.text = "没有返回可用模型"; return@runOnUiThread }
                    AlertDialog.Builder(this)
                        .setTitle("选择模型")
                        .setItems(models.toTypedArray()) { _, i -> onPicked(models[i]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }.onFailure {
                runOnUiThread { status.text = "获取失败：${it.message}" }
            }
        }.start()
    }
}