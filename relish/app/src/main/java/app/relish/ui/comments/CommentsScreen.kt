package app.relish.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.model.Comment
import app.relish.data.model.CommentSort
import app.relish.data.model.MoreComments
import app.relish.data.model.Post
import app.relish.data.model.VoteDir
import app.relish.data.prefs.ThumbnailMode
import app.relish.ui.common.HtmlText
import app.relish.ui.feed.ActionIcon
import app.relish.ui.feed.PostActions
import app.relish.ui.feed.VoteColumn
import app.relish.util.timeAgo
import app.relish.ui.feed.PostDisplay
import app.relish.ui.feed.PostMeta
import app.relish.ui.feed.PostThumbnail
import app.relish.ui.feed.PostTitle
import app.relish.ui.nav.LocalMessenger
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.nav.permalinkUrl
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(vm: CommentsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val messenger = LocalMessenger.current
    val clipboard = LocalClipboardManager.current
    val s = ui.state
    val post = s.post
    var replyTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // fullname to label
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.events.collect { messenger(it) } }

    Scaffold(
        topBar = {
            val colors = Relish.colors
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.appBar,
                    titleContentColor = colors.onAppBar,
                    navigationIconContentColor = colors.onAppBar,
                    actionIconContentColor = colors.onAppBar,
                ),
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(post?.title ?: "Comments", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        post?.let { Text("/r/${it.subreddit}", style = MaterialTheme.typography.labelMedium, color = colors.subtle) }
                    }
                },
                actions = {
                    post?.let { p ->
                        IconButton(onClick = { replyTarget = p.fullname to "Reply to post" }) {
                            Icon(Icons.AutoMirrored.Filled.Comment, "Reply to post")
                        }
                    }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(overflow, onDismissRequest = { overflow = false }) {
                            post?.let { p ->
                                DropdownMenuItem(text = { Text(if (p.saved) "Unsave" else "Save") }, onClick = { overflow = false; vm.toggleSavePost() })
                                DropdownMenuItem(text = { Text("Share link") }, onClick = { overflow = false; nav.share(p.title, if (p.isSelf) permalinkUrl(p.permalink) else p.url) })
                                DropdownMenuItem(text = { Text("Open link") }, onClick = { overflow = false; nav.openPostContent(p) })
                                DropdownMenuItem(text = { Text("Go to r/${p.subreddit}") }, onClick = { overflow = false; nav.openSubreddit(p.subreddit) })
                                DropdownMenuItem(text = { Text("View u/${p.author}") }, onClick = { overflow = false; nav.openUser(p.author) })
                                DropdownMenuItem(text = { Text("Share comments link") }, onClick = { overflow = false; nav.share(p.title, permalinkUrl(p.permalink)) })
                                DropdownMenuItem(text = { Text("Open in browser") }, onClick = { overflow = false; nav.openInBrowser(permalinkUrl(p.permalink)) })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                post == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.error ?: "Couldn't load post", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::refresh) { Text("Retry") }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "post") { PostHeader(post = post, onVote = vm::votePost) }
                    item(key = "band") {
                        Row(
                            Modifier.fillMaxWidth().background(Relish.colors.flairBackground).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${post.numComments} comments", style = MaterialTheme.typography.titleSmall)
                                Text("sorted by ${s.sort.label.lowercase()}", style = MetaTextStyle, color = Relish.colors.subtle)
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Settings, "Sort comments", tint = Relish.colors.subtle) }
                                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                    CommentSort.entries.forEach { c ->
                                        DropdownMenuItem(text = { Text(c.label) }, onClick = { sortMenu = false; vm.setSort(c) })
                                    }
                                }
                            }
                        }
                    }
                    if (s.focusCommentId != null) {
                        item(key = "focus") {
                            Text(
                                "You're viewing a single comment thread. View all comments →",
                                style = MetaTextStyle,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { nav.openComments(post.id) }
                                    .background(Relish.colors.actionBar).padding(10.dp),
                            )
                        }
                    }
                    if (ui.rows.isEmpty()) {
                        item { Text("No comments yet", color = Relish.colors.subtle, modifier = Modifier.padding(16.dp)) }
                    }
                    items(ui.rows, key = { it.key }) { row ->
                        when (val node = row.node) {
                            is Comment -> if (row.repliesStub) {
                                RepliesStubItem(row.hiddenChildren) { vm.expandReplies(node.fullname) }
                            } else CommentItem(
                                comment = node,
                                collapsed = row.collapsed,
                                hiddenChildren = row.hiddenChildren,
                                selected = s.selected == node.fullname,
                                isOp = node.author == post.author && node.author != "[deleted]",
                                showFlair = appSettings.showCommentFlair,
                                guideLines = appSettings.commentGuideLines,
                                onClick = { vm.select(node.fullname) },
                                onToggleCollapse = { vm.toggleCollapse(node.fullname) },
                                onLinkClick = nav::openLink,
                                actions = CommentActions(
                                    onUpvote = { vm.voteComment(node, VoteDir.UP) },
                                    onDownvote = { vm.voteComment(node, VoteDir.DOWN) },
                                    onReply = { replyTarget = node.fullname to "Reply to ${node.author}" },
                                    onProfile = { nav.openUser(node.author) },
                                    onContext = { nav.openComments(post.id, node.id) },
                                    onCopy = { clipboard.setText(AnnotatedString(node.body)); messenger("Comment copied") },
                                ),
                            )
                            is MoreComments -> MoreCommentsItem(node, loading = node.fullname in s.loadingMore) {
                                if (node.isContinueThread) nav.openComments(post.id, node.parentId.removePrefix("t1_"))
                                else vm.loadMore(node)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }

    replyTarget?.let { (fullname, label) ->
        ReplyDialog(
            title = label,
            onDismiss = { replyTarget = null },
            onSend = { text, done -> vm.reply(fullname, text) { ok -> done(); if (ok) replyTarget = null } },
        )
    }
}

@Composable
private fun PostHeader(post: Post, onVote: (VoteDir) -> Unit) {
    val nav = LocalNavigator.current
    val sp = Relish.spacing
    val display = PostDisplay(
        hideThumbnails = false, loadThumbnails = true, blurNsfw = false, blurSpoilers = false,
        largeCards = false, inlinePreviews = true, showSubreddit = true,
    )
    val votes = PostActions(
        onUpvote = { onVote(VoteDir.UP) }, onDownvote = { onVote(VoteDir.DOWN) },
        onSubreddit = {}, onComments = {}, onShare = {}, onHide = {}, onSave = {}, onOpenLink = {},
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(end = sp.rowHorizontal, top = 8.dp, bottom = 10.dp)) {
            VoteColumn(post, votes)
            Column(Modifier.weight(1f).padding(top = 2.dp).clickable { nav.openPostContent(post) }) {
                PostTitle(post, isRead = false, rifStyle = true)
                Spacer(Modifier.height(4.dp))
                Text("posted to ${post.subreddit}", style = MetaTextStyle, color = Relish.colors.subtle)
                Text("${timeAgo(post.createdUtc)} by ${post.author}", style = MetaTextStyle, color = Relish.colors.subtle)
            }
            if (!post.isSelf) {
                Spacer(Modifier.width(8.dp))
                PostThumbnail(post, display, Modifier.size(sp.thumbnail + 8.dp)) { nav.openPostContent(post) }
            }
        }
        if (post.selftext.isNotBlank()) {
            HtmlText(
                html = post.selftextHtml,
                fallback = post.selftext,
                onLinkClick = nav::openLink,
                modifier = Modifier.fillMaxWidth().padding(start = sp.rowHorizontal + 4.dp, end = sp.rowHorizontal + 4.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ReplyDialog(title: String, onDismiss: () -> Unit, onSend: (String, () -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Markdown supported") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                enabled = !sending,
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !sending,
                onClick = { sending = true; onSend(text) { sending = false } },
            ) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}
