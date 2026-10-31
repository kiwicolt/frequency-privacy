package app.relish.ui.feed

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.relish.data.model.Post
import app.relish.data.model.PostMedia
import app.relish.data.model.VoteDir
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.RifTitleStyle
import app.relish.ui.theme.Relish
import app.relish.util.compactCount
import app.relish.util.timeAgo
import coil.compose.AsyncImage

/** Callbacks for RIF's inline context action bar. */
data class PostActions(
    val onUpvote: () -> Unit,
    val onDownvote: () -> Unit,
    val onSubreddit: () -> Unit,
    val onComments: () -> Unit,
    val onShare: () -> Unit,
    val onHide: () -> Unit,
    val onSave: () -> Unit,
    val onOpenLink: () -> Unit,
    val onUser: () -> Unit = {},
)

/** Display options derived from settings + connectivity, computed once per list. */
data class PostDisplay(
    /** Thumbnails mode "Never": no thumbnail column at all. */
    val hideThumbnails: Boolean,
    /** False on metered networks in "Wi-Fi only" mode: show type icons instead of images. */
    val loadThumbnails: Boolean,
    val blurNsfw: Boolean,
    val blurSpoilers: Boolean,
    val largeCards: Boolean,
    val inlinePreviews: Boolean,
    val showSubreddit: Boolean,
    val leftHanded: Boolean = false,
    val showFlair: Boolean = true,
)

/**
 * A feed row. Tapping the row toggles the action bar; tapping the thumbnail (or card image)
 * opens the content directly.
 */
@Composable
fun PostItem(
    post: Post,
    isRead: Boolean,
    selected: Boolean,
    display: PostDisplay,
    onClick: () -> Unit,
    onContentClick: () -> Unit,
    actions: PostActions,
    modifier: Modifier = Modifier,
) {
    val colors = Relish.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(
                when {
                    !selected -> Color.Transparent
                    display.largeCards -> colors.selectedRow
                    else -> colors.appBar.copy(alpha = 0.6f)
                }
            )
            .clickable(onClick = onClick)
    ) {
        if (display.largeCards) LargeCard(post, isRead, display, onContentClick)
        else CompactRow(post, isRead, display, onContentClick, actions, selected)

        AnimatedVisibility(visible = selected, enter = expandVertically(), exit = shrinkVertically()) {
            if (display.largeCards) PostActionBar(post, actions) else RifActionBar(post, actions)
        }
        // RIF's list has no row dividers; cards keep them.
        if (display.largeCards) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CompactRow(
    post: Post,
    isRead: Boolean,
    display: PostDisplay,
    onContentClick: () -> Unit,
    actions: PostActions,
    selected: Boolean,
) {
    val sp = Relish.spacing
    Row(
        Modifier.fillMaxWidth().padding(
            end = if (!display.hideThumbnails && !display.leftHanded) 0.dp else sp.rowHorizontal,
            top = sp.rowVertical,
            bottom = sp.rowVertical,
        ),
        verticalAlignment = Alignment.Top,
    ) {
        val thumb: @Composable () -> Unit = {
            if (!display.hideThumbnails) PostThumbnail(post, display, Modifier.size(sp.thumbnail + 8.dp), onContentClick)
        }
        if (display.leftHanded) { thumb(); if (!display.hideThumbnails) Spacer(Modifier.width(8.dp)) } else VoteColumn(post, actions)
        Column(Modifier.weight(1f)) {
            PostTitle(post, isRead, rifStyle = true, showFlair = display.showFlair)
            Spacer(Modifier.height(2.dp))
            Text(
                "${post.numComments} comments  ${post.subreddit}",
                style = MetaTextStyle,
                color = Relish.colors.subtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Text("${timeAgo(post.createdUtc)} by ${post.author}", style = MetaTextStyle, color = Relish.colors.subtle, maxLines = 1)
            }
        }
        if (display.leftHanded) VoteColumn(post, actions) else { if (!display.hideThumbnails) Spacer(Modifier.width(8.dp)); thumb() }
    }
}

/** RIF's left column: up arrow, score, down arrow; tappable without opening the action bar. */
@Composable
fun VoteColumn(post: Post, actions: PostActions) {
    val colors = Relish.colors
    // Up arrow beside the title's first line, score beside the second: never taller than the text.
    Column(
        Modifier.width(44.dp).padding(top = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        VoteArrow(up = true, color = if (post.vote == VoteDir.UP) colors.upvote else colors.voteArrow, onClick = actions.onUpvote)
        Text(
            compactCount(post.score),
            style = MetaTextStyle.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = when (post.vote) {
                VoteDir.UP -> colors.upvote
                VoteDir.DOWN -> colors.downvote
                VoteDir.NONE -> colors.subtle
            },
            maxLines = 1,
        )
        VoteArrow(up = false, color = if (post.vote == VoteDir.DOWN) colors.downvote else colors.voteArrow, onClick = actions.onDownvote)
    }
}

/** RIF's flat triangle arrow, drawn compactly instead of a padded 24dp icon. */
@Composable
private fun VoteArrow(up: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 18.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (up) "Upvote" else "Downvote" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
            val path = Path().apply {
                if (up) {
                    moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height)
                } else {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
                }
                close()
            }
            drawPath(path, color)
        }
    }
}

