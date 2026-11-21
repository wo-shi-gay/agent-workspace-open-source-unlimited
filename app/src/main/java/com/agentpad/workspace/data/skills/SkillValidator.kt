package com.agentpad.workspace.data.skills

/**
 * 照抄 RikkaHub 3.2.0：
 * app/src/main/java/me/rerere/rikkahub/data/skills/SkillValidator.kt
 *
 * 安装技能前校验 SKILL.md：name 必须能原样当目录名（trim 不变、≤64、不以 . 开头、无非法字符），
 * 且 description 不能空。逻辑与源码一致，只改了 package。
 */
object SkillValidator {
    const val MAX_NAME_LENGTH = 64

    /** 通过时返回技能名，失败时为 [SkillValidationException] */
    fun validate(content: String): Result<String> = runCatching {
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: throw SkillValidationException(SkillValidationError.MissingName)
        // 安装时若 trim 后作为目录名，读回来的 name 仍带空白，两者就对不上了
        ensure(name == name.trim(), SkillValidationError.NameHasSurroundingWhitespace)
        ensure(name.length <= MAX_NAME_LENGTH, SkillValidationError.NameTooLong)
        // 以 . 开头的目录会被当作隐藏目录跳过，装上也看不到
        ensure(!name.startsWith('.'), SkillValidationError.NameStartsWithDot)
        ensure(name.none { it == '/' || it == '\\' || it.isISOControl() }, SkillValidationError.NameHasInvalidChars)
        ensure(!frontmatter["description"].isNullOrBlank(), SkillValidationError.MissingDescription)
        name
    }

    private fun ensure(condition: Boolean, error: SkillValidationError) {
        if (!condition) throw SkillValidationException(error)
    }
}

enum class SkillValidationError {
    MissingName,
    NameHasSurroundingWhitespace,
    NameTooLong,
    NameStartsWithDot,
    NameHasInvalidChars,
    MissingDescription,
}

/** 界面按 [error] 显示本地化文案 */
class SkillValidationException(val error: SkillValidationError) : IllegalArgumentException(error.name)

/** 中文文案（RikkaHub 用 strings.xml，这里直接内联，省一堆资源文件） */
fun SkillValidationError.message(): String = when (this) {
    SkillValidationError.MissingName -> "SKILL.md 的 frontmatter 缺少 name"
    SkillValidationError.NameHasSurroundingWhitespace -> "name 首尾不能有空格"
    SkillValidationError.NameTooLong -> "name 不能超过 ${SkillValidator.MAX_NAME_LENGTH} 个字符"
    SkillValidationError.NameStartsWithDot -> "name 不能以 . 开头（会被当成隐藏目录）"
    SkillValidationError.NameHasInvalidChars -> "name 不能含 / \\ 或控制字符"
    SkillValidationError.MissingDescription -> "SKILL.md 的 frontmatter 缺少 description"
}
