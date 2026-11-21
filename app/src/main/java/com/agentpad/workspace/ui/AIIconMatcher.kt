package com.agentpad.workspace.ui

/**
 * 模型名 → LOGO 资源名 的匹配器。
 *
 * 机制照抄 RikkaHub `utils/AIIconMatcher.kt`：拿模型名小写后逐条正则去碰，
 * 命中就返回 `assets/icons/` 下的文件名，全部不中就返回 null
 * （调用方退化成「首字母圆角块」文字头像）。
 *
 * 图标本身来自 lobehub 图标集（参考源码同款，55 个，共约 216KB）。
 *
 * ⚠ 顺序有意义：越具体的规则要越靠前。
 *   例如 "gemini" 必须排在 "google" 前面，否则 Gemini 会被判成 Google；
 *   "anthropic" 排在 "claude" 前，避免某些代理名里带 anthropic 却指向 claude。
 *
 * 结果做了一个小缓存：模型名在同一会话里会被反复查询（每帧渲染输入栏时都会问一次），
 * 正则串匹配虽然不贵，但没必要每帧跑 55 条。
 */
object AIIconMatcher {

    private val cache = HashMap<String, String?>(64)

    /**
     * @param name 模型 id 或显示名，例如 "deepseek-v3.2-exp"、"claude-sonnet-4"、"gpt-5"
     * @return assets/icons/ 下的文件名；无匹配返回 null
     */
    fun iconFor(name: String): String? {
        if (name.isBlank()) return null
        cache[name]?.let { return it }
        val lower = name.lowercase()
        val hit = when {
            RIKKA.containsMatchIn(lower) -> "rikkahub.svg"
            GEMINI.containsMatchIn(lower) -> "gemini-color.svg"
            GOOGLE.containsMatchIn(lower) -> "google-color.svg"
            ANTHROPIC.containsMatchIn(lower) -> "anthropic.svg"
            CLAUDE.containsMatchIn(lower) -> "claude-color.svg"
            DEEPSEEK.containsMatchIn(lower) -> "deepseek-color.svg"
            GROK.containsMatchIn(lower) -> "xai.svg"
            XAI.containsMatchIn(lower) -> "xai.svg"
            QWEN.containsMatchIn(lower) -> "qwen-color.svg"
            DOUBAO.containsMatchIn(lower) -> "doubao-color.svg"
            OPENROUTER.containsMatchIn(lower) -> "openrouter.svg"
            ZHIPU.containsMatchIn(lower) -> "zhipu-color.svg"
            MISTRAL.containsMatchIn(lower) -> "mistral-color.svg"
            META.containsMatchIn(lower) -> "meta-color.svg"
            HUNYUAN.containsMatchIn(lower) -> "hunyuan-color.svg"
            GEMMA.containsMatchIn(lower) -> "gemma-color.svg"
            PERPLEXITY.containsMatchIn(lower) -> "perplexity-color.svg"
            ALIYUN.containsMatchIn(lower) -> "alibabacloud-color.svg"
            BYTEDANCE.containsMatchIn(lower) -> "bytedance-color.svg"
            SILICON_CLOUD.containsMatchIn(lower) -> "siliconflow.svg"
            AIHUBMIX.containsMatchIn(lower) -> "aihubmix-color.svg"
            NAAPI.containsMatchIn(lower) -> "naapi-color.svg"
            OLLAMA.containsMatchIn(lower) -> "ollama.svg"
            GITHUB.containsMatchIn(lower) -> "github.svg"
            CLOUDFLARE.containsMatchIn(lower) -> "cloudflare-color.svg"
            MINIMAX.containsMatchIn(lower) -> "minimax-color.svg"
            JUHENEXT.containsMatchIn(lower) -> "juhenext.png"
            KIMI.containsMatchIn(lower) -> "kimi-color.svg"
            MOONSHOT.containsMatchIn(lower) -> "moonshot.svg"
            NUM302.containsMatchIn(lower) -> "302ai.svg"
            STEP.containsMatchIn(lower) -> "stepfun-color.svg"
            INTERN.containsMatchIn(lower) -> "internlm-color.svg"
            COHERE.containsMatchIn(lower) -> "cohere-color.svg"
            TAVERN.containsMatchIn(lower) -> "tavern.png"
            CEREBRAS.containsMatchIn(lower) -> "cerebras-color.svg"
            NVIDIA.containsMatchIn(lower) -> "nvidia-color.svg"
            PPIO.containsMatchIn(lower) -> "ppio-color.svg"
            VERCEL.containsMatchIn(lower) -> "vercel.svg"
            GROQ.containsMatchIn(lower) -> "groq.svg"
            TOKENPONY.containsMatchIn(lower) -> "tokenpony.svg"
            LING.containsMatchIn(lower) -> "ling.png"
            MIMO.containsMatchIn(lower) -> "xiaomimimo.svg"
            LONGCAT.containsMatchIn(lower) -> "longcat-color.svg"
            ELEVENLABS.containsMatchIn(lower) -> "elevenlabs.svg"
            FISH_AUDIO.containsMatchIn(lower) -> "fish-audio.svg"
            SEARCH_LINKUP.containsMatchIn(lower) -> "linkup.png"
            SEARCH_BING.containsMatchIn(lower) -> "bing.png"
            SEARCH_TAVILY.containsMatchIn(lower) -> "tavily.png"
            SEARCH_EXA.containsMatchIn(lower) -> "exa.png"
            SEARCH_BRAVE.containsMatchIn(lower) -> "brave.svg"
            SEARCH_METASO.containsMatchIn(lower) -> "metaso.svg"
            SEARCH_FIRECRAWL.containsMatchIn(lower) -> "firecrawl.svg"
            SEARCH_JINA.containsMatchIn(lower) -> "jina.svg"
            SEARCH_SEARXNG.containsMatchIn(lower) -> "searxng.svg"
            // OpenAI 必须最后：它的正则 (gpt|openai|o\d) 太宽，
            // 放前面会把 "openrouter" 之外的很多名字误判成 GPT。
            OPENAI.containsMatchIn(lower) -> "openai.svg"
            else -> null
        }
        cache[name] = hit
        return hit
    }

