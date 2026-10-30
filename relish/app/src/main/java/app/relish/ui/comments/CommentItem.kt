package app.relish.ui.comments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.relish.data.model.Comment
import app.relish.data.model.MoreComments
import app.relish.data.model.VoteDir
import app.relish.ui.common.HtmlText
import app.relish.ui.feed.ActionIcon
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish
import app.relish.ui.theme.RelishColors
import app.relish.util.compactCount
import app.relish.util.timeAgo

data class CommentActions(
    val onUpvote: () -> Unit,
    val onDownvote: () -> Unit,
    val onReply: () -> Unit,
    val onProfile: () -> Unit,
    val onContext: () -> Unit,
    val onCopy: () -> Unit,
)

/** Draws one thin coloured bar per nesting level, then indents the content past them. */
fun Modifier.depthBars(depth: Int, indent: Dp, colors: RelishColors): Modifier =
    if (depth <= 0) this
    else this
        .drawBehind {
            val step = indent.toPx()
            val w = 2.dp.toPx()
            for (level in 0 until depth) {
                drawRect(
                    color = colors.depthColor(level),
                    topLeft = Offset(step * level + step / 2f, 0f),
                    size = Size(w, size.height),
                )
            }
        }
        .padding(start = indent * depth + 4.dp)

/**
 * A comment in the tree. Tap reveals the inline action bar (Upvote, Downvote, Reply, Profile,
 * Context, Copy); tap the header or long-press anywhere to collapse/expand the subtree.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommentItem(
    comment: Comment,
    collapsed: Boolean,
    hiddenChildren: Int,
    selected: Boolean,
    isOp: Boolean,
    onClick: () -> Unit,
    onToggleCollapse: () -> Unit,
    onLinkClick: (String) -> Unit,
    actions: CommentActions,
) {
    val colors = Relish.colors
    val sp = Relish.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.selectedRow else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onToggleCollapse)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .depthBars(comment.depth, sp.depthIndent, colors)
                .padding(end = sp.rowHorizontal, top = sp.commentVertical, bottom = sp.commentVertical)
                .padding(start = if (comment.depth == 0) sp.rowHorizontal else 0.dp)
        ) {
            CommentHeader(comment, collapsed, hiddenChildren, isOp, Modifier.clickable(onClick = onToggleCollapse))
            if (!collapsed) {
                Spacer(Modifier.height(2.dp))
                HtmlText(
                    html = comment.bodyHtml,
                    fallback = comment.body,
                    onLinkClick = onLinkClick,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        AnimatedVisibility(visible = selected && !collapsed, enter = expandVertically(), exit = shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth().depthBars(comment.depth, sp.depthIndent, colors).height(sp.actionHeight).background(colors.actionBar),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionIcon(Icons.Default.ArrowUpward, "Upvote", if (comment.vote == VoteDir.UP) colors.upvote else null, actions.onUpvote)
                ActionIcon(Icons.Default.ArrowDownward, "Downvote", if (comment.vote == VoteDir.DOWN) colors.downvote else null, actions.onDownvote)
                ActionIcon(Icons.AutoMirrored.Filled.Reply, "Reply", null, actions.onReply)
                ActionIcon(Icons.Default.Person, "Profile", null, actions.onProfile)
                ActionIcon(Icons.Default.Forum, "Context", null, actions.onContext)
                ActionIcon(Icons.Default.ContentCopy, "Copy", null, actions.onCopy)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CommentHeader(comment: Comment, collapsed: Boolean, hiddenChildren: Int, isOp: Boolean, modifier: Modifier) {
    val colors = Relish.colors
    val authorColor = when {
        comment.distinguished == "moderator" -> colors.mod
        comment.distinguished == "admin" -> colors.nsfw
        isOp || comment.isSubmitter -> colors.op
        else -> MaterialTheme.colorScheme.primary
    }
    val scoreColor = when (comment.vote) {
        VoteDir.UP -> colors.upvote
        VoteDir.DOWN -> colors.downvote
        VoteDir.NONE -> colors.subtle
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.Bold)) { append(comment.author) }
        if (comment.isSubmitter) withStyle(SpanStyle(color = colors.op, fontWeight = FontWeight.Bold)) { append(" [OP]") }
        comment.flair?.let { withStyle(SpanStyle(color = colors.subtle)) { append(" [$it]") } }
        append("  ")
        withStyle(SpanStyle(color = scoreColor, fontWeight = FontWeight.Bold)) {
            append(if (comment.scoreHidden) "[score hidden]" else "${compactCount(comment.score)} pts")
        }
        withStyle(SpanStyle(color = colors.subtle)) {
            append(" · ${timeAgo(comment.createdUtc)}")
            if (comment.edited) append("*")
            if (comment.stickied) append(" · stickied")
        }
        if (collapsed) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("  [+${hiddenChildren + 1}]") }
    }
    Text(text, style = MetaTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun MoreCommentsItem(more: MoreComments, loading: Boolean, onClick: () -> Unit) {
    val colors = Relish.colors
    val sp = Relish.spacing
    Column(Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth()
                .depthBars(more.depth, sp.depthIndent, colors)
                .padding(start = if (more.depth == 0) sp.rowHorizontal else 0.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (more.isContinueThread) "Continue this thread →" else "Load ${more.count} more ${if (more.count == 1) "reply" else "replies"}",
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A comment shown in a user/saved listing, with the post it belongs to. */
@Composable
fun UserCommentRow(comment: Comment, onClick: () -> Unit, onLinkClick: (String) -> Unit) {
    val colors = Relish.colors
    val sp = Relish.spacing
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = sp.rowHorizontal, vertical = sp.rowVertical)) {
            comment.linkTitle?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${comment.author} in r/${comment.subreddit} · ${compactCount(comment.score)} pts · ${timeAgo(comment.createdUtc)}",
                style = MetaTextStyle,
                color = colors.subtle,
            )
            Spacer(Modifier.height(2.dp))
            HtmlText(comment.bodyHtml, comment.body, onLinkClick, maxLines = 6)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
