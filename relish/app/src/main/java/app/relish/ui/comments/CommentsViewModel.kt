package app.relish.ui.comments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.relish.data.model.Comment
import app.relish.data.model.CommentNode
import app.relish.data.model.CommentSort
import app.relish.data.model.MoreComments
import app.relish.data.model.Post
import app.relish.data.model.VoteDir
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.repo.RedditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommentRow(val node: CommentNode, val collapsed: Boolean, val hiddenChildren: Int)

data class CommentsState(
    val post: Post? = null,
    val nodes: List<CommentNode> = emptyList(),
    val collapsed: Set<String> = emptySet(),
    val selected: String? = null,
    val sort: CommentSort = CommentSort.BEST,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val loadingMore: Set<String> = emptySet(),
    val focusCommentId: String? = null,
)

data class CommentsUi(val state: CommentsState, val rows: List<CommentRow>)

@HiltViewModel
class CommentsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: RedditRepository,
    private val prefs: PreferencesDataStore,
) : ViewModel() {

    val postId: String = checkNotNull(savedState.get<String>("postId")).removePrefix("t3_")
    private val focus: String? = savedState.get<String>("focus")?.removePrefix("t1_")

    private val state = MutableStateFlow(CommentsState(sort = prefs.current.value.defaultCommentSort, focusCommentId = focus))

    val ui: StateFlow<CommentsUi> = state
        .map { CommentsUi(it, visibleRows(it.nodes, it.collapsed)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommentsUi(state.value, emptyList()))

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { load(refresh = false) }

    fun setSort(sort: CommentSort) {
        state.update { it.copy(sort = sort) }
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) = viewModelScope.launch {
        state.update { it.copy(loading = it.post == null, refreshing = refresh, error = null) }
        runCatching { repo.postWithComments(postId, state.value.sort, focus, refresh) }
            .onSuccess { res ->
                state.update { it.copy(post = res.post, nodes = res.comments, loading = false, refreshing = false, collapsed = emptySet()) }
                if (prefs.current.value.markRead) repo.markRead(res.post.fullname)
            }
            .onFailure { e -> state.update { it.copy(loading = false, refreshing = false, error = RedditRepository.friendlyError(e)) } }
    }

    fun select(fullname: String) = state.update { it.copy(selected = if (it.selected == fullname) null else fullname) }

    fun toggleCollapse(fullname: String) = state.update {
        it.copy(collapsed = if (fullname in it.collapsed) it.collapsed - fullname else it.collapsed + fullname, selected = null)
    }

    fun loadMore(more: MoreComments) {
        val post = state.value.post ?: return
        if (more.fullname in state.value.loadingMore) return
        state.update { it.copy(loadingMore = it.loadingMore + more.fullname) }
        viewModelScope.launch {
            runCatching { repo.moreChildren(post.fullname, more, state.value.sort) }
                .onSuccess { children ->
                    state.update { s ->
                        val idx = s.nodes.indexOfFirst { it.fullname == more.fullname }
                        if (idx < 0) return@update s.copy(loadingMore = s.loadingMore - more.fullname)
                        val existing = s.nodes.mapTo(HashSet()) { it.fullname }
                        val fresh = children.filter { it.fullname !in existing }
                        s.copy(
                            nodes = s.nodes.subList(0, idx) + fresh + s.nodes.subList(idx + 1, s.nodes.size),
                            loadingMore = s.loadingMore - more.fullname,
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(loadingMore = it.loadingMore - more.fullname) }
                    _events.send(RedditRepository.friendlyError(e))
                }
        }
    }

    fun votePost(dir: VoteDir) {
        val post = state.value.post ?: return
        val newDir = if (post.vote == dir) VoteDir.NONE else dir
        state.update { it.copy(post = post.copy(vote = newDir, score = post.score - post.vote.value + newDir.value)) }
        viewModelScope.launch {
            runCatching { repo.vote(post.fullname, newDir) }.onFailure {
                state.update { s -> s.copy(post = post) }
                _events.send(RedditRepository.friendlyError(it))
            }
        }
    }

    fun toggleSavePost() {
        val post = state.value.post ?: return
        state.update { it.copy(post = post.copy(saved = !post.saved)) }
        viewModelScope.launch {
            runCatching { repo.setSaved(post.fullname, !post.saved) }.onFailure {
                state.update { s -> s.copy(post = post) }
                _events.send(RedditRepository.friendlyError(it))
            }
        }
    }

    fun voteComment(comment: Comment, dir: VoteDir) {
        val newDir = if (comment.vote == dir) VoteDir.NONE else dir
        replace(comment.copy(vote = newDir, score = comment.score - comment.vote.value + newDir.value))
        viewModelScope.launch {
            runCatching { repo.vote(comment.fullname, newDir) }.onFailure {
                replace(comment)
                _events.send(RedditRepository.friendlyError(it))
            }
        }
    }

    /** Posts a reply and inserts it directly beneath its parent. Returns via [onDone] for the dialog. */
    fun reply(parentFullname: String, text: String, onDone: (Boolean) -> Unit) {
        val parentDepth = state.value.nodes.firstOrNull { it.fullname == parentFullname }?.depth ?: -1
        viewModelScope.launch {
            runCatching { repo.reply(parentFullname, text, parentDepth + 1) }
                .onSuccess { created ->
                    if (created != null) state.update { s ->
                        val idx = s.nodes.indexOfFirst { it.fullname == parentFullname }
                        val nodes = if (idx < 0) listOf<CommentNode>(created) + s.nodes
                        else s.nodes.subList(0, idx + 1) + created + s.nodes.subList(idx + 1, s.nodes.size)
                        s.copy(nodes = nodes, collapsed = s.collapsed - parentFullname)
                    }
                    _events.send("Reply posted")
                    onDone(true)
                }
                .onFailure {
                    _events.send(RedditRepository.friendlyError(it))
                    onDone(false)
                }
        }
    }

    private fun replace(comment: Comment) = state.update { s ->
        s.copy(nodes = s.nodes.map { if (it.fullname == comment.fullname) comment else it })
    }

    companion object {
        /** Pre-order walk that hides the subtrees of collapsed comments. */
        fun visibleRows(nodes: List<CommentNode>, collapsed: Set<String>): List<CommentRow> {
            val out = ArrayList<CommentRow>(nodes.size)
            var skipDeeperThan = Int.MAX_VALUE
            for (i in nodes.indices) {
                val n = nodes[i]
                if (n.depth > skipDeeperThan) continue
                skipDeeperThan = Int.MAX_VALUE
                if (n is Comment && n.fullname in collapsed) {
                    var hidden = 0
                    var j = i + 1
                    while (j < nodes.size && nodes[j].depth > n.depth) {
                        val c = nodes[j]
                        hidden += if (c is MoreComments) c.count else 1
                        j++
                    }
                    out += CommentRow(n, collapsed = true, hiddenChildren = hidden)
                    skipDeeperThan = n.depth
                } else {
                    out += CommentRow(n, collapsed = false, hiddenChildren = 0)
                }
            }
            return out
        }
    }
}
