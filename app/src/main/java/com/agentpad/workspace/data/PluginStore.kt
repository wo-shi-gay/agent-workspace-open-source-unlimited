package com.agentpad.workspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 插件存储（路由 B：JS 沙箱插件）。
 *
 * 一个插件 = 一个目录 filesDir/plugins/<id>/：
 *  - manifest.json   清单（id/name/desc/type/params/permissions/entry）
 *  - main.js         JS 实现（type="js" 时）
 *  - icon.png        可选图标
 *
 * 本对象负责：清单解析、已安装列表、启用/禁用、卸载、安装（从 zip 解包）。
 */
object PluginStore {

    private const val PREF = "plugin_config"
    private const val KEY_ENABLED = "enabled_ids"   // JSON 数组：启用的插件 id

    /** 一个插件参数定义。 */
    data class Param(
        val name: String,
        val type: String,          // string | number | boolean
        val desc: String,
        val required: Boolean,
    )

    /** 插件清单。 */
    data class Manifest(
        val format: String,
        val version: Int,
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val category: String,
        val type: String,           // "js" | "declarative"
        val entry: String,          // type=js 时的入口文件名
        val params: List<Param>,
        val permissions: List<String>,
        /** type=declarative：动作名（宿主内置原子能力） */
        val action: String = "",
    )

    /** 已安装插件（清单 + 目录 + 启用态）。 */
    data class Installed(
        val manifest: Manifest,
        val dir: File,
        val enabled: Boolean,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 插件根目录。 */
    fun pluginsDir(c: Context): File = File(c.filesDir, "plugins").apply { mkdirs() }

    fun dirFor(c: Context, id: String): File = File(pluginsDir(c), id)

    // ---------------- 启用状态 ----------------

    private fun enabledIds(c: Context): MutableSet<String> {
        val raw = prefs(c).getString(KEY_ENABLED, null) ?: return mutableSetOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toMutableSet()
        }.getOrDefault(mutableSetOf())
    }

    private fun saveEnabledIds(c: Context, ids: Set<String>) {
        val arr = JSONArray().apply { ids.forEach { put(it) } }
        prefs(c).edit().putString(KEY_ENABLED, arr.toString()).apply()
    }

    fun setEnabled(c: Context, id: String, enabled: Boolean) {
        val ids = enabledIds(c)
        if (enabled) ids.add(id) else ids.remove(id)
        saveEnabledIds(c, ids)
    }

    fun isEnabled(c: Context, id: String): Boolean = id in enabledIds(c)

    // ---------------- 清单解析 ----------------

    fun parseManifest(json: String): Manifest? = runCatching {
        val o = JSONObject(json)
        if (o.optString("format") != "agentpad-plugin") return null
        val id = o.optString("id").trim()
        if (id.isBlank()) return null
        val paramsArr = o.optJSONArray("params")
        val params = if (paramsArr != null) buildList {
            for (i in 0 until paramsArr.length()) {
                val p = paramsArr.optJSONObject(i) ?: continue
                add(
                    Param(
                        name = p.optString("name"),
                        type = p.optString("type", "string"),
                        desc = p.optString("desc", ""),
                        required = p.optBoolean("required", false),
                    )
                )
            }
        } else emptyList()
        val permsArr = o.optJSONArray("permissions")
        val perms = if (permsArr != null) (0 until permsArr.length()).mapNotNull { permsArr.optString(it).ifBlank { null } } else emptyList()
        Manifest(
            format = "agentpad-plugin",
            version = o.optInt("version", 1),
            id = id,
            name = o.optString("name", id),
            author = o.optString("author", ""),
            description = o.optString("description", ""),
            category = o.optString("category", "其他"),
            type = o.optString("type", "js"),
            entry = o.optString("entry", "main.js"),
            params = params,
            permissions = perms,
            action = o.optString("action", ""),
        )
    }.getOrNull()

    // ---------------- 已安装列表 ----------------

