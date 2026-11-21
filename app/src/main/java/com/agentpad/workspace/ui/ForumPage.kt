package com.agentpad.workspace.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpad.workspace.data.ForumStore
import com.agentpad.workspace.data.UserStore
import org.json.JSONObject
import java.io.File

/**
 * 社区论坛页（底部导航第五页）。
 *
 * 三层视图：板块列表 → 帖子列表 → 帖子详情（含回复）。
 * 视觉与首页/工作区/设置完全一致（同一套 AColors / AgentCard / 圆角）。
 * 读取免登录；发帖、回帖、版主操作需要登录（走 UserStore 鉴权通道）。
 */
@Composable
fun ForumPage() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    var boards by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var boardId by remember { mutableStateOf<Int?>(null) }
    var boardTitle by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var postDetailId by remember { mutableStateOf<Int?>(null) }
    var detailPost by remember { mutableStateOf<JSONObject?>(null) }
    var replies by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf(-1) }
    var tick by remember { mutableStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 附件（发帖 / 回帖各一份，选中后拷贝到 cacheDir 的临时文件）
    var createAttach by remember { mutableStateOf<File?>(null) }
    var replyAttach by remember { mutableStateOf<File?>(null) }

    val loggedIn = UserStore.isLoggedIn(context)

    // 附件选择器
    fun copyUriToTemp(uri: Uri): File? = runCatching {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "attach_${System.currentTimeMillis()}"
        val f = File(context.cacheDir, "forum_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    }.getOrNull()

    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = copyUriToTemp(uri)
        if (f == null) { toast(context, "读取文件失败"); return@rememberLauncherForActivityResult }
        if (f.length() > 50L * 1048576) { f.delete(); toast(context, "文件超过 50MB"); return@rememberLauncherForActivityResult }
        if (showCreate) createAttach = f else replyAttach = f
        toast(context, "已选择：${f.name}（${"%.1f".format(f.length() / 1048576.0)}MB）")
    }

    // 数据加载：随层级 / 刷新变化
    LaunchedEffect(tick, boardId, postDetailId) {
        loading = true; error = null
        val pid = postDetailId
        val bid = boardId
        Thread {
            if (pid != null) {
                val (err, data) = ForumStore.postDetail(pid)
                main.post {
                    error = err
                    if (data != null) { detailPost = data.first; replies = data.second }
                    loading = false
                }
            } else if (bid == null) {
                val (err, list) = ForumStore.boards()
                main.post { error = err; boards = list; loading = false }
            } else {
                val (err, list) = ForumStore.posts(bid)
                main.post { error = err; posts = list; loading = false }
            }
        }.start()
    }

    // 登录态变化时拉取论坛身份
    LaunchedEffect(loggedIn, tick) {
        if (loggedIn) Thread { val r = ForumStore.myRole(context); main.post { role = r } }.start()
        else role = -1
    }

    AgentPage {
        when {
            postDetailId != null -> DetailLayer(
                post = detailPost, replies = replies, loading = loading, error = error,
                role = role, loggedIn = loggedIn, busy = busy,
                replyText = replyText, onReplyChange = { replyText = it },
                replyAttach = replyAttach,
                onPickAttach = { attachPicker.launch(arrayOf("*/*")) },
                onClearAttach = { replyAttach?.delete(); replyAttach = null },
                onBack = { postDetailId = null; detailPost = null; replies = emptyList() },
                onSendReply = {
                    val pid = postDetailId ?: return@DetailLayer
                    val c = replyText.trim()
                    if (c.isEmpty()) { toast(context, "请输入回复内容"); return@DetailLayer }
                    if (!loggedIn) { toast(context, "请先登录后再回复"); return@DetailLayer }
                    busy = true
                    val att = replyAttach
                    Thread {
                        val err = ForumStore.reply(context, pid, c, att)
                        main.post {
                            busy = false
                            if (err == null) {
                                replyText = ""; att?.delete(); replyAttach = null
                                toast(context, "回复成功"); tick++
                            } else toast(context, err)
                        }
                    }.start()
                },
                onDeletePost = {
                    val pid = postDetailId ?: return@DetailLayer
                    busy = true
                    Thread {
                        val err = ForumStore.action(context, "delete_post", "post_id", pid)
                        main.post {
                            busy = false
                            if (err == null) { toast(context, "已删除"); postDetailId = null; tick++ }
                            else toast(context, err)
                        }
                    }.start()
                },
                onPin = { v ->
                    val pid = postDetailId ?: return@DetailLayer
                    Thread {
                        val err = ForumStore.action(context, "pin", "post_id", pid, v)
                        main.post { if (err == null) { toast(context, "已更新"); tick++ } else toast(context, err) }
                    }.start()
                },
                onEssence = { v ->
                    val pid = postDetailId ?: return@DetailLayer
                    Thread {
                        val err = ForumStore.action(context, "essence", "post_id", pid, v)
                        main.post { if (err == null) { toast(context, "已更新"); tick++ } else toast(context, err) }
                    }.start()
                },
                onDeleteReply = { rid ->
                    busy = true
                    Thread {
                        val err = ForumStore.action(context, "delete_reply", "reply_id", rid)
                        main.post { busy = false; if (err == null) { toast(context, "已删除"); tick++ } else toast(context, err) }
                    }.start()
                },
            )

            boardId == null -> BoardLayer(
                boards = boards, loading = loading, error = error,
                onRefresh = { tick++ },
                onOpenBoard = { id, name -> boardId = id; boardTitle = name; postDetailId = null },
                onOpenAll = { boardId = 0; boardTitle = "全部帖子"; postDetailId = null },
            )

            else -> PostListLayer(
                title = boardTitle, posts = posts, loading = loading, error = error,
                onBack = { boardId = null },
                onRefresh = { tick++ },
                onCreate = {
                    if (!loggedIn) toast(context, "请先登录后再发帖") else showCreate = true
                },
                onOpenPost = { postDetailId = it },
            )
        }
    }

    if (showCreate) {
        CreateDialog(
            boards = boards,
            defaultBoard = boardId ?: 0,
            attach = createAttach,
            onPickAttach = { attachPicker.launch(arrayOf("*/*")) },
            onClearAttach = { createAttach?.delete(); createAttach = null },
            onDismiss = { showCreate = false; createAttach?.delete(); createAttach = null },
            onSubmit = { bId, title, content ->
                showCreate = false
                busy = true
                val att = createAttach
                Thread {
                    val err = ForumStore.createPost(context, bId, title, content, att)
                    main.post {
                        busy = false
                        att?.delete(); createAttach = null
                        if (err == null) { toast(context, "发布成功"); tick++ } else toast(context, err)
                    }
                }.start()
            },
        )
    }
}

// ---------------- 板块列表层 ----------------

@Composable
private fun ColumnScope.BoardLayer(
    boards: List<JSONObject>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenBoard: (Int, String) -> Unit,
    onOpenAll: () -> Unit,
) {
    PageHeader("社区论坛", "交流、提问、分享你的作品") {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(AColors.Surface)
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = AColors.InkSoft, modifier = Modifier.size(20.dp))
        }
    }

    // 全部帖子入口
    AgentCard(padding = 15.dp, onClick = onOpenAll) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(Icons.Outlined.Forum, AColors.Accent)
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text("全部帖子", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                Text("浏览社区里最新的全部讨论", fontSize = 12.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(ADimens.GapCard))
    SectionLabel("板块")

    when {
        loading && boards.isEmpty() ->
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AColors.Accent)
            }
        error != null && boards.isEmpty() ->
            EmptyState(Icons.Outlined.Forum, "加载失败", error)
        boards.isEmpty() ->
            EmptyState(Icons.Outlined.Forum, "还没有板块", "请让管理员在论坛后台创建板块")
        else -> LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ADimens.GapCard),
        ) {
            items(boards) { b ->
                val id = b.optInt("id")
                val name = b.optString("name")
                val desc = b.optString("description")
                val count = b.optInt("post_count")
                AgentCard(padding = 15.dp, onClick = { onOpenBoard(id, name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBox(Icons.Outlined.ChatBubbleOutline, AColors.Violet)
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                            if (desc.isNotBlank()) {
                                Text(desc, fontSize = 12.sp, color = AColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Tag("$count 帖", AColors.Muted, AColors.SurfaceAlt)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------- 帖子列表层 ----------------

@Composable
private fun ColumnScope.PostListLayer(
    title: String,
    posts: List<JSONObject>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpenPost: (Int) -> Unit,
) {
    // 头部：返回 + 标题 + 刷新
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AColors.Surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AColors.Ink, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${posts.size} 个帖子", fontSize = 12.sp, color = AColors.Muted,
                modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(AColors.Surface)
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = AColors.InkSoft, modifier = Modifier.size(20.dp))
        }
    }

    // 发帖按钮
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(ADimens.CardRadiusSmall))
            .background(AColors.Accent).clickable(onClick = onCreate),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            Text("发新帖", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 7.dp))
        }
    }
    Spacer(Modifier.height(14.dp))

    when {
        loading && posts.isEmpty() ->
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AColors.Accent)
            }
        error != null && posts.isEmpty() ->
            EmptyState(Icons.Outlined.ChatBubbleOutline, "加载失败", error)
        posts.isEmpty() ->
            EmptyState(Icons.Outlined.ChatBubbleOutline, "还没有帖子", "点上方按钮发布第一个帖子")
        else -> LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ADimens.GapCard),
        ) {
            items(posts, key = { it.optInt("id") }) { p ->
                PostCard(p) { onOpenPost(p.optInt("id")) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PostCard(p: JSONObject, onClick: () -> Unit) {
    AgentCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.optInt("is_pinned") == 1) {
                Tag("置顶", AColors.Danger, AColors.DangerSoft)
                Spacer(Modifier.width(6.dp))
            }
            if (p.optInt("is_essence") == 1) {
                Tag("精华", AColors.Warning, AColors.WarningSoft)
                Spacer(Modifier.width(6.dp))
            }
            Text(p.optString("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = AColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val content = p.optString("content")
        if (content.isNotBlank()) {
            Text(content, fontSize = 13.sp, color = AColors.InkSoft,
                modifier = Modifier.padding(top = 8.dp), lineHeight = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "${p.optString("author")} · ${p.optString("board_name")} · ${p.optString("created_at").take(16)}",
            fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 9.dp),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = AColors.Faint, modifier = Modifier.size(14.dp))
            Text(" ${p.optInt("reply_count")}", fontSize = 11.sp, color = AColors.Muted)
            Spacer(Modifier.width(12.dp))
            Text("浏览 ${p.optInt("view_count")}", fontSize = 11.sp, color = AColors.Muted)
            if (p.optString("attach_name").isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(14.dp))
                Text(" 附件", fontSize = 11.sp, color = AColors.Accent)
            }
        }
    }
}

// ---------------- 帖子详情层 ----------------

@Composable
private fun ColumnScope.DetailLayer(
    post: JSONObject?,
    replies: List<JSONObject>,
    loading: Boolean,
    error: String?,
    role: Int,
    loggedIn: Boolean,
    busy: Boolean,
    replyText: String,
    onReplyChange: (String) -> Unit,
    replyAttach: File?,
    onPickAttach: () -> Unit,
    onClearAttach: () -> Unit,
    onBack: () -> Unit,
    onSendReply: () -> Unit,
    onDeletePost: () -> Unit,
    onPin: (Int) -> Unit,
    onEssence: (Int) -> Unit,
    onDeleteReply: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AColors.Surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = AColors.Ink, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("帖子详情", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AColors.Ink)
            if (post != null) {
                Text(post.optString("board_name"), fontSize = 12.sp, color = AColors.Muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    when {
        loading && post == null ->
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AColors.Accent)
            }
        error != null && post == null ->
            EmptyState(Icons.Outlined.ChatBubbleOutline, "加载失败", error)
        post == null ->
            EmptyState(Icons.Outlined.ChatBubbleOutline, "帖子不存在", null)
        else -> Column(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ADimens.GapCard)) {
                // 标题 + 正文
                item {
                    AgentCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (post.optInt("is_pinned") == 1) {
                                Tag("置顶", AColors.Danger, AColors.DangerSoft); Spacer(Modifier.width(6.dp))
                            }
                            if (post.optInt("is_essence") == 1) {
                                Tag("精华", AColors.Warning, AColors.WarningSoft); Spacer(Modifier.width(6.dp))
                            }
                            Text(post.optString("title"), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = AColors.Ink, modifier = Modifier.weight(1f))
                        }
                        Text("${post.optString("author")} · ${post.optString("created_at").take(16)} · 浏览 ${post.optInt("view_count")}",
                            fontSize = 11.sp, color = AColors.Muted, modifier = Modifier.padding(top = 8.dp))
                        Divider(Modifier.padding(vertical = 10.dp))
                        Text(post.optString("content"), fontSize = 14.sp, color = AColors.InkSoft, lineHeight = 21.sp)
                        // 帖子附件
                        if (post.optString("attach_name").isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            AttachChip(
                                name = post.optString("attach_name"),
                                sizeText = post.optString("attach_size_text"),
                                onClick = { downloadAttach(context, post.optInt("id"), "post") },
                            )
                        }
                        // 版主操作
                        if (role >= 1) {
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallBtn(if (post.optInt("is_pinned") == 1) "取消置顶" else "置顶", AColors.Danger) {
                                    onPin(if (post.optInt("is_pinned") == 1) 0 else 1)
                                }
                                SmallBtn(if (post.optInt("is_essence") == 1) "取消加精" else "加精", AColors.Warning) {
                                    onEssence(if (post.optInt("is_essence") == 1) 0 else 1)
                                }
                                SmallBtn("删除", AColors.Muted) { onDeletePost() }
                            }
                        }
                    }
                }
                item { SectionLabel("全部回复（${replies.size}）") }
                if (replies.isEmpty()) {
                    item {
                        EmptyState(Icons.Outlined.ChatBubbleOutline, "还没有回复", "在下方输入框回复楼主")
                    }
                } else {
                    items(replies, key = { it.optInt("id") }) { r ->
                        AgentCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(AColors.AccentSoft),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(r.optString("author").take(1).ifBlank { "?" },
                                        fontSize = 13.sp, color = AColors.Accent, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(r.optString("author"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AColors.Ink)
                                    Text(r.optString("created_at").take(16), fontSize = 11.sp, color = AColors.Muted)
                                }
                                if (role >= 1) {
                                    Box(
                                        Modifier.size(30.dp).clip(CircleShape)
                                            .clickable { onDeleteReply(r.optInt("id")) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除",
                                            tint = AColors.Danger.copy(alpha = .8f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            Text(r.optString("content"), fontSize = 14.sp, color = AColors.InkSoft,
                                lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
                            if (r.optString("attach_name").isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                AttachChip(
                                    name = r.optString("attach_name"),
                                    sizeText = r.optString("attach_size_text"),
                                    onClick = { downloadAttach(context, r.optInt("id"), "reply") },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            ReplyBar(replyText, onReplyChange, replyAttach, onPickAttach, onClearAttach, loggedIn, busy, onSendReply)
        }
    }
}

@Composable
private fun ReplyBar(
    text: String,
    onChange: (String) -> Unit,
    attach: File?,
    onPickAttach: () -> Unit,
    onClearAttach: () -> Unit,
    loggedIn: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (attach != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .clip(RoundedCornerShape(ADimens.ChipRadius))
                    .background(AColors.AccentSoft).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(16.dp))
                Text(" ${attach.name} · ${"%.1f".format(attach.length() / 1048576.0)}MB",
                    fontSize = 12.sp, color = AColors.Accent, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClearAttach), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = "移除附件", tint = AColors.Muted, modifier = Modifier.size(15.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 附件按钮
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(AColors.SurfaceAlt).clickable(onClick = onPickAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "添加附件", tint = AColors.InkSoft, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(if (loggedIn) "写下你的回复…" else "登录后可回复", color = AColors.Faint, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(ADimens.CardRadiusSmall),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AColors.Accent,
                    unfocusedBorderColor = AColors.Line,
                    focusedContainerColor = AColors.Surface,
                    unfocusedContainerColor = AColors.Surface,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                    .background(if (busy) AColors.Accent.copy(alpha = .5f) else AColors.Accent)
                    .clickable(enabled = !busy, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 附件展示条（点击下载） */
@Composable
private fun AttachChip(name: String, sizeText: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.ChipRadius))
            .background(AColors.SurfaceAlt).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(name, fontSize = 13.sp, color = AColors.Ink, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sizeText.isNotBlank()) Text(sizeText, fontSize = 11.sp, color = AColors.Muted)
        }
        Icon(Icons.Outlined.Download, contentDescription = "下载", tint = AColors.Accent, modifier = Modifier.size(18.dp))
    }
}

/** 打开附件下载链接（走系统浏览器/下载） */
private fun downloadAttach(context: android.content.Context, id: Int, kind: String) {
    runCatching {
        val url = UserStore.BASE_URL + "/api/forum_download.php?id=$id&kind=$kind"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

// ---------------- 发帖对话框 ----------------

@Composable
private fun CreateDialog(
    boards: List<JSONObject>,
    defaultBoard: Int,
    attach: File?,
    onPickAttach: () -> Unit,
    onClearAttach: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (Int, String, String) -> Unit,
) {
    var selBoard by remember { mutableStateOf(defaultBoard) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布新帖", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("选择板块", fontSize = 12.sp, color = AColors.Muted)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    boards.take(3).forEach { b ->
                        val id = b.optInt("id")
                        val active = selBoard == id
                        Box(
                            Modifier.clip(RoundedCornerShape(ADimens.ChipRadius))
                                .background(if (active) AColors.Accent else AColors.SurfaceAlt)
                                .clickable { selBoard = id }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(b.optString("name"), fontSize = 12.sp,
                                color = if (active) Color.White else AColors.InkSoft,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AgentTextField(title, { title = it }, "标题（1-120 字）")
                Spacer(Modifier.height(10.dp))
                AgentTextField(content, { content = it }, "正文内容…", singleLine = false)
                Spacer(Modifier.height(12.dp))
                // 附件
                if (attach == null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(AColors.SurfaceAlt).clickable(onClick = onPickAttach)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AColors.Muted, modifier = Modifier.size(18.dp))
                        Text(" 添加附件（≤50MB，可选）", fontSize = 13.sp, color = AColors.Muted)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(ADimens.CardRadiusSmall))
                            .background(AColors.AccentSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = AColors.Accent, modifier = Modifier.size(17.dp))
                        Text(" ${attach.name} · ${"%.1f".format(attach.length() / 1048576.0)}MB",
                            fontSize = 12.sp, color = AColors.Accent, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClearAttach), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除附件", tint = AColors.Muted, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val t = title.trim(); val c = content.trim()
                if (selBoard <= 0) { return@TextButton }
                if (t.isEmpty() || c.isEmpty()) { return@TextButton }
                onSubmit(selBoard, t, c)
            }) { Text("发布") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SmallBtn(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(ADimens.ChipRadius)).background(color.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}