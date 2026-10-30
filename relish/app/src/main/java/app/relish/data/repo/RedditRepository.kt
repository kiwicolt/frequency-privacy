package app.relish.data.repo

import android.content.Context
import android.net.Uri
import app.relish.data.local.FilterDao
import app.relish.data.local.FilterEntity
import app.relish.data.local.FilterType
import app.relish.data.local.HiddenPostDao
import app.relish.data.local.HiddenPostEntity
import app.relish.data.local.NsfwShortcutDao
import app.relish.data.local.NsfwShortcutEntity
import app.relish.data.local.ReadPostDao
import app.relish.data.local.ReadPostEntity
import app.relish.data.local.SearchHistoryDao
import app.relish.data.local.SearchHistoryEntity
import app.relish.data.local.SubscriptionDao
import app.relish.data.local.SubscriptionEntity
import app.relish.data.model.Account
import app.relish.data.model.Comment
import app.relish.data.model.CommentNode
import app.relish.data.model.CommentSort
import app.relish.data.model.FeedItem
import app.relish.data.model.FeedSource
import app.relish.data.model.Listing
import app.relish.data.model.Message
import app.relish.data.model.MoreComments
import app.relish.data.model.Multireddit
import app.relish.data.model.Post
import app.relish.data.model.PostWithComments
import app.relish.data.model.Sort
import app.relish.data.model.Subreddit
import app.relish.data.model.TimeFilter
import app.relish.data.model.VoteDir
import app.relish.data.prefs.AppSettings
import app.relish.data.prefs.ContentRouting
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.SecureStore
import app.relish.data.remote.OAuthApi
import app.relish.data.remote.OAuthException
import app.relish.data.remote.OAuthManager
import app.relish.data.remote.PublicApi
import app.relish.data.remote.RedditApiService
import app.relish.data.remote.RedditApiService.Companion.NO_CACHE
import app.relish.data.remote.RedditParser
import app.relish.data.remote.RedgifsClient
import app.relish.di.ApplicationScope
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Cache
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class NotLoggedInException(action: String) : IllegalStateException("Log in to $action")

/** Both transports refused a read; carries both causes so the UI can show what actually happened. */
class BothRoutesFailedException(val web: Throwable, val oauth: Throwable) : IOException("Both routes failed")

private fun isWebRefusal(e: Throwable) =
    (e is HttpException && e.code() in setOf(401, 403, 429)) || e is SerializationException || e is IllegalArgumentException

