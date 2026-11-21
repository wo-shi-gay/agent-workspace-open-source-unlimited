package com.agentpad.workspace.ai

/**
 * Thinking-strength levels, mirroring RikkaHub's ReasoningLevel design.
 * Each level carries a budgetTokens hint and the standard OpenAI-compatible
 * `reasoning_effort` string. AUTO means "let the server decide" (no param).
 */
enum class ReasoningLevel(val budgetTokens: Int, val effort: String, val label: String) {
    OFF(0, "none", "关闭"),
    AUTO(-1, "auto", "自动"),
    LOW(1_000, "low", "低"),
    MEDIUM(2_000, "medium", "中"),
    HIGH(8_000, "high", "高"),
    XHIGH(16_000, "xhigh", "超高"),
    MAX(32_000, "max", "最大");

    val isEnabled: Boolean get() = this != OFF

    /**
     * 实际上线的 `reasoning_effort` 取值。
     *
     * DeepSeek 官方只接受 none / low / high / max；文档虽给了兼容映射
     * （minimal→low，medium/xhigh→high），但直接发合法值能避开各家网关
     * 兼容层不一致导致的 400。medium/xhigh 归到 high，语义最接近。
     */
    val wireEffort: String
        get() = when (this) {
            OFF -> "none"
            AUTO -> "none"      // AUTO 走 thinking.disabled 分支，这里不参与
            LOW -> "low"
            MEDIUM, HIGH -> "high"
            XHIGH, MAX -> "max"
        }

    companion object {
        fun fromName(raw: String?): ReasoningLevel =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}