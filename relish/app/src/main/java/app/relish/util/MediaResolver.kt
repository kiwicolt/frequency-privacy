package app.relish.util

import app.relish.data.model.GalleryItem
import app.relish.data.model.PostMedia
import app.relish.data.remote.arr
import app.relish.data.remote.bool
import app.relish.data.remote.obj
import app.relish.data.remote.str
import kotlinx.serialization.json.JsonObject

/** Classifies a post's link into something the in-app media viewer can render. */
object MediaResolver {

    private val imageExt = Regex("""\.(jpe?g|png|webp|gif|bmp)$""", RegexOption.IGNORE_CASE)
    private val videoExt = Regex("""\.(mp4|webm|mkv|mov|m3u8|mpd)$""", RegexOption.IGNORE_CASE)
    private val audioExt = Regex("""\.(mp3|ogg|oga|m4a|aac|wav|flac|opus)$""", RegexOption.IGNORE_CASE)

    fun resolve(post: JsonObject): PostMedia {
        // Crossposts carry their media on the original post.
        post.arr("crosspost_parent_list")?.firstOrNull()?.let { parent ->
            if (parent is JsonObject) {
                val m = resolve(parent)
                if (m !is PostMedia.None) return m
            }
        }
        if (post.bool("is_self") == true) return PostMedia.None

        redditVideo(post.obj("secure_media") ?: post.obj("media"))?.let { return it }
        if (post.bool("is_gallery") == true) gallery(post)?.let { return it }

        val url = clean(post.str("url_overridden_by_dest") ?: post.str("url") ?: return PostMedia.None)
        val path = url.substringBefore('?').substringBefore('#')
        val host = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")

        when {
            host.endsWith("redgifs.com") -> {
                val id = path.trimEnd('/').substringAfterLast('/').substringBefore('-').lowercase()
                if (id.isNotBlank()) return PostMedia.Redgifs(id, url)
            }
            host == "i.imgur.com" || host == "imgur.com" || host == "m.imgur.com" -> imgur(path)?.let { return it }
            host == "gfycat.com" -> return PostMedia.Link(url) // gfycat is gone; let the browser handle redirects
        }

        // Animated previews Reddit generates for gifs and some external video hosts.
        val preview = post.obj("preview")
        redditVideo(preview, key = "reddit_video_preview")?.let { return it }

        return when {
            audioExt.containsMatchIn(path) -> PostMedia.Audio(url)
            videoExt.containsMatchIn(path) -> PostMedia.Video(url)
            path.endsWith(".gif", ignoreCase = true) -> gifVariant(preview)?.let { PostMedia.Video(it, isGif = true) }
                ?: PostMedia.Image(url)
            imageExt.containsMatchIn(path) -> PostMedia.Image(url)
            post.str("post_hint") == "image" -> PostMedia.Image(previewSource(preview) ?: url)
            else -> PostMedia.Link(url)
        }
    }

    private fun redditVideo(container: JsonObject?, key: String = "reddit_video"): PostMedia.Video? {
        val v = container.obj(key) ?: return null
        val hls = v.str("hls_url")?.let(::clean)
        val dash = v.str("dash_url")?.let(::clean)
        val fallback = v.str("fallback_url")?.let(::clean)
        val primary = hls ?: dash ?: fallback ?: return null
        return PostMedia.Video(primary, fallbackUrl = fallback, isGif = v.bool("is_gif") == true)
    }

    private fun gallery(post: JsonObject): PostMedia.Gallery? {
        val meta = post.obj("media_metadata") ?: return null
        val items = post.obj("gallery_data")?.arr("items").orEmpty().mapNotNull { el ->
            val id = el.str("media_id") ?: return@mapNotNull null
            val m = meta[id] as? JsonObject ?: return@mapNotNull null
            if (m.str("status") != "valid") return@mapNotNull null
            val s = m.obj("s") ?: return@mapNotNull null
            val animated = m.str("e") == "AnimatedImage"
            val mp4 = s.str("mp4")
            val url = (if (animated) mp4 ?: s.str("gif") else s.str("u")) ?: return@mapNotNull null
            GalleryItem(clean(url), el.str("caption"), isVideo = animated && mp4 != null)
        }
        return if (items.isEmpty()) null else PostMedia.Gallery(items)
    }

    private fun imgur(path: String): PostMedia? {
        if (path.contains("/a/") || path.contains("/gallery/")) return null // albums need the Imgur API
        val id = path.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
        if (id.isBlank()) return null
        return when {
            path.endsWith(".gifv", true) || path.endsWith(".mp4", true) -> PostMedia.Video("https://i.imgur.com/$id.mp4", isGif = true)
            imageExt.containsMatchIn(path) -> PostMedia.Image("https://i.imgur.com/" + path.substringAfterLast('/'))
            else -> PostMedia.Image("https://i.imgur.com/$id.jpg")
        }
    }

    private fun gifVariant(preview: JsonObject?): String? =
        preview?.arr("images")?.firstOrNull().obj("variants")?.obj("mp4")?.obj("source")?.str("url")?.let(::clean)

    private fun previewSource(preview: JsonObject?): String? =
        preview?.arr("images")?.firstOrNull().obj("source")?.str("url")?.let(::clean)

    private fun clean(url: String) = url.replace("&amp;", "&")
}
