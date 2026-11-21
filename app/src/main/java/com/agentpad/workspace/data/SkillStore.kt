package com.agentpad.workspace.data

import android.content.Context
import com.agentpad.workspace.data.skills.SkillManager
import com.agentpad.workspace.data.skills.SkillMetadata
/**
 * 技能（Skill）数据层 —— 转发到 [SkillManager]。
 *
 * 2026-11-15：skill 整套改成照抄 RikkaHub 3.2.0 的 `data/skills/`（5 个类 + `use_skill` 工具），
 * 这里只留一层转发，避免调用方到处 import 两个包。
 */
object SkillStore {

    fun manager(c: Context): SkillManager = SkillManager.of(c)

    /** 全部技能（用户 + 内置，同名时用户覆盖内置）。 */
    fun all(c: Context): List<SkillMetadata> = manager(c).listSkills()

    /** AI 可见的技能：只有被启用的才会注册成 use_skill 的候选。 */
    fun enabled(c: Context): List<SkillMetadata> = manager(c).enabledSkills()

    /** 无法识别的目录（缺 SKILL.md / frontmatter 不全），单独列出来让用户清理。 */
    fun invalid(c: Context) = manager(c).listInvalidSkillDirs()

    fun setEnabled(c: Context, name: String, enabled: Boolean) = manager(c).setEnabled(name, enabled)

    fun isEnabled(c: Context, name: String) = manager(c).isEnabled(name)

    fun save(c: Context, name: String, content: String) = manager(c).saveSkill(name, content)

    fun delete(c: Context, skill: SkillMetadata) = manager(c).deleteSkill(skill)

    fun deleteInvalidDir(c: Context, dirName: String) = manager(c).deleteInvalidSkillDir(dirName)

    fun prune(c: Context) = manager(c).pruneOrphanedEnabledSkills()

    /** 从 zip / 裸 SKILL.md 导入；返回 null 表示成功。 */
    fun importBytes(c: Context, bytes: ByteArray): String? = manager(c).importSkillFromBytes(bytes)

    /** 从 GitHub 仓库导入；返回 null 表示成功。 */
    fun importGitHub(c: Context, repoUrl: String): String? = manager(c).importSkillFromGitHub(repoUrl)
}