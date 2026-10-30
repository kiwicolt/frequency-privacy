package app.relish.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

/**
 * Renders Reddit's `body_html`/`selftext_html` (already unescaped thanks to raw_json=1) with
 * clickable links routed through [onLinkClick] so reddit links open natively.
 */
@Composable
fun HtmlText(
    html: String?,
    fallback: String,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val currentOnClick by rememberUpdatedState(onLinkClick)
    val text = remember(html, fallback, linkColor) {
        if (html.isNullOrBlank()) AnnotatedString(fallback)
        else runCatching {
            AnnotatedString.fromHtml(
                htmlString = prepare(html),
                linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None)),
                linkInteractionListener = { link -> (link as? LinkAnnotation.Url)?.url?.let { currentOnClick(it) } },
            ).trimEnd()
        }.getOrElse { AnnotatedString(fallback) }
    }
    Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines)
}

/** Strips Reddit's wrapper and turns spoiler spans into a visible marker. */
private fun prepare(html: String): String = html
    .replace("<!-- SC_OFF -->", "")
    .replace("<!-- SC_ON -->", "")
    .replace(Regex("""<span class="md-spoiler-text">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL), "<i>[spoiler: $1]</i>")
    .trim()

private fun AnnotatedString.trimEnd(): AnnotatedString {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return if (end == length) this else subSequence(0, end)
}