@Composable
private fun LargeCard(post: Post, isRead: Boolean, display: PostDisplay, onContentClick: () -> Unit) {
    val sp = Relish.spacing
    Column(Modifier.fillMaxWidth().padding(horizontal = sp.rowHorizontal, vertical = sp.rowVertical + 2.dp)) {
        PostTitle(post, isRead)
        Spacer(Modifier.height(4.dp))
        val preview = post.previewUrl
        if (display.inlinePreviews && display.loadThumbnails && preview != null && !post.isSelf) {
            val obscure = (post.over18 && display.blurNsfw) || (post.spoiler && display.blurSpoilers)
            Box(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onContentClick),
                contentAlignment = Alignment.Center,
            ) {
                if (obscure && Build.VERSION.SDK_INT < 31) {
                    ObscuredPlaceholder(post)
                } else {
                    AsyncImage(
                        model = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(if (obscure) Modifier.blur(28.dp) else Modifier),
                    )
                    if (obscure) ObscuredPlaceholder(post, overlay = true)
                }
                MediaTypeBadge(post.media, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            Spacer(Modifier.height(4.dp))
        } else if (post.isSelf && post.selftext.isNotBlank()) {
            Text(
                post.selftext,
                style = MaterialTheme.typography.bodySmall,
                color = Relish.colors.subtle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        } else if (!post.isSelf) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onContentClick).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Link, null, Modifier.size(16.dp), tint = Relish.colors.subtle)
                Spacer(Modifier.width(6.dp))
                Text(post.url, style = MetaTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Relish.colors.subtle)
            }
            Spacer(Modifier.height(4.dp))
        }
        PostMeta(post, display.showSubreddit)
    }
}

