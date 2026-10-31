package app.relish.data.demo

import app.relish.data.model.Comment
import app.relish.data.model.CommentNode
import app.relish.data.model.FeedItem
import app.relish.data.model.FeedSource
import app.relish.data.model.GalleryItem
import app.relish.data.model.Listing
import app.relish.data.model.Message
import app.relish.data.model.MoreComments
import app.relish.data.model.Post
import app.relish.data.model.PostMedia
import app.relish.data.model.PostWithComments
import app.relish.data.model.Subreddit
import app.relish.data.model.VoteDir

/**
 * Offline sample content for Demo mode: exercises every post type, media type and comment
 * feature without touching Reddit. Media points at stable public test assets (Google sample
 * videos, Mux/Akamai test streams, Picsum photos, Wikimedia GIFs, Blender open films).
 */
object DemoData {

    private fun ago(minutes: Long) = System.currentTimeMillis() / 1000 - minutes * 60

    private fun picsum(id: Int, w: Int, h: Int) = "https://picsum.photos/id/$id/$w/$h"
    private fun thumb(id: Int) = picsum(id, 160, 160)
    private fun preview(id: Int) = picsum(id, 960, 600)

    private const val GOOGLE_SAMPLES = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample"
    private const val YT_ID = "aqz-KE-bpKQ" // Blender Foundation: Big Buck Bunny

    private fun md(vararg paragraphs: String) =
        "<div class=\"md\">" + paragraphs.joinToString("") { if (it.startsWith("<")) it else "<p>$it</p>" } + "</div>"

    private fun post(
        id: String,
        title: String,
        media: PostMedia,
        sub: String = "relish_demo",
        author: String = "demo_poster",
        domain: String,
        url: String,
        thumbnail: String? = null,
        previewUrl: String? = thumbnail,
        score: Int = 1234,
        comments: Int = 56,
        minutesAgo: Long = 90,
        over18: Boolean = false,
        spoiler: Boolean = false,
        stickied: Boolean = false,
        locked: Boolean = false,
        flair: String? = null,
        selftext: String = "",
        selftextHtml: String? = null,
    ) = Post(
        id = id,
        fullname = "t3_$id",
        subreddit = sub,
        title = title,
        author = author,
        domain = domain,
        url = url,
        permalink = "/r/$sub/comments/$id/demo/",
        selftext = selftext,
        selftextHtml = selftextHtml,
        score = score,
        upvoteRatio = 0.95f,
        numComments = comments,
        createdUtc = ago(minutesAgo),
        over18 = over18,
        spoiler = spoiler,
        stickied = stickied,
        locked = locked,
        isSelf = media == PostMedia.None,
        thumbnail = thumbnail,
        previewUrl = previewUrl,
        flair = flair,
        vote = VoteDir.NONE,
        saved = false,
        hidden = false,
        media = media,
    )

    private fun selfPost(id: String, title: String, html: String, plain: String, minutesAgo: Long, flair: String? = null,
                         stickied: Boolean = false, spoiler: Boolean = false, locked: Boolean = false, score: Int = 321) =
        post(
            id, title, PostMedia.None, domain = "self.relish_demo", url = "https://www.reddit.com/r/relish_demo/comments/$id/",
            selftext = plain, selftextHtml = html, minutesAgo = minutesAgo, flair = flair, stickied = stickied,
            spoiler = spoiler, locked = locked, score = score,
        )

