package app.relish

import app.relish.data.model.Comment
import app.relish.data.model.FeedSource
import app.relish.data.model.MoreComments
import app.relish.data.model.PostMedia
import app.relish.data.model.VoteDir
import app.relish.data.remote.RedditParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditParserTest {

    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `parses an NSFW video post with placeholder thumbnail`() {
        val listing = RedditParser.posts(parse(
            """
            {"kind":"Listing","data":{"after":"t3_next","children":[{"kind":"t3","data":{
              "id":"abc","name":"t3_abc","subreddit":"example","title":"Hello & <world>","author":"someone",
              "domain":"v.redd.it","url":"https://v.redd.it/xyz","permalink":"/r/example/comments/abc/hello/",
              "selftext":"","score":1234,"num_comments":56,"created_utc":1700000000.0,"over_18":true,
              "spoiler":false,"stickied":false,"is_self":false,"thumbnail":"nsfw","likes":true,"saved":false,
              "preview":{"images":[{"source":{"url":"https://preview.redd.it/full.jpg"},"resolutions":[
                {"url":"https://preview.redd.it/108.jpg"},{"url":"https://preview.redd.it/216.jpg"}]}]},
              "secure_media":{"reddit_video":{"hls_url":"https://v.redd.it/xyz/HLSPlaylist.m3u8","fallback_url":"https://v.redd.it/xyz/DASH_720.mp4","is_gif":false}}
            }}]}}
            """
        ))
        assertEquals("t3_next", listing.after)
        val post = listing.items.single()
        assertTrue(post.over18)
        assertEquals(VoteDir.UP, post.vote)
        assertEquals("Hello & <world>", post.title)
        // "nsfw" placeholder replaced by a real preview image.
        assertEquals("https://preview.redd.it/216.jpg", post.thumbnail)
        val media = post.media as PostMedia.Video
        assertEquals("https://v.redd.it/xyz/HLSPlaylist.m3u8", media.url)
        assertEquals("https://v.redd.it/xyz/DASH_720.mp4", media.fallbackUrl)
    }

    @Test
    fun `flattens nested comments and keeps more stubs with depth`() {
        val result = RedditParser.postWithComments(parse(
            """
            [{"kind":"Listing","data":{"children":[{"kind":"t3","data":{"id":"p","name":"t3_p","title":"t","is_self":true}}]}},
             {"kind":"Listing","data":{"children":[
               {"kind":"t1","data":{"id":"c1","name":"t1_c1","parent_id":"t3_p","body":"top","edited":false,"replies":{
                 "kind":"Listing","data":{"children":[
                   {"kind":"t1","data":{"id":"c2","name":"t1_c2","parent_id":"t1_c1","body":"child","edited":1700000001,"replies":""}},
                   {"kind":"more","data":{"id":"c9","name":"t1_c9","parent_id":"t1_c1","count":7,"children":["c9","c10"]}}
                 ]}}}},
               {"kind":"t1","data":{"id":"c3","name":"t1_c3","parent_id":"t3_p","body":"second","replies":""}}
             ]}}]
            """
        ))
        val nodes = result.comments
        assertEquals(listOf("t1_c1", "t1_c2", "t1_c9", "t1_c3"), nodes.map { it.fullname })
        assertEquals(listOf(0, 1, 1, 0), nodes.map { it.depth })
        assertFalse((nodes[0] as Comment).edited)
        assertTrue((nodes[1] as Comment).edited)
        assertEquals(7, (nodes[2] as MoreComments).count)
    }

    @Test
    fun `resolves galleries, imgur gifv and redgifs`() {
        val gallery = RedditParser.post(parse(
            """{"is_gallery":true,"url":"https://www.reddit.com/gallery/x",
               "gallery_data":{"items":[{"media_id":"a","caption":"one"},{"media_id":"b"}]},
               "media_metadata":{"a":{"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/a.jpg?x=1&amp;y=2"}},
                                 "b":{"status":"valid","e":"AnimatedImage","s":{"gif":"https://i.redd.it/b.gif","mp4":"https://preview.redd.it/b.mp4"}}}}"""
        ) as kotlinx.serialization.json.JsonObject).media as PostMedia.Gallery
        assertEquals(2, gallery.items.size)
        assertEquals("https://preview.redd.it/a.jpg?x=1&y=2", gallery.items[0].url)
        assertTrue(gallery.items[1].isVideo)

        val gifv = RedditParser.post(parse("""{"url":"https://i.imgur.com/abc.gifv"}""") as kotlinx.serialization.json.JsonObject).media
        assertEquals(PostMedia.Video("https://i.imgur.com/abc.mp4", isGif = true), gifv)

        val redgifs = RedditParser.post(parse("""{"url":"https://www.redgifs.com/watch/somegifid"}""") as kotlinx.serialization.json.JsonObject).media
        assertEquals("somegifid", (redgifs as PostMedia.Redgifs).id)
    }

    @Test
    fun `feed source round trips through navigation encoding`() {
        listOf(
            FeedSource.FrontPage, FeedSource.Subreddit("a+b"), FeedSource.Multi("me", "multi"),
            FeedSource.User("someone", "submitted"), FeedSource.Saved("me"), FeedSource.Search("cats | dogs", "pics"),
            FeedSource.Search("q", null),
        ).forEach { assertEquals(it, FeedSource.decode(it.encode())) }
    }
}