/** Bold title followed inline by the red NSFW pill, spoiler pill, flair and "(domain)". */
@Composable
fun PostTitle(post: Post, isRead: Boolean, maxLines: Int = Int.MAX_VALUE, rifStyle: Boolean = false, showFlair: Boolean = true) {
    val colors = Relish.colors
    val titleColor = when {
        post.stickied -> colors.stickied
        rifStyle && isRead -> colors.titleRead
        rifStyle -> colors.title
        isRead -> colors.readTitle
        else -> MaterialTheme.colorScheme.onSurface
    }
    val text = buildAnnotatedString {
        if (post.stickied) { appendInlineContent(INLINE_PIN, "[pinned]"); append(" ") }
        if (rifStyle && showFlair) post.flair?.let {
            withStyle(SpanStyle(background = colors.flairBackground, color = colors.subtle, fontSize = 12.sp, fontWeight = FontWeight.Normal)) {
                append("\u00A0$it\u00A0")
            }
            append(" ")
        }
        withStyle(
            SpanStyle(
                fontWeight = if (rifStyle) FontWeight.Normal else if (isRead) FontWeight.Medium else FontWeight.Bold,
                color = titleColor,
            )
        ) { append(post.title) }
        if (post.over18) { append(" "); appendInlineContent(INLINE_NSFW, "[NSFW]") }
        if (post.spoiler) { append(" "); appendInlineContent(INLINE_SPOILER, "[spoiler]") }
        if (!rifStyle && showFlair) post.flair?.let {
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)) { append("[$it]") }
        }
        withStyle(SpanStyle(color = colors.subtle, fontSize = if (rifStyle) 13.sp else 11.sp, fontWeight = FontWeight.Normal)) { append(" (${post.domain})") }
    }
    Text(
        text,
        style = if (rifStyle) RifTitleStyle else MaterialTheme.typography.titleSmall,
        inlineContent = inlineBadges(),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val INLINE_NSFW = "nsfw"
private const val INLINE_SPOILER = "spoiler"
private const val INLINE_PIN = "pin"

@Composable
private fun inlineBadges(): Map<String, InlineTextContent> {
    val colors = Relish.colors
    return mapOf(
        INLINE_NSFW to InlineTextContent(Placeholder(3.2.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            Badge("NSFW", colors.nsfw)
        },
        INLINE_SPOILER to InlineTextContent(Placeholder(4.6.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            Badge("SPOILER", colors.spoiler)
        },
        INLINE_PIN to InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Default.PushPin, null, tint = colors.stickied)
        },
    )
}

@Composable
fun Badge(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp, maxLines = 1)
    }
}

/** "1.2k pts · 34 comments · u/someone · 4h ago · r/sub" */
@Composable
fun PostMeta(post: Post, showSubreddit: Boolean) {
    val colors = Relish.colors
    val scoreColor = when (post.vote) {
        VoteDir.UP -> colors.upvote
        VoteDir.DOWN -> colors.downvote
        VoteDir.NONE -> colors.subtle
    }
    val text: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = scoreColor, fontWeight = FontWeight.Bold)) { append("${compactCount(post.score)} pts") }
        withStyle(SpanStyle(color = colors.subtle)) {
            append(" · ${compactCount(post.numComments)} comments · ")
            append("u/${post.author} · ${timeAgo(post.createdUtc)}")
            if (showSubreddit) append(" · r/${post.subreddit}")
        }
        if (post.saved) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(" · saved") }
        if (post.locked) withStyle(SpanStyle(color = colors.mod)) { append(" · locked") }
    }
    Text(text, style = MetaTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Square thumbnail with explicit NSFW handling: blur on API 31+, red NSFW placeholder pill otherwise. */
@Composable
fun PostThumbnail(post: Post, display: PostDisplay, modifier: Modifier, onClick: () -> Unit) {
    val colors = Relish.colors
    val obscure = (post.over18 && display.blurNsfw) || (post.spoiler && display.blurSpoilers)
    val url = post.thumbnail
    Box(
        modifier.clipToBounds().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            post.isSelf && url == null -> Icon(Icons.Default.PriorityHigh, "Text post", tint = colors.title.copy(alpha = 0.45f), modifier = Modifier.size(36.dp))
            !display.loadThumbnails -> Icon(mediaIcon(post.media), null, tint = colors.subtle)
            url == null -> when {
                post.over18 -> ObscuredPlaceholder(post)
                post.media is PostMedia.Link -> Icon(Icons.Default.QuestionMark, "Link", tint = colors.title.copy(alpha = 0.45f), modifier = Modifier.size(36.dp))
                else -> Icon(mediaIcon(post.media), null, tint = colors.subtle)
            }
            obscure && Build.VERSION.SDK_INT < 31 -> ObscuredPlaceholder(post)
            else -> {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (obscure) Modifier.blur(10.dp) else Modifier),
                )
                if (obscure) ObscuredPlaceholder(post, overlay = true)
            }
        }
        MediaTypeBadge(post.media, Modifier.align(Alignment.BottomEnd).padding(2.dp), small = true)
    }
}

