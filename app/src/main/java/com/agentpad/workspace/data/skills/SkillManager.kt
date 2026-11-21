package com.agentpad.workspace.data.skills

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 照抄 RikkaHub 3.2.0：
 * app/src/main/java/me/rerere/rikkahub/data/skills/SkillManager.kt
 *
 * 与原版的三处差别（其余函数逐行一致）：
 *  1. 原版是 Koin 单例、构造参数 (context, settingsStore)，enabledSkills 存在助手的
 *     DataStore 里；本 App 没有 DataStore，改成 prefs("skill_config") 的字符串集合，
 *     语义仍是「AI 只看得到被启用的技能」。
 *  2. 原版 deleteSkill / pruneOrphanedEnabledSkills 是 suspend 并写回 SettingsStore，
 *     这里改为同步 + 写 prefs，逻辑顺序不变。
 *  3. 额外补了 importSkillFromBytes / importSkillFromGitHub（原版这两个在 SkillsVM 里），
 *     方便「插件与技能」页面的手动导入入口直接调用。
 */
class SkillManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"
        private const val PREF = "skill_config"
        private const val KEY_ENABLED = "enabled"

        @Volatile
        private var INSTANCE: SkillManager? = null

        fun of(context: Context): SkillManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    private fun prefs() = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 启用状态（原版在 Assistant.enabledSkills） ----------------

    fun enabledNames(): Set<String> = prefs().getStringSet(KEY_ENABLED, null)?.toSet() ?: emptySet()

    fun isEnabled(name: String): Boolean = name in enabledNames()

    fun setEnabled(name: String, enabled: Boolean) {
        val cur = enabledNames().toMutableSet()
        if (enabled) cur.add(name) else cur.remove(name)
        prefs().edit().putStringSet(KEY_ENABLED, cur).apply()
    }

    /** 启用的技能列表：AI 的 use_skill 工具只暴露这些。 */
    fun enabledSkills(): List<SkillMetadata> = listSkills().filter { isEnabled(it.name) }

    // ---------------- 目录 ----------------

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve("skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve("builtin_skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。 */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /** 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。 */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> =
        skillDirsIn(root).mapNotNull { parseSkillDir(it, builtin).getOrNull() }

    /**
     * 列出用户技能目录下无法识别为技能的目录，如空目录、缺少 SKILL.md 或 frontmatter 不完整。
     * 这些目录不会出现在 [listSkills] 中，单独列出来让用户清理。
     */
    fun listInvalidSkillDirs(): List<InvalidSkillDir> = skillDirsIn(getSkillsDir())
        .mapNotNull { dir ->
            val reason = when (val error = parseSkillDir(dir, builtin = false).exceptionOrNull()) {
                null -> return@mapNotNull null
                is InvalidSkillException -> error.reason
                else -> InvalidSkillReason.ReadFailed
            }
            InvalidSkillDir(dirName = dir.name, reason = reason)
        }
        .sortedBy { it.dirName }

    fun deleteInvalidSkillDir(dirName: String): Boolean {
        val dir = SkillPaths.resolveSkillDir(getSkillsDir(), dirName) ?: return false
        if (!dir.isDirectory) return false
        // 列表展示期间目录可能已在工作区里补全成有效技能，删除前再确认一次
        if (parseSkillDir(dir, builtin = false).isSuccess) return false
        return dir.deleteRecursively()
    }

    // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
    private fun skillDirsIn(root: File): List<File> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()

    // ---------------- 读写 ----------------

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        // 新建即启用，否则 AI 的 use_skill 看不到它
        setEnabled(name, true)
        return parseSkillDir(skillDir, builtin = false).getOrNull()
    }

    fun deleteSkill(skill: SkillMetadata): Boolean {
        if (skill.builtin) return false
        val name = skill.name
        // 按技能实际所在的目录删除：在工作区里直接创建、复制或改过 name 的技能，
        // 目录名可能和 frontmatter 的 name 不一致，按 name 拼路径会找不到目录
        val skillDir = SkillPaths.resolveSkillDir(getSkillsDir(), skill.skillDir.name) ?: return false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理启用状态
        if (!skillDir.exists()) return false
        val deleted = skillDir.deleteRecursively()
        // 还有同名技能（被覆盖的内置技能或另一个同名用户技能）会继续生效，保留启用状态
        if (deleted && listSkills().none { it.name == name }) setEnabled(name, false)
        return deleted
    }

    /**
     * 清理 enabledSkills 中已不存在于磁盘的技能名。
     * 当用户在 App 外直接删除 /skills/ 下的技能时，不会走 [deleteSkill]，会残留「幽灵」技能名。
     */
    fun pruneOrphanedEnabledSkills(): List<SkillMetadata> {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        val pruned = enabledNames().filterTo(LinkedHashSet()) { it in existing }
        if (pruned.size != enabledNames().size) {
            prefs().edit().putStringSet(KEY_ENABLED, pruned).apply()
        }
        return skills
    }

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val parent = target.parentFile ?: return false
        // 先写同目录临时文件再 rename 覆盖，避免写到一半失败时损坏原文件
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillMd = files["SKILL.md"] ?: return false
        // 目录名必须和 SKILL.md 的 name 一致，否则之后按名字找不到目录
        if (SkillValidator.validate(skillMd.toString(Charsets.UTF_8)).getOrNull() != skillName) {
            Log.w(TAG, "saveSkillFilesAtomically: Invalid SKILL.md for $skillName")
            return false
        }
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    /** 技能目录里的全部文件（相对路径，已排序，给详情页展示文件树用）。 */
    fun listSkillFiles(skill: SkillMetadata, limit: Int = 200): List<String> =
        skill.skillDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(skill.skillDir).path }
            .sorted()
            .take(limit)
            .toList()

    private fun resolveSkillDir(skillName: String): File? =
        SkillPaths.resolveSkillDir(getSkillsDir(), skillName)

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    /** 解析技能目录；无法识别时为 [InvalidSkillException]，读文件失败时为 IO 异常 */
    private fun parseSkillDir(skillDir: File, builtin: Boolean): Result<SkillMetadata> = runCatching {
        val skillFile = skillDir.resolve("SKILL.md")
        if (!skillFile.isFile) throw InvalidSkillException(InvalidSkillReason.MissingSkillMd)
        val frontmatter = SkillFrontmatterParser.parse(skillFile.readText())
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingName)
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() }
            ?: throw InvalidSkillException(InvalidSkillReason.MissingDescription)
        SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            skillDir = skillDir,
            builtin = builtin,
        )
    }

    // ---------------- 导入（原版在 SkillsVM） ----------------

    /**
     * 从文件导入：zip 包（找最浅的 SKILL.md 并保留同目录附属文件）或裸 SKILL.md。
     * @return null 表示成功，否则是错误文案。
     */
    fun importSkillFromBytes(bytes: ByteArray): String? {
        val entries = collectZipEntries(bytes)
        val skillMdNames = entries.map { it.first }
            .filter { it.equals("SKILL.md", true) || it.endsWith("/SKILL.md", true) }

        val skillMdBytes: ByteArray
        val files = LinkedHashMap<String, ByteArray>()
        if (skillMdNames.isEmpty()) {
            // 裸 SKILL.md（zip 里没有就按纯文本处理）
            skillMdBytes = bytes
            files["SKILL.md"] = bytes
        } else {
            val picked = skillMdNames.minByOrNull { it.length }!!
            val prefix = picked.substringBeforeLast('/', "")
            skillMdBytes = entries.first { it.first == picked }.second
            entries.forEach { (path, body) ->
                if (path.endsWith("/")) return@forEach
                val rel = if (prefix.isEmpty()) path else path.removePrefix("$prefix/")
                if (rel.isNotBlank()) files[rel] = body
            }
            files["SKILL.md"] = skillMdBytes
        }

        val name = SkillValidator.validate(skillMdBytes.toString(Charsets.UTF_8)).getOrElse { err ->
            return (err as? SkillValidationException)?.error?.message() ?: "SKILL.md 校验失败：${err.message}"
        }
        return if (saveSkillFileBytesAtomically(name, files)) {
            setEnabled(name, true)
            null
        } else {
            "保存失败：$name"
        }
    }

    /** 从 GitHub 仓库导入：先试 raw.githubusercontent 的 HEAD/main/master，再退到 codeload zip。 */
    fun importSkillFromGitHub(repoUrl: String): String? {
        val cleaned = repoUrl.trim().removeSuffix("/").removeSuffix(".git")
        val m = Regex("""github\.com/([^/]+)/([^/]+)""").find(cleaned)
            ?: return "只支持 GitHub 仓库链接，例如 https://github.com/owner/repo"
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        for (branch in listOf("HEAD", "main", "master")) {
            val text = httpGetString("https://raw.githubusercontent.com/$owner/$repo/$branch/SKILL.md")
            if (!text.isNullOrBlank() && !text.trimStart().startsWith("404")) {
                return importSkillFromBytes(text.toByteArray())
            }
        }
        for (branch in listOf("main", "master")) {
            val zip = httpGetBytes("https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch")
            if (zip != null && zip.size > 100) return importSkillFromBytes(zip)
        }
        return "仓库里找不到 SKILL.md（也可能是网络不可达）"
    }

    private fun collectZipEntries(bytes: ByteArray, limit: Int = 2000): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null && out.size < limit) {
                    if (!e.isDirectory) {
                        val buf = ByteArrayOutputStream()
                        val tmp = ByteArray(64 * 1024)
                        while (true) {
                            val r = zis.read(tmp)
                            if (r <= 0) break
                            buf.write(tmp, 0, r)
                        }
                        out.add(e.name to buf.toByteArray())
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        }
        return out
    }

    private fun httpGetString(url: String): String? = httpGetBytes(url)?.toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "agentpad-workspace")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/** 用户技能目录下无法识别为技能的目录 */
data class InvalidSkillDir(
    val dirName: String,
    val reason: InvalidSkillReason,
)

enum class InvalidSkillReason {
    MissingSkillMd,
    MissingName,
    MissingDescription,
    ReadFailed,
}

internal class InvalidSkillException(val reason: InvalidSkillReason) : Exception(reason.name)

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val skillDir: File,
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
