package app.relish.data.remote

import app.relish.data.model.Account
import app.relish.data.model.Comment
import app.relish.data.model.CommentNode
import app.relish.data.model.FeedItem
import app.relish.data.model.Listing
import app.relish.data.model.Message
import app.relish.data.model.MoreComments
import app.relish.data.model.Multireddit
import app.relish.data.model.Post
import app.relish.data.model.PostWithComments
import app.relish.data.model.Subreddit
import app.relish.data.model.VoteDir
import app.relish.util.MediaResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns Reddit's "Thing"/"Listing" JSON into domain models. Works on raw [JsonElement]s because
 * Reddit's payloads are polymorphic (`t1`/`t3`/`more`) and inconsistent (`replies` is `""` when
 * empty, `edited` is `false` or a timestamp), which typed DTOs handle poorly.
 */
object RedditParser {

    fun posts(root: JsonElement): Listing<Post> {
        val (children, after) = listing(root)
        return Listing(children.mapNotNull { if (it.kind == "t3") post(it.data) else null }, after)
    }

    fun feedItems(root: JsonElement): Listing<FeedItem> {
        val (children, after) = listing(root)
        val items = children.mapNotNull {
            when (it.kind) {
                "t3" -> FeedItem.PostEntry(post(it.data))
                "t1" -> FeedItem.CommentEntry(comment(it.data, 0))
                else -> null
            }
        }
        return Listing(items, after)
    }

    fun postWithComments(root: JsonElement): PostWithComments {
        val arr = root as JsonArray
        val post = posts(arr[0]).items.first()
        val comments = mutableListOf<CommentNode>()
        listing(arr[1]).first.forEach { flatten(it, 0, comments) }
        return PostWithComments(post, comments)
    }

    /** `/api/morechildren` returns a flat, depth-first list under json.data.things. */
    fun moreChildren(root: JsonElement, baseDepth: Int): List<CommentNode> {
        val things = root.obj("json")?.obj("data")?.arr("things") ?: return emptyList()
        val depths = HashMap<String, Int>()
        return things.mapNotNull { el ->
            val thing = el as? JsonObject ?: return@mapNotNull null
            val data = thing.obj("data") ?: return@mapNotNull null
            val parent = data.str("parent_id").orEmpty()
            val depth = data.int("depth") ?: depths[parent]?.plus(1) ?: baseDepth
            val node: CommentNode? = when (thing.str("kind")) {
                "t1" -> comment(data, depth)
                "more" -> more(data, depth)
                else -> null
            }
            node?.also { depths[it.fullname] = it.depth }
        }
    }

    fun subreddits(root: JsonElement): Listing<Subreddit> {
        val (children, after) = listing(root)
        return Listing(children.filter { it.kind == "t5" }.map { subreddit(it.data) }, after)
    }

    fun subredditAbout(root: JsonElement): Subreddit = subreddit(root.obj("data") ?: JsonObject(emptyMap()))

    fun multis(root: JsonElement): List<Multireddit> = (root as? JsonArray).orEmpty().mapNotNull { el ->
        val d = el.obj("data") ?: return@mapNotNull null
        Multireddit(
            name = d.str("name").orEmpty(),
            displayName = d.str("display_name") ?: d.str("name").orEmpty(),
            path = d.str("path").orEmpty().trim('/'),
            over18 = d.bool("over_18") ?: false,
            subreddits = d.arr("subreddits").orEmpty().mapNotNull { it.str("name") },
        )
    }

    fun account(root: JsonElement): Account {
        val d = root as JsonObject
        return Account(
            name = d.str("name").orEmpty(),
            linkKarma = d.int("link_karma") ?: 0,
            commentKarma = d.int("comment_karma") ?: 0,
            inboxCount = d.int("inbox_count") ?: 0,
            over18 = d.bool("over_18") ?: false,
            iconUrl = d.str("icon_img")?.substringBefore('?'),
        )
    }

    fun messages(root: JsonElement): Listing<Message> {
        val (children, after) = listing(root)
        return Listing(children.map { (_, d) ->
            Message(
                id = d.str("id").orEmpty(),
                fullname = d.str("name").orEmpty(),
                author = d.str("author") ?: "[deleted]",
                subject = d.str("subject").orEmpty(),
                body = d.str("body").orEmpty(),
                bodyHtml = d.str("body_html"),
                createdUtc = d.long("created_utc") ?: 0,
                isNew = d.bool("new") ?: false,
                wasComment = d.bool("was_comment") ?: false,
                context = d.str("context")?.ifBlank { null },
                subreddit = d.str("subreddit"),
            )
        }, after)
    }

    /** Newly created comment from POST /api/comment (api_type=json). */
    fun createdComment(root: JsonElement, depth: Int): Comment? {
        val json = root.obj("json") ?: return null
        val errors = json.arr("errors")
        if (!errors.isNullOrEmpty()) throw IllegalStateException(errors.joinToString { it.toString() })
        val thing = json.obj("data")?.arr("things")?.firstOrNull() ?: return null
        return thing.obj("data")?.let { comment(it, depth) }
    }

    // ---- internals -------------------------------------------------------------------------

