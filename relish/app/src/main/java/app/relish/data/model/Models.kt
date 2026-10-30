package app.relish.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A page of results plus the cursor for the next one (`null` = end of listing). */
data class Listing<T>(val items: List<T>, val after: String?)

enum class VoteDir(val value: Int) { UP(1), NONE(0), DOWN(-1) }

data class Post(
    val id: String,
    val fullname: String,
    val subreddit: String,
    val title: String,
    val author: String,
    val domain: String,
    val url: String,
    val permalink: String,
    val selftext: String,
    val selftextHtml: String?,
    val score: Int,
    val upvoteRatio: Float,
    val numComments: Int,
    val createdUtc: Long,
    val over18: Boolean,
    val spoiler: Boolean,
    val stickied: Boolean,
    val locked: Boolean,
    val isSelf: Boolean,
    val thumbnail: String?,
    val previewUrl: String?,
    val flair: String?,
    val vote: VoteDir,
    val saved: Boolean,
    val hidden: Boolean,
    val media: PostMedia,
)

/** Everything the media viewer needs. Serializable so it can travel as a navigation argument. */
@Serializable
sealed interface PostMedia {
    @Serializable @SerialName("none") data object None : PostMedia
    @Serializable @SerialName("image") data class Image(val url: String) : PostMedia
    @Serializable @SerialName("video") data class Video(
        val url: String,
        /** HLS/DASH carry audio for v.redd.it; the mp4 fallback is silent. */
        val fallbackUrl: String? = null,
        val isGif: Boolean = false,
    ) : PostMedia
    @Serializable @SerialName("audio") data class Audio(val url: String) : PostMedia
    @Serializable @SerialName("gallery") data class Gallery(val items: List<GalleryItem>) : PostMedia
    @Serializable @SerialName("redgifs") data class Redgifs(val id: String, val pageUrl: String) : PostMedia
    @Serializable @SerialName("link") data class Link(val url: String) : PostMedia

    val isViewable: Boolean get() = this !is None && this !is Link
}

@Serializable
data class GalleryItem(val url: String, val caption: String? = null, val isVideo: Boolean = false)

sealed interface CommentNode {
    val id: String
    val fullname: String
    val depth: Int
    val parentId: String
}

data class Comment(
    override val id: String,
    override val fullname: String,
    override val depth: Int,
    override val parentId: String,
    val linkId: String,
    val subreddit: String,
    val author: String,
    val body: String,
    val bodyHtml: String?,
    val score: Int,
    val scoreHidden: Boolean,
    val createdUtc: Long,
    val edited: Boolean,
    val isSubmitter: Boolean,
    val distinguished: String?,
    val stickied: Boolean,
    val vote: VoteDir,
    val saved: Boolean,
    val permalink: String,
    val flair: String?,
    /** Present only for comments that come from user/saved listings. */
    val linkTitle: String? = null,
) : CommentNode

data class MoreComments(
    override val id: String,
    override val fullname: String,
    override val depth: Int,
    override val parentId: String,
    val count: Int,
    val children: List<String>,
) : CommentNode {
    /** Reddit emits `more` with no children when the thread continues on a separate page. */
    val isContinueThread: Boolean get() = children.isEmpty()
}

/** Items that can appear in a mixed listing (user overview, saved). */
sealed interface FeedItem {
    val key: String
    data class PostEntry(val post: Post) : FeedItem { override val key get() = post.fullname }
    data class CommentEntry(val comment: Comment) : FeedItem { override val key get() = comment.fullname }
}

data class Subreddit(
    val name: String,
    val title: String,
    val over18: Boolean,
    val subscribers: Long,
    val iconUrl: String?,
    val publicDescription: String,
    val userIsSubscriber: Boolean,
)

data class Multireddit(
    val name: String,
    val displayName: String,
    val path: String,
    val over18: Boolean,
    val subreddits: List<String>,
)

data class Account(
    val name: String,
    val linkKarma: Int,
    val commentKarma: Int,
    val inboxCount: Int,
    val over18: Boolean,
    val iconUrl: String?,
)

data class Message(
    val id: String,
    val fullname: String,
    val author: String,
    val subject: String,
    val body: String,
    val bodyHtml: String?,
    val createdUtc: Long,
    val isNew: Boolean,
    val wasComment: Boolean,
    val context: String?,
    val subreddit: String?,
)

data class PostWithComments(val post: Post, val comments: List<CommentNode>)

enum class Sort(val path: String, val label: String, val hasTime: Boolean) {
    HOT("hot", "Hot", false),
    NEW("new", "New", false),
    TOP("top", "Top", true),
    RISING("rising", "Rising", false),
    CONTROVERSIAL("controversial", "Controversial", true),
}

enum class TimeFilter(val param: String, val label: String) {
    HOUR("hour", "Past hour"),
    DAY("day", "Past 24 hours"),
    WEEK("week", "Past week"),
    MONTH("month", "Past month"),
    YEAR("year", "Past year"),
    ALL("all", "All time"),
}

enum class CommentSort(val param: String, val label: String) {
    BEST("confidence", "Best"),
    TOP("top", "Top"),
    NEW("new", "New"),
    CONTROVERSIAL("controversial", "Controversial"),
    OLD("old", "Old"),
    QA("qa", "Q&A"),
}

/** What a feed screen is showing. Encoded into a single string for navigation. */
sealed interface FeedSource {
    val title: String

    data object FrontPage : FeedSource { override val title = "Front page" }
    data class Subreddit(val name: String) : FeedSource {
        override val title get() = if (name.equals("all", true) || name.equals("popular", true)) name else "r/$name"
    }
    data class Multi(val owner: String, val name: String) : FeedSource { override val title get() = "m/$name" }
    data class User(val name: String, val where: String = "overview") : FeedSource { override val title get() = "u/$name" }
    data class Saved(val user: String) : FeedSource { override val title = "Saved" }
    data class Search(val query: String, val subreddit: String? = null) : FeedSource {
        override val title get() = "\"$query\"" + (subreddit?.let { " in r/$it" } ?: "")
    }

    val supportsSort: Boolean get() = this !is Saved

    fun encode(): String = when (this) {
        FrontPage -> "front"
        is Subreddit -> "r|$name"
        is Multi -> "m|$owner|$name"
        is User -> "u|$name|$where"
        is Saved -> "saved|$user"
        is Search -> "search|${subreddit.orEmpty()}|$query"
    }

    companion object {
        fun decode(raw: String?): FeedSource {
            if (raw.isNullOrBlank()) return FrontPage
            val p = raw.split('|', limit = 3)
            return when (p[0]) {
                "r" -> Subreddit(p[1])
                "m" -> Multi(p[1], p[2])
                "u" -> User(p[1], p.getOrElse(2) { "overview" })
                "saved" -> Saved(p[1])
                "search" -> Search(p.getOrElse(2) { "" }, p[1].ifBlank { null })
                else -> FrontPage
            }
        }
    }
}