class DemoDataTest {
    @Test
    fun `demo feed covers every media type and paginates once`() {
        val first = app.relish.data.demo.DemoData.feed(FeedSource.FrontPage, null)
        val media = first.items.map { (it as app.relish.data.model.FeedItem.PostEntry).post.media }
        assertTrue(media.any { it is PostMedia.Image })
        assertTrue(media.any { it is PostMedia.Video && it.isGif })
        assertTrue(media.any { it is PostMedia.Video && it.url.endsWith(".m3u8") })
        assertTrue(media.any { it is PostMedia.Gallery })
        assertTrue(media.any { it is PostMedia.Audio })
        assertTrue(media.any { it is PostMedia.Link && it.url.contains("youtu") })
        assertEquals("demo_page_2", first.after)
        assertNull(app.relish.data.demo.DemoData.feed(FeedSource.FrontPage, first.after).after)
    }

    @Test
    fun `demo comment tree is well formed`() {
        val nodes = app.relish.data.demo.DemoData.postWithComments("img1").comments
        assertEquals(0, nodes.first().depth)
        // Depth never jumps by more than one level going down the list.
        nodes.zipWithNext().forEach { (a, b) -> assertTrue("${a.fullname} -> ${b.fullname}", b.depth <= a.depth + 1) }
        val more = nodes.filterIsInstance<MoreComments>().first { it.children.isNotEmpty() }
        assertEquals(more.children.size, app.relish.data.demo.DemoData.moreChildren("img1", more).size)
    }
}

class CommentFoldingTest {
    @Test
    fun `compressed mode folds replies behind one stub per top-level comment`() {
        val nodes = app.relish.data.demo.DemoData.postWithComments("img1").comments
        val folded = app.relish.ui.comments.CommentsViewModel.visibleRows(nodes, emptySet(), expanded = emptySet())
        // Only top-level comments and stubs (plus top-level "more" rows) remain.
        assertTrue(folded.all { it.repliesStub || it.node.depth == 0 })
        val stub = folded.first { it.repliesStub }
        assertTrue(stub.hiddenChildren > 1)
        // Expanding that comment reveals its replies.
        val opened = app.relish.ui.comments.CommentsViewModel.visibleRows(nodes, emptySet(), expanded = setOf(stub.node.fullname))
        assertTrue(opened.any { it.node.depth > 0 })
        assertFalse(opened.any { it.repliesStub && it.node.fullname == stub.node.fullname })
        // Keys stay unique for LazyColumn.
        assertEquals(opened.size, opened.map { it.key }.toSet().size)
    }
}

class SubscriptionImportTest {
    private fun parse(s: String) = app.relish.data.repo.RedditRepository.parseSubredditNames(s)

    @Test
    fun `parses a multireddit link`() {
        assertEquals(listOf("AirReps", "Android", "AndroidAuto"), parse("https://old.reddit.com/r/AirReps+Android+AndroidAuto/"))
    }

    @Test
    fun `parses free-form lists and skips junk`() {
        assertEquals(listOf("pics", "AndroidTV", "nrl"), parse("r/pics\n/r/AndroidTV/, nrl  all popular x"))
    }
}
