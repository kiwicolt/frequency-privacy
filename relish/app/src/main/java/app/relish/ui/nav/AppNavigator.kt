package app.relish.ui.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import app.relish.data.model.FeedSource
import app.relish.data.model.Post
import app.relish.data.model.PostMedia
import app.relish.data.prefs.AppSettings
import app.relish.util.MediaResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object Routes {
    const val FEED = "feed?src={src}"
    const val COMMENTS = "comments/{postId}?focus={focus}"
    const val MEDIA = "media?data={data}&title={title}"
    const val WEB = "web?url={url}"
    const val LOGIN = "login"
    const val SETTINGS = "settings"
    const val FILTERS = "settings/filters"
    const val INBOX = "inbox"
    const val SEARCH = "search?sub={sub}"

    fun feed(source: FeedSource) = "feed?src=${Uri.encode(source.encode())}"
    fun comments(postId: String, focus: String? = null) = "comments/$postId" + (focus?.let { "?focus=$it" } ?: "")
    fun media(data: String, title: String) = "media?data=${Uri.encode(data)}&title=${Uri.encode(title)}"
    fun web(url: String) = "web?url=${Uri.encode(url)}"
    fun search(sub: String?) = "search" + (sub?.let { "?sub=${Uri.encode(it)}" } ?: "")
}

val mediaJson = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

/**
 * Centralised navigation + link routing, so list items only need to say *what* was tapped.
 * Reddit links open natively, media opens in the viewer, everything else in the internal
 * WebView or a Custom Tab depending on settings.
 */
class AppNavigator(
    val nav: NavHostController,
    private val context: Context,
    private val settings: () -> AppSettings,
) {
    fun back() { nav.popBackStack() }

    fun openFeed(source: FeedSource) = nav.navigate(Routes.feed(source)) { launchSingleTop = true }
    fun openSubreddit(name: String) = openFeed(FeedSource.Subreddit(name))
    fun openUser(name: String) = openFeed(FeedSource.User(name))
    fun openComments(postId: String, focus: String? = null) = nav.navigate(Routes.comments(postId.removePrefix("t3_"), focus))
    fun openSettings() = nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
    fun openFilters() = nav.navigate(Routes.FILTERS)
    fun openInbox() = nav.navigate(Routes.INBOX) { launchSingleTop = true }
    fun openSearch(sub: String?) = nav.navigate(Routes.search(sub))
    fun openLogin() = nav.navigate(Routes.LOGIN)

    fun openMedia(media: PostMedia, title: String) =
        nav.navigate(Routes.media(mediaJson.encodeToString(media), title))

    /** Thumbnail tap / "Open link": the post's content rather than its comments. */
    fun openPostContent(post: Post) {
        when {
            post.isSelf -> openComments(post.id)
            post.media.isViewable && settings().internalMediaViewer -> openMedia(post.media, post.title)
            else -> openLink(post.url)
        }
    }

    fun openLink(rawUrl: String) {
        val url = when {
            rawUrl.startsWith("/r/") || rawUrl.startsWith("/u/") || rawUrl.startsWith("/user/") -> "https://www.reddit.com$rawUrl"
            rawUrl.startsWith("r/") || rawUrl.startsWith("u/") -> "https://www.reddit.com/$rawUrl"
            else -> rawUrl
        }
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("old.")?.removePrefix("new.")?.removePrefix("np.").orEmpty()
        val segs = uri.pathSegments

        if (host == "reddit.com" || host == "m.reddit.com") {
            when {
                // /r/{sub}/comments/{id}/{slug}/{commentId}
                segs.size >= 4 && segs[0] == "r" && segs[2] == "comments" -> return openComments(segs[3], segs.getOrNull(5))
                segs.size >= 2 && segs[0] == "comments" -> return openComments(segs[1], segs.getOrNull(3))
                segs.size >= 2 && segs[0] == "r" && segs.getOrNull(2) != "s" -> return openSubreddit(segs[1])
                segs.size >= 2 && (segs[0] == "u" || segs[0] == "user") -> return openUser(segs[1])
            }
        }
        if (host == "redd.it" && segs.size == 1) return openComments(segs[0])
        // YouTube: hand to the YouTube app (Android picks it for youtube.com/youtu.be), like RIF did.
        if (host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be" || host == "music.youtube.com") {
            return openInBrowser(url)
        }
        if (host == "v.redd.it" && segs.isNotEmpty() && settings().internalMediaViewer) {
            return openMedia(PostMedia.Video("https://v.redd.it/${segs[0]}/HLSPlaylist.m3u8"), "")
        }

        // Direct media links (i.redd.it, imgur, redgifs, .mp4 …) go to the viewer.
        if (settings().internalMediaViewer) {
            val media = MediaResolver.resolve(buildJsonObject { put("url", JsonPrimitive(url)) })
            if (media.isViewable) return openMedia(media, "")
        }
        openExternalOrWeb(url)
    }

    fun openExternalOrWeb(url: String) {
        if (settings().internalBrowser) {
            nav.navigate(Routes.web(url))
        } else {
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
            } catch (e: ActivityNotFoundException) {
                nav.navigate(Routes.web(url))
            }
        }
    }

    fun openInBrowser(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("No AppNavigator provided") }

fun permalinkUrl(permalink: String) = "https://www.reddit.com$permalink"