    val posts: List<Post> by lazy {
        listOf(
            selfPost(
                "welcome", "Welcome to Relish demo mode: every post type in one feed",
                md(
                    "This feed is <strong>offline sample content</strong>. Nothing here touches Reddit.",
                    "Tap a row for the action bar, tap a thumbnail to open media, and open the comments to try collapsing, depth colours and <em>load more</em>.",
                    "Turn demo mode off in <strong>Settings › Account</strong>.",
                ),
                "This feed is offline sample content.", minutesAgo = 5, flair = "Announcement", stickied = true, score = 99,
            ),
            post("img1", "Image: mountain lake at sunrise (landscape)", PostMedia.Image(picsum(1015, 2400, 1600)),
                domain = "i.redd.it", url = picsum(1015, 2400, 1600), thumbnail = thumb(1015), previewUrl = preview(1015),
                score = 25_400, comments = 412, minutesAgo = 30, flair = "OC"),
            post("img2", "Image: tall portrait photo (pinch and double-tap to zoom)", PostMedia.Image(picsum(1025, 1400, 2100)),
                domain = "i.redd.it", url = picsum(1025, 1400, 2100), thumbnail = thumb(1025), previewUrl = preview(1025),
                score = 8_120, comments = 97, minutesAgo = 75),
            post("nsfw1", "NSFW-flagged image (check the badge, and blur in Settings)", PostMedia.Image(picsum(1062, 2000, 1333)),
                sub = "relish_demo_nsfw", domain = "i.redd.it", url = picsum(1062, 2000, 1333), thumbnail = thumb(1062),
                previewUrl = preview(1062), over18 = true, score = 3_303, comments = 41, minutesAgo = 120),
            post("gif1", "GIF: rotating Earth (animated .gif)",
                PostMedia.Image("https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif"),
                domain = "upload.wikimedia.org", url = "https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif",
                thumbnail = "https://upload.wikimedia.org/wikipedia/commons/2/2c/Rotating_earth_%28large%29.gif",
                score = 5_550, comments = 23, minutesAgo = 140),
            post("gif2", "GIF: Newton's cradle (animated .gif)",
                PostMedia.Image("https://upload.wikimedia.org/wikipedia/commons/d/d3/Newtons_cradle_animation_book_2.gif"),
                domain = "upload.wikimedia.org", url = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Newtons_cradle_animation_book_2.gif",
                thumbnail = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Newtons_cradle_animation_book_2.gif",
                score = 1_870, comments = 12, minutesAgo = 160),
            post("gifv", "GIF-style video (muted mp4 loop, like imgur .gifv / Reddit gifs)",
                PostMedia.Video("$GOOGLE_SAMPLES/ForBiggerFun.mp4", isGif = true),
                domain = "i.imgur.com", url = "$GOOGLE_SAMPLES/ForBiggerFun.mp4", thumbnail = thumb(1043), previewUrl = preview(1043),
                score = 9_876, comments = 150, minutesAgo = 200),
            post("vid1", "Video with sound: Big Buck Bunny (mp4, ~10 min)",
                PostMedia.Video("$GOOGLE_SAMPLES/BigBuckBunny.mp4"),
                domain = "v.redd.it", url = "$GOOGLE_SAMPLES/BigBuckBunny.mp4",
                thumbnail = "https://img.youtube.com/vi/$YT_ID/hqdefault.jpg", score = 14_200, comments = 300, minutesAgo = 260),
            post("hls1", "Adaptive HLS stream (how v.redd.it serves video with audio)",
                PostMedia.Video("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", fallbackUrl = "$GOOGLE_SAMPLES/ElephantsDream.mp4"),
                domain = "v.redd.it", url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", thumbnail = thumb(1084),
                previewUrl = preview(1084), score = 4_420, comments = 61, minutesAgo = 300),
            post("dash1", "DASH stream (Reddit's other video format)",
                PostMedia.Video("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"),
                domain = "v.redd.it", url = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd", thumbnail = thumb(1074),
                previewUrl = preview(1074), score = 2_048, comments = 33, minutesAgo = 330),
            post("gal1", "Gallery: 5 items, one of them an animated clip (swipe)",
                PostMedia.Gallery(
                    listOf(
                        GalleryItem(picsum(1036, 2000, 1300), "Snowy forest"),
                        GalleryItem(picsum(1039, 2000, 1300), "Waterfall"),
                        GalleryItem("$GOOGLE_SAMPLES/ForBiggerJoyrides.mp4", "Animated item (mp4)", isVideo = true),
                        GalleryItem(picsum(1041, 1300, 2000), "Tall shot"),
                        GalleryItem(picsum(1044, 2000, 1300), null),
                    )
                ),
                domain = "reddit.com", url = "https://www.reddit.com/gallery/gal1", thumbnail = thumb(1036), previewUrl = preview(1036),
                score = 7_007, comments = 88, minutesAgo = 360),
            post("gal2", "NSFW-flagged gallery (3 images)",
                PostMedia.Gallery(listOf(GalleryItem(picsum(1050, 1600, 1600)), GalleryItem(picsum(1051, 1600, 1600)), GalleryItem(picsum(1053, 1600, 1600)))),
                sub = "relish_demo_nsfw", domain = "reddit.com", url = "https://www.reddit.com/gallery/gal2", thumbnail = thumb(1050),
                previewUrl = preview(1050), over18 = true, score = 1_212, comments = 19, minutesAgo = 400),
            post("aud1", "Audio file (mp3) with player controls",
                PostMedia.Audio("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
                domain = "soundhelix.com", url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3", score = 640, comments = 9, minutesAgo = 450),
            post("yt1", "YouTube link (opens the YouTube app): Big Buck Bunny 4K",
                PostMedia.Link("https://www.youtube.com/watch?v=$YT_ID"),
                domain = "youtube.com", url = "https://www.youtube.com/watch?v=$YT_ID",
                thumbnail = "https://img.youtube.com/vi/$YT_ID/hqdefault.jpg", score = 18_900, comments = 512, minutesAgo = 500),
            post("yt2", "YouTube short link (youtu.be)",
                PostMedia.Link("https://youtu.be/$YT_ID"), domain = "youtu.be", url = "https://youtu.be/$YT_ID",
                thumbnail = "https://img.youtube.com/vi/$YT_ID/mqdefault.jpg", score = 777, comments = 14, minutesAgo = 540),
            post("web1", "Article link (opens the in-app browser or a Custom Tab)",
                PostMedia.Link("https://en.wikipedia.org/wiki/Reddit_is_Fun"), domain = "en.wikipedia.org",
                url = "https://en.wikipedia.org/wiki/Reddit_is_Fun", thumbnail = thumb(1060), previewUrl = preview(1060),
                score = 2_345, comments = 120, minutesAgo = 600),
            selfPost(
                "md1", "Text post with formatting: lists, quotes, code, links",
                md(
                    "Some <strong>bold</strong>, some <em>italic</em>, some <del>strikethrough</del> and <code>inline code</code>.",
                    "<ul><li>First bullet</li><li>Second bullet with a <a href=\"/r/relish_demo_nsfw\">subreddit link</a></li><li>Third: <a href=\"/u/demo_poster\">a user link</a></li></ul>",
                    "<blockquote><p>A quoted paragraph, like replying to someone.</p></blockquote>",
                    "Links: <a href=\"https://picsum.photos/id/1011/1600/1000\">an image (opens the viewer)</a> · <a href=\"https://www.youtube.com/watch?v=$YT_ID\">a YouTube video</a> · <a href=\"https://en.wikipedia.org/wiki/Android_(operating_system)\">a web page</a>",
                ),
                "Some bold, some italic…", minutesAgo = 640, flair = "Discussion",
            ),
            selfPost(
                "spoil1", "Spoiler-flagged post: the ending of the movie",
                md("Spoiler text: <span class=\"md-spoiler-text\">it was the dog all along</span>."),
                "Spoiler text", minutesAgo = 700, spoiler = true, score = 150,
            ),
            selfPost(
                "lock1", "Locked, controversial post with a negative score",
                md("Comments are locked on this one. The score is negative so you can see how that renders."),
                "Comments are locked.", minutesAgo = 760, locked = true, score = -42,
            ),
            post("broken", "Broken image link (tests the error state)", PostMedia.Image("https://picsum.photos/id/does-not-exist/800/600"),
                domain = "i.redd.it", url = "https://picsum.photos/id/does-not-exist/800/600", score = 3, comments = 0, minutesAgo = 900),
        )
    }

    /** Page two: plain image posts so infinite scroll has something to load. */
    private val pageTwo: List<Post> by lazy {
        (0 until 12).map { i ->
            val pic = 1000 + (i * 7) % 85
            post("p2_$i", "More images, page 2 (#${i + 1})", PostMedia.Image(picsum(pic, 1800, 1200)),
                domain = "i.redd.it", url = picsum(pic, 1800, 1200), thumbnail = thumb(pic), previewUrl = preview(pic),
                score = 100 + i * 37, comments = i * 3, minutesAgo = 1_000L + i * 45, over18 = i % 5 == 4)
        }
    }

    private val allPosts get() = posts + pageTwo

    fun feed(source: FeedSource, after: String?): Listing<FeedItem> {
        val base: List<Post> = when (source) {
            is FeedSource.Subreddit ->
                if (source.name.contains("nsfw", ignoreCase = true)) allPosts.filter { it.over18 }
                else if (after == null) posts else pageTwo
            is FeedSource.Search -> allPosts.filter { it.title.contains(source.query, ignoreCase = true) }
            is FeedSource.User -> allPosts.filter { it.author.equals(source.name, ignoreCase = true) }
            is FeedSource.Saved -> emptyList()
            else -> if (after == null) posts else pageTwo
        }
        val paged = source is FeedSource.FrontPage || source is FeedSource.Multi ||
            (source is FeedSource.Subreddit && !source.name.contains("nsfw", true))
        val next = if (paged && after == null) "demo_page_2" else null
        val items = base.map { p ->
            // Single-subreddit views show posts as belonging to the opened subreddit.
            if (source is FeedSource.Subreddit && !source.name.contains("nsfw", true) && p.subreddit == "relish_demo") {
                FeedItem.PostEntry(p.copy(subreddit = source.name))
            } else FeedItem.PostEntry(p)
        }
        return Listing(items, next)
    }

    fun post(postId: String): Post =
        allPosts.firstOrNull { it.id == postId } ?: posts.first()

    fun postWithComments(postId: String): PostWithComments {
        val p = post(postId)
        return PostWithComments(p, commentsFor(p))
    }

    private fun c(
        post: Post, key: String, depth: Int, parent: String, author: String, html: String, plain: String,
        score: Int = 10, minutesAgo: Long = 60, distinguished: String? = null, stickied: Boolean = false,
        scoreHidden: Boolean = false, edited: Boolean = false, flair: String? = null,
    ) = Comment(
        id = "${post.id}_$key",
        fullname = "t1_${post.id}_$key",
        depth = depth,
        parentId = parent,
        linkId = post.fullname,
        subreddit = post.subreddit,
        author = author,
        body = plain,
        bodyHtml = md(html),
        score = score,
        scoreHidden = scoreHidden,
        createdUtc = ago(minutesAgo),
        edited = edited,
        isSubmitter = author == post.author,
        distinguished = distinguished,
        stickied = stickied,
        vote = VoteDir.NONE,
        saved = false,
        permalink = "${post.permalink}${post.id}_$key/",
        flair = flair,
    )

    private fun commentsFor(p: Post): List<CommentNode> {
        val out = mutableListOf<CommentNode>()
        out += c(p, "mod", 0, p.fullname, "relish_mod",
            "<strong>Mod note:</strong> this is a stickied, distinguished moderator comment.", "Mod note", score = 1,
            distinguished = "moderator", stickied = true, minutesAgo = 10)

        // A deep chain to show every depth colour.
        var parent = p.fullname
        val voices = listOf("alice", "bob", p.author, "carol", "dave", "erin", "frank", "grace", "heidi")
        voices.forEachIndexed { depth, who ->
            val node = c(p, "chain$depth", depth, parent, who,
                if (depth == 0) "Top comment. Tap me for actions, long-press (or tap my header) to collapse this whole thread of ${voices.size} replies."
                else "Reply at depth $depth. Each level gets its own bar colour.",
                "Reply at depth $depth", score = 900 - depth * 97, minutesAgo = 20L + depth * 4,
                flair = if (depth == 2) "OP flair" else null)
            out += node
            parent = node.fullname
        }
        out += MoreComments("${p.id}_more_chain", "t1_${p.id}_more_chain", 1, "t1_${p.id}_chain0", 3,
            listOf("${p.id}_m1", "${p.id}_m2", "${p.id}_m3"))

        val rich = c(p, "rich", 0, p.fullname, "bob",
            "Comments render formatting too: <em>italic</em>, <strong>bold</strong>, a <a href=\"https://picsum.photos/id/1035/1600/1000\">photo link</a>, " +
                "a <a href=\"https://youtu.be/$YT_ID\">YouTube link</a> and a spoiler: <span class=\"md-spoiler-text\">hidden text</span>." +
                "</p><blockquote><p>And a quote block.</p></blockquote><p>",
            "Comments render formatting", score = 456, minutesAgo = 45, edited = true)
        out += rich
        out += c(p, "rich_r1", 1, rich.fullname, "carol", "Replying to the formatted comment.", "Replying", score = 12, minutesAgo = 40)
        out += MoreComments("_", "t1__", 2, "t1_${p.id}_rich_r1", 0, emptyList()) // "continue this thread"

        out += c(p, "hidden", 0, p.fullname, "dave", "A fresh comment with its score hidden.", "Score hidden",
            scoreHidden = true, minutesAgo = 2)
        out += c(p, "neg", 0, p.fullname, "erin", "An unpopular opinion with a negative score.", "Unpopular", score = -17, minutesAgo = 300)
        out += MoreComments("${p.id}_more_top", "t1_${p.id}_more_top", 0, p.fullname, 5,
            (1..5).map { "${p.id}_t$it" })
        return out
    }

    /** "Load more": materialises the requested ids as simple comments at the stub's depth. */
    fun moreChildren(postId: String, more: MoreComments): List<CommentNode> {
        val p = post(postId)
        return more.children.mapIndexed { i, id ->
            Comment(
                id = id, fullname = "t1_$id", depth = more.depth, parentId = more.parentId, linkId = p.fullname,
                subreddit = p.subreddit, author = "loaded_user_${i + 1}",
                body = "Loaded via \"load more\" (${i + 1} of ${more.children.size}).",
                bodyHtml = md("Loaded via <em>load more</em> (${i + 1} of ${more.children.size})."),
                score = 5 + i, scoreHidden = false, createdUtc = ago(15L + i), edited = false, isSubmitter = false,
                distinguished = null, stickied = false, vote = VoteDir.NONE, saved = false,
                permalink = "${p.permalink}$id/", flair = null,
            )
        }
    }

    fun reply(parentFullname: String, text: String, depth: Int): Comment {
        val id = "demo_reply_${System.currentTimeMillis()}"
        return Comment(
            id = id, fullname = "t1_$id", depth = depth, parentId = parentFullname, linkId = "",
            subreddit = "relish_demo", author = "you (demo)", body = text, bodyHtml = null, score = 1, scoreHidden = false,
            createdUtc = System.currentTimeMillis() / 1000, edited = false, isSubmitter = false, distinguished = null,
            stickied = false, vote = VoteDir.UP, saved = false, permalink = "", flair = null,
        )
    }

    fun subreddits(query: String): List<Subreddit> = listOf(
        Subreddit("relish_demo", "Relish demo", false, 12_345, null, "Sample content for every post type.", false),
        Subreddit("relish_demo_nsfw", "Relish demo (NSFW-flagged)", true, 6_789, null, "Only the NSFW-flagged samples.", false),
    ).filter { query.isBlank() || it.name.contains(query, true) || it.title.contains(query, true) }
        .ifEmpty { listOf(Subreddit(query.trim().ifBlank { "relish_demo" }, "Demo result", false, 1, null, "Any subreddit works in demo mode.", false)) }

    fun about(name: String) = Subreddit(name, name, name.contains("nsfw", true), 12_345, null, "Demo subreddit", false)

    fun inbox(): List<Message> = listOf(
        Message("m1", "t4_m1", "relish_mod", "Welcome to the demo inbox", "Messages and replies show up here.",
            md("Messages and replies show up here."), ago(30), isNew = true, wasComment = false, context = null, subreddit = null),
        Message("m2", "t1_welcome_chain1", "bob", "comment reply", "Replied to your comment in the welcome post.",
            md("Replied to your comment in the welcome post."), ago(120), isNew = false, wasComment = true,
            context = "/r/relish_demo/comments/welcome/demo/welcome_chain1/", subreddit = "relish_demo"),
    )
}
