package com.agentpad.workspace.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(
    private val appContext: Context,
    private val projectDir: File = SandboxInstaller.projectDir(appContext),
    private val sessionId: String = "",
    private val onPlanChanged: (() -> Unit)? = null
) {
    private val terminalTool = TerminalTool(
        SandboxRunner(
            sandboxDir = SandboxInstaller.sandboxDir(appContext),
            rootfs = SandboxInstaller.rootfsDir(appContext),
            nativeDir = SandboxInstaller.nativeDir(appContext),
            initScript = SandboxInstaller.initScript(appContext),
projectDir = projectDir,
                monitorSessionId = if (sessionId.isBlank()) "ai-terminal" else "ai-terminal-$sessionId"
            )
    )
    /** 与 Agent Skills 规范的 description 上限一致（照抄 RikkaHub SkillsTools.kt） */
    private val MAX_SKILL_DESCRIPTION_LENGTH = 1024

    private val tools = linkedMapOf<String, (JSONObject) -> String>()
    /** 远端 MCP 工具的规格（name → ToolSpec），供 specs() 返回正确描述与 schema。 */
    private val remoteSpecs = linkedMapOf<String, ToolSpec>()
    /** 插件工具表（toolName → Installed）。必须在 init{} 之前声明：
     *  Kotlin 按声明顺序初始化属性，若声明在 init 之后，init 调 loadPlugins() 时它是 null → NPE。 */
    private val pluginManifests = linkedMapOf<String, com.agentpad.workspace.data.PluginStore.Installed>()
    /** skill 插件（type="skill"）的工具规格：name → ToolSpec。
     *  和 pluginManifests 一样必须在 init{} 之前声明，否则 init 里 loadSkills() 会 NPE。 */
    private val skillSpecs = linkedMapOf<String, ToolSpec>()

    init {
        register("terminal") { args ->
            val cmd = args.optString("command")
            val timeout = args.optLong("timeout_ms", 60_000L)

            // 优先级：无线 ADB（配对码） > Shizuku ADB > 沙箱
            if (com.agentpad.workspace.data.WirelessAdb.isEnabled(appContext)) {
                runCatching {
                    val r = com.agentpad.workspace.data.WirelessAdb.exec(appContext, cmd, timeout)
                    JSONObject()
                        .put("success", r.exitCode == 0)
                        .put("status", if (r.exitCode == 0) "success" else "error")
                        .put("mode", "wireless-adb")
                        .put("summary", "wireless-adb | exit_code=${r.exitCode}")
                        .put("exit_code", r.exitCode)
                        .put("stdout", r.stdout.take(1800))
                        .put("stderr", r.stderr.take(1200))
                        .put("stdout_truncated", r.stdout.length > 1800)
                        .put("stderr_truncated", r.stderr.length > 1200)
                        .toString()
                }.getOrElse { e ->
                    JSONObject().put("success", false).put("mode", "wireless-adb")
                        .put("error", "无线 ADB 执行异常：${e.message}").toString()
                }
            } else if (com.agentpad.workspace.data.AdbShell.available(appContext)) {
                runCatching {
                    val r = com.agentpad.workspace.data.AdbShell.exec(appContext, cmd, timeout)
                    JSONObject()
                        .put("success", r.exitCode == 0)
                        .put("status", if (r.exitCode == 0) "success" else "error")
                        .put("mode", "adb")
                        .put("summary", "adb | exit_code=${r.exitCode}")
                        .put("exit_code", r.exitCode)
                        .put("stdout", r.stdout.take(1800))
                        .put("stderr", r.stderr.take(1200))
                        .put("stdout_truncated", r.stdout.length > 1800)
                        .put("stderr_truncated", r.stderr.length > 1200)
                        .toString()
                }.getOrElse { e ->
                    JSONObject().put("success", false).put("mode", "adb")
                        .put("error", "ADB 执行异常：${e.message}").toString()
                }
            } else {
                val out = terminalTool.execute(args)
                runCatching { JSONObject(out).put("mode", "sandbox").toString() }.getOrDefault(out)
            }
        }
        register("terminal_screen") { args -> terminalScreen(args) }
        register("write_file") { args -> writeFile(args) }
        register("edit_file") { args -> editFile(args) }
        register("read_file") { args -> readFile(args) }
        register("install_apk") { args -> installApk(args) }
        register("plan") { args -> planTool(appContext, args) }
        register("list_env_cards") { args -> listEnvCards(args) }
        register("create_plugin") { args -> createPlugin(args) }
        // 逆向工具集（原生层，不经 proot）—— 见 ai/ReverseTools.kt
        ReverseTools.register(this, appContext)
        // 生图工具：只有「生图配置」启用且勾了暴露才注册（否则模型会看到一堆用不了的工具）
        runCatching {
            val ig = com.agentpad.workspace.data.ImageGenStore.config(appContext)
            if (ig.usable && ig.exposeTool) ImageGenTools.register(this, appContext)
        }
        // ask_user：HITL 问答（照抄 RikkaHub AskUserTool）。模型调用时阻塞等用户回答。
        register(ASK_USER_TOOL_NAME) { args -> askUser(args) }
        register("create_skill") { args -> createSkill(args) }
        register("list_skills") { args -> listSkillsTool(args) }
        register("delete_skill") { args -> deleteSkillTool(args) }
        register("install_plugin") { args -> installPlugin(args) }
        register("list_plugins") { args -> listPlugins(args) }
        register("publish_plugin") { args -> publishPlugin(args) }
        register("manage_market_app") { args -> manageMarketApp(args) }
        register("list_my_market_apps") { args -> listMyMarketApps(args) }
        register("deploy_website") { args -> deployWebsite(args) }
        // 远程工作区（SSH）工具。实现见 ai/RemoteWorkspaceTools.kt。
        // 这些工具让 AI 能直接读写远程服务器上的文件、执行命令。
        RemoteWorkspaceTools.register(this, appContext)
        // 网页工具：WebView 渲染抓取 + 必应搜索。实现见 ai/WebTools.kt。
        WebTools.register(this, appContext)
        BrowserTools.register(this)
        if (com.agentpad.workspace.screencontrol.ScreenControlManager.isSupported) {
            com.agentpad.workspace.screencontrol.ScreenControlTools.register(this, appContext)
        }
        loadPlugins()
        loadRemoteTools()
        loadSkills()

        // ---- 多子智能体代理协调（API 设置里的独立开关控制）----
        // 关闭时这两个工具根本不注册 → 不会出现在 specs() → 模型完全看不到，
        // 不是「看到但拒绝」，避免模型绕着试。
        if (com.agentpad.workspace.data.ChatStore.subAgentsEnabled(appContext)) {
            val runner = SubAgentRunner(appContext, this)
            register("subagent_run") { args ->
                runner.runTask(
                    task = args.optString("task"),
                    contextText = args.optString("context"),
                    maxSteps = args.optInt("max_steps", 8),
                )
            }
            register("subagent_parallel") { args ->
                runner.runParallel(
                    tasks = args.optJSONArray("tasks") ?: org.json.JSONArray(),
                    contextText = args.optString("context"),
                    maxSteps = args.optInt("max_steps", 8),
                )
            }
        }
    }

    /**
     * 加载已安装 + 已启用的插件，注册为工具（name = "plugin__<id>"）。
     * 插件是「系统级工具」：JS 沙箱内可调 host.fs / host.http / host.shellExec 等原生能力，
     * 与终端脚本完全不同层级。每次对话新建 registry 时加载，保证安装/启停即时生效。
     */
    /**
     * 加载技能（照抄 RikkaHub 3.2.0 的 data/ai/tools/SkillsTools.kt 思路）。
     *
     * 与原版一致：**只注册一个 `use_skill` 工具**，把所有已启用技能的
     * name + description 放进该工具的系统提示里，由模型自己挑；
     * 调用时返回该技能 SKILL.md 的正文（去掉 frontmatter），
     * 也可传 path 读取技能目录内的附属文件（越界会被 SkillPaths 拦下）。
     *
     * 与 RikkaHub 的差别只有一处：原版把 enabledSkills 存在 Assistant 上（DataStore），
     * 本 App 存在 prefs("skill_config")，语义相同。
     */
    fun loadSkills() {
        // 先摘掉上一次注册的 use_skill（启停/增删后要能立刻反映）
        tools.remove("use_skill")
        skillSpecs.remove("use_skill")

        val manager = com.agentpad.workspace.data.skills.SkillManager.of(appContext)
        val available = runCatching { manager.enabledSkills() }.getOrDefault(emptyList())
        if (available.isEmpty()) return

        ToolProtocol.registerKnownTool("use_skill")

        val systemPrompt = buildString {
            appendLine("**Skills**")
            appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
            appendLine("<available_skills>")
            available.forEach { skill ->
                appendLine("  <skill>")
                // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                appendLine("    <name>${skill.name.escapeXml()}</name>")
                appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                appendLine("  </skill>")
            }
            append("</available_skills>")
        }

        register("use_skill") { args ->
            val name = args.optString("name")
            // 模型可能照抄系统提示中转义后的名称，两种形式都接受
            val skill = available.firstOrNull { it.name == name || it.name.escapeXml() == name }
            if (skill == null) {
                JSONObject().put("success", false)
                    .put("error", "Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                    .toString()
            } else {
                val path = args.optString("path").trim()
                if (path.isBlank()) {
                    val raw = runCatching { skill.skillFile.readText() }.getOrNull()
                    if (raw == null) {
                        JSONObject().put("success", false).put("error", "Skill '$name' not found").toString()
                    } else {
                        // 返回去掉 frontmatter 的正文，与 RikkaHub extractBody 一致
                        val body = com.agentpad.workspace.data.skills.SkillFrontmatterParser.extractBody(raw)
                        JSONObject()
                            .put("success", true)
                            .put("skill", skill.name)
                            .put("description", skill.description)
                            .put("content", body)
                            .put("truncated", body.length > 28000)
                            .put("files", org.json.JSONArray().apply {
                                manager.listSkillFiles(skill).forEach { put(it) }
                            })
                            .toString()
                    }
                } else {
                    val target = com.agentpad.workspace.data.skills.SkillPaths.resolveSkillFile(skill.skillDir, path)
                    if (target == null) {
                        JSONObject().put("success", false)
                            .put("error", "Path '$path' is outside the skill directory").toString()
                    } else if (!target.isFile) {
                        JSONObject().put("success", false)
                            .put("error", "File '$path' not found in skill '$name'").toString()
                    } else {
                        JSONObject().put("success", true)
                            .put("skill", skill.name)
                            .put("path", path)
                            .put("content", runCatching { target.readText() }.getOrDefault(""))
                            .toString()
                    }
                }
            }
        }

        skillSpecs["use_skill"] = ToolSpec(
            "use_skill",
            systemPrompt,
            "{\"type\":\"object\",\"properties\":{" +
                "\"name\":{\"type\":\"string\",\"description\":\"The name of the skill to use\"}," +
                "\"path\":{\"type\":\"string\",\"description\":\"Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths.\"}" +
                "},\"required\":[\"name\"]}",
        )
    }

    /**
     * ask_user 执行体。
     *
     * 与 RikkaHub 的差别：原版把这次调用挂成 Pending、结束本轮并等下一轮用户消息；
     * 我们这里由 AskUserHub 在原地等（本函数跑在后台线程，不会卡 UI），
     * 用户答卷后返回 {"answers":{...}} 作为 tool 结果，模型继续往下答。
     */
    private fun askUser(args: JSONObject): String {
        val questions = parseAskUserQuestions(args)
        if (questions.isEmpty()) {
            return JSONObject().put("success", false)
                .put("error", "缺少 questions：必须给 1 个以上问题，每项含 id / question，可选 options 与 selection_type(text|single|multi)")
                .toString()
        }
        // callId 用不了（execute 只给 args），Hub 自己维护「当前唯一等待」的语义
        val answer = AskUserHub.await(AskUserRequest(callId = "ask_user", questions = questions))
        return if (answer == null) {
            JSONObject().put("success", false)
                .put("cancelled", true)
                .put("error", "用户没有回答（取消或超时）。可以换个说法再问一次，或按默认方案继续。")
                .toString()
        } else {
            JSONObject()
                .put("success", true)
                .put("answers", JSONObject(answer).optJSONObject("answers") ?: JSONObject())
                .toString()
        }
    }

    private fun String.escapeXml(): String = buildString(length) {
        val amp = 0x26.toChar()   // &
        val lt = 0x3C.toChar()    // <
        val gt = 0x3E.toChar()    // >
        val quot = 0x22.toChar()  // "
        val apos = 0x27.toChar()  // '
        for (c in this@escapeXml) when (c) {
            amp -> append("&#38;")
            lt -> append("&#60;")
            gt -> append("&#62;")
            quot -> append("&#34;")
            apos -> append("&#39;")
            else -> append(c)
        }
    }

    fun loadPlugins() {
        // 先移除上一次注册的插件工具（改启用态/覆盖安装后要能反映变化）
        pluginManifests.keys.toList().forEach { tools.remove(it) }
        pluginManifests.clear()
        val plugins = runCatching {
            com.agentpad.workspace.data.PluginStore.installed(appContext)
                .filter { it.enabled && it.manifest.type != "skill" }   // skill 走 use_skill，不再当插件注册
        }.getOrDefault(emptyList())
        plugins.forEach { p ->
            val toolName = "plugin__${p.manifest.id}"
            pluginManifests[toolName] = p
            tools[toolName] = { args -> runPlugin(p, args) }
        }
    }

    /** 执行一个插件：JS 类型走 PluginRuntime，声明式类型走宿主原子能力。 */
    private fun runPlugin(p: com.agentpad.workspace.data.PluginStore.Installed, args: JSONObject): String {
        return try {
            when (p.manifest.type) {
                "js" -> {
                    val src = com.agentpad.workspace.data.PluginStore.readEntry(appContext, p)
                        ?: return JSONObject().put("success", false).put("error", "读取插件入口失败").toString()
                    val res = com.agentpad.workspace.ai.PluginRuntime.runJsPlugin(
                        appContext, p, src, args, projectDir,
                    )
                    JSONObject().put("success", res.ok).put("output", res.output).toString()
                }
                else -> JSONObject().put("success", false)
                    .put("error", "不支持的插件类型：${p.manifest.type}").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", "插件执行异常：${e.message}").toString()
        }
    }

    /**
     * 列出「设置 → 下载环境」里所有可下载资源卡片及其安装状态。
     *
     * 存在的意义：AI 经常凭记忆瞎判断某个工具链「有没有」，谎报缺失/存在。
     * 这个工具把**真实清单**喂给它（id / 名称 / 说明 / 是否已安装），
     * 让 AI 只能照实说，不再自说自话。
     */
    private fun listEnvCards(args: JSONObject): String {
        val cards = EnvCatalog.cards(appContext)
        val arr = JSONArray()
        val installed = mutableListOf<String>()
        for (c in cards) {
            val ok = runCatching { c.installed(appContext) }.getOrDefault(false)
            if (ok) installed.add(c.title)
            arr.put(JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("desc", c.desc)
                .put("installed", ok))
        }
        return JSONObject()
            .put("success", true)
            .put("count", cards.size)
            .put("installed_count", installed.size)
            .put("cards", arr)
            .put("installed_titles", JSONArray().apply { installed.forEach { put(it) } })
            .put("note", "这是「设置 → 下载环境」里全部可下载组件的真实清单。只能照此判断某工具链是否存在；清单里没有的，就不要声称它可下载。缺的东西让用户去「设置 → 下载环境」手动安装，AI 不能代装。")
            .toString()
    }

    /**
     * AI 自建插件：直接传 manifest JSON + 插件文件内容，落盘成已启用的插件。
     * 调用后该插件立刻注册为工具（下一次对话/工具刷新即可见），AI 可再用它。
     */
    private fun createPlugin(args: JSONObject): String {
        val manifestJson = args.optString("manifest")
        if (manifestJson.isBlank()) {
            return JSONObject().put("success", false).put("error", "缺少 manifest").toString()
        }
        val filesArr = args.optJSONArray("files")
            ?: return JSONObject().put("success", false)
                .put("error", "缺少 files 数组：[{\"path\":\"main.js\",\"content\":\"...\"}]").toString()
        val files = linkedMapOf<String, String>()
        for (i in 0 until filesArr.length()) {
            val o = filesArr.optJSONObject(i) ?: continue
            val p = o.optString("path").trim()
            if (p.isNotBlank()) files[p] = o.optString("content")
        }
        if (files.isEmpty()) return JSONObject().put("success", false).put("error", "files 为空").toString()

        val (ok, msg, manifest) = com.agentpad.workspace.data.PluginStore.installFromSource(appContext, manifestJson, files)
        // 重新加载插件，使新插件立即可用
        if (ok) runCatching { loadPlugins() }
        return JSONObject()
            .put("success", ok).put("message", msg)
            .put("plugin_id", manifest?.id ?: "")
            .put("tool_name", if (manifest != null) "plugin__${manifest.id}" else "")
            .put("hint", if (ok) "插件已安装并启用，可立即像内置工具一样调用它（工具名见 tool_name）。" else "")
            .toString()
    }
    /**
     * 从 URL 下载并安装插件包（.apk-plugin / zip）——市场一键安装也走这里。
     */
    private fun installPlugin(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isBlank()) return JSONObject().put("success", false).put("error", "缺少 url").toString()
        return runCatching {
            val tmp = File(appContext.cacheDir, "plugin_dl_${System.currentTimeMillis()}.zip")
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000
                setRequestProperty("User-Agent", "AgentPad/PluginInstaller")
            }
            conn.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            conn.disconnect()
            val (ok, msg, manifest) = com.agentpad.workspace.data.PluginStore.installFromZip(appContext, tmp)
            tmp.delete()
            if (ok) runCatching { loadPlugins() }
            JSONObject().put("success", ok).put("message", msg)
                .put("plugin_id", manifest?.id ?: "")
                .put("tool_name", if (manifest != null) "plugin__${manifest.id}" else "")
                .toString()
        }.getOrElse { JSONObject().put("success", false).put("error", "下载失败：${it.message}").toString() }
    }

    /**
     * AI 制作技能（Skill）：照抄 RikkaHub 3.2.0 的 SkillValidator + SkillManager.saveSkill
     * —— 内容必须是带 YAML frontmatter 的完整 SKILL.md，目录名直接取 frontmatter 的 name。
     *
     * 与 create_plugin 的分工：
     *   create_plugin → 可执行的 JS 工具（run(args, host)）；
     *   create_skill  → 给 AI 自己看的 SKILL.md 操作手册，之后用 use_skill 取回。
     */
    private fun createSkill(args: JSONObject): String {
        var content = args.optString("content")
        if (content.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "缺少 content：必须是完整的 SKILL.md（含 --- frontmatter，name + description）")
                .toString()
        }
        // 容错：只给了 name/description 却没写 frontmatter 时，替他拼一个
        if (!content.trimStart().startsWith("---")) {
            val name = args.optString("name")
            if (name.isBlank()) {
                return JSONObject().put("success", false)
                    .put("error", "content 不含 frontmatter 时必须同时传 name")
                    .toString()
            }
            val desc = args.optString("description").ifBlank { name }
            content = "---\nname: $name\ndescription: \"$desc\"\n---\n\n" + content.trimStart()
        }

        val manager = com.agentpad.workspace.data.skills.SkillManager.of(appContext)
        val extra = mutableMapOf<String, ByteArray>()
        args.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("path").trim()
                if (p.isNotBlank()) extra[p] = o.optString("content").toByteArray()
            }
        }
        val bytes = LinkedHashMap<String, ByteArray>()
        bytes["SKILL.md"] = content.toByteArray()
        bytes.putAll(extra)

        // 先用 Validator 拿 name（它同时会把 6 种非法情况全挡下来）
        val name = com.agentpad.workspace.data.skills.SkillValidator.validate(content).getOrElse { err ->
            val code = (err as? com.agentpad.workspace.data.skills.SkillValidationException)?.error
            // 直接内联文案，不用扩展函数（全限定名调用时扩展函数解析不到）
            val msg = when (code) {
                com.agentpad.workspace.data.skills.SkillValidationError.MissingName ->
                    "SKILL.md 的 frontmatter 缺少 name"
                com.agentpad.workspace.data.skills.SkillValidationError.NameHasSurroundingWhitespace ->
                    "name 首尾不能有空格"
                com.agentpad.workspace.data.skills.SkillValidationError.NameTooLong ->
                    "name 不能超过 ${com.agentpad.workspace.data.skills.SkillValidator.MAX_NAME_LENGTH} 个字符"
                com.agentpad.workspace.data.skills.SkillValidationError.NameStartsWithDot ->
                    "name 不能以 . 开头（会被当成隐藏目录）"
                com.agentpad.workspace.data.skills.SkillValidationError.NameHasInvalidChars ->
                    "name 不能含 / \\ 或控制字符"
                com.agentpad.workspace.data.skills.SkillValidationError.MissingDescription ->
                    "SKILL.md 的 frontmatter 缺少 description"
                null -> "SKILL.md 校验失败：${err.message}"
            }
            return JSONObject().put("success", false).put("error", msg).toString()
        }
        val ok = manager.saveSkillFileBytesAtomically(name, bytes)
        if (ok) {
            manager.setEnabled(name, true)
            runCatching { loadSkills() }   // 立刻重新注册 use_skill，下一次调用就能取回
        }
        return JSONObject()
            .put("success", ok)
            .put("skill", name)
            .put("hint", if (ok) "技能已保存并启用。之后用 use_skill(name=\"$name\") 取回它的 SKILL.md。" else "")
            .toString()
    }

    /** 列出全部技能（含启用态与是否为内置），AI 自查用。 */
    private fun listSkillsTool(args: JSONObject): String {
        val manager = com.agentpad.workspace.data.skills.SkillManager.of(appContext)
        val arr = JSONArray()
        manager.listSkills().forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("compatibility", s.compatibility.orEmpty())
                    .put("enabled", manager.isEnabled(s.name))
                    .put("builtin", s.builtin)
                    .put("dir", s.skillDir.absolutePath)
            )
        }
        val invalid = JSONArray()
        manager.listInvalidSkillDirs().forEach {
            invalid.put(JSONObject().put("dir", it.dirName).put("reason", it.reason.name))
        }
        return JSONObject()
            .put("success", true)
            .put("count", arr.length())
            .put("skills", arr)
            .put("invalid_dirs", invalid)
            .put("note", "只有 enabled=true 的技能会出现在 use_skill 的候选里。目录：${manager.getSkillsDir().absolutePath}")
            .toString()
    }

    /** 删除一个用户技能（内置技能不可删）。 */
    private fun deleteSkillTool(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) {
            return JSONObject().put("success", false).put("error", "缺少 name").toString()
        }
        val manager = com.agentpad.workspace.data.skills.SkillManager.of(appContext)
        val skill = manager.findSkill(name)
            ?: return JSONObject().put("success", false)
                .put("error", "找不到技能「$name」").toString()
        if (skill.builtin) {
            return JSONObject().put("success", false).put("error", "「$name」是内置技能，只读，不能删除").toString()
        }
        val ok = manager.deleteSkill(skill)
        if (ok) runCatching { loadSkills() }
        return JSONObject().put("success", ok).put("deleted", name).toString()
    }

    /** 列出已安装插件（AI 自查用）。 */
    private fun listPlugins(args: JSONObject): String {
        val list = com.agentpad.workspace.data.PluginStore.installed(appContext)
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.manifest.id)
                    .put("name", p.manifest.name)
                    .put("tool_name", "plugin__${p.manifest.id}")
                    .put("enabled", p.enabled)
                    .put("type", p.manifest.type)
                    .put("params", JSONArray(p.manifest.params.map { it.name }))
                    .put("permissions", JSONArray(p.manifest.permissions))
            )
        }
        return JSONObject().put("success", true).put("count", list.size).put("plugins", arr).toString()
    }

    /**
     * AI 发布插件到市场：把本地已安装插件打包 + 上传（需用户已登录）。
     * 用户不用懂上传 —— AI 直接把插件推到市场。
     */
    private fun publishPlugin(args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isBlank()) return JSONObject().put("success", false).put("error", "缺少 id（插件 id）").toString()
        val name = args.optString("name").trim()
        val desc = args.optString("description").trim()
        if (name.isBlank()) return JSONObject().put("success", false).put("error", "缺少 name（发布名）").toString()
        if (desc.isBlank()) return JSONObject().put("success", false).put("error", "缺少 description（简介）").toString()

        val token = com.agentpad.workspace.data.UserStore.token(appContext)
        if (token.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "未登录：发布插件到市场需要用户登录账号。请先让用户在「设置 → 登录/注册」登录后再试。")
                .toString()
        }
        val pkg = com.agentpad.workspace.data.PluginStore.installed(appContext).firstOrNull { it.manifest.id == id }
            ?: return JSONObject().put("success", false).put("error", "本地没有插件：$id").toString()
        val zip = File(appContext.cacheDir, "publish_${id.replace(Regex("[^\\w.-]"), "_")}.zip")
        val packed = com.agentpad.workspace.data.PluginStore.packToZip(appContext, id, zip)
            ?: return JSONObject().put("success", false).put("error", "打包失败").toString()
        return try {
            val (code, text) = com.agentpad.workspace.data.MarketStore.uploadPlugin(token, name, desc, packed)
            val resp = runCatching { JSONObject(text) }.getOrNull()
            val msg = resp?.optString("msg") ?: "HTTP $code"
            JSONObject().put("success", resp?.optInt("code") == 200)
                .put("message", msg)
                .put("http", code)
                .put("plugin_id", id)
                .put("hint", if (resp?.optInt("code") == 200) "插件已直接上架（插件市场免审核，无需等待）。所有人可在 应用市场 → 插件 里一键安装；如需撤销，用 manage_market_app(action=unpublish)。" else "")
                .toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", "上传失败：${e.message}").toString()
        } finally {
            packed.delete()
        }
    }

    /**
     * AI 下架 / 重新上架自己发布的插件（或应用）。
     *
     * 之前只有 publish_plugin，没有反向操作，AI 只能发布不能撤 → 用户说「下架」时
     * AI 只能让他自己去 UI 点。这里把 market_manage.php 的 unpublish / republish
     * 暴露成工具。
     *
     * 目标定位优先级：
     *  1) 显式 app_id
     *  2) name（市场显示名，模糊匹配）→ 在「我的」列表里找
     *  3) plugin_id（本地插件 manifest id）→ 打包名/tool_name 反查
     */
    private fun manageMarketApp(args: JSONObject): String {
        val action = args.optString("action", "unpublish").trim().lowercase()
        if (action != "unpublish" && action != "republish") {
            return JSONObject().put("success", false)
                .put("error", "action 只能是 unpublish（下架）或 republish（重新提交审核）").toString()
        }
        val token = com.agentpad.workspace.data.UserStore.token(appContext)
        if (token.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "未登录：管理市场作品需要用户登录账号。请先让用户在「设置 → 登录/注册」登录后再试。")
                .toString()
        }

        // 拉「我的」，拿 status / app_name / kind，既能定位也能预判合法性
        val (err, mine) = com.agentpad.workspace.data.MarketStore.myApps(token)
        if (err != null) {
            return JSONObject().put("success", false).put("error", "读取我的作品失败：$err").toString()
        }
        val onlyPlugins = args.optBoolean("plugins_only", false)

        var appId = args.optInt("app_id", 0)
        var target: JSONObject? = mine.firstOrNull { it.optInt("id") == appId }

        if (target == null) {
            val nameQ = args.optString("name").trim()
            val pidQ = args.optString("plugin_id").trim()
            val pool = if (onlyPlugins) mine.filter { it.optString("kind") == "plugin" } else mine
            target = when {
                nameQ.isNotBlank() -> pool.firstOrNull { it.optString("app_name").equals(nameQ, ignoreCase = true) }
                    ?: pool.firstOrNull { it.optString("app_name").contains(nameQ, ignoreCase = true) }
                pidQ.isNotBlank() -> pool.firstOrNull {
                    it.optString("app_name").contains(pidQ, ignoreCase = true) ||
                        it.optString("apk_path", "").contains(pidQ, ignoreCase = true)
                }
                else -> null
            }
            appId = target?.optInt("id") ?: 0
        }

        if (target == null || appId <= 0) {
            val names = mine.joinToString("、") { "${it.optString("app_name")}(id=${it.optInt("id")})" }
            return JSONObject().put("success", false)
                .put("error", "没有在你的账号下找到目标作品。请提供 app_id 或与市场显示名一致的 name。")
                .put("my_items", if (names.isBlank()) "（你的账号下暂无作品）" else names)
                .toString()
        }

        val st = target.optInt("status")
        val appName = target.optString("app_name")
        if (action == "unpublish" && st == 9) {
            return JSONObject().put("success", true).put("message", "「$appName」已经是下架状态")
                .put("app_id", appId).put("status", 9).toString()
        }
        if (action == "republish" && st != 9 && st != 2) {
            return JSONObject().put("success", false)
                .put("error", "「$appName」当前状态为 $st，只有已下架(9)或已拒绝(2)才能重新提交").toString()
        }

        val (code, text) = com.agentpad.workspace.data.MarketStore.manageApp(token, action, appId)
        val resp = runCatching { JSONObject(text) }.getOrNull()
        val ok = resp?.optInt("code") == 200
        return JSONObject()
            .put("success", ok)
            .put("message", resp?.optString("msg") ?: "HTTP $code")
            .put("http", code)
            .put("action", action)
            .put("app_id", appId)
            .put("app_name", appName)
            .put("status_before", st)
            .put("status_after", when {
                !ok -> st
                action == "unpublish" -> 9
                else -> 0
            })
            .put("hint", when {
                !ok -> resp?.optString("msg") ?: ""
                action == "unpublish" -> "已下架，市场不再展示。可用 action=republish 重新提交审核。"
                else -> "已重新提交，等待审核。"
            })
            .toString()
    }

    /**
     * 列出自己在市场发布的作品（含状态），供 AI 在自己下架前确认目标。
     */
    private fun listMyMarketApps(args: JSONObject): String {
        val token = com.agentpad.workspace.data.UserStore.token(appContext)
        if (token.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "未登录：需要用户登录后才能查看「我的作品」。").toString()
        }
        val (err, mine) = com.agentpad.workspace.data.MarketStore.myApps(token)
        if (err != null) return JSONObject().put("success", false).put("error", err).toString()
        val onlyPlugins = args.optBoolean("plugins_only", false)
        val statusName = mapOf(0 to "待审核", 1 to "已上架", 2 to "已拒绝", 9 to "已下架")
        val arr = JSONArray()
        mine.filter { !onlyPlugins || it.optString("kind") == "plugin" }.forEach { a ->
            arr.put(JSONObject()
                .put("app_id", a.optInt("id"))
                .put("app_name", a.optString("app_name"))
                .put("kind", a.optString("kind"))
                .put("status", a.optInt("status"))
                .put("status_text", statusName[a.optInt("status")] ?: "未知")
                .put("downloads", a.optInt("downloads")))
        }
        return JSONObject().put("success", true).put("count", arr.length()).put("items", arr).toString()
    }

    /**
     * 部署网站（原生工具）：把本地目录或 zip 上传到用户自己的托管服务器。
     *
     * path：目录（自动打包）或 .zip 文件
     * host：normal（1GB/月·1MB/s·稳定）或 performance（10GB/月·5MB/s·可能不稳定）
     */
    private fun deployWebsite(args: JSONObject): String {
        val rawPath = args.optString("path").trim()
        if (rawPath.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "缺少 path：请传入要部署的网页目录或 .zip 文件路径").toString()
        }
        val host = args.optString("host", "normal").trim()
            .let { if (it == "performance") "performance" else "normal" }
        val siteName = args.optString("site_name").trim()

        // 1) 登录检查
        val token = com.agentpad.workspace.data.UserStore.token(appContext)
        if (token.isBlank()) {
            return JSONObject().put("success", false)
                .put("error", "未登录：部署网站需要用户先登录账号。请让用户在「设置 → 登录/注册」登录后再试。")
                .toString()
        }

        // 2) 解析路径
        val target = resolveWorkspacePath(rawPath)
            ?: return JSONObject().put("success", false).put("error", "路径无效：$rawPath").toString()

        // 3) 准备 zip
        val zipFile: File
        var tempZip: File? = null
        try {
            if (target.isDirectory) {
                val z = File(appContext.cacheDir, "deploy_${System.currentTimeMillis()}.zip")
                val packed = packDirectoryToZip(target, z)
                if (!packed) {
                    return JSONObject().put("success", false)
                        .put("error", "打包失败：目录为空或读取受限（${target.absolutePath}）").toString()
                }
                tempZip = z
                zipFile = z
            } else if (target.isFile && target.name.lowercase().endsWith(".zip")) {
                zipFile = target
            } else {
                return JSONObject().put("success", false)
                    .put("error", "路径必须是目录或 .zip 文件：${target.absolutePath}").toString()
            }

            if (zipFile.length() <= 0) {
                return JSONObject().put("success", false).put("error", "压缩包为空").toString()
            }
            if (zipFile.length() > 20L * 1048576) {
                return JSONObject().put("success", false)
                    .put("error", "压缩包超过 20MB 上限（当前 ${zipFile.length() / 1048576} MB），请精简后再试").toString()
            }

            // 4) 上传部署
            val site = com.agentpad.workspace.data.DeployStore.deploy(token, host, siteName, zipFile)

            return JSONObject()
                .put("success", true)
                .put("host", site.host)
                .put("host_label", site.hostLabel)
                .put("site_name", site.siteName)
                .put("url", site.preferredUrl)
                .put("url_direct", site.urlDirect)
                .put("url_gateway", site.urlGateway)
                .put("slug", site.slug)
                .put("status", site.statusText)
                .put("expires_at", site.expiresAt)
                .put("days_left", site.daysLeft)
                .put("traffic_quota_text", site.trafficText)
                .put("message", "网站已部署成功")
                .put(
                    "hint",
                    "【重要】每个用户只能选一个服务器档位，本次已锁定为「${site.hostLabel}」。" +
                        "如需更换服务器，必须先删除当前站点。" +
                        "访问地址优先用 url（直连），备用 url_gateway。" +
                        "有效期 30 天，可在「设置 → 站点托管」续期。" +
                        if (host == "performance")
                            " 这是高性能档：每月 10GB 流量、5MB/s 带宽，但主机可能不稳定，请提醒用户自行备份重要数据。"
                        else ""
                )
                .toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false)
                .put("error", "部署失败：${e.message ?: e.javaClass.simpleName}").toString()
        } finally {
            tempZip?.delete()
        }
    }

    /** 把目录递归打包成 zip（不含顶层目录本身，index.html 直接位于根）。 */
    private fun packDirectoryToZip(dir: File, out: File): Boolean {
        return runCatching {
            val root = dir.canonicalFile
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                var count = 0
                root.walkTopDown().forEach { f ->
                    if (f == root) return@forEach
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (rel.startsWith("..")) return@forEach
                    if (f.isDirectory) {
                        zos.putNextEntry(java.util.zip.ZipEntry("$rel/"))
                        zos.closeEntry()
                    } else if (f.isFile) {
                        zos.putNextEntry(java.util.zip.ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                    }
                }
                count > 0
            }
        }.getOrDefault(false)
    }

    /**
     * 注册远端 MCP 工具（供 ToolRegistry 内部调用）。
     * @param name 合成名（mcp__<serverId>__<toolName>）
     * @param spec 规格（描述 + JSON Schema）
     * @param block 执行体：调用远端并返回结果字符串
     */
    fun registerRemote(name: String, spec: ToolSpec, block: (JSONObject) -> String) {
        remoteSpecs[name] = spec
        tools[name] = block
    }

    /**
     * 从 [McpStore] 加载已启用的远端 MCP 工具并注册。
     * 由 ChatActivity 在创建 registry 后调用；每次对话都重新加载，保证配置改动即时生效。
     */
    fun loadRemoteTools() {
        val enabledRemotes = McpStore.remotes(appContext).filter { it.enabled && it.exposeToAi }
        if (enabledRemotes.isEmpty()) return
        val allTools = McpStore.remoteTools(appContext).filter { rt ->
            enabledRemotes.any { it.id == rt.serverId }
        }
        allTools.forEach { rt ->
            val server = enabledRemotes.firstOrNull { it.id == rt.serverId } ?: return@forEach
            val composite = McpStore.compositeName(rt.serverId, rt.name)
            val spec = ToolSpec(
                name = composite,
                description = "[${server.name}] ${rt.description}".trim(),
                parametersSchema = rt.schema,
            )
            registerRemote(composite, spec) { args ->
                try {
                    val session = McpClient.initialize(server.url, server.token)
                    McpClient.callTool(server.url, server.token, session, rt.name, args)
                } catch (e: Exception) {
                    JSONObject().put("success", false)
                        .put("error", "${server.name} 调用失败：${e.message}").toString()
                }
            }
        }
    }

    /** 任务规划工具：AI 自主创建/更新计划，用户可在侧栏实时看到进度。
     *  action=set：整体替换计划；action=update：更新单条状态；action=clear：清空。 */
    private fun planTool(context: Context, args: JSONObject): String {
        val sid = sessionId
        if (sid.isBlank()) return JSONObject().put("success", false).put("error", "no session").toString()
        val items = PlanStore.load(context, sid).toMutableList()
        when (args.optString("action", "set")) {
            "set" -> {
                val arr = args.optJSONArray("items") ?: return JSONObject().put("success", false).put("error", "items array required").toString()
                items.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("text").trim()
                    if (text.isBlank()) continue
                    items.add(PlanStore.Item("p_${i + 1}", text, o.optString("status", "pending").ifBlank { "pending" }))
                }
            }
            "update" -> {
                val id = args.optString("id")
                val status = args.optString("status")
                if (id.isBlank() || status.isBlank()) return planError("id and status required", sid)
                val idx = items.indexOfFirst { it.id == id }
                if (idx < 0) return planError("id not found: $id (available: ${items.joinToString { it.id }})", sid)
                items[idx] = items[idx].copy(status = status)
            }
            "add" -> {
                val text = args.optString("text").trim()
                if (text.isBlank()) return JSONObject().put("success", false).put("error", "text required").toString()
                items.add(PlanStore.Item("p_${items.size + 1}_${System.currentTimeMillis() % 10000}", text, "pending"))
            }
            "clear" -> PlanStore.clear(context, sid)
        }
        PlanStore.save(context, sid, items)
        onPlanChanged?.invoke()
        return JSONObject()
            .put("success", true)
            .put("plan", JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("status", it.status)) } })
            .toString()
    }

    /** plan 工具错误返回：附带当前完整计划，AI 可自纠而不用盲目重试。 */
    private fun planError(msg: String, sid: String): String {
        val arr = JSONArray()
        PlanStore.load(appContext, sid).forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("status", it.status)) }
        return JSONObject().put("success", false).put("error", msg).put("plan", arr).toString()
    }

    fun register(name: String, block: (JSONObject) -> String) { tools[name] = block }

    private fun terminalScreen(args: JSONObject): String {
        val requested = args.optString("session_id").trim()
        val session = TerminalMonitor.snapshot().firstOrNull { it.id == requested } ?: TerminalMonitor.snapshot().lastOrNull()
            ?: return JSONObject().put("success", false).put("error", "no terminal session").toString()
        val max = args.optInt("max_chars", 4000).coerceIn(200, 12000)
        val output = session.output.takeLast(max)
        return JSONObject().put("success", true).put("session_id", session.id).put("running", session.running)
            .put("command_running", TerminalService.commandRunning(session.id))
            .put("cwd", session.cwd).put("command", session.command).put("output", output)
            .put("truncated", session.output.length > output.length).toString()
    }

    fun specs(): List<ToolSpec> = tools.keys.map { name ->
        // ask_user：HITL 问答（规格写死在 AskUserTool.kt，与 RikkaHub schema 对齐）
        if (name == ASK_USER_TOOL_NAME) return@map askUserSpec()
        // 逆向工具集（原生 DexKit）规格
        ReverseTools.spec(name)?.let { return@map it }
        // 生图工具集规格
        ImageGenTools.spec(name)?.let { return@map it }
        // 远端 MCP 工具优先用缓存规格
        remoteSpecs[name]?.let { return@map it }
        // skill 工具（use_skill / create_skill / list_skills / delete_skill）规格
        skillSpecs[name]?.let { return@map it }
        // 插件工具：从清单生成 schema
        pluginManifests[name]?.let { p -> return@map pluginSpec(name, p) }
        when (name) {
            "terminal" -> ToolSpec("terminal", "Run a shell command in the current interactive terminal directory", "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},\"timeout_ms\":{\"type\":\"integer\"}},\"required\":[\"command\"]}")
            "terminal_screen" -> ToolSpec("terminal_screen", "Read output for a running or interactive task. command_running tracks the command; running tracks shell liveness. Space observations apart and avoid duplicate builds.", "{\"type\":\"object\",\"properties\":{\"session_id\":{\"type\":\"string\"},\"max_chars\":{\"type\":\"integer\"}}}")
            "write_file" -> ToolSpec(
                "write_file",
                "Create a file or fully overwrite it. Path is workspace-relative (e.g. \"project/app.lf\") or absolute (e.g. \"/sdcard/Download/a.txt\"). Parent directories are created automatically.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}"
            )
            "edit_file" -> ToolSpec(
                "edit_file",
                "Partially edit an existing file: replace the FIRST occurrence of [old] with [new]. Cheaper than rewriting the whole file. Use write_file to create new files or full rewrites.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old\":{\"type\":\"string\"},\"new\":{\"type\":\"string\"}},\"required\":[\"path\",\"old\",\"new\"]}"
            )
            "read_file" -> ToolSpec(
                "read_file",
                "Read a text file. Path is workspace-relative (e.g. \"src/main.lf\") or absolute (e.g. \"/sdcard/a.txt\"). Optional line-based windowing for large files.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"end_line\":{\"type\":\"integer\"}},\"required\":[\"path\"]}"
            )
            "install_apk" -> ToolSpec(
                "install_apk",
                "Open the Android system installer for an APK file. Path may be workspace-relative (e.g. \"build/app-debug.apk\") or absolute (e.g. \"/sdcard/Download/app.apk\"). Use this after building or downloading an APK so the user does not have to locate it manually. The system still shows its own install confirmation dialog.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"
            )
            "plan" -> ToolSpec(
                "plan",
                "Optional task planning tool. Use it WHENEVER you take on a multi-step task: 1) BEFORE starting work, call with action=set to create a short plan (3-7 items); 2) when you START an item call action=update status=in_progress; 3) when it is DONE call action=update status=done; 4) plan may be revised with action=set/add as work evolves. Skip planning for trivial single-step tasks. The user sees the plan live in a side panel.",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"set\",\"update\",\"add\",\"clear\"]},\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"done\"]}}}},\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"done\"]},\"text\":{\"type\":\"string\"}},\"required\":[\"action\"]}"
            )
            "list_env_cards" -> ToolSpec(
                "list_env_cards",
                "List every downloadable component available under 设置 → 下载环境 (Settings → Download Environment), with the REAL installed/not-installed status of each, read from the device. CALL THIS BEFORE claiming a toolchain/environment exists or is missing, or before telling the user to install something. Never guess: only report components that appear in this list. You cannot install components yourself — the user must tap the card in 设置 → 下载环境.",
                "{\"type\":\"object\",\"properties\":{}}"
            )
            "create_plugin" -> ToolSpec(
                "create_plugin",
                "Create (or overwrite) a JS plugin that becomes a system-level tool for yourself. Use this WHENEVER the user asks you to build your own tool/plugin/capability that the built-in tools can't do. " +
                    "A plugin = manifest JSON + a JS entry file whose `run(args, host)` implements the logic. " +
                    "The `host` object exposes: host.fs.read/write/exists/list/mkdir/delete (perm fs.read/fs.write), host.http.get/post/request (perm net.http), host.shellExec (perm shell), host.notify (perm notify), host.toast, host.clipboardRead/Write (perm clipboard), host.device.sms/contacts/location/info (perm device.sms/device.contacts/device.location), and helpers host.time/random/sleep/base64Encode/base64Decode/jsonParse/jsonStringify. " +
                    "Declare every capability you use in the manifest's `permissions` array, otherwise it throws. After creation the plugin is enabled immediately and callable as tool `plugin__<id>`.",
                "{\"type\":\"object\",\"properties\":{\"manifest\":{\"type\":\"string\",\"description\":\"Full manifest JSON with format=agentpad-plugin, id, name, type=js, entry, params[], permissions[]\"},\"files\":{\"type\":\"array\",\"description\":\"Plugin files\",\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}}},\"required\":[\"manifest\",\"files\"]}"
            )
            "create_skill" -> ToolSpec(
                "create_skill",
                "Create (or overwrite) a Skill: a SKILL.md playbook saved on the device and exposed to you via use_skill. Use this WHENEVER the user says 做个技能 / skill / 操作手册, or when a repeatable procedure (install flow, troubleshooting steps, project conventions) should be persisted so future sessions follow it. " +
                    "Format is the Agent Skills standard (same as RikkaHub/Claude skills): the SKILL.md MUST start with a YAML frontmatter block delimited by --- lines, containing `name` (doubles as the folder name: <=64 chars, no / \\ and not starting with .) and `description` (one line, required). The body then holds 适用场景 / 执行步骤 / 约束边界 / 期望输出. " +
                    "Not executable JS — use create_plugin for that. Optional files[] adds supporting scripts/templates inside the skill folder.",
                "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Full SKILL.md text, starting with ---\\nname: ...\\ndescription: ...\\n---\"},\"name\":{\"type\":\"string\",\"description\":\"Only needed when content has no frontmatter; used to build it\"},\"description\":{\"type\":\"string\",\"description\":\"Only needed when content has no frontmatter\"},\"files\":{\"type\":\"array\",\"description\":\"Optional extra files inside the skill folder\",\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}}},\"required\":[\"content\"]}"
            )
            "list_skills" -> ToolSpec(
                "list_skills",
                "List every Skill on the device with its enabled state, description, builtin flag and folder path, plus any invalid skill folders (missing SKILL.md or incomplete frontmatter). Call this BEFORE claiming a skill exists or does not exist.",
                "{\"type\":\"object\",\"properties\":{}}"
            )
            "delete_skill" -> ToolSpec(
                "delete_skill",
                "Delete a user Skill by name (its whole folder). Built-in skills are read-only and cannot be deleted. Use when the user says 删掉这个技能 / 不要这个技能了.",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Skill name (frontmatter name)\"}},\"required\":[\"name\"]}"
            )
            "install_plugin" -> ToolSpec(
                "install_plugin",
                "Download and install a plugin package (.apk-plugin / zip) from a URL. Used for one-click installs from the app market or any direct link. The package must contain manifest.json (+ main.js for js plugins).",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}"
            )
            "list_plugins" -> ToolSpec(
                "list_plugins",
                "List all installed plugins with their id, name, tool name (plugin__<id>), enabled state, params and permissions. Use before building/installing a plugin to avoid duplicates, or to check what tools you already have.",
                "{\"type\":\"object\",\"properties\":{}}"
            )
            "publish_plugin" -> ToolSpec(
                "publish_plugin",
                "Publish a locally-installed plugin to the app market (submits for review). Use this when the user wants to SHARE a plugin you built. REQUIRES the user to be logged in — if not, tell them to log in first. Provide a display name and description; the plugin package is packed and uploaded automatically (the user does NOT handle any file).",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"installed plugin id\"},\"name\":{\"type\":\"string\",\"description\":\"market display name\"},\"description\":{\"type\":\"string\",\"description\":\"market description\"}},\"required\":[\"id\",\"name\",\"description\"]}"
            )
            "manage_market_app" -> ToolSpec(
                "manage_market_app",
                "管理你在应用市场/插件市场已发布的作品：下架(从市场撤下)或重新提交审核。当用户说「下架这个插件/应用」「撤掉」「重新上架」时用这个工具，不要让用户自己点 UI。\n" +
                    "action=unpublish：下架（status→9，市场不再展示）。action=republish：重新提交审核（仅已下架/已拒绝可用，status→0）。\n" +
                    "目标定位：传 app_id 最准；也可以用 name（市场显示名，支持模糊）或 plugin_id（本地插件 id）。\n" +
                    "需要用户已登录（用的是用户自己的账号权限）。返回 status_before / status_after 便于核对。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"action\":{\"type\":\"string\",\"enum\":[\"unpublish\",\"republish\"],\"description\":\"unpublish=下架，republish=重新提交审核\"}," +
                    "\"app_id\":{\"type\":\"integer\",\"description\":\"市场条目 id（最精确，优先使用）\"}," +
                    "\"name\":{\"type\":\"string\",\"description\":\"市场显示名，支持模糊匹配\"}," +
                    "\"plugin_id\":{\"type\":\"string\",\"description\":\"本地插件 manifest id\"}," +
                    "\"plugins_only\":{\"type\":\"boolean\",\"description\":\"只在插件里找，默认 false\"}" +
                    "},\"required\":[\"action\"]}"
            )
            "list_my_market_apps" -> ToolSpec(
                "list_my_market_apps",
                "列出你在应用市场/插件市场发布的所有作品及其状态（待审核0/已上架1/已拒绝2/已下架9）和 app_id。下架前先用它确认目标，避免选错。需用户已登录。",
                "{\"type\":\"object\",\"properties\":{\"plugins_only\":{\"type\":\"boolean\",\"description\":\"只列插件，默认 false\"}}}"
            )
            "deploy_website" -> ToolSpec(
                "deploy_website",
                "部署一个网站到公网（原生升级工具，非插件非 MCP）。把用户做好的网页（目录或 zip）上传到服务器，自动分配随机访问地址。\n" +
                    "【核心规则】每个用户只能选**一个**服务器档位，部署后档位即锁定；如需更换服务器，必须先删除当前站点（用户可在「设置 → 站点托管」删除）。\n" +
                    "两种档位：\n" +
                    "  normal      = 普通服务器：每月 1GB 流量 · 1MB/s 带宽 · 稳定\n" +
                    "  performance = 高性能服务器：每月 10GB 流量 · 5MB/s 带宽 · 但可能不稳定，需提醒用户自行备份重要数据\n" +
                    "zip 解压后必须包含 index.html（或 index.htm）作为首页。\n" +
                    "返回 url（优先直连地址，已带 HTTPS 证书）、url_gateway（网关备用）、expires_at（30天到期，可续期）、traffic_quota。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"path\":{\"type\":\"string\",\"description\":\"要部署的网页路径：可以是目录（如 \\\"my-site\\\"，会自动打包）或 .zip 文件。工作区相对路径或绝对路径均可。\"}," +
                    "\"host\":{\"type\":\"string\",\"enum\":[\"normal\",\"performance\"],\"description\":\"服务器档位，仅在首次部署时生效并锁定。默认 normal（1GB/月·1MB/s·稳定）；performance 为 10GB/月·5MB/s 但可能不稳定，选择前请先告知用户风险并征得同意。\"}," +
                    "\"site_name\":{\"type\":\"string\",\"description\":\"站点名称（可选，展示用）\"}" +
                    "},\"required\":[\"path\"]}"
            )
            // ---- 远程工作区（SSH）工具集 ----
            // 说明：workspace_id 可选，缺省用第一个已配置的工作区。
            // 所有 path 参数都会规范化并校验必须落在该工作区的 remoteDir 之内。
            "remote_list" -> ToolSpec(
                "remote_list",
                "列出所有已配置的远程工作区（SSH）。返回每个工作区的 id、名称、地址、远程目录、认证方式、主机公钥是否已信任。\n" +
                    "调用其他 remote_* 工具前可先用它确认 workspace_id。",
                "{\"type\":\"object\",\"properties\":{}}"
            )
            "remote_test" -> ToolSpec(
                "remote_test",
                "测试远程工作区（SSH）连接。返回服务器出示的主机公钥指纹、家目录、shell 路径、远程目录是否存在。\n" +
                    "首次连接会自动信任并保存主机公钥指纹。如果之前连过但现在报「主机公钥已变更」，说明服务器重装或遭遇中间人攻击，需告知用户。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}" +
                    "}}"
            )
            "remote_ls" -> ToolSpec(
                "remote_ls",
                "列出远程工作区某个目录下的文件和子目录。返回 name/type/size/mtime/perms，目录排前面，最多 500 条。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"目录路径。相对路径基于工作区的 remoteDir；绝对路径必须在其之内。默认 .\"}" +
                    "}}"
            )
            "remote_read" -> ToolSpec(
                "remote_read",
                "读取远程工作区里的一个文本文件。默认最多读 128KB，超出部分截断并在 truncated 里标记。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}," +
                    "\"max_bytes\":{\"type\":\"integer\",\"description\":\"最多读多少字节，默认 131072\"}" +
                    "},\"required\":[\"path\"]}"
            )
            "remote_write" -> ToolSpec(
                "remote_write",
                "在远程工作区写文件（覆盖已有内容）。父目录不存在会自动创建。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}," +
                    "\"content\":{\"type\":\"string\",\"description\":\"完整文件内容\"}" +
                    "},\"required\":[\"path\",\"content\"]}"
            )
            "remote_edit" -> ToolSpec(
                "remote_edit",
                "在远程工作区编辑文件：把 old 精确替换成 new。\n" +
                    "old 必须在文件里唯一出现，否则会报错让你提供更长的片段；确实要全部替换时传 replace_all=true。\n" +
                    "修改前建议先 remote_read 确认内容。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}," +
                    "\"old\":{\"type\":\"string\",\"description\":\"要被替换的原文（必须精确匹配）\"}," +
                    "\"new\":{\"type\":\"string\",\"description\":\"替换成的新内容\"}," +
                    "\"replace_all\":{\"type\":\"boolean\",\"description\":\"是否替换所有匹配，默认 false\"}" +
                    "},\"required\":[\"path\",\"old\",\"new\"]}"
            )
            "remote_grep" -> ToolSpec(
                "remote_grep",
                "在远程工作区里按内容搜索（grep -rn）。返回匹配行，最多 200 行。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"pattern\":{\"type\":\"string\",\"description\":\"搜索内容或正则\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"搜索起点，默认 .\"}," +
                    "\"include\":{\"type\":\"string\",\"description\":\"只搜匹配的文件名模式，如 *.php\"}" +
                    "},\"required\":[\"pattern\"]}"
            )
            "remote_glob" -> ToolSpec(
                "remote_glob",
                "在远程工作区里按文件名找文件（find -name）。返回文件路径列表，最多 300 条。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"pattern\":{\"type\":\"string\",\"description\":\"文件名模式，如 *.log\"}," +
                    "\"path\":{\"type\":\"string\",\"description\":\"搜索起点，默认 .\"}" +
                    "},\"required\":[\"pattern\"]}"
            )
            "remote_shell" -> ToolSpec(
                "remote_shell",
                "在远程工作区执行 shell 命令。命令会先 cd 到工作区的 remoteDir 再执行。\n" +
                    "返回 exit_code、stdout（最多 8000 字符）、stderr（最多 4000 字符）、timed_out、truncated。\n" +
                    "适合装依赖、跑构建、看服务状态、管理进程等。注意：这是真实服务器，命令会真的执行。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"workspace_id\":{\"type\":\"string\",\"description\":\"工作区 id，省略则用第一个\"}," +
                    "\"command\":{\"type\":\"string\",\"description\":\"要执行的 shell 命令\"}," +
                    "\"timeout_ms\":{\"type\":\"integer\",\"description\":\"超时毫秒，默认 60000，最大 600000\"}" +
                    "},\"required\":[\"command\"]}"
            )
            // ---- 网页工具 ----
            // 关键：settle_seconds / timeout_seconds 让 AI 自己决定等多久，
            // 应对不同网站的渲染速度（SPA 慢、静态页快）。
            "web_fetch" -> ToolSpec(
                "web_fetch",
"查看任意网页 / 搜索：用后台 WebView 内核真实渲染（执行 JS），等页面渲染完成后再提取正文返回。\n" +
                    "适用于：需要 JS 才显示内容的页面（React/Vue/SPA）、要读文章正文、查文档、看新闻、以及【搜索】。\n" +
                    "\n【搜索怎么用 —— 重要】\n" +
                    "  没有独立的搜索工具了，搜索统一走本工具，直接把关键词拼进必应 URL：\n" +
                    "    url = \"https://cn.bing.com/search?q=张雪峰\"\n" +
                    "    url = \"https://cn.bing.com/search?q=python asyncio 教程\"\n" +
                    "  ✅ url 里的中文/空格会自动做 percent-encoding，你直接写中文就行，\n" +
                    "     不需要自己转成 %E5%BC%A0 这类编码。\n" +
                    "  ✅ 多个关键词用空格分隔，不要用 + 号（也支持，但空格更直观）。\n" +
                    "  ✅ 搜索类请求建议 settle_seconds=3~5、timeout_seconds=45，等结果页渲染完。\n" +
                    "  ✅ 拿到的 content 是搜索结果页的正文文本，里面就有标题和摘要。\n" +
                    "     需要看某条结果的完整内容，再对它的 URL 调一次本工具。\n" +
                    "  ✅ 中文话题用 cn.bing.com 命中率更高；英文/技术话题可用 www.bing.com。\n" +
                    "\n【等待策略】\n" +
                    "  1. 加载页面，等 onPageFinished\n" +
                    "  2. 轮询 document.readyState 直到 complete\n" +
                    "  3. 若 auto_scroll=true，自动滚到底触发懒加载，等 DOM 高度稳定\n" +
                    "  4. 再等 settle_seconds 秒（让异步请求落地）\n" +
                    "  5. 提取 document.body.innerText\n" +
                    "\n【何时调大参数】\n" +
                    "  · 页面正文迟迟不出现 → settle_seconds 调大到 5-10\n" +
                    "  · 无限滚动列表要更多内容 → settle_seconds 调大 + auto_scroll=true\n" +
                    "  · 网站慢 → timeout_seconds 调大到 60-120\n" +
                    "\n【降级】WebView 失败时自动改用纯 HTTP 抓取（mode=http），\n" +
                    "但那种方式拿不到 JS 渲染的内容。返回值里的 mode 字段会标明用了哪种。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"url\":{\"type\":\"string\",\"description\":\"完整网址，必须以 http:// 或 https:// 开头。可直接包含中文，会自动编码。搜索用 https://cn.bing.com/search?q=关键词\"}," +
                    "\"settle_seconds\":{\"type\":\"integer\",\"description\":\"渲染完成后额外等待的秒数，默认 2，范围 0-30。SPA 或懒加载页面建议 5-10\"}," +
                    "\"timeout_seconds\":{\"type\":\"integer\",\"description\":\"总超时秒数，默认 30，范围 5-120。网站慢时调大\"}," +
                    "\"auto_scroll\":{\"type\":\"boolean\",\"description\":\"是否自动滚动到底部触发懒加载，默认 true。长列表页面建议保持 true\"}," +
                    "\"include_image_links\":{\"type\":\"boolean\",\"description\":\"是否收集页面图片链接，默认 false。需要配图时打开\"}," +
                    "\"max_chars\":{\"type\":\"integer\",\"description\":\"正文最多返回多少字符，默认 30000，范围 1000-200000\"}" +
                    "},\"required\":[\"url\"]}"
            )
            // 持久化浏览器会话（见 ai/BrowserTools.kt）。
            // 和 web_fetch 的区别：web_fetch 抓完就销毁；这套是「一直开着的浏览器」，
            // 同一页面能被反复读、能被用户接管、接管后 AI 还能接着读。
            "browser_open" -> ToolSpec(
                "browser_open",
                "在后台打开一个【持久】浏览器并开始渲染网页。用户看不到这个页面（除非后面交给用户）。\n" +
                    "\n【和 web_fetch 的区别 —— 很重要】\n" +
                    "  · web_fetch：一次性抓取，抓完立刻销毁，每次都重新加载。\n" +
                    "  · browser_open：开一个持续存在的浏览器。之后可以反复 browser_poll 读正文，\n" +
                    "    页面状态（登录态、表单、滚动位置）全部保留。\n" +
                    "\n【什么时候用这套，而不是 web_fetch】\n" +
                    "  · 要读同一页面的多次变化（投票数、评论区、价格刷新）\n" +
                    "  · 需要登录才能看到内容\n" +
                    "  · 页面可能弹人机验证，需要用户介入\n" +
                    "  · 要模拟用户操作后看结果\n" +
                    "\n【调用顺序】\n" +
                    "  browser_open → browser_poll（可多次）→ 需要人工时 browser_handoff\n" +
                    "  → 用户点「交给 AI」→ 再 browser_poll 读处理后的内容 → browser_close\n" +
                    "\n【同一个 URL 重复 open 不会重新加载】会直接复用现有会话，保住状态。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"url\":{\"type\":\"string\",\"description\":\"完整网址，必须以 http:// 或 https:// 开头\"}" +
                    "},\"required\":[\"url\"]}"
            )
            "browser_poll" -> ToolSpec(
                "browser_poll",
                "读取当前【持久浏览器】里页面的正文文本。可以反复调用，每次拿到的都是最新内容。\n" +
                    "\n返回字段关键点：\n" +
                    "  · content          —— 页面正文（innerText，已压掉多余空白）\n" +
                    "  · loading          —— 页面是否还在加载\n" +
                    "  · challenge        —— true 表示这页像人机验证 / 反爬拦截页\n" +
                    "  · handed_to_user   —— 当前控制权是否在用户手上\n" +
                    "  · user_returned    —— true 表示用户刚点过「交给 AI」，可以接着读了\n" +
                    "  · redirected_from  —— 发生过跳转时给出原 URL\n" +
                    "\n【challenge=true 怎么办】\n" +
                    "  调用 browser_handoff 交给用户手动完成。用户点「交给 AI」后你下次 poll\n" +
                    "  就会看到 user_returned=true，并且拿到处理完之后的新页面内容。\n" +
                    "\n【wait_seconds】最多等多久让页面加载完（默认 15 秒）。页面慢就调大。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"wait_seconds\":{\"type\":\"integer\",\"description\":\"最多等多少秒让页面加载完，默认 15，范围 0-120\"}," +
                    "\"max_chars\":{\"type\":\"integer\",\"description\":\"正文最多返回多少字符，默认 20000，范围 500-100000\"}" +
                    "}}"
            )
            "browser_handoff" -> ToolSpec(
                "browser_handoff",
                "把当前网页交给用户手动处理（人机验证、登录、短信码、拖滑块等）。\n" +
                    "\n【流程】\n" +
                    "  1. 用户会看到一个确认弹窗（说明为什么要他接手）\n" +
                    "  2. 用户点「接手页面」→ 那个已经渲染好的网页全屏展示给用户，\n" +
                    "     页面状态完全保持原样（验证码进度、已填表单、滚动位置都不变）\n" +
                    "  3. 用户处理完点「交给 AI」→ 你下次 browser_poll 就能读到新内容\n" +
                    "  4. 用户也可能点「✕」直接退出，同样会回到你手里\n" +
                    "\n【返回值】accepted=true 表示用户接手了；false 表示用户拒绝，\n" +
                    "那你就换个思路（找页面 API、用 web_fetch 抓静态版、或就现有内容作答）。\n" +
                    "\n【注意】这个工具会阻塞等到用户点按钮（最长 180 秒）。",
                "{\"type\":\"object\",\"properties\":{" +
                    "\"reason\":{\"type\":\"string\",\"description\":\"给用户看的一句话说明，比如「这个页面需要点一下人机验证」\"}" +
                    "}}"
            )
            "browser_close" -> ToolSpec(
                "browser_close",
                "关闭持久浏览器会话，回收 WebView（停止占用内存和电量）。\n" +
                    "用完就关，尤其是那些会一直跑动画/轮询的页面。",
                "{\"type\":\"object\",\"properties\":{}}"
            )
            // ---- 多子智能体代理协调（开关关闭时这两个工具不会被注册，走不到这里）----
            "subagent_run" -> ToolSpec(
                "subagent_run",
                "把一个**可以独立完成**的子任务委托给子代理执行。子代理有自己的工具、自己的多步循环，"
                    + "只会把最终结论（摘要）回传，中间过程不占你的上下文。\n"
                    + "适合：大范围检索/多文件分析/互不依赖的独立步骤。如果几个子任务互不依赖，"
                    + "优先用 subagent_parallel 一次并行下发，比串行快得多。\n"
                    + "注意：子代理看不到你的对话历史，任务描述必须自包含；不要用它做需要和你实时交互的事。",
                "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\",\"description\":\"自包含的子任务描述（必要背景、目标、验收标准都写清楚）\"},"
                    + "\"context\":{\"type\":\"string\",\"description\":\"补充上下文，可选：限制条件、已知路径、资源编号\"},"
                    + "\"max_steps\":{\"type\":\"integer\",\"description\":\"步数预算，默认 8，最大 12\"}},\"required\":[\"task\"]}",
            )
            "subagent_parallel" -> ToolSpec(
                "subagent_parallel",
                "一次并行下发多个互不依赖的子任务给多个子代理（最多 4 个并发）。"
                    + "每个子代理独立跑完整工具循环，各自只回传一段结论；系统会把「同伴任务清单」"
                    + "注入给每个子代理，让它们自行避免重复劳动和抢同一个文件。\n"
                    + "适合：同时分析多个文件/多个目录、并行跑多个互不影响的命令、多角度调研。\n"
                    + "不适合：有前后依赖的步骤（那会白跑），以及会写同一个文件的修改变更。",
                "{\"type\":\"object\",\"properties\":{\"tasks\":{\"type\":\"array\",\"description\":\"子任务数组，元素可以是字符串，"
                    + "也可以是 {task, context} 对象\",\"items\":{\"type\":\"object\"}},"
                    + "\"context\":{\"type\":\"string\",\"description\":\"所有子代理共享的上下文\"},"
                    + "\"max_steps\":{\"type\":\"integer\",\"description\":\"每个子代理的步数预算，默认 8，最大 12\"}},\"required\":[\"tasks\"]}",
            )
            else -> ToolSpec(name, "Custom tool", "{}")
        }
    }

    /** 插件工具规格：从清单的 params 生成 JSON Schema。 */
    private fun pluginSpec(name: String, p: com.agentpad.workspace.data.PluginStore.Installed): ToolSpec {
        val props = JSONObject()
        val required = JSONArray()
        p.manifest.params.forEach { pr ->
            val t = when (pr.type) {
                "number" -> "number"; "boolean" -> "boolean"; else -> "string"
            }
            props.put(pr.name, JSONObject().put("type", t).put("description", pr.desc))
            if (pr.required) required.put(pr.name)
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .apply { if (required.length() > 0) put("required", required) }
        val desc = buildString {
            append("【插件】").append(p.manifest.name)
            if (p.manifest.description.isNotBlank()) append(" — ").append(p.manifest.description)
            if (p.manifest.author.isNotBlank()) append("（作者：${p.manifest.author}）")
        }
        return ToolSpec(name, desc, schema.toString())
    }

    fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.name] ?: return ToolResult(call.id, call.name, "unsupported tool")
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
        return ToolResult(call.id, call.name, tool(args))
    }

    // ---- File tools ----
    // Paths may be workspace-relative or absolute (absolute paths are allowed on
    // purpose so the AI can operate on files outside the workspace, e.g. /sdcard).
    // ".." segments are resolved via canonical path rather than textually rejected.

    private fun resolveWorkspacePath(rawPath: String): File? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("/")) {
            // Absolute path: allow access outside the workspace (e.g. /sdcard/...)
            runCatching { File(trimmed).canonicalFile }.getOrElse { return null }
        } else {
            val root = projectDir.canonicalFile
            val normalized = File(File(root, trimmed.replace("\\", "/")).path).normalize()
            normalized
        }
    }

    private fun installApk(args: JSONObject): String {
        val path = args.optString("path")
        val target = resolveWorkspacePath(path)
            ?: return JSONObject().put("success", false).put("error", "invalid path: $path").toString()
        if (!target.isFile) {
            return JSONObject().put("success", false).put("error", "APK not found: $path").toString()
        }
        if (!target.name.lowercase().endsWith(".apk")) {
            return JSONObject().put("success", false).put("error", "not an APK file: ${target.name}").toString()
        }
        var error: String? = null
        val done = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", target)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!appContext.packageManager.canRequestPackageInstalls()) {
                        Toast.makeText(appContext, "如未弹出安装界面，请在系统设置中允许本应用安装未知应用", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { error = it.message ?: "无法打开系统安装器" }
            done.countDown()
        }
        done.await()
        return if (error != null) JSONObject().put("success", false).put("error", error).toString()
        else JSONObject().put("success", true).put("path", target.absolutePath).put("message", "已调用系统安装器").toString()
    }

    private fun writeFile(args: JSONObject): String {
        val path = args.optString("path")
        val content = args.optString("content")
        val target = resolveWorkspacePath(path)
            ?: return JSONObject().put("success", false).put("error", "invalid path: $path").toString()
        // 记录改动前状态（用于「浏览 / 撤回 AI 操作」）
        val existedBefore = target.isFile
        val beforeText = if (existedBefore) runCatching { target.readText() }.getOrNull() else null
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.onFailure { e ->
            return JSONObject().put("success", false).put("error", "${e.message}").toString()
        }
        // 记录文件操作历史（仅文件工具）
        runCatching {
            com.agentpad.workspace.data.FileHistoryStore.record(
                appContext, sessionId, path, target.absolutePath,
                beforeText, content, existedBefore, "write_file",
            )
        }
        return JSONObject()
            .put("success", true)
            .put("path", path)
            .put("bytes", content.toByteArray().size)
            .toString()
    }

    private fun readFile(args: JSONObject): String {
        val path = args.optString("path")
        val target = resolveWorkspacePath(path)
            ?: return JSONObject().put("success", false).put("error", "invalid path: $path").toString()
        if (!target.isFile) {
            return JSONObject().put("success", false).put("error", "file not found: $path (use write_file to create it)").toString()
        }
        val lines = runCatching { target.readText().lines() }.getOrElse { e ->
            return JSONObject().put("success", false).put("error", "read failed: ${e.message}").toString()
        }
        // Optional 1-based line window for large files
        val startLine = if (args.has("start_line")) args.optInt("start_line", 1).coerceAtLeast(1) else 1
        val endLine = if (args.has("end_line")) args.optInt("end_line", lines.size).coerceAtMost(lines.size) else lines.size
        if (startLine > endLine) {
            return JSONObject().put("success", false).put("error", "start_line ($startLine) > end_line ($endLine), file has ${lines.size} lines").toString()
        }
        val window = lines.subList(startLine - 1, endLine)
        val content = window.joinToString("\n")
        // Truncate to keep context budget reasonable (same spirit as terminal stdout limit)
        val limit = 5000
        val out = if (content.length > limit) content.take(limit) + "\n...[truncated, total ${content.length} chars]" else content
        return JSONObject()
            .put("success", true)
            .put("path", path)
            .put("total_lines", lines.size)
            .put("start_line", startLine)
            .put("end_line", endLine)
            .put("content", out)
            .toString()
    }

    private fun editFile(args: JSONObject): String {
        val path = args.optString("path")
        val old = args.optString("old")
        val new = args.optString("new")
        val target = resolveWorkspacePath(path)
            ?: return JSONObject().put("success", false).put("error", "invalid path: $path").toString()
        if (!target.isFile) {
            return JSONObject().put("success", false).put("error", "file not found: $path (use write_file to create it)").toString()
        }
        if (old.isEmpty()) {
            return JSONObject().put("success", false).put("error", "old text is empty").toString()
        }
        val content = runCatching { target.readText() }.getOrElse { e ->
            return JSONObject().put("success", false).put("error", "read failed: ${e.message}").toString()
        }
        val first = content.indexOf(old)
        if (first < 0) {
            return JSONObject().put("success", false).put("error", "old text not found in $path — re-read the file and try an exact match").toString()
        }
        val updated = content.substring(0, first) + new + content.substring(first + old.length)
        runCatching { target.writeText(updated) }.onFailure { e ->
            return JSONObject().put("success", false).put("error", "write failed: ${e.message}").toString()
        }
        // 记录文件操作历史（改动前=旧内容，改动后=新内容）
        runCatching {
            com.agentpad.workspace.data.FileHistoryStore.record(
                appContext, sessionId, path, target.absolutePath,
                content, updated, true, "edit_file",
            )
        }
        return JSONObject()
            .put("success", true)
            .put("path", path)
            .put("replaced_at_offset", first)
            .put("old_len", old.length)
            .put("new_len", new.length)
            .toString()
    }
}