    fun installed(c: Context): List<Installed> {
        val root = pluginsDir(c)
        val ids = enabledIds(c)
        return (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { dir ->
            val mf = File(dir, "manifest.json")
            if (!mf.isFile) return@mapNotNull null
            val manifest = parseManifest(runCatching { mf.readText() }.getOrDefault("")) ?: return@mapNotNull null
            Installed(manifest, dir, manifest.id in ids)
        }.sortedBy { it.manifest.name }
    }

    fun find(c: Context, id: String): Installed? = installed(c).firstOrNull { it.manifest.id == id }

    // ---------------- 安装 / 卸载 ----------------

    /**
     * 从 zip 包安装插件：解包到 filesDir/plugins/<id>/（先读 manifest 拿 id）。
     * 返回 (成功, 错误信息, 清单)。
     */
    fun installFromZip(c: Context, zipFile: File): Triple<Boolean, String, Manifest?> {
        return runCatching {
            // 1) 先用临时目录解开，读 manifest
            val tmp = File(c.cacheDir, "plugin_install_tmp").apply { deleteRecursively(); mkdirs() }
            java.util.zip.ZipFile(zipFile).use { zf ->
                val entries = zf.entries().toList()
                for (e in entries) {
                    val target = File(tmp, e.name)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator) &&
                        target.canonicalPath != tmp.canonicalPath) continue
                    if (e.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        zf.getInputStream(e).use { ins -> target.outputStream().use { ins.copyTo(it) } }
                    }
                }
            }
            val mf = File(tmp, "manifest.json")
            if (!mf.isFile) return Triple(false, "缺少 manifest.json", null)
            val manifest = parseManifest(mf.readText())
                ?: return Triple(false, "清单格式不正确（需 format=agentpad-plugin）", null)
            if (manifest.type == "js" && !File(tmp, manifest.entry).isFile) {
                return Triple(false, "缺少 JS 入口文件：${manifest.entry}", null)
            }
            // 2) 覆盖安装
            val dst = dirFor(c, manifest.id)
            if (dst.exists()) dst.deleteRecursively()
            dst.parentFile?.mkdirs()
            tmp.copyRecursively(dst, overwrite = true)
            tmp.deleteRecursively()
            // 新装默认启用
            setEnabled(c, manifest.id, true)
            Triple(true, "安装成功", manifest)
        }.getOrElse { Triple(false, "安装失败：${it.message}", null) }
    }

    fun uninstall(c: Context, id: String) {
        dirFor(c, id).deleteRecursively()
        val ids = enabledIds(c); ids.remove(id); saveEnabledIds(c, ids)
    }

    /**
     * 直接从源码创建 / 覆盖一个插件（AI 在对话里自建插件用，无需打包 zip）。
     * @param manifestJson 清单 JSON 全文
     * @param files 插件目录下要写入的文件（相对路径 → 内容），必须含 entry 指向的 JS
     * @return (成功, 信息, 清单)
     */
    fun installFromSource(
        c: Context,
        manifestJson: String,
        files: Map<String, String>,
    ): Triple<Boolean, String, Manifest?> {
        return runCatching {
            val manifest = parseManifest(manifestJson)
                ?: return Triple(false, "清单格式不正确（需 format=agentpad-plugin 且含 id）", null)
            if (manifest.type == "js" && (files[manifest.entry] ?: "").isBlank()) {
                return Triple(false, "缺少 JS 入口文件：${manifest.entry}", null)
            }
            val dst = dirFor(c, manifest.id)
            if (dst.exists()) dst.deleteRecursively()
            dst.mkdirs()
            // 写清单
            File(dst, "manifest.json").writeText(manifestJson)
            // 写插件文件（防路径穿越）
            files.forEach { (rel, content) ->
                val target = File(dst, rel).canonicalFile
                if (!target.path.startsWith(dst.canonicalPath + File.separator)) return@forEach
                target.parentFile?.mkdirs()
                target.writeText(content)
            }
            setEnabled(c, manifest.id, true)
            Triple(true, "插件「${manifest.name}」已安装并启用", manifest)
        }.getOrElse { Triple(false, "创建插件失败：${it.message}", null) }
    }

    /** 读取插件入口 JS 源码。 */
    fun readEntry(c: Context, plugin: Installed): String? =
        runCatching { File(plugin.dir, plugin.manifest.entry).readText() }.getOrNull()

    /** 读取插件目录内任意文本文件。 */
    fun readTextFile(plugin: Installed, name: String = "SKILL.md"): String? =
        runCatching { File(plugin.dir, name).readText() }.getOrNull()

    /**
     * 把一个已安装插件打包成 .apk-plugin（zip）到 [dest]。
     *  供 AI「发布插件到市场」用：本地插件 → 上传包。 */
    fun packToZip(c: Context, id: String, dest: File): File? {
        return runCatching {
            val dir = dirFor(c, id)
            if (!dir.isDirectory) return null
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            java.util.zip.ZipOutputStream(dest.outputStream()).use { zos ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(dir).path.replace('\\', '/')
                    zos.putNextEntry(java.util.zip.ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            dest
        }.getOrNull()
    }
}