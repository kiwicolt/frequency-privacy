package app.relish.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.relish.data.model.CommentSort
import app.relish.data.model.Sort
import app.relish.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark"), PURE_BLACK("Pure black (AMOLED)") }

enum class FontSize(val label: String, val scale: Float) {
    SMALL("Small", 0.87f), MEDIUM("Medium", 1f), LARGE("Large", 1.15f), EXTRA_LARGE("Extra large", 1.3f)
}

enum class ThumbnailMode(val label: String) { ALWAYS("Always"), WIFI_ONLY("Wi-Fi only"), NEVER("Never") }

enum class PostLayout(val label: String) { COMPACT_LIST("Compact list"), LARGE_CARDS("Large cards") }

/**
 * How read-only browsing requests are routed.
 *  - AUTO: OAuth when logged in, falling back to the public `.json` endpoints whenever OAuth
 *    suppresses or refuses NSFW content (403/404/451 or an empty listing). Public `.json` when logged out.
 *  - PUBLIC_JSON: always browse through www.reddit.com `.json` with the over18 session cookie;
 *    OAuth is used only for account actions (vote, save, reply, subscribe, inbox).
 *  - OAUTH_ONLY: never touch the public endpoints.
 */
enum class ContentRouting(val label: String) {
    AUTO("Auto (OAuth, fall back to web JSON)"),
    PUBLIC_JSON("Always web JSON + over18 cookie"),
    OAUTH_ONLY("OAuth only"),
}