@Composable
private fun ObscuredPlaceholder(post: Post, overlay: Boolean = false) {
    val colors = Relish.colors
    Box(
        Modifier.fillMaxSize().background(if (overlay) Color(0x55000000) else colors.nsfw.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Badge(if (post.over18) "NSFW" else "SPOILER", if (post.over18) colors.nsfw else colors.spoiler)
    }
}

@Composable
private fun MediaTypeBadge(media: PostMedia, modifier: Modifier, small: Boolean = false) {
    val icon = when (media) {
        is PostMedia.Video, is PostMedia.Redgifs -> Icons.Default.PlayArrow
        is PostMedia.Gallery -> Icons.Default.Collections
        is PostMedia.Audio -> Icons.Default.Audiotrack
        else -> return
    }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(if (small) 12.dp else 16.dp), tint = Color.White)
            if (media is PostMedia.Gallery) {
                Text("${media.items.size}", color = Color.White, fontSize = if (small) 9.sp else 11.sp, modifier = Modifier.padding(end = 2.dp))
            }
        }
    }
}

private fun mediaIcon(media: PostMedia): ImageVector = when (media) {
    is PostMedia.Image -> Icons.Default.Image
    is PostMedia.Video, is PostMedia.Redgifs -> Icons.Default.PlayArrow
    is PostMedia.Gallery -> Icons.Default.Collections
    is PostMedia.Audio -> Icons.Default.Audiotrack
    is PostMedia.Link -> Icons.Default.Link
    PostMedia.None -> Icons.AutoMirrored.Filled.Article
}

/** RIF's text action bar under a selected row: share · save · hide · … · comments. */
@Composable
private fun RifActionBar(post: Post, actions: PostActions) {
    val colors = Relish.colors
    var more by remember { mutableStateOf(false) }
    @Composable
    fun Action(label: String, onClick: () -> Unit) {
        TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = colors.onAppBar)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(Relish.spacing.actionHeight + 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Action("share", actions.onShare)
        Action(if (post.saved) "unsave" else "save", actions.onSave)
        Action("hide", actions.onHide)
        Box {
            Action("…") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("Open link") }, onClick = { more = false; actions.onOpenLink() })
                DropdownMenuItem(text = { Text("Go to r/${post.subreddit}") }, onClick = { more = false; actions.onSubreddit() })
                DropdownMenuItem(text = { Text("View u/${post.author}") }, onClick = { more = false; actions.onUser() })
            }
        }
        Action("comments", actions.onComments)
    }
}

/** RIF's signature inline bar: Upvote, Downvote, Subreddit, Comments, Share, Hide, Save, Open link. */
@Composable
fun PostActionBar(post: Post, actions: PostActions) {
    val colors = Relish.colors
    val sp = Relish.spacing
    Row(
        Modifier.fillMaxWidth().height(sp.actionHeight).background(colors.actionBar),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Default.ArrowUpward, "Upvote", if (post.vote == VoteDir.UP) colors.upvote else null, actions.onUpvote)
        ActionIcon(Icons.Default.ArrowDownward, "Downvote", if (post.vote == VoteDir.DOWN) colors.downvote else null, actions.onDownvote)
        ActionIcon(Icons.Default.Tag, "r/${post.subreddit}", null, actions.onSubreddit)
        ActionIcon(Icons.AutoMirrored.Filled.Comment, "Comments", null, actions.onComments)
        ActionIcon(Icons.Default.Share, "Share", null, actions.onShare)
        ActionIcon(Icons.Default.VisibilityOff, "Hide", null, actions.onHide)
        ActionIcon(
            if (post.saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, if (post.saved) "Unsave" else "Save",
            if (post.saved) MaterialTheme.colorScheme.primary else null, actions.onSave,
        )
        ActionIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open link", null, actions.onOpenLink)
    }
}

@Composable
fun ActionIcon(icon: ImageVector, description: String, tint: Color?, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Relish.spacing.actionHeight)) {
        Icon(icon, contentDescription = description, tint = tint ?: Relish.colors.subtle, modifier = Modifier.size(20.dp))
    }
}
