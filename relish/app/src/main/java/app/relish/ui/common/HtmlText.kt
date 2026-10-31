package app.relish.ui.common

import android.util.LruCache
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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
    val text = remember(html, fallback, linkColor) { HtmlCache.get(html, fallback, linkColor) }
    val currentOnClick by rememberUpdatedState(onLinkClick)
    // Links carry plain URL annotations (so parsed text can be cached and shared); taps arrive here.
    val handler = remember { object : UriHandler { override fun openUri(uri: String) = currentOnClick(uri) } }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines)
    }
}

/**
 * Parsed bodies, keyed by HTML + link colour. Parsing happens once per comment rather than every
 * time a row scrolls back into view, which keeps comment threads smooth.
 */
private object HtmlCache {
    private val cache = LruCache<String, AnnotatedString>(600)

    fun get(html: String?, fallback: String, linkColor: Color): AnnotatedString {
        if (html.isNullOrBlank()) return AnnotatedString(fallback)
        val key = "${linkColor.value}|$html"
        cache.get(key)?.let { return it }
        val parsed = runCatching {
            AnnotatedString.fromHtml(
                htmlString = prepare(html),
                linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.None)),
            ).trimEnd()
        }.getOrElse { AnnotatedString(fallback) }
        cache.put(key, parsed)
        return parsed
    }
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