    // ---- 各家模型名特征（顺序敏感，见文件头注释）----
    private val RIKKA = Regex("rikka|auto")
    private val GEMINI = Regex("gemini|nano-banana")
    private val GOOGLE = Regex("google")
    private val ANTHROPIC = Regex("anthropic")
    private val CLAUDE = Regex("claude")
    private val DEEPSEEK = Regex("deepseek")
    private val GROK = Regex("grok")
    private val XAI = Regex("xai")
    private val QWEN = Regex("qwen|qwq|qvq|\\bwanx?[\\d-]")
    private val DOUBAO = Regex("doubao|豆包|火山")
    private val OPENROUTER = Regex("openrouter")
    private val ZHIPU = Regex("zhipu|智谱|glm")
    private val MISTRAL = Regex("mistral")
    private val META = Regex("meta\\b|(?<!o)llama|muse")
    private val HUNYUAN = Regex("hunyuan|tencent|\\bhy(\\b|\\d)")
    private val GEMMA = Regex("gemma")
    private val PERPLEXITY = Regex("perplexity")
    private val BYTEDANCE = Regex("bytedance|火山")
    private val ALIYUN = Regex("aliyun|阿里云|百炼")
    private val SILICON_CLOUD = Regex("silicon|硅基")
    private val AIHUBMIX = Regex("aihubmix")
    private val NAAPI = Regex("naapi|钠api|鈉api")
    private val OLLAMA = Regex("ollama")
    private val GITHUB = Regex("github")
    private val CLOUDFLARE = Regex("cloudflare")
    private val MINIMAX = Regex("minimax")
    private val JUHENEXT = Regex("juhenext")
    private val KIMI = Regex("kimi|k3")
    private val MOONSHOT = Regex("moonshot|月之暗面")
    private val NUM302 = Regex("302")
    private val STEP = Regex("step|阶跃")
    private val INTERN = Regex("intern|书生")
    private val COHERE = Regex("cohere|command-.+")
    private val TAVERN = Regex("tavern")
    private val CEREBRAS = Regex("cerebras")
    private val NVIDIA = Regex("nvidia")
    private val PPIO = Regex("ppio|派欧")
    private val VERCEL = Regex("vercel")
    private val GROQ = Regex("groq")
    private val TOKENPONY = Regex("tokenpony|小马算力")
    private val LING = Regex("ling|ring|百灵")
    private val MIMO = Regex("mimo|xiaomi|小米")
    private val LONGCAT = Regex("longcat")
    private val ELEVENLABS = Regex("elevenlabs")
    private val FISH_AUDIO = Regex("fish.?audio|fishaudio")
    private val SEARCH_LINKUP = Regex("linkup")
    private val SEARCH_BING = Regex("bing")
    private val SEARCH_TAVILY = Regex("tavily")
    private val SEARCH_EXA = Regex("exa")
    private val SEARCH_BRAVE = Regex("brave")
    private val SEARCH_METASO = Regex("metaso|秘塔")
    private val SEARCH_FIRECRAWL = Regex("firecrawl")
    private val SEARCH_JINA = Regex("jina")
    private val SEARCH_SEARXNG = Regex("searxng")
    private val OPENAI = Regex("(gpt|openai|o\\d)")

