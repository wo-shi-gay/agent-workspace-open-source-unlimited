package com.agentpad.workspace.data.skills

import java.io.File

/**
 * 照抄 RikkaHub 3.2.0：
 * app/src/main/java/me/rerere/rikkahub/data/skills/SkillPaths.kt
 *
 * 只改了 package，逻辑一字不改（含 canonicalFile + 前缀校验的双重越界防护）。
 */
internal object SkillPaths {
    fun resolveSkillDir(skillsRoot: File, skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName == "." || skillName == "..") return null
        if (skillName.contains('/') || skillName.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillName).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    fun resolveSkillFile(skillDir: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null

        val canonicalSkillDir = skillDir.canonicalFile
        val canonicalTarget = canonicalSkillDir.resolve(relativePath).canonicalFile

        return canonicalTarget.takeIf { it.isSameOrInside(canonicalSkillDir) }
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}
