// Message list adapted from RikkaHub 2.4.17 (AGPL-3.0-only),
// ChatMessage.kt / ChatMessageCot.kt, ported onto the ChatStore parts model.

package com.agentpad.workspace.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import com.agentpad.workspace.ai.ToolProtocol
import com.agentpad.workspace.data.ChatStore
import com.agentpad.workspace.ui.icons.HugeIconsLocal
import kotlin.math.roundToInt
// 图标统一用 material-icons-core（项目已有的依赖，零新增体积）。
import kotlinx.coroutines.CoroutineScope
/** 照抄源码 ChatList.kt:91 的常量：列表末尾的滚动锚点 item key。 */
private const val ScrollBottomKey = "ScrollBottomKey"

// ============================================================
// 顶部渐隐 / 消息快速跳转
// 逐条照抄 RikkaHub 3.1.1：
//   ChatList.kt:192-209  Modifier.topFadingEdge(height)
//   ChatList.kt:778-878  BoxScope.MessageJumper(show, onLeft, scope, state)
// ============================================================

/**
 * 顶部渐隐：内容滚到标题栏下面时逐渐透明。
 *
 * 源码 ChatList.kt:194-209 的关键点有两条：
 *  1. `CompositingStrategy.Offscreen` —— 不额外开离屏层就没法用 DstIn 擦除；
 *  2. 用 `BlendMode.DstIn` 把渐变当成「透明度掩码」刷在内容上，
 *     而不是盖一层背景色 —— 这样背景图/动态渐变背景不会被遮住。
 */
private fun Modifier.topFadingEdge(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val fadeHeight = height.toPx()
        val brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.6f to Color.Black.copy(alpha = 0.15f),
            1f to Color.Black,
            startY = 0f,
            endY = fadeHeight,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush, size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
        }
    }



/**
 * Ported from RikkaHub's ImeAutoScroller.kt. Keeps the tail visible when the
 * soft keyboard opens, so the newest reply is not hidden behind the IME.
 */
@Composable
private fun ImeLazyListAutoScroller(lazyListState: LazyListState) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    var lastImeHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { ime.getBottom(density) }.collect { keyboardHeight ->
            if (keyboardHeight > 0) {
                lazyListState.scrollBy((keyboardHeight - lastImeHeight).toFloat())
                lastImeHeight = keyboardHeight
            }
        }
    }
}

/**
 * 思考链里的一个步骤 **及其在完整 parts 列表中的下标**。
 *
 * 照抄 RikkaHub `ChatMessageCot.kt:34`：分组时就把 index 带上。
 * 源码里是 `ContentBlock(part, index)`；这里同样在分组阶段记录，
 * 渲染时直接读，不再回头做 `parts.indexOf(part)` ——
 * `ChatStore.Part` 是 data class，`indexOf` 走 equals 会逐字符比较 text，
 * 流式期间每帧都跑一遍 O(n)，是「越流越卡」的执行点之一。
 */
private data class MsgStep(val part: ChatStore.Part, val index: Int)

private sealed interface MsgBlock {
    data class Cot(val steps: List<MsgStep>) : MsgBlock
    /** 正文块。带全局 index，供 key() 与「是否最后一块」判断使用。 */
    data class Body(val text: String, val index: Int) : MsgBlock
    /** 图片 / 视频 / 音频 / 文档附件瓦片（RikkaHub ChatMessageMediaTile）。 */
    data class Media(val kind: String, val uri: String, val name: String, val index: Int) : MsgBlock
    /** 生图进行中的占位卡（呼吸点 + prompt）。 */
    data class GeneratingImage(val prompt: String, val index: Int) : MsgBlock
}

/**
 * RikkaHub's groupMessageParts(): consecutive reasoning/tool parts form a
 * ThinkingBlock; text closes it and renders as its own body block. Tools are
 * steps inside the chain, never standalone cards.
 */
private data class DeliveredFile(val path: String, val name: String, val note: String)

/** 兼容 [AI_FILE] 和 [AIFILE] 两种标记（AI 常写成后者）。 */
private val DELIVERED_RE = Regex("\\[(?:AI_)?FILE\\]([\\s\\S]*?)\\[/(?:AI_)?FILE\\]", RegexOption.IGNORE_CASE)

private fun deliveredFiles(text: String): List<DeliveredFile> {
    return DELIVERED_RE.findAll(text).mapNotNull { m ->
        val block = m.groupValues[1]
        val path = DELIVERED_PATH_RE.find(block)?.groupValues?.get(1)?.trim()
            ?.trim('`', '"', '\'', ' ')
            ?: return@mapNotNull null
        if (path.isBlank()) return@mapNotNull null
        val name = DELIVERED_NAME_RE.find(block)?.groupValues?.get(1)?.trim()?.trim('`', '"', '\'', ' ')
            ?: path.substringAfterLast('/').ifBlank { "交付文件" }
        val note = DELIVERED_NOTE_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        DeliveredFile(path, name, note)
    }.toList()
}

private fun cleanDeliveredMarkers(text: String): String =
    text.replace(DELIVERED_RE, "").trim()

/**
 * 渲染层的最小清理：只剥掉协议包裹标记本身，**绝不删除正文内容**。
 *
 * 与 [ToolProtocol.cleanAssistantText] 的区别：
 *  - cleanAssistantText 会把 <tool_call> 之后的所有内容 / 裸工具 JSON 整块删掉；
 *  - 这里只在「标记单独成段」时才剥除，正文里的任何文字都保留。
 *
 * 为什么渲染层必须这么保守：流式阶段 part 是逐块累加的，如果渲染时按
 * cleanAssistantText 处理，用户会先看到字、随后整块消失。
 */