    /**
     * 显示用的短名：把冗长的模型 id 压缩成适合塞进小 Chip 的形式。
     *
     * 例：
     *   "deepseek-v3.2-exp-thinking"  → "DeepSeek 3.2"
     *   "claude-sonnet-4-20250514"    → "Claude Sonnet 4"
     *   "gpt-5-mini"                  → "GPT 5 mini"
     *
     * 目的就是主人说的「省空间」——不让长 id 把输入栏顶爆。
     */
    fun shortName(name: String): String {
        val raw = name.trim()
        if (raw.isEmpty()) return ""
        // 去掉常见的渠道前缀（如 "openai/gpt-5"、"deepseek/deepseek-chat"）
        val base = raw.substringAfterLast('/')
        val hit = iconFor(base)
        val brand = when (hit) {
            "openai.svg" -> "GPT"
            "claude-color.svg", "anthropic.svg" -> "Claude"
            "deepseek-color.svg" -> "DeepSeek"
            "gemini-color.svg" -> "Gemini"
            "qwen-color.svg" -> "Qwen"
            "doubao-color.svg" -> "豆包"
            "kimi-color.svg", "moonshot.svg" -> "Kimi"
            "zhipu-color.svg" -> "GLM"
            "hunyuan-color.svg" -> "混元"
            "minimax-color.svg" -> "MiniMax"
            "mistral-color.svg" -> "Mistral"
            "meta-color.svg" -> "Llama"
            "xai.svg" -> "Grok"
            "stepfun-color.svg" -> "Step"
            "cohere-color.svg" -> "Cohere"
            "nvidia-color.svg" -> "NVIDIA"
            else -> null
        }
        if (brand == null) {
            // 没识别出品牌：原样返回，但过长就截断
            return if (base.length <= 18) base else base.take(17) + "…"
        }
        // 品牌后只保留版本号部分（数字 + 少量字母后缀）
        val tail = base.lowercase()
            .removePrefix(brand.lowercase())
            .replace(Regex("[-_](latest|chat|preview|exp|thinking|instruct|2025\\d*|2024\\d*)$"), "")
            .trim(' ', '-', '_')
        val version = Regex("\\d+(\\.\\d+)?").find(tail)?.value
        val suffix = listOf("mini", "flash", "pro", "max", "turbo", "plus", "lite", "nano")
            .firstOrNull { tail.contains(it) }
        return buildString {
            append(brand)
            version?.let { append(' ').append(it) }
            suffix?.let { append(' ').append(it) }
        }
    }
}