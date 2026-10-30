package app.relish.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.relish.data.model.Message
import app.relish.data.repo.RedditRepository
import app.relish.ui.common.HtmlText
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish
import app.relish.util.timeAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InboxTab(val where: String, val label: String) {
    ALL("inbox", "All"), UNREAD("unread", "Unread"), MESSAGES("messages", "Messages"),
    COMMENTS("comments", "Comment replies"), POSTS("selfreply", "Post replies"), MENTIONS("mentions", "Mentions"), SENT("sent", "Sent"),
}

data class InboxState(
    val tab: InboxTab = InboxTab.ALL,
    val items: List<Message> = emptyList(),
    val after: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class InboxViewModel @Inject constructor(private val repo: RedditRepository) : ViewModel() {
    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()

    init { load(reset = true) }

    fun setTab(tab: InboxTab) {
        _state.update { InboxState(tab = tab) }
        load(reset = true)
    }

    fun load(reset: Boolean) = viewModelScope.launch {
        val s = _state.value
        if (!reset && (s.after == null || s.loading)) return@launch
        _state.update { it.copy(loading = true, error = null) }
        runCatching { repo.inbox(s.tab.where, if (reset) null else s.after) }
            .onSuccess { page -> _state.update { it.copy(items = if (reset) page.items else it.items + page.items, after = page.after, loading = false) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = RedditRepository.friendlyError(e)) } }
    }

    fun open(message: Message) {
        if (message.isNew) viewModelScope.launch {
            runCatching { repo.markMessageRead(message.fullname) }
            _state.update { s -> s.copy(items = s.items.map { if (it.fullname == message.fullname) it.copy(isNew = false) else it }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: InboxViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text("Inbox") },
                )
                PrimaryScrollableTabRow(selectedTabIndex = s.tab.ordinal, edgePadding = 8.dp) {
                    InboxTab.entries.forEach { t ->
                        Tab(selected = s.tab == t, onClick = { vm.setTab(t) }, text = { Text(t.label) })
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = s.loading && s.items.isNotEmpty(), onRefresh = { vm.load(reset = true) }, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.items, key = { it.fullname }) { m ->
                    LaunchedEffect(m.fullname) { if (m == s.items.lastOrNull()) vm.load(reset = false) }
                    MessageRow(m, onLinkClick = nav::openLink) {
                        vm.open(m)
                        val ctx = m.context
                        if (m.wasComment && ctx != null) nav.openLink(ctx)
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { vm.load(reset = true) }) { Text("Retry") }
                            }
                            s.loading -> CircularProgressIndicator()
                            s.items.isEmpty() -> Text("Nothing here", color = Relish.colors.subtle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: Message, onLinkClick: (String) -> Unit, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            m.subject + (m.subreddit?.let { " · r/$it" } ?: ""),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (m.isNew) FontWeight.Bold else FontWeight.Normal,
            color = if (m.isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text("from u/${m.author} · ${timeAgo(m.createdUtc)}", style = MetaTextStyle, color = Relish.colors.subtle)
        Spacer(Modifier.height(4.dp))
        HtmlText(m.bodyHtml, m.body, onLinkClick, maxLines = 8)
    }
    HorizontalDivider(thickness = 0.5.dp)
}