    private data class Thing(val kind: String, val data: JsonObject)

    private fun listing(root: JsonElement): Pair<List<Thing>, String?> {
        val data = root.obj("data") ?: return emptyList<Thing>() to null
        val children = data.arr("children").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Thing(o.str("kind").orEmpty(), o.obj("data") ?: return@mapNotNull null)
        }
        return children to data.str("after")
    }

    private fun flatten(thing: Thing, depth: Int, out: MutableList<CommentNode>) {
        when (thing.kind) {
            "t1" -> {
                out += comment(thing.data, depth)
                val replies = thing.data["replies"]
                if (replies is JsonObject) listing(replies).first.forEach { flatten(it, depth + 1, out) }
            }
            "more" -> out += more(thing.data, depth)
        }
    }

    fun post(d: JsonObject): Post {
        val preview = d.obj("preview")?.arr("images")?.firstOrNull()
        val source = preview?.obj("source")?.str("url")
        // Pick a mid-sized resolution for list thumbnails/cards rather than the full source.
        val mid = preview?.arr("resolutions")?.let { res -> res.getOrNull(minOf(3, res.size - 1)) }?.str("url")
        val thumb = d.str("thumbnail")?.takeIf { it.startsWith("http") }
        return Post(
            id = d.str("id").orEmpty(),
            fullname = d.str("name").orEmpty(),
            subreddit = d.str("subreddit").orEmpty(),
            title = d.str("title").orEmpty(),
            author = d.str("author") ?: "[deleted]",
            domain = d.str("domain").orEmpty(),
            url = d.str("url_overridden_by_dest") ?: d.str("url").orEmpty(),
            permalink = d.str("permalink").orEmpty(),
            selftext = d.str("selftext").orEmpty(),
            selftextHtml = d.str("selftext_html"),
            score = d.int("score") ?: 0,
            upvoteRatio = (d.double("upvote_ratio") ?: 1.0).toFloat(),
            numComments = d.int("num_comments") ?: 0,
            createdUtc = d.long("created_utc") ?: 0,
            over18 = d.bool("over_18") ?: false,
            spoiler = d.bool("spoiler") ?: false,
            stickied = d.bool("stickied") ?: false,
            locked = d.bool("locked") ?: false,
            isSelf = d.bool("is_self") ?: false,
            // Reddit returns "nsfw"/"spoiler"/"default" placeholders instead of URLs; fall back to preview.
            thumbnail = thumb ?: mid ?: source,
            previewUrl = mid ?: source,
            flair = d.str("link_flair_text")?.ifBlank { null },
            vote = vote(d),
            saved = d.bool("saved") ?: false,
            hidden = d.bool("hidden") ?: false,
            media = MediaResolver.resolve(d),
        )
    }

    fun comment(d: JsonObject, depth: Int) = Comment(
        id = d.str("id").orEmpty(),
        fullname = d.str("name").orEmpty(),
        depth = depth,
        parentId = d.str("parent_id").orEmpty(),
        linkId = d.str("link_id").orEmpty(),
        subreddit = d.str("subreddit").orEmpty(),
        author = d.str("author") ?: "[deleted]",
        body = d.str("body").orEmpty(),
        bodyHtml = d.str("body_html"),
        score = d.int("score") ?: 0,
        scoreHidden = d.bool("score_hidden") ?: false,
        createdUtc = d.long("created_utc") ?: 0,
        edited = d["edited"].let { it is JsonPrimitive && it !is JsonNull && it.booleanOrNull != false },
        isSubmitter = d.bool("is_submitter") ?: false,
        distinguished = d.str("distinguished"),
        stickied = d.bool("stickied") ?: false,
        vote = vote(d),
        saved = d.bool("saved") ?: false,
        permalink = d.str("permalink").orEmpty(),
        flair = d.str("author_flair_text")?.ifBlank { null },
        linkTitle = d.str("link_title"),
    )

    private fun more(d: JsonObject, depth: Int) = MoreComments(
        id = d.str("id").orEmpty(),
        fullname = d.str("name").orEmpty(),
        depth = depth,
        parentId = d.str("parent_id").orEmpty(),
        count = d.int("count") ?: 0,
        children = d.arr("children").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )

    private fun subreddit(d: JsonObject) = Subreddit(
        name = d.str("display_name").orEmpty(),
        title = d.str("title").orEmpty(),
        over18 = d.bool("over18") ?: false,
        subscribers = d.long("subscribers") ?: 0,
        iconUrl = (d.str("community_icon")?.ifBlank { null } ?: d.str("icon_img")?.ifBlank { null })?.substringBefore('?'),
        publicDescription = d.str("public_description").orEmpty(),
        userIsSubscriber = d.bool("user_is_subscriber") ?: false,
    )

    private fun vote(d: JsonObject) = when (d.bool("likes")) {
        true -> VoteDir.UP
        false -> VoteDir.DOWN
        null -> VoteDir.NONE
    }
}

// Small, null-tolerant accessors over JsonElement.
internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal fun JsonElement?.bool(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.long(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonElement?.int(key: String): Int? = long(key)?.toInt()
internal fun JsonElement?.double(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull
