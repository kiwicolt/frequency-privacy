package app.relish.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.relish.data.local.FilterType
import app.relish.data.model.FeedItem
import app.relish.data.model.FeedSource
import app.relish.data.model.Post
import app.relish.data.model.Sort
import app.relish.data.model.Subreddit
import app.relish.data.model.TimeFilter
import app.relish.data.model.VoteDir
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.ThumbnailMode
import app.relish.data.repo.RedditRepository
import app.relish.util.Connectivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedState(
    val source: FeedSource = FeedSource.FrontPage,
    val sort: Sort = Sort.HOT,
    val time: TimeFilter = TimeFilter.DAY,
    val items: List<FeedItem> = emptyList(),
    val after: String? = null,
    val endReached: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val selectedKey: String? = null,
    val subreddit: Subreddit? = null,
    /** Keys of the first item of page 2, 3, … (RIF's "page N" bars). */
    val pageStarts: List<String> = emptyList(),
)

/** What the screen renders: [FeedState] after NSFW/filters/hide/read rules are applied. */
data class FeedUi(
    val state: FeedState,
    val visible: List<FeedItem>,
    val read: Set<String>,
    val display: PostDisplay,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repo: RedditRepository,
    prefs: PreferencesDataStore,
    connectivity: Connectivity,
) : ViewModel() {

    private val state = MutableStateFlow(FeedState())
    private val settings = prefs.current
    private var initialized = false
    private var loadJob: Job? = null

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val localState = combine(repo.filters(), repo.hiddenPosts(), repo.readPosts(), connectivity.unmetered) { f, h, r, u ->
        LocalState(f, h, r, u)
    }

    private data class LocalState(
        val filters: List<app.relish.data.local.FilterEntity>,
        val hidden: Set<String>,
        val read: Set<String>,
        val unmetered: Boolean,
    )

    val ui: StateFlow<FeedUi> = combine(state, settings, localState) { s, set, local ->
        val showingSub = (s.source as? FeedSource.Subreddit)?.name
        val visible = s.items.filter { item ->
            when (item) {
                is FeedItem.PostEntry -> RedditRepository.isVisible(item.post, set, local.filters, local.hidden, local.read, showingSub)
                is FeedItem.CommentEntry -> local.filters.none { it.type == FilterType.USER && it.value.equals(item.comment.author, true) }
            }
        }
        val src = s.source
        FeedUi(
            state = s,
            visible = visible,
            read = if (set.markRead) local.read else emptySet(),
            display = PostDisplay(
                hideThumbnails = set.thumbnails == ThumbnailMode.NEVER,
                loadThumbnails = set.thumbnails == ThumbnailMode.ALWAYS || (set.thumbnails == ThumbnailMode.WIFI_ONLY && local.unmetered),
                blurNsfw = set.blurNsfw,
                blurSpoilers = set.blurSpoilers,
                largeCards = set.postLayout == app.relish.data.prefs.PostLayout.LARGE_CARDS,
                inlinePreviews = set.inlinePreviewsInCards,
                // Show "r/sub" when the feed mixes subreddits.
                leftHanded = set.leftHanded,
                showFlair = set.showLinkFlair,
                showSubreddit = !(src is FeedSource.Subreddit && !src.name.contains('+') &&
                    !src.name.equals("all", true) && !src.name.equals("popular", true)),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUi(FeedState(), emptyList(), emptySet(), PostDisplay(false, true, false, true, false, true, true)))

    init {
        // Reload when demo mode is switched on or off.
        viewModelScope.launch {
            settings.map { it.demoMode }.distinctUntilChanged().drop(1).collect { if (initialized) refresh() }
        }
    }

    fun init(source: FeedSource) {
        if (initialized) return
        initialized = true
        // Front page opens on Best like RIF; Best only exists there, so elsewhere it means Hot.
        val default = settings.value.defaultSort
        val sort = when {
            source == FeedSource.FrontPage && default == Sort.HOT -> Sort.BEST
            source != FeedSource.FrontPage && default == Sort.BEST -> Sort.HOT
            else -> default
        }
        state.value = FeedState(source = source, sort = sort)
        load(reset = true, refresh = false)
        if (source is FeedSource.Subreddit && !source.name.contains('+') && source.name.lowercase() !in setOf("all", "popular")) {
            viewModelScope.launch {
                runCatching { repo.subredditAbout(source.name) }.onSuccess { sub -> state.update { it.copy(subreddit = sub) } }
            }
        }
    }

    fun setSort(sort: Sort, time: TimeFilter? = null) {
        state.update { it.copy(sort = sort, time = time ?: it.time) }
        load(reset = true, refresh = false)
    }

    fun refresh() = load(reset = true, refresh = true)

    fun loadMore() {
        val s = state.value
        if (s.loading || s.endReached || s.error != null || s.items.isEmpty()) return
        load(reset = false, refresh = false)
    }

    fun retry() {
        state.update { it.copy(error = null) }
        load(reset = state.value.items.isEmpty(), refresh = true)
    }

    private fun load(reset: Boolean, refresh: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = state.value
            state.update { it.copy(loading = true, refreshing = refresh && reset, error = null) }
            runCatching { repo.feed(s.source, s.sort, s.time, if (reset) null else s.after, refresh) }
                .onSuccess { page ->
                    state.update { cur ->
                        val merged = if (reset) page.items else (cur.items + page.items).distinctBy { it.key }
                        val existing = cur.items.mapTo(HashSet()) { it.key }
                        val firstNew = page.items.firstOrNull { it.key !in existing }?.key
                        cur.copy(
                            items = merged,
                            pageStarts = if (reset) emptyList() else cur.pageStarts + listOfNotNull(firstNew),
                            after = page.after,
                            endReached = page.after == null,
                            loading = false,
                            refreshing = false,
                            selectedKey = if (reset) null else cur.selectedKey,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    state.update { it.copy(loading = false, refreshing = false, error = RedditRepository.friendlyError(e)) }
                }
        }
    }

    fun select(key: String) = state.update { it.copy(selectedKey = if (it.selectedKey == key) null else key) }

    fun markRead(post: Post) {
        if (settings.value.markRead) viewModelScope.launch { repo.markRead(post.fullname) }
    }

    fun vote(post: Post, dir: VoteDir) {
        val newDir = if (post.vote == dir) VoteDir.NONE else dir
        val updated = post.copy(vote = newDir, score = post.score - post.vote.value + newDir.value)
        replacePost(updated)
        viewModelScope.launch {
            runCatching { repo.vote(post.fullname, newDir) }.onFailure {
                replacePost(post)
                _events.send(RedditRepository.friendlyError(it))
            }
        }
    }

    fun toggleSave(post: Post) {
        replacePost(post.copy(saved = !post.saved))
        viewModelScope.launch {
            runCatching { repo.setSaved(post.fullname, !post.saved) }
                .onSuccess { _events.send(if (post.saved) "Unsaved" else "Saved") }
                .onFailure { replacePost(post); _events.send(RedditRepository.friendlyError(it)) }
        }
    }

    fun hide(post: Post) {
        viewModelScope.launch {
            repo.setHidden(post.fullname, true)
            _events.send("Post hidden")
        }
        state.update { it.copy(selectedKey = null) }
    }

    fun filterSubreddit(name: String) = viewModelScope.launch {
        repo.addFilter(FilterType.SUBREDDIT, name)
        _events.send("r/$name filtered from mixed feeds")
    }

    private fun replacePost(post: Post) = state.update { s ->
        s.copy(items = s.items.map { if (it is FeedItem.PostEntry && it.post.fullname == post.fullname) FeedItem.PostEntry(post) else it })
    }
}