/** Snapshot of every user preference. Defaults mirror RIF, with NSFW enabled. */
data class AppSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val lastDarkTheme: ThemeMode = ThemeMode.DARK,
    val accentColor: Long = DEFAULT_ACCENT,
    val depthColors: List<Long> = DEFAULT_DEPTH_COLORS,
    val fontSize: FontSize = FontSize.MEDIUM,
    val compactMode: Boolean = false,
    // Post layout & media
    val thumbnails: ThumbnailMode = ThumbnailMode.ALWAYS,
    val postLayout: PostLayout = PostLayout.COMPACT_LIST,
    val autoMuteVideos: Boolean = true,
    val loopVideos: Boolean = true,
    val internalMediaViewer: Boolean = true,
    val internalBrowser: Boolean = true,
    val inlinePreviewsInCards: Boolean = true,
    // Content filtering & NSFW
    val showNsfw: Boolean = true,
    val blurNsfw: Boolean = false,
    val bypassAgePrompt: Boolean = true,
    val blurSpoilers: Boolean = true,
    val routing: ContentRouting = ContentRouting.AUTO,
    // History & privacy
    val markRead: Boolean = true,
    val hideReadPosts: Boolean = false,
    val saveSearchHistory: Boolean = true,
    // Behaviour
    val defaultSort: Sort = Sort.HOT,
    val defaultCommentSort: CommentSort = CommentSort.BEST,
    val startupSubreddit: String = "",
    /** Offline sample content instead of Reddit (for trying the app without an API key). */
    val demoMode: Boolean = false,
    /** RIF: show only top-level comments, with replies folded into "N more comments [+]". */
    val compressComments: Boolean = true,
) {
    companion object {
        const val DEFAULT_ACCENT = 0xFFFF4500
        /** RIF-style depth bars: one hue per nesting level, cycling. */
        val DEFAULT_DEPTH_COLORS = listOf(
            0xFF2196F3, 0xFF4CAF50, 0xFFFFC107, 0xFFFF5722, 0xFF9C27B0, 0xFF00BCD4, 0xFFE91E63, 0xFF8BC34A,
        )
        val DEPTH_PALETTES: Map<String, List<Long>> = mapOf(
            "Rainbow" to DEFAULT_DEPTH_COLORS,
            "Classic" to listOf(0xFF7C8DA6, 0xFF6F9E73, 0xFFB59B5A, 0xFFA8705E, 0xFF8E6FA6),
            "Monochrome" to listOf(0xFF9E9E9E, 0xFF808080, 0xFF6A6A6A, 0xFF555555),
            "Ocean" to listOf(0xFF0D47A1, 0xFF1976D2, 0xFF42A5F5, 0xFF80DEEA, 0xFF26A69A),
        )
        val ACCENTS: List<Long> = listOf(
            0xFFFF4500, 0xFF2196F3, 0xFF4CAF50, 0xFF9C27B0, 0xFFE91E63, 0xFF009688, 0xFFFF9800, 0xFF607D8B,
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "relish_settings")

@Singleton
class PreferencesDataStore @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) {

    private val store = context.dataStore

    private object K {
        val theme = stringPreferencesKey("theme")
        val lastDark = stringPreferencesKey("last_dark_theme")
        val accent = longPreferencesKey("accent_color")
        val depthColors = stringPreferencesKey("depth_colors")
        val fontSize = stringPreferencesKey("font_size")
        val compact = booleanPreferencesKey("compact_mode")
        val thumbnails = stringPreferencesKey("thumbnails")
        val layout = stringPreferencesKey("post_layout")
        val autoMute = booleanPreferencesKey("auto_mute_videos")
        val loop = booleanPreferencesKey("loop_videos")
        val internalMedia = booleanPreferencesKey("internal_media_viewer")
        val internalBrowser = booleanPreferencesKey("internal_browser")
        val inlinePreviews = booleanPreferencesKey("inline_previews")
        val showNsfw = booleanPreferencesKey("show_nsfw")
        val blurNsfw = booleanPreferencesKey("blur_nsfw")
        val bypassAge = booleanPreferencesKey("bypass_age_prompt")
        val blurSpoilers = booleanPreferencesKey("blur_spoilers")
        val routing = stringPreferencesKey("content_routing")
        val markRead = booleanPreferencesKey("mark_read")
        val hideRead = booleanPreferencesKey("hide_read")
        val searchHistory = booleanPreferencesKey("save_search_history")
        val defaultSort = stringPreferencesKey("default_sort")
        val commentSort = stringPreferencesKey("default_comment_sort")
        val startup = stringPreferencesKey("startup_subreddit")
        val demo = booleanPreferencesKey("demo_mode")
        val compress = booleanPreferencesKey("compress_comments")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /**
     * Synchronous snapshot for code that can't suspend (OkHttp interceptors, first frame).
     * Seeded with one blocking read so the theme/startup subreddit are right on cold start.
     */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { settings.first() })

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enumOr(this[K.theme], d.theme),
            lastDarkTheme = enumOr(this[K.lastDark], d.lastDarkTheme),
            accentColor = this[K.accent] ?: d.accentColor,
            depthColors = this[K.depthColors]?.let(::parseColors)?.takeIf { it.isNotEmpty() } ?: d.depthColors,
            fontSize = enumOr(this[K.fontSize], d.fontSize),
            compactMode = this[K.compact] ?: d.compactMode,
            thumbnails = enumOr(this[K.thumbnails], d.thumbnails),
            postLayout = enumOr(this[K.layout], d.postLayout),
            autoMuteVideos = this[K.autoMute] ?: d.autoMuteVideos,
            loopVideos = this[K.loop] ?: d.loopVideos,
            internalMediaViewer = this[K.internalMedia] ?: d.internalMediaViewer,
            internalBrowser = this[K.internalBrowser] ?: d.internalBrowser,
            inlinePreviewsInCards = this[K.inlinePreviews] ?: d.inlinePreviewsInCards,
            showNsfw = this[K.showNsfw] ?: d.showNsfw,
            blurNsfw = this[K.blurNsfw] ?: d.blurNsfw,
            bypassAgePrompt = this[K.bypassAge] ?: d.bypassAgePrompt,
            blurSpoilers = this[K.blurSpoilers] ?: d.blurSpoilers,
            routing = enumOr(this[K.routing], d.routing),
            markRead = this[K.markRead] ?: d.markRead,
            hideReadPosts = this[K.hideRead] ?: d.hideReadPosts,
            saveSearchHistory = this[K.searchHistory] ?: d.saveSearchHistory,
            defaultSort = enumOr(this[K.defaultSort], d.defaultSort),
            defaultCommentSort = enumOr(this[K.commentSort], d.defaultCommentSort),
            startupSubreddit = this[K.startup] ?: d.startupSubreddit,
            demoMode = this[K.demo] ?: d.demoMode,
            compressComments = this[K.compress] ?: d.compressComments,
        )
    }

    suspend fun setTheme(v: ThemeMode) = store.edit {
        it[K.theme] = v.name
        if (v == ThemeMode.DARK || v == ThemeMode.PURE_BLACK) it[K.lastDark] = v.name
    }

    /** Overflow-menu "Night mode": flips between light and the last dark variant used. */
    suspend fun toggleNightMode(systemIsDark: Boolean) = store.edit {
        val mode = enumOr(it[K.theme], ThemeMode.SYSTEM)
        val isDark = when (mode) {
            ThemeMode.SYSTEM -> systemIsDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
        }
        it[K.theme] = if (isDark) ThemeMode.LIGHT.name else (it[K.lastDark] ?: ThemeMode.DARK.name)
    }

    suspend fun setAccent(v: Long) = store.edit { it[K.accent] = v }
    suspend fun setDepthColors(v: List<Long>) = store.edit { it[K.depthColors] = v.joinToString(",") { c -> "%08X".format(c) } }
    suspend fun setFontSize(v: FontSize) = store.edit { it[K.fontSize] = v.name }
    suspend fun setCompactMode(v: Boolean) = store.edit { it[K.compact] = v }
    suspend fun setThumbnails(v: ThumbnailMode) = store.edit { it[K.thumbnails] = v.name }
    suspend fun setPostLayout(v: PostLayout) = store.edit { it[K.layout] = v.name }
    suspend fun setAutoMute(v: Boolean) = store.edit { it[K.autoMute] = v }
    suspend fun setLoopVideos(v: Boolean) = store.edit { it[K.loop] = v }
    suspend fun setInternalMediaViewer(v: Boolean) = store.edit { it[K.internalMedia] = v }
    suspend fun setInternalBrowser(v: Boolean) = store.edit { it[K.internalBrowser] = v }
    suspend fun setInlinePreviews(v: Boolean) = store.edit { it[K.inlinePreviews] = v }
    suspend fun setShowNsfw(v: Boolean) = store.edit { it[K.showNsfw] = v }
    suspend fun setBlurNsfw(v: Boolean) = store.edit { it[K.blurNsfw] = v }
    suspend fun setBypassAgePrompt(v: Boolean) = store.edit { it[K.bypassAge] = v }
    suspend fun setBlurSpoilers(v: Boolean) = store.edit { it[K.blurSpoilers] = v }
    suspend fun setRouting(v: ContentRouting) = store.edit { it[K.routing] = v.name }
    suspend fun setMarkRead(v: Boolean) = store.edit { it[K.markRead] = v }
    suspend fun setHideRead(v: Boolean) = store.edit { it[K.hideRead] = v }
    suspend fun setSaveSearchHistory(v: Boolean) = store.edit { it[K.searchHistory] = v }
    suspend fun setDefaultSort(v: Sort) = store.edit { it[K.defaultSort] = v.name }
    suspend fun setDefaultCommentSort(v: CommentSort) = store.edit { it[K.commentSort] = v.name }
    suspend fun setCompressComments(v: Boolean) = store.edit { it[K.compress] = v }
    suspend fun setDemoMode(v: Boolean) = store.edit { it[K.demo] = v }
    suspend fun setStartupSubreddit(v: String) = store.edit { it[K.startup] = v.trim().removePrefix("r/") }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    companion object {
        /** Parses "#RRGGBB, AARRGGBB, ..." into ARGB longs; invalid entries are skipped. */
        fun parseColors(raw: String): List<Long> = raw.split(',', ' ', '\n')
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .mapNotNull { hex ->
                when (hex.length) {
                    6 -> hex.toLongOrNull(16)?.or(0xFF000000)
                    8 -> hex.toLongOrNull(16)
                    else -> null
                }
            }
    }
}