// ⚠ 2026-11-04 修正则：原来的两条都匹配不到实际遇到的形态。
//
//  形态一（完整块）：<tool_call>{...}</tool_call>
//  形态二（未闭合）：<tool_call>{...}          ← 流式中途或模型漏写闭合标签
//  形态三（DSML）：  <||DSML||parameter> / </||DSML||invoke> / <|DSML|...>
//                    ↑ 注意是 `</||DSML||…>`，斜杠后面直接跟两根竖线
//
//  旧正则的问题：
//    TOOL_CALL_BLOCK_RE 强制要求 </tool_call>，形态二漏掉（就是截图里那串）；
//    DSML_TAG_RE 是 `<\|?DSML\|?[^>]*>`，`<` 后面只允许「一个可选竖线」，
//    匹配不了 `</||DSML||…>`（斜杠 + 两根竖线）。
private val TOOL_CALL_BLOCK_RE = Regex("(?is)<tool_call>.*?</tool_call>")
// 未闭合的 <tool_call>：完整块已在上面被移除，所以走到这里还剩下的必然没有闭合标签，
// 直接从它开始剥到结尾。这一段一定是协议载荷（截图里正文全是这个），不是人话。
private val TOOL_CALL_OPEN_RE = Regex("(?is)<tool_call>.*$")
// DSML 标签：兼容 <||DSML||…>、</||DSML||…>、<|DSML|…> 等形式
private val DSML_TAG_RE = Regex("(?i)</?\\|*DSML\\|*[^>]*>")
// 落单的闭合标记（如 `</||DSML||invoke>` 被上面的规则吃掉后残留的空标签行）
private val DANGLING_CLOSE_RE = Regex("(?i)^\\s*</[^>]*>\\s*$", RegexOption.MULTILINE)
// ⚠ 2026-11-01 性能修复：这个正则原本在 stripProtocolWrappers 里每次新建，
// 而该函数每个 text part、每次重组都要跑。提到顶层只编译一次。
private val BLANK_RUN_RE = Regex("\\n{3,}")
// 交付文件块的元信息解析（原本每次调用都新建 3 个 Regex）
private val DELIVERED_PATH_RE = Regex("(?m)^\\s*(?:路径|Path)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
private val DELIVERED_NAME_RE = Regex("(?m)^\\s*(?:名称|Name)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
private val DELIVERED_NOTE_RE = Regex("(?m)^\\s*(?:说明|Note)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)

private fun stripProtocolWrappers(text: String): String {
    if (text.isBlank()) return ""
    // 顺序有讲究：
    //  1) 先剥完整块，避免「未闭合」规则把完整块的收尾标记也一起吞掉；
    //  2) 再剥未闭合的 <tool_call>…（从它到结尾全是协议载荷）；
    //  3) 再剥 DSML 标签（含 </||DSML||…> 这种）；
    //  4) 最后清掉单独成行的落单闭合标签残留。
    var out = TOOL_CALL_BLOCK_RE.replace(text, "")
    out = TOOL_CALL_OPEN_RE.replace(out, "")
    out = DSML_TAG_RE.replace(out, "")
    out = DANGLING_CLOSE_RE.replace(out, "")
    // 只压掉连续空行，不做 trim 之外的任何内容裁剪
    return out.replace(BLANK_RUN_RE, "\n\n").trim()
}


/**
 * 从工具结果里抽出图片本地路径。
 *
 * 支持两种来源：
 *  · generate_image  → {"images":[{"path":"..."}, ...]}
 *  · read_image      → {"image_path":"..."}
 * 解析失败一律返回空表，绝不影响正文与思考链的渲染。
 */
private fun imagePathsOf(part: ChatStore.Part): List<String> {
    if (part.toolName != "generate_image" && part.toolName != "read_image") return emptyList()
    val raw = part.toolResult
    if (raw.isBlank() || !raw.trimStart().startsWith("{")) return emptyList()
    return runCatching {
        val o = org.json.JSONObject(raw)
        if (o.optBoolean("success") == false) return emptyList()
        val out = ArrayList<String>()
        o.optString("image_path").takeIf { it.isNotBlank() }?.let { out.add(it) }
        o.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        out
    }.getOrDefault(emptyList())
}


/** 从 generate_image 的参数里取 prompt，用于「正在生成」占位卡。 */
private fun generateImagePromptOf(part: ChatStore.Part): String = runCatching {
    val o = org.json.JSONObject(part.toolArguments.ifBlank { "{}" })
    o.optString("prompt").take(120)
}.getOrDefault("")

private fun groupParts(parts: List<ChatStore.Part>): List<MsgBlock> {
    val out = mutableListOf<MsgBlock>()
    val cot = mutableListOf<MsgStep>()
    fun flush() { if (cot.isNotEmpty()) { out += MsgBlock.Cot(cot.toList()); cot.clear() } }
    // ⚠ 同一张图只画一次。
    // 模型常见行为：generate_image 之后又调 read_image 「确认一下」——
    // 两条工具结果都带同一个本地路径，于是同一张图在气泡里出现两遍。
    // 这里按「规范化路径」去重；keep 的是第一次出现的那张。
    val seenImages = HashSet<String>()
    fun pushImage(path: String, index: Int): Boolean {
        val key = runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
        if (!seenImages.add(key)) return false
        flush()
        out += MsgBlock.Media(
            kind = "image",
            uri = path,
            name = path.substringAfterLast('/'),
            index = index,
        )
        return true
    }
    // ⚠ 照抄 RikkaHub ChatMessageCot.kt:57 的 fastForEachIndexed：
    // 分组时就带上全局 index，渲染端不再做 indexOf 反查。
    parts.forEachIndexed { index, part ->
        when (part.type) {
            // reasoning / tool：收进思考链，带全局下标
            "reasoning", "tool" -> {
                cot += MsgStep(part, index)
                // 生图 / 识图：把图片当成独立瓦片插进消息流，用户直接看到缩略图。
                // 以前只在工具结果里躺着一段 JSON，用户什么都看不到。
                if (part.type == "tool") {
                    val imgs = imagePathsOf(part)
                    if (imgs.isNotEmpty()) {
                        imgs.forEach { p -> pushImage(p, index) }
                    } else if (part.running && part.toolName == "generate_image") {
                        // 生成中：必须有可见反馈，否则用户以为卡死
                        flush()
                        out += MsgBlock.GeneratingImage(generateImagePromptOf(part), index)
                    }
                }
            }
            // 附件类 part：RikkaHub 里图片/视频/音频/文档是独立瓦片，不进思考链
            "image", "video", "audio", "document", "file" -> {
                flush()
                val uri = part.toolResult.ifBlank { part.text }
                if (uri.isNotBlank()) {
                    out += MsgBlock.Media(
                        kind = part.type,
                        uri = uri,
                        name = part.toolName.ifBlank { uri.substringAfterLast('/') },
                        index = index,
                    )
                }
            }
            else -> {
                flush()
                // ⚠ 2026-10-19 关键修复：「正文字流出来了，一结束就被吞」的最后一层。
                // 这里原来对每个 text part 再跑一次 cleanAssistantText，
                // 而该函数遇到 <tool_call> 或裸工具 JSON 会整段/大段删除。
                // 流式阶段 part 是逐块累加的，一旦正文里出现这类片段，
                // 渲染时整条气泡就被清空 —— 正是用户看到的现象。
                // 现在：正文只做最小清理（去掉协议包裹标记本身，不动正文内容），
                // 真正的工具 payload 剥离交给 post() 落盘前那一次。
                //
                // ⚠ 2026-11-04 修复「[AI_FILE] 原始标记漏进正文」：
                // 之前这里只有 stripProtocolWrappers，漏了 cleanDeliveredMarkers，
                // 于是交付文件块的原始标记（路径/名称/说明三行）被当成普通正文
                // 渲染出来了。bodyText 那边跑了这一步才能提取出文件卡片，
                // 正文块必须同步跑，两边行为才一致。
                val cleaned = cleanDeliveredMarkers(stripProtocolWrappers(part.text))
                if (cleaned.isNotBlank()) out += MsgBlock.Body(cleaned, index)
            }
        }
    }
    flush()
    return out
}

/** Internal bookkeeping rows (tool intents / tool groups) are not chat messages. */
fun ChatStore.Message.isRenderable(): Boolean = when (kind) {
    "toolintent", "tool", "toolgroup" -> false
    else -> true
}

/**
 * Chat transcript. Mirrors RikkaHub's message surface: user bubbles on the
 * right, assistant turns with an avatar header, a ChainOfThought card per
 * thinking group, markdown body, animated action row, and a nerd line.
 *
 * Auto-scroll follows the reference behaviour: it sticks to the bottom while
 * the user is already at the bottom, and never yanks the viewport away from a
 * reader who scrolled up.
 */
@Composable
fun AgentChatMessages(
    messages: List<ChatStore.Message>,
    onClickSuggestion: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    openLocatorSignal: Int = 0,
    /** Height of the composer overlay; the auto-scroller subtracts it so a tall
     *  message is fully reachable instead of hiding behind the input bar. */
    contentBottomPadding: Dp = 0.dp,
    /**
     * 顶部覆盖层（标题栏 + 状态栏）的高度。
     *
     * 照抄 RikkaHub ChatList.kt:340 的 `topFadingEdge(innerPadding.calculateTopPadding() + 24.dp)`：
     * 内容滚到标题栏下面时用 DstIn 掩码渐隐，避免标题和正文糊在一起。
     * 顶栏若完全占位、不与列表重叠，传 0 即可（渐隐会从列表自身顶部开始）。
     */
    contentTopPadding: Dp = 0.dp,
    /**
     * 分页（移植 Operit ChatDisplayWindowController）：
     * 上方是否还有更早的历史、当前是否正在加载、以及"再往上翻一页"的回调。
     * 未提供回调时保持旧行为（一次性全量渲染）。
     */
    hasOlderHistory: Boolean = false,
    isLoadingOlder: Boolean = false,
    onLoadOlder: (() -> Unit)? = null,
    /** 需要滚动到的条目下标（-1 = 不滚）。 */
    scrollToIndex: Int = -1,
    /** 每次自增代表一次新的滚动请求（同一下标也能重复触发）。 */
    scrollToIndexSignal: Int = 0,
    /** 每次自增代表用户点了「回到底部」，组件内部据此贴底。 */
    returnToLatestSignal: Int = 0,
    /** 点「回到底部」时通知外部把窗口重锚到最新页。 */
    onReturnToLatest: (() -> Unit)? = null,
    onRegenerate: ((ChatStore.Message) -> Unit)? = null,
    onEdit: ((ChatStore.Message) -> Unit)? = null,
    onDelete: ((ChatStore.Message) -> Unit)? = null,
    onShare: ((ChatStore.Message) -> Unit)? = null,
    onFork: ((ChatStore.Message) -> Unit)? = null,
    onOpenDeliveredFile: ((String) -> Unit)? = null,
    /**
     * 切换消息分支（照抄 RikkaHub `onUpdateMessage` → `vm.selectMessageNode`）。
     * 参数是 (消息, 目标分支下标)。
     */
    onSelectBranch: ((ChatStore.Message, Int) -> Unit)? = null,
    /**
     * 消息列表底色。
     * 默认透明 —— 让对话背景层（纯色/图片/动态/视频）透出来。
     * 只有「默认背景」模式才会传纸面色。
     */
    listBackground: Color = Color.Transparent,
) {
    val state = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // 源码 ChatList.kt:241 同样在 composable 顶部取一次 density，供
    // 局部函数 isAtBottom() 捕获使用。
    val density = LocalDensity.current
    // Hidden bookkeeping rows (tool intents / tool groups) never get a bubble;
    // they exist only so the API replay keeps assistant→tool ordering valid.
    val visible = remember(messages) { messages.filter { it.isRenderable() } }
    // ⚠ 旧写法 `visible.lastOrNull { it.role == "assistant" }` 每次重组都从尾部
    // 反向扫描（最坏 O(n)）。流式期间每帧都重组，等于每帧扫一遍。
    // 这里只依赖列表长度和最后一个元素：最后一条通常就是正在流式的 assistant。
val streamingId = remember(visible.size, visible.lastOrNull()?.id) {
        visible.lastOrNull { it.role == "assistant" }?.id
    }

    // ⚠ 2026-11-04 移除流式生成时的 KeyboardTap 触感反馈。
    // 原逻辑（照抄 RikkaHub ChatMessage.kt:302-335）在思考/正文每 50ms 更新时
    // 让马达轻震一下，一个长回复能震上百次，实际体验是「莫名其妙一直在震」。
    // 且当时**没有任何界面开关**，用户完全无法关闭。
    // 现在整块移除；下面的 streamingId 仍被 MessageRow 用来标记正在生成的那条。
    // ---- Auto-scroll：逐条照抄 RikkaHub ChatList.kt:263 与 :302-314 ----
    // 源码 :263 `fun List<LazyListItemInfo>.isAtBottom()` 是一个捕获
    // state / innerPadding 的**局部函数**，这里原样保留：局部函数 + 捕获。
    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { contentBottomPadding.toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        return lastPos <= inputPos - 8
    }
    val atBottom by remember {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.isAtBottom() }
    }
    // 照抄源码 ChatList.kt:239 `var isRecentScroll by remember { mutableStateOf(false) }`
    // 与 :317-326 的计时 effect：
    //   开始滚动 → 立刻置 true，停止滚动 → 1.5s 后置 false。
    // 这个 1500ms 就是 MessageJumper 的显隐节拍（源码 :542 用它当显示条件）。
    var isRecentScroll by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            isRecentScroll = true
            delay(1500)
            isRecentScroll = false
        } else {
            delay(1500)
            isRecentScroll = false
        }
    }

    // 源码 :238/:240 —— `LaunchedEffect(state)` 只在 state 变化时重启，
    // 直接把 streaming / visible 捕获进去会**冻在首次组合的值**（进会话时
    // streaming=false），条件永远为假、自动滚动永不触发。必须用
    // rememberUpdatedState 包一层，让 collect 每次读到最新值。
    val streamingState by rememberUpdatedState(streaming)
    val visibleState by rememberUpdatedState(visible)

    // 源码 :302-314：监听 LAYOUT（不是内容），仅在「不在滚动 且 正在生成」
    // 且「已到底」时，用 requestScrollToItem 排到下一次测量再滚。
    // 上滑停止不需要计时器：拖动时 isScrollInProgress=true 自动暂停跟随；
    // 松手停在上方则 isAtBottom() 为 false，自然不跟随 —— 精华全在这两句。
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
            if (!state.isScrollInProgress && streamingState) {
                if (visibleItemsInfo.isAtBottom()) {
                    state.requestScrollToItem(visibleState.lastIndex + 10)
                }
            }
        }
    }
    // ---- 首次进入 / 打开历史对话：直接贴到底部 ----
    //
    // ⚠ 上面那个自动跟随 effect 有 `&& streaming` 条件，也就是说**非流式时
    // 根本不跟随**（这是照抄源码的：源码同样用 loadingState 当开关）。
    // 打开一个已有对话时 streaming=false，列表就停在顶部，
    // 用户必须手动往下翻几百屏才能看到最新消息 —— 完全反人类。
    //
    // 这里做一次性贴底：
    //  - 只在「首次进入」或「切换了会话」时触发（用首条消息 id 做标识）；
    //  - 用 scrollToItem(lastIndex, Int.MAX_VALUE) 让列表钳到绝对底部。
    var didInitialScroll by remember { mutableStateOf(false) }
    val firstMessageId = visible.firstOrNull()?.id
    LaunchedEffect(firstMessageId, visible.size) {
        if (visible.isEmpty()) return@LaunchedEffect
        if (didInitialScroll) return@LaunchedEffect
        // 等第一帧布局完成再滚：刚进 LaunchedEffect 时 layoutInfo 还是空的，
        // 直接判 totalItemsCount 会永远拿到 0，滚底就永远不触发。
        // snapshotFlow 会在布局信息真正可用时再发一次，这里取第一个非零值。
        val ready = withTimeoutOrNull(2_000L) {
            snapshotFlow { state.layoutInfo.totalItemsCount }
                .first { it > 0 }
        }
        if (ready == null) return@LaunchedEffect
        state.scrollToItem(visible.lastIndex, Int.MAX_VALUE)
        didInitialScroll = true
    }

    // Keep the tail visible when the soft keyboard opens.
    ImeLazyListAutoScroller(state)

    // 用户点「回到底部」：窗口重锚后贴到底（内容变了，需要重新定位）。
    LaunchedEffect(returnToLatestSignal) {
        if (returnToLatestSignal <= 0 || visible.isEmpty()) return@LaunchedEffect
        state.scrollToItem(visible.lastIndex, Int.MAX_VALUE)
    }

    // 补完更早的历史页后，把新加载的第一条提到顶部，避免列表跳到最顶。
    LaunchedEffect(scrollToIndexSignal) {
        if (scrollToIndex < 0 || visible.isEmpty()) return@LaunchedEffect
        val headerOffset = if (hasOlderHistory || isLoadingOlder) 1 else 0
        state.scrollToItem((scrollToIndex + headerOffset).coerceIn(0, visible.lastIndex + headerOffset))
    }

    var locatorOpen by remember { mutableStateOf(false) }
    var highlightIndex by remember { mutableStateOf(-1) }
    // Composer search button: any increment opens the locator.
    LaunchedEffect(openLocatorSignal) {
        if (openLocatorSignal > 0) locatorOpen = true
    }

    Box(modifier.fillMaxWidth()) {
        // ⚠ 2026-11-04 暗色主题可读性修复：
        // 聊天页没有 Surface 包裹（顶栏/输入栏是各自独立的 ComposeView），
        // 于是 LocalContentColor 退回 material3 默认的**黑色**。
        // BetterText 的 Markdown 正文用 `color.takeOrElse { LocalContentColor.current }`
        // 取色 —— 浅色主题下墨色≈黑看不出问题，暗色主题下就成了「黑字黑底」。
        // 这里显式提供聊天正文色，让 Markdown 正文/表格/公式都跟着主题走。
        CompositionLocalProvider(
            LocalContentColor provides ChatBody,
            LocalTextStyle provides LocalTextStyle.current.merge(
                androidx.compose.ui.text.TextStyle(color = ChatBody),
            ),
        ) {
        LazyColumn(
            // 照抄源码 ChatList.kt:338-341 —— topFadingEdge 挂在 LazyColumn 的
            // modifier 上，高度取「顶栏高度 + 24dp」，让内容滚进标题栏时自然淡出。
            modifier = Modifier
                .fillMaxSize()
                .background(listBackground)
                .topFadingEdge(contentTopPadding + 24.dp),
            state = state,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // ⚠ 2026-11-04 顶栏叠加后：顶栏浮在列表上（不再占位），
            // 所以这里必须把顶栏高度加进 contentPadding.top，
            // 否则第一条消息会被顶栏压住。照抄 RikkaHub ChatList.kt:332-335：
            //   contentPadding = PaddingValues(16.dp) + PaddingValues(
            //       top = innerPadding.calculateTopPadding(),
            //       bottom = 32.dp + innerPadding.calculateBottomPadding(),
            //   )
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp + contentTopPadding,
                bottom = 20.dp,
            ),
        ) {
            if (hasOlderHistory || isLoadingOlder) {
                item(key = "__load_older__") {
                    LoadOlderRow(
                        loading = isLoadingOlder,
                        onClick = { if (!isLoadingOlder) onLoadOlder?.invoke() },
                    )
                }
            }
            itemsIndexed(
                visible,
                // ⚠ key 里带上 selectIndex：切换分支时本体 id 不变（节点身份不能变），
                // 只靠 id 作 key 会让 Compose 认为该 item 未变化而不重组，
                // 表现就是"点了分支没反应"。加上选中下标后 key 变化 → 正常重组。
                key = { _, message -> "${message.id}#${message.selectIndex}" },
            ) { index, message ->
                AgentMessageRow(
                    message = message,
                    streaming = streaming && message.id == streamingId,
                    highlighted = index == highlightIndex,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onShare = onShare,
                    onFork = onFork,
                    onOpenDeliveredFile = onOpenDeliveredFile,
                    onSelectBranch = onSelectBranch,
                )
            }

            // 照抄源码 ChatList.kt:433-440 —— 末尾放一个锚点 item，
            // `requestScrollToItem(lastIndex + 10)` 就是往这儿滚（越界会被钳住）。
            // 没有它，滚到最后一条消息只会对齐该消息顶部，长回答仍看不全。
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }
        }  // ← CompositionLocalProvider（暗色正文色）结束
        // Jump-to-bottom pill: appears only when the reader is scrolled up.
        AnimatedVisibility(
            visible = !atBottom && visible.isNotEmpty(),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
        ) {
            Surface(
                color = ChatAccent,
                shape = CircleShape,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(36.dp)
                    .clickable {
                        // 先让外部把窗口重锚到最新页，再贴底；否则最新消息还在窗口外，
                        // 滚到最后一条也看不到真正的最新内容。
                        onReturnToLatest?.invoke()
                        coroutineScope.launch { state.animateScrollToItem(visible.lastIndex) }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ArrowDownward, "回到底部", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ---- 消息快速跳转（照抄 ChatList.kt:778-878 MessageJumper）----
        // 源码 ChatList.kt:541-546 的显示条件：
        //   show = isRecentScroll && !state.isScrollInProgress &&
        //          settings.displaySetting.showMessageJumper && !captureProgress
        // 这里省去 settings 开关与截图态（本项目没有对应设置项），
        // 保留最核心的两条：**刚滚动过** + **没在滚动中**。
        BoxScope_MessageJumper(
            show = isRecentScroll && !state.isScrollInProgress && visible.isNotEmpty(),
            onLeft = false,
            scope = coroutineScope,
            state = state,
        )


        if (locatorOpen) {
            MessageLocatorSheet(
                messages = visible,
                onDismiss = { locatorOpen = false },
                onPick = { index ->
                    locatorOpen = false
                    highlightIndex = index
                    coroutineScope.launch {
                        state.animateScrollToItem(index)
                        delay(700)
                        highlightIndex = -1
                    }
                },
            )
        }
    }
}

/** 「加载更早历史」行（Operit ChatArea 里那颗 load_more_history 的等价物）。 */
@Composable
private fun LoadOlderRow(loading: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            DotLoading(size = 7.dp, color = ChatAccent)
        } else {
            Text("加载更早的消息", color = ChatFaint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AgentMessageRow(
    message: ChatStore.Message,
    streaming: Boolean,
    highlighted: Boolean = false,
    onRegenerate: ((ChatStore.Message) -> Unit)?,
    onEdit: ((ChatStore.Message) -> Unit)?,
    onDelete: ((ChatStore.Message) -> Unit)?,
    onShare: ((ChatStore.Message) -> Unit)?,
    onFork: ((ChatStore.Message) -> Unit)?,
    onOpenDeliveredFile: ((String) -> Unit)?,
    onSelectBranch: ((ChatStore.Message, Int) -> Unit)? = null,
) {
    val highlightBg = if (highlighted) ChatAccent.copy(alpha = 0.10f) else Color.Transparent
    Box(Modifier.fillMaxWidth().background(highlightBg, RoundedCornerShape(12.dp))) {
        when (message.role) {
            "user" -> UserBubble(message, onEdit, onShare, onDelete, onFork)
            "assistant" -> AssistantTurn(
                message = message,
                streaming = streaming,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onDelete = onDelete,
                onShare = onShare,
                onFork = onFork,
                onOpenDeliveredFile = onOpenDeliveredFile,
                onSelectBranch = onSelectBranch,
            )
            else -> Unit // tool / toolintent rows are folded into the assistant parts
        }
    }
}

@Composable
private fun UserBubble(
    message: ChatStore.Message,
    onEdit: ((ChatStore.Message) -> Unit)?,
    onShare: ((ChatStore.Message) -> Unit)?,
    onDelete: ((ChatStore.Message) -> Unit)?,
    onFork: ((ChatStore.Message) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // RikkaHub 3.1.1 ChatMessage.kt:405-423 原文：
        //   ChatMessageBubble(
        //       role = role,
        //       color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = bubbleOpacity),
        //       onClick = { onUserMessageClick?.invoke() },
        //       // 用户气泡不占满整行，和助手的回复错开
        //       modifier = Modifier.padding(start = 24.dp).animateContentSize(),
        //   ) { MarkdownBlock(...) }
        //
        // 三个照抄要点（之前全做错了）：
        //   1. 颜色 = primaryContainer（我们映射到 accentSoft），不是主色实心
        //   2. 不 fillMaxWidth(0.85f)，改用 padding(start = 24.dp) 让气泡自然收缩
        //   3. 文字色跟随 onPrimaryContainer，不是硬编码白色
        RikkaBubble(
            isUser = true,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .padding(start = 24.dp)
                .animateContentSize(),
            onClick = { onEdit?.invoke(message) },
        ) {
            SelectionContainer {
                Text(
                    message.content,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatMessageActionRow(
                isAssistant = false,
                onCopy = {
                    runCatching {
                        val mgr = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        mgr.setPrimaryClip(android.content.ClipData.newPlainText("message", message.content))
                    }
                },
                onMore = { menu = true },
            )
            ActionIcon(Icons.Outlined.Edit, "编辑并重新发送") { onEdit?.invoke(message) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("复制") }, onClick = {
                menu = false
                runCatching {
                    val mgr = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    mgr.setPrimaryClip(android.content.ClipData.newPlainText("message", message.content))
                }
            })
            DropdownMenuItem(text = { Text("分享为文本") }, onClick = { menu = false; onShare?.invoke(message) })
            DropdownMenuItem(text = { Text("从此处回溯") }, onClick = { menu = false; onFork?.invoke(message) })
            DropdownMenuItem(text = { Text("删除这条消息") }, onClick = { menu = false; onDelete?.invoke(message) })
        }
    }
}

// motionScheme 在 material3 1.4.0 里是 internal，改用 ExpressiveMotion
// （数值抄自官方 ExpressiveMotionTokens，见 ui/ExpressiveMotion.kt）。
@Composable
private fun AssistantTurn(
    message: ChatStore.Message,
    streaming: Boolean,
    onRegenerate: ((ChatStore.Message) -> Unit)?,
    onEdit: ((ChatStore.Message) -> Unit)?,
    onDelete: ((ChatStore.Message) -> Unit)?,
    onShare: ((ChatStore.Message) -> Unit)?,
    onFork: ((ChatStore.Message) -> Unit)?,
    onOpenDeliveredFile: ((String) -> Unit)?,
    onSelectBranch: ((ChatStore.Message, Int) -> Unit)? = null,
) {
    val parts = remember(message.parts, message.content, message.thinking) {
        // ⚠ 2026-10-19：fallback 路径也不能跑 cleanAssistantText ——
        // 历史消息里保存的 content 已经是干净正文，再清洗一次会把
        // 含 <tool_call> / 裸 JSON 的正文整段吞掉（重进会话后正文消失）。
        val cleanedContent = stripProtocolWrappers(message.content)
        message.parts.ifEmpty {
            buildList {
                if (message.thinking.isNotBlank()) add(ChatStore.Part("reasoning", text = message.thinking))
                if (cleanedContent.isNotBlank()) add(ChatStore.Part("text", text = cleanedContent))
            }
        }
    }
    val blocks = remember(parts) { groupParts(parts) }
    // Nothing but protocol payload? Render no bubble at all.
    if (blocks.isEmpty() && !streaming) return
    val bodyText = remember(parts, message.content) {
        // ⚠ 2026-10-19：这里原来对每个 text part 再跑 cleanAssistantText，
        // 是「正文流完就被吞」的直接执行点之一。改用保守清理，
        // 正文内容一律保留，只剥协议包裹标记。
        parts.filter { it.type == "text" }
            .joinToString("\n\n") { cleanDeliveredMarkers(stripProtocolWrappers(it.text)) }
            .ifBlank { cleanDeliveredMarkers(stripProtocolWrappers(message.content)) }
    }
    val delivered = remember(bodyText, message.content) { deliveredFiles(message.content.ifBlank { bodyText }) }
    val clipboard = LocalClipboardManager.current
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // 按需求去掉助手身份显示（原「头像 + AgentPad」标签行）。
        // 流式生成时只在正文区用 BreathingDots 表示，不再占一行。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MsgBlock.Cot -> ChainOfThought(
                        modifier = Modifier.fillMaxWidth(),
                        steps = block.steps,
                        collapsedVisibleCount = 2,
                        collapsedAdaptiveWidth = block.steps.all { it.part.type == "reasoning" },
                        cardColors = CardDefaults.cardColors(containerColor = ChatCotSurface),
                    ) { step ->
                        // ⚠ 照抄 RikkaHub ChatMessage.kt:362 / :373 的 key()：
                        //   key(step.reasoning.createdAt) / key(step.tool.toolCallId)
                        // 用完整 parts 列表里的全局下标（分组时已带上），
                        // 不再做 parts.indexOf(part) —— 那是 data class 的 equals 扫描，
                        // 会逐字符比较 text，流式期间每帧都跑一遍 O(n)。
                        val globalIndex = step.index
                        val part = step.part
                        // A reasoning part is only "live" while it is the LAST
                        // part of the turn. Once a tool call or body text follows,
                        // that thinking block is done — it must fold itself on its
                        // own instead of staying expanded until the whole turn
                        // (all tool rounds) finishes.
                        val isLive = streaming && globalIndex == parts.lastIndex
                        when (part.type) {
                            "reasoning" -> key("${message.id}:r$globalIndex") {
                                ReasoningStep(
                                    part = part,
                                    stepKey = "${message.id}:r$globalIndex",
                                    streaming = isLive,
                                    isLastStep = globalIndex == parts.lastIndex,
                                )
                            }
                            else -> key(
                                part.toolCallId.ifBlank { "${message.id}:t$globalIndex" }
                            ) {
                                // ⚠ 照抄 RikkaHub ChatMessageTools.kt:141-145：
                                //   ask_user 是交互式问答流程，不走注册式渲染框架。
                                if (part.toolName == "ask_user") {
                                    AskUserStep(part = part)
                                } else {
                                    ToolStep(
                                        part = part,
                                        stepKey = "${message.id}:t${part.toolCallId.ifBlank { globalIndex.toString() }}",
                                        streaming = isLive,
                                    )
                                }
                            }
                        }
                    }

                    is MsgBlock.Body -> key("b${block.index}") {
                        // RikkaHub 3.1.1 源码实证：
                        //   ChatMessage.kt:425  if (settings.displaySetting.showAssistantBubble) { 套气泡 }
                        //   Settings.kt:140     val showAssistantBubble: Boolean = false   ← 默认值
                        // 也就是说助手正文默认**不套气泡**，直接铺在背景上。
                        // 现在照抄：无气泡，只留正文 + animateContentSize。
                        //
                        // 2026-11-03：正文渲染从自研 MarkdownView 换成 BetterText 的
                        // AgentMarkdownBlock（照抄参考的 MarkdownBlock）。
                        // 这一换解决的是「长思考越流越卡」的最后一环：
                        // 自研 parseMd 是 remember(text) 全量重解析，流式期每帧重跑；
                        // BetterText 内置 MarkdownStreamParser，是增量解析。
                        // ⚠ 2026-11-04 长按复制修复。
                        //
                        // 之前这里直接渲染 AgentMarkdownBlock，外面**没有任何选择容器**，
                        // 于是助手正文整段都无法长按选中/复制。别处（代码块、工具输出）
                        // 都包了容器，唯独正文漏了 —— 这就是「AI 文字不能复制」的根因。
                        //
                        // 参考源码 ChatMessage.kt:457-460：
                        //     // Markdown 由 BetterText 渲染，要用它自己的选择容器；生成中也可以选择
                        //     SelectableContent { textContent() }
                        //
                        // 必须用 SelectableContent（内部是 BetterSelectionContainer）而不是
                        // 原生 SelectionContainer：BetterText 的文本不走 Compose 的
                        // TextLayoutResult，原生容器包上去长按无效。
                        // 同时 SelectableContent 自带去重，代码块不会重复建容器。
                        SelectableContent {
                            AgentMarkdownBlock(
                                content = block.text,
                                streaming = streaming && block === blocks.lastOrNull(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                            )
                        }
                    }

                    is MsgBlock.GeneratingImage -> key("gi${block.index}") {
                        // 生图进行中：呼吸点占位，告诉用户“在干活，不是卡死”
                        GeneratingImageCard(prompt = block.prompt)
                    }
                    is MsgBlock.Media -> key("m${block.index}") {
                        // 图片：真的画缩略图（生图/识图结果必须看得见）
                        // 视频/音频/文件：仍是图标瓦片
                        if (block.kind == "image") {
                            RikkaImageTile(
                                path = block.uri,
                                onClick = { onOpenDeliveredFile?.invoke(block.uri) },
                            )
                        } else {
                            RikkaMediaTile(
                                icon = when (block.kind) {
                                    "video" -> Icons.Outlined.PlayCircleOutline
                                    "audio" -> Icons.Outlined.MusicNote
                                    else -> Icons.Outlined.InsertDriveFile
                                },
                                label = block.name,
                                onClick = { onOpenDeliveredFile?.invoke(block.uri) },
                            )
                        }
                    }
                }
            }

            if (blocks.isEmpty() && streaming) {
                Surface(color = AColors.Surface, shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        // RikkaHub 风格的三点呼吸：每点错开 1/6 相位
                        BreathingDots(color = ChatAccent, dotSize = 7.dp)
                    }
                }
            }
            delivered.forEach { file ->
                Surface(
                    color = AColors.AccentSoft,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenDeliveredFile?.invoke(file.path) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(ChatAccent.copy(alpha = .13f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = ChatAccent, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(file.name, color = ChatInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(file.note.ifBlank { "AI 已交付文件 · 点击选择应用打开" }, color = ChatMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.OpenInNew, contentDescription = "打开", tint = ChatAccent, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        // RikkaHub 3.1.1 ChatMessage.kt:206-213 原文：
        //   enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
        //           fadeIn(motionScheme.defaultEffectsSpec())
        //   exit  = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
        //           fadeOut(motionScheme.fastEffectsSpec())
        // 关键点：入场用 default（慢一点、有回弹），退场用 fast（干脆利落）。
        // 这种"进慢出快"的不对称是 RikkaHub 操作栏的标志性手感。
        // 规范取自 MaterialTheme.motionScheme（material3 1.5.0-beta02 公开 API）。
        val motionScheme = androidx.compose.material3.MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = !streaming && (bodyText.isNotBlank() || parts.isNotEmpty()),
            enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                fadeIn(motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                fadeOut(motionScheme.fastEffectsSpec()),
        ) {
            Column(Modifier.animateContentSize()) {
                // RikkaHub ChatMessageActionButtons：FlowRow 自适应换行，
                // 顺序 复制 → 重新生成 → 更多，外加大号「分享/编辑/删除」收进更多菜单。
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChatMessageActionRow(
                        isAssistant = true,
                        onCopy = { clipboard.setText(AnnotatedString(bodyText)) },
                        onRegenerate = onRegenerate?.let { { it.invoke(message) } },
                        onMore = { moreMenu = true },
                        // ⚠ 照抄 RikkaHub ChatMessage.kt:217-227 的操作栏分支选择器：
                        // 只有一条分支时不显示（ChatMessageBranch.kt:37 `messages.size > 1`）。
                        branchIndex = message.selectIndex,
                        branchCount = (message.alternatives.size + 1),
                        onBranchChange = onSelectBranch?.let { cb -> { idx -> cb(message, idx) } },
                    )
                    Spacer(Modifier.weight(1f))
                    ActionIcon(Icons.Outlined.Edit, "编辑并重发") { onEdit?.invoke(message) }
                    ActionIcon(Icons.Outlined.Share, "分享") { onShare?.invoke(message) }
                    ActionIcon(Icons.Outlined.Delete, "删除") { confirmDelete = true }
                }
            }
        }

        // RikkaHub ChatMessageNerdLine：FlowRow 三段统计 + 点击弹出用量详情。
        // 数据来自 message.stats（本轮收尾时写入的 JSON），旧格式纯文本会被
        // parseNerdStats 判为 null 而自动隐藏，不会出现解析报错。
        RikkaNerdLine(message.stats, modifier = Modifier.padding(start = 2.dp, top = 2.dp))

        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            DropdownMenuItem(text = { Text("复制全文") }, onClick = {
                moreMenu = false
                clipboard.setText(AnnotatedString(bodyText))
            })
            DropdownMenuItem(text = { Text("编辑并重发") }, onClick = { moreMenu = false; onEdit?.invoke(message) })
            DropdownMenuItem(text = { Text("分享为文本") }, onClick = { moreMenu = false; onShare?.invoke(message) })
            DropdownMenuItem(text = { Text("从此处回溯") }, onClick = { moreMenu = false; onFork?.invoke(message) })
            DropdownMenuItem(text = { Text("删除这条回复") }, onClick = { moreMenu = false; confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条回复？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke(message) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, label, tint = AColors.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CopyAction(text: String) {
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(30.dp)) {
        Icon(Icons.Outlined.ContentCopy, "复制", tint = AColors.Muted, modifier = Modifier.size(16.dp))
    }
}

/**
 * Message locator (the old 跳转到消息 bottom sheet, rebuilt for Compose):
 * live search over the transcript with numbered previews; picking a row scrolls
 * the lazy list to that message and flashes it.
 */
@Composable
fun MessageLocatorSheet(
    messages: List<ChatStore.Message>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(messages, query) {
        val q = query.trim()
        messages.mapIndexedNotNull { index, message ->
            val raw = when (message.role) {
                "assistant" -> message.parts
                    .filter { it.type == "text" }
                    .joinToString("\n\n") { it.text }
                    .ifBlank { message.content }
                else -> message.content
            }
            // ⚠ 2026-10-19：搜索索引用保守清理，避免正文里的 <tool_call>/裸 JSON
            // 把后面整段内容从索引里删掉（表现是"明明有这句话，搜不到"）。
            val text = stripProtocolWrappers(raw).replace('\n', ' ')
            if (text.isBlank()) return@mapIndexedNotNull null
            when {
                q.isBlank() -> Triple(index, message, text.take(48))
                text.contains(q, ignoreCase = true) -> {
                    val hit = text.indexOf(q, ignoreCase = true)
                    val start = (hit - 20).coerceAtLeast(0)
                    Triple(index, message, text.substring(start, (start + 48).coerceAtMost(text.length)))
                }
                else -> null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到消息") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = ChatFaint) },
                    placeholder = { Text("搜索聊天记录", color = ChatFaint, fontSize = 14.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                )
                Spacer(Modifier.height(10.dp))
                if (results.isEmpty()) {
                    Text(
                        if (query.isBlank()) "暂无消息" else "没有匹配的消息",
                        color = ChatFaint,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(results, key = { it.first }) { (index, message, preview) ->
                            val roleLabel = when {
                                message.role == "user" -> "用户"
                                else -> "AI"
                            }
                            Surface(
                                color = AColors.SurfaceAlt,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onPick(index) },
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("#${index + 1}", color = ChatAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(" $roleLabel", color = ChatMuted, fontSize = 11.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text("${message.content.length} 字", color = ChatFaint, fontSize = 11.sp)
                                    }
                                    Text(
                                        preview.ifBlank { "（无预览）" },
                                        color = ChatBody,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ============================================================
// 消息快速跳转浮标
// 逐条照抄 RikkaHub 3.1.1 `ChatList.kt:778-878 BoxScope.MessageJumper`。
//
// ⚠ 2026-11-04 图标来源变更：
//   原版用 HugeIcons，但那个库本身 21MB（5000+ 图标），只为 4 个图标把
//   APK 撑大 20MB，且导致 R8 长时间跑不完。已移除该依赖，
//   改用项目已有的 material-icons-core（零新增体积、零新增依赖）。
//
// 图标映射：
//   HugeIconsLocal.ArrowUpDouble    → HugeIconsLocal.ArrowUpDouble
//   HugeIconsLocal.ArrowUp01        → HugeIconsLocal.ArrowUp01
//   HugeIconsLocal.ArrowDown01      → HugeIconsLocal.ArrowDown01
//   HugeIconsLocal.ArrowDownDouble  → HugeIconsLocal.ArrowDownDouble
// ============================================================
@Composable
private fun BoxScope.BoxScope_MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    state: LazyListState,
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = { scope.launch { state.scrollToItem(0) } },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = AColors.Surface.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIconsLocal.ArrowUpDouble,
                    contentDescription = "回到顶部",
                    tint = ChatMuted,
                    modifier = Modifier.padding(4.dp).size(20.dp),
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(0)
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = AColors.Surface.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIconsLocal.ArrowUp01,
                    contentDescription = "上一条",
                    tint = ChatMuted,
                    modifier = Modifier.padding(4.dp).size(20.dp),
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = AColors.Surface.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIconsLocal.ArrowDown01,
                    contentDescription = "下一条",
                    tint = ChatMuted,
                    modifier = Modifier.padding(4.dp).size(20.dp),
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = AColors.Surface.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIconsLocal.ArrowDownDouble,
                    contentDescription = "回到最底",
                    tint = ChatMuted,
                    modifier = Modifier.padding(4.dp).size(20.dp),
                )
            }
        }
    }
}