@Singleton
class RedditRepository @Inject constructor(
    @OAuthApi private val oauthApi: RedditApiService,
    @PublicApi private val publicApi: RedditApiService,
    private val oauth: OAuthManager,
    private val secureStore: SecureStore,
    private val prefs: PreferencesDataStore,
    private val readDao: ReadPostDao,
    private val hiddenDao: HiddenPostDao,
    private val filterDao: FilterDao,
    private val searchDao: SearchHistoryDao,
    private val subscriptionDao: SubscriptionDao,
    private val shortcutDao: NsfwShortcutDao,
    private val redgifs: RedgifsClient,
    private val httpCache: Cache,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    init {
        scope.launch { readDao.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(180)) }
    }

    val username: StateFlow<String?> = secureStore.username
    val isLoggedIn: Boolean get() = oauth.isLoggedIn

    // =========================================================================================
    // Hybrid routing
    // =========================================================================================

    /**
     * Runs a read-only call on the right transport.
     *
     * @param personal the result depends on the logged-in account (front page, saved, own profile);
     *        prefer OAuth for it whenever we have a user token, regardless of routing mode.
     * @param isEmpty lets the caller declare a result "suspiciously empty" — OAuth silently drops
     *        NSFW items for accounts without the over_18 pref, so an empty listing from OAuth for
     *        a subreddit/search triggers a retry on the public `.json` transport.
     */
    private suspend fun <T> browse(
        personal: Boolean = false,
        isEmpty: (T) -> Boolean = { false },
        call: suspend RedditApiService.() -> T,
    ): T = withContext(Dispatchers.IO) {
        val s = prefs.current.value
        val loggedIn = oauth.isLoggedIn
        val useOAuth = when (s.routing) {
            ContentRouting.OAUTH_ONLY -> true
            ContentRouting.PUBLIC_JSON -> personal && loggedIn
            ContentRouting.AUTO -> loggedIn
        }
        if (!useOAuth) {
            return@withContext try {
                publicApi.call()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Web JSON refused (403/429) or answered with an HTML block page: use OAuth with an
                // application-only token instead. NSFW may be filtered there, but the feed loads.
                if (s.routing == ContentRouting.PUBLIC_JSON || !oauth.hasClientId || !isWebRefusal(e)) throw e
                try {
                    oauthApi.call()
                } catch (o: Exception) {
                    if (o is kotlinx.coroutines.CancellationException) throw o
                    throw BothRoutesFailedException(e, o)
                }
            }
        }

        val canFallBack = s.routing != ContentRouting.OAUTH_ONLY && s.showNsfw
        try {
            val result = oauthApi.call()
            if (canFallBack && isEmpty(result)) runCatching { publicApi.call() }.getOrDefault(result) else result
        } catch (e: HttpException) {
            if (canFallBack && e.code() in NSFW_REFUSAL_CODES) publicApi.call() else throw e
        } catch (e: OAuthException) {
            if (s.routing != ContentRouting.OAUTH_ONLY) publicApi.call() else throw e
        }
    }

    /** Account actions always use OAuth with a user token. */
    private suspend fun <T> account(action: String, call: suspend RedditApiService.() -> T): T {
        if (!oauth.isLoggedIn) throw NotLoggedInException(action)
        return withContext(Dispatchers.IO) { oauthApi.call() }
    }

    // =========================================================================================
    // Feeds, posts, comments
    // =========================================================================================

    suspend fun feed(
        source: FeedSource,
        sort: Sort,
        time: TimeFilter,
        after: String?,
        refresh: Boolean,
    ): Listing<FeedItem> {
        val cache = if (refresh) NO_CACHE else null
        val t = if (sort.hasTime) time.param else null
        val emptyListing: (kotlinx.serialization.json.JsonElement) -> Boolean = {
            after == null && RedditParser.feedItems(it).items.isEmpty()
        }
        val json = when (source) {
            FeedSource.FrontPage -> browse(personal = true) { frontPage(sort.path, t, after, cacheControl = cache) }
            is FeedSource.Subreddit -> browse(isEmpty = emptyListing) { subreddit(source.name, sort.path, t, after, cacheControl = cache) }
            is FeedSource.Multi -> browse(personal = true, isEmpty = emptyListing) {
                multireddit(source.owner, source.name, sort.path, t, after, cacheControl = cache)
            }
            is FeedSource.User -> browse(personal = source.name.equals(username.value, true)) {
                user(source.name, source.where, sort.path, t, after, cacheControl = cache)
            }
            is FeedSource.Saved -> account("view saved items") { user(source.user, "saved", after = after, cacheControl = cache) }
            is FeedSource.Search -> {
                val searchSort = if (sort == Sort.HOT) "relevance" else sort.path
                browse(isEmpty = emptyListing) {
                    if (source.subreddit != null) searchSubreddit(source.subreddit, source.query, sort = searchSort, time = t, after = after, cacheControl = cache)
                    else search(source.query, searchSort, t, after, cacheControl = cache)
                }
            }
        }
        return RedditParser.feedItems(json)
    }

    suspend fun postWithComments(postId: String, sort: CommentSort, focusCommentId: String?, refresh: Boolean): PostWithComments {
        val cache = if (refresh) NO_CACHE else null
        val json = browse {
            if (focusCommentId != null) commentThread(postId, focusCommentId, sort = sort.param, cacheControl = cache)
            else comments(postId, sort.param, cacheControl = cache)
        }
        return RedditParser.postWithComments(json)
    }

    suspend fun moreChildren(linkFullname: String, more: MoreComments, sort: CommentSort): List<CommentNode> {
        // The endpoint accepts at most 100 ids per call.
        return more.children.chunked(100).flatMap { chunk ->
            val json = browse { this.moreChildren(linkFullname, chunk.joinToString(","), sort.param) }
            RedditParser.moreChildren(json, more.depth)
        }
    }

    suspend fun searchSubreddits(query: String): List<Subreddit> =
        RedditParser.subreddits(browse { this.searchSubreddits(query) }).items

    suspend fun subredditAbout(name: String): Subreddit = RedditParser.subredditAbout(browse { this.subredditAbout(name) })

    suspend fun resolveRedgifs(id: String): String = redgifs.resolve(id)

    // =========================================================================================
    // Account
    // =========================================================================================

    fun loginUrl(): String {
        if (!oauth.hasClientId) throw OAuthException("Add your Reddit app client id in Settings › Account first")
        return oauth.authorizeUrl()
    }

    fun isOAuthRedirect(url: String) = oauth.isRedirect(url)

    suspend fun completeLogin(redirect: Uri): Account = withContext(Dispatchers.IO) {
        oauth.completeLogin(redirect)
        val me = RedditParser.account(oauthApi.me())
        secureStore.setUsername(me.name)
        clearHttpCache()
        if (prefs.current.value.showNsfw) enableAccountNsfw()
        runCatching { syncSubscriptions() }
        me
    }

    /** Turns on the account-level prefs that stop OAuth listings from dropping NSFW posts. */
    suspend fun enableAccountNsfw() = account("change account preferences") {
        updatePrefs(buildJsonObject {
            put("over_18", JsonPrimitive(true))
            put("search_include_over_18", JsonPrimitive(true))
            put("label_nsfw", JsonPrimitive(true))
        })
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        oauth.logout()
        subscriptionDao.replaceAccount(emptyList())
        clearHttpCache()
    }

    suspend fun account(): Account? =
        if (!oauth.isLoggedIn) null else runCatching { RedditParser.account(account("view your profile") { me() }) }.getOrNull()

    suspend fun syncSubscriptions() {
        if (!oauth.isLoggedIn) return
        val all = mutableListOf<Subreddit>()
        var after: String? = null
        var pages = 0
        do {
            val page = RedditParser.subreddits(account("sync subscriptions") { mySubscriptions(after) })
            all += page.items
            after = page.after
        } while (after != null && ++pages < 20)
        subscriptionDao.replaceAccount(all.map { SubscriptionEntity(it.name, local = false, over18 = it.over18, iconUrl = it.iconUrl) })
    }

    suspend fun multireddits(): List<Multireddit> =
        if (!oauth.isLoggedIn) emptyList() else RedditParser.multis(account("view multireddits") { myMultireddits() })

    suspend fun inbox(where: String, after: String?): Listing<Message> =
        RedditParser.messages(account("read your inbox") { this.inbox(where, after) })

    suspend fun markMessageRead(fullname: String) {
        account("mark messages read") { readMessage(fullname) }
    }

    // =========================================================================================
    // Actions
    // =========================================================================================

    suspend fun vote(fullname: String, dir: VoteDir) {
        account("vote") { this.vote(fullname, dir.value) }.throwIfFailed()
    }

    suspend fun setSaved(fullname: String, saved: Boolean) {
        account("save") { if (saved) save(fullname) else unsave(fullname) }.throwIfFailed()
    }

    /** Hidden posts are always recorded locally; when logged in they're hidden on the account too. */
    suspend fun setHidden(fullname: String, hidden: Boolean) {
        if (hidden) hiddenDao.insert(HiddenPostEntity(fullname)) else hiddenDao.delete(fullname)
        if (oauth.isLoggedIn) runCatching { account("hide") { if (hidden) hide(fullname) else unhide(fullname) } }
    }

    suspend fun reply(parentFullname: String, markdown: String, depth: Int): Comment? =
        RedditParser.createdComment(account("comment") { comment(parentFullname, markdown) }, depth)

    /**
     * Logged in: subscribes on the account. Logged out (or [localOnly]): keeps a device-only
     * subscription so anonymous NSFW browsing still gets a drawer list.
     */
    suspend fun setSubscribed(name: String, subscribed: Boolean, over18: Boolean, localOnly: Boolean = false) {
        val clean = name.trim().removePrefix("r/").removePrefix("/r/")
        if (oauth.isLoggedIn && !localOnly) {
            account("subscribe") { subscribe(if (subscribed) "sub" else "unsub", clean) }.throwIfFailed()
        }
        if (subscribed) subscriptionDao.upsert(SubscriptionEntity(clean, local = !oauth.isLoggedIn || localOnly, over18 = over18))
        else subscriptionDao.delete(clean)
    }

    fun subscriptions(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll().map { rows ->
        rows.distinctBy { it.name.lowercase() }
    }

    // =========================================================================================
    // Local state: read marks, filters, history, shortcuts
    // =========================================================================================

    fun readPosts(): Flow<Set<String>> = readDao.observeAll().map { it.toHashSet() }
    fun hiddenPosts(): Flow<Set<String>> = hiddenDao.observeAll().map { it.toHashSet() }
    suspend fun markRead(fullname: String) = readDao.insert(ReadPostEntity(fullname))
    suspend fun clearReadHistory() { readDao.clear(); hiddenDao.clear() }

    fun filters(): Flow<List<FilterEntity>> = filterDao.observeAll()
    suspend fun addFilter(type: FilterType, value: String) {
        val v = value.trim().lowercase().removePrefix("r/").removePrefix("u/").removePrefix("/r/").removePrefix("/u/")
        if (v.isNotEmpty()) filterDao.insert(FilterEntity(type = type, value = v))
    }
    suspend fun deleteFilter(id: Long) = filterDao.delete(id)

    fun searchHistory(): Flow<List<String>> = searchDao.observeRecent().map { list -> list.map { it.query } }
    suspend fun recordSearch(query: String) {
        if (prefs.current.value.saveSearchHistory && query.isNotBlank()) searchDao.upsert(SearchHistoryEntity(query.trim()))
    }
    suspend fun deleteSearch(query: String) = searchDao.delete(query)
    suspend fun clearSearchHistory() = searchDao.clear()

    fun nsfwShortcuts(): Flow<List<String>> = shortcutDao.observeAll().map { list -> list.map { it.name } }
    suspend fun addNsfwShortcut(name: String) {
        val clean = name.trim().removePrefix("r/").removePrefix("/r/")
        if (clean.isNotEmpty()) shortcutDao.upsert(NsfwShortcutEntity(clean, position = Int.MAX_VALUE / 2))
    }
    suspend fun removeNsfwShortcut(name: String) = shortcutDao.delete(name)

    suspend fun clearHttpCache() = withContext(Dispatchers.IO) { runCatching { httpCache.evictAll() } }

    /** Clears API responses plus Coil's image memory/disk caches. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearAllCaches() = withContext(Dispatchers.IO) {
        clearHttpCache()
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
    }

    private fun retrofit2.Response<Unit>.throwIfFailed() {
        if (!isSuccessful) throw HttpException(this)
    }

    companion object {
        /** Responses OAuth gives for NSFW/quarantined/gated content it won't serve to third-party apps. */
        private val NSFW_REFUSAL_CODES = setOf(403, 404, 451)

        /** Decides whether a post survives the NSFW master switch, block lists and hide/read state. */
        fun isVisible(
            post: Post,
            settings: AppSettings,
            filters: List<FilterEntity>,
            hidden: Set<String>,
            read: Set<String>,
            showingSubreddit: String?,
        ): Boolean {
            if (post.over18 && !settings.showNsfw) return false
            if (post.fullname in hidden || post.hidden) return false
            if (settings.hideReadPosts && post.fullname in read) return false
            val title = post.title.lowercase()
            return filters.none { f ->
                when (f.type) {
                    FilterType.KEYWORD -> title.contains(f.value)
                    // Don't hide a filtered sub's posts when the user deliberately opened that sub.
                    FilterType.SUBREDDIT -> post.subreddit.equals(f.value, true) && !post.subreddit.equals(showingSubreddit, true)
                    FilterType.USER -> post.author.equals(f.value, true)
                    FilterType.DOMAIN -> post.domain.lowercase().let { it == f.value || it.endsWith(".${f.value}") }
                }
            }
        }

        fun friendlyError(e: Throwable): String = when (e) {
            is BothRoutesFailedException ->
                "Reddit refused anonymous web access (${friendlyError(e.web)}), and the OAuth fallback failed: ${friendlyError(e.oauth)}"
            is NotLoggedInException -> e.message ?: "Log in first"
            is OAuthException -> e.message ?: "Authentication failed"
            is HttpException -> when (e.code()) {
                401 -> "Not authorised (401). Try logging in again."
                403 -> "Forbidden (403)"
                404 -> "Not found (404)"
                429 -> "Rate limited by Reddit (429). Slow down a little."
                in 500..599 -> "Reddit is having trouble (${e.code()})"
                else -> "HTTP ${e.code()}"
            }
            is SerializationException, is IllegalArgumentException -> "Reddit returned a block page instead of data"
            is IOException -> "Network error: ${e.message ?: "check your connection"}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
