package app.relish.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.relish.data.model.FeedSource
import app.relish.data.model.Subreddit
import app.relish.data.repo.RedditRepository
import app.relish.ui.feed.Badge
import app.relish.ui.nav.LocalMessenger
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish
import app.relish.util.compactCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchMode { POSTS, SUBREDDITS }

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: RedditRepository,
) : ViewModel() {
    val restrictTo: String? = savedState.get<String>("sub")
    val history: StateFlow<List<String>> = repo.searchHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _subs = MutableStateFlow<List<Subreddit>?>(null)
    val subs: StateFlow<List<Subreddit>?> = _subs.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun record(q: String) = viewModelScope.launch { repo.recordSearch(q) }
    fun deleteHistory(q: String) = viewModelScope.launch { repo.deleteSearch(q) }

    fun searchSubreddits(q: String) = viewModelScope.launch {
        record(q)
        _loading.value = true
        _error.value = null
        runCatching { repo.searchSubreddits(q) }
            .onSuccess { _subs.value = it }
            .onFailure { _error.value = RedditRepository.friendlyError(it) }
        _loading.value = false
    }

    fun subscribeLocal(sub: Subreddit) = viewModelScope.launch { repo.setSubscribed(sub.name, true, sub.over18) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: SearchViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val messenger = LocalMessenger.current
    val history by vm.history.collectAsStateWithLifecycle()
    val subs by vm.subs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.POSTS) }
    var restrict by remember { mutableStateOf(vm.restrictTo != null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        when (mode) {
            SearchMode.POSTS -> {
                vm.record(text)
                nav.openFeed(FeedSource.Search(text, if (restrict) vm.restrictTo else null))
            }
            SearchMode.SUBREDDITS -> vm.searchSubreddits(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (mode == SearchMode.POSTS) "Search posts" else "Search subreddits") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    IconButton(onClick = { submit() }) { Icon(Icons.Default.Search, "Search") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = mode == SearchMode.POSTS, onClick = { mode = SearchMode.POSTS }, label = { Text("Posts") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = mode == SearchMode.SUBREDDITS, onClick = { mode = SearchMode.SUBREDDITS }, label = { Text("Subreddits") })
                    vm.restrictTo?.let { sub ->
                        if (mode == SearchMode.POSTS) {
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = restrict, onClick = { restrict = !restrict }, label = { Text("Only r/$sub") })
                        }
                    }
                }
            }
            item { Text("NSFW results included (include_over_18=on)", style = MetaTextStyle, color = Relish.colors.subtle, modifier = Modifier.padding(horizontal = 16.dp)) }

            if (mode == SearchMode.SUBREDDITS && (subs != null || loading || error != null)) {
                if (loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                items(subs.orEmpty(), key = { it.name }) { sub ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.openSubreddit(sub.name) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("r/${sub.name}", style = MaterialTheme.typography.titleSmall)
                                if (sub.over18) { Spacer(Modifier.width(6.dp)); Badge("NSFW", Relish.colors.nsfw) }
                            }
                            Text("${compactCount(sub.subscribers)} members · ${sub.publicDescription}", style = MetaTextStyle, color = Relish.colors.subtle, maxLines = 2)
                        }
                        IconButton(onClick = { vm.subscribeLocal(sub); messenger("Subscribed to r/${sub.name}") }) {
                            Icon(Icons.Default.Add, "Subscribe")
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            } else {
                if (history.isNotEmpty()) item {
                    Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                }
                items(history, key = { "h-$it" }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { submit(h) }.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, null, tint = Relish.colors.subtle)
                        Spacer(Modifier.width(12.dp))
                        Text(h, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.deleteHistory(h) }) { Icon(Icons.Default.Close, "Remove", tint = Relish.colors.subtle) }
                    }
                }
            }
        }
    }
}
