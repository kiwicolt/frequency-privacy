package app.relish.ui.feed

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.model.FeedItem
import app.relish.data.model.FeedSource
import app.relish.data.model.Post
import app.relish.data.model.Sort
import app.relish.data.model.TimeFilter
import app.relish.data.model.VoteDir
import app.relish.ui.MainViewModel
import app.relish.ui.comments.UserCommentRow
import app.relish.ui.drawer.AppDrawer
import app.relish.ui.nav.LocalMessenger
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.nav.permalinkUrl
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(main: MainViewModel, source: FeedSource, vm: FeedViewModel = hiltViewModel()) {
    LaunchedEffect(source) { vm.init(source) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val messenger = LocalMessenger.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val systemDark = isSystemInDarkTheme()
    val subscriptions by main.subscriptions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.events.collect { messenger(it) } }

    // Infinite scroll.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 10
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) vm.loadMore() }
    }
    // Scroll to top whenever the listing is replaced (sort change / refresh).
    val firstKey = ui.state.items.firstOrNull()?.key
    var lastFirstKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(firstKey) {
        if (firstKey != null && lastFirstKey != null && lastFirstKey != firstKey) listState.scrollToItem(0)
        if (firstKey != null) lastFirstKey = firstKey
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(main = main, currentSource = source, onNavigate = { scope.launch { drawerState.close() } })
        },
    ) {
        Scaffold(
            topBar = {
                FeedTopBar(
                    source = source,
                    sort = ui.state.sort,
                    time = ui.state.time,
                    isSubscribed = (source as? FeedSource.Subreddit)?.name?.let { name ->
                        subscriptions.any { it.name.equals(name, true) }
                    },
                    onMenu = { scope.launch { drawerState.open() } },
                    onSort = vm::setSort,
                    onRefresh = vm::refresh,
                    onSearch = { nav.openSearch((source as? FeedSource.Subreddit)?.name) },
                    onNightMode = { main.toggleNightMode(systemDark) },
                    onSettings = { nav.openSettings() },
                    onToggleSubscribe = { sub ->
                        val name = (source as FeedSource.Subreddit).name
                        main.setSubscribed(name, sub, ui.state.subreddit?.over18 ?: false)
                    },
                    onAddShortcut = { main.addNsfwShortcut((source as FeedSource.Subreddit).name); messenger("Added to NSFW shortcuts") },
                    onFilterSub = { vm.filterSubreddit((source as FeedSource.Subreddit).name) },
                    scrollBehavior = null,
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = ui.state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(ui.visible, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is FeedItem.PostEntry -> {
                                val post = item.post
                                PostItem(
                                    post = post,
                                    isRead = post.fullname in ui.read,
                                    selected = ui.state.selectedKey == post.fullname,
                                    display = ui.display,
                                    onClick = { vm.select(post.fullname) },
                                    onContentClick = { vm.markRead(post); nav.openPostContent(post) },
                                    actions = postActions(post, vm, nav),
                                )
                            }
                            is FeedItem.CommentEntry -> UserCommentRow(
                                comment = item.comment,
                                onClick = { nav.openComments(item.comment.linkId, item.comment.id) },
                                onLinkClick = nav::openLink,
                            )
                        }
                    }
                    item(key = "footer") { FeedFooter(ui, onRetry = vm::retry) }
                }
            }
        }
    }
}

private fun postActions(post: Post, vm: FeedViewModel, nav: app.relish.ui.nav.AppNavigator) = PostActions(
    onUpvote = { vm.vote(post, VoteDir.UP) },
    onDownvote = { vm.vote(post, VoteDir.DOWN) },
    onSubreddit = { nav.openSubreddit(post.subreddit) },
    onComments = { vm.markRead(post); nav.openComments(post.id) },
    onShare = { nav.share(post.title, if (post.isSelf) permalinkUrl(post.permalink) else post.url) },
    onHide = { vm.hide(post) },
    onSave = { vm.toggleSave(post) },
    onOpenLink = { vm.markRead(post); if (post.isSelf) nav.openComments(post.id) else nav.openLink(post.url) },
)

@Composable
private fun FeedFooter(ui: FeedUi, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            ui.state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MetaTextStyle)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            ui.state.loading && !ui.state.refreshing -> CircularProgressIndicator(Modifier.padding(8.dp))
            ui.state.endReached && ui.visible.isEmpty() -> Text("Nothing here", color = Relish.colors.subtle)
            ui.state.endReached -> Text("End of listing", color = Relish.colors.subtle, style = MetaTextStyle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTopBar(
    source: FeedSource,
    sort: Sort,
    time: TimeFilter,
    isSubscribed: Boolean?,
    onMenu: () -> Unit,
    onSort: (Sort, TimeFilter?) -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onNightMode: () -> Unit,
    onSettings: () -> Unit,
    onToggleSubscribe: (Boolean) -> Unit,
    onAddShortcut: () -> Unit,
    onFilterSub: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior?,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var timeMenuFor by remember { mutableStateOf<Sort?>(null) }
    var overflow by remember { mutableStateOf(false) }

    TopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") } },
        title = {
            Column {
                Text(source.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (source.supportsSort) {
                    Text(
                        sort.label + if (sort.hasTime) " · ${time.label}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Relish.colors.subtle,
                    )
                }
            }
        },
        actions = {
            if (source.supportsSort) {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        Sort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label + if (s.hasTime) " ›" else "") },
                                onClick = {
                                    sortMenu = false
                                    if (s.hasTime) timeMenuFor = s else onSort(s, null)
                                },
                            )
                        }
                    }
                    DropdownMenu(expanded = timeMenuFor != null, onDismissRequest = { timeMenuFor = null }) {
                        TimeFilter.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                onClick = { timeMenuFor?.let { onSort(it, t) }; timeMenuFor = null },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text("Search") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = { overflow = false; onSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text("Night mode") }, leadingIcon = { Icon(Icons.Default.DarkMode, null) },
                        onClick = { overflow = false; onNightMode() },
                    )
                    if (isSubscribed != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (isSubscribed) "Unsubscribe" else "Subscribe") },
                            leadingIcon = { Icon(if (isSubscribed) Icons.Default.RemoveCircleOutline else Icons.Default.Add, null) },
                            onClick = { overflow = false; onToggleSubscribe(!isSubscribed) },
                        )
                        DropdownMenuItem(
                            text = { Text("Add to NSFW shortcuts") }, leadingIcon = { Icon(Icons.Default.Whatshot, null) },
                            onClick = { overflow = false; onAddShortcut() },
                        )
                        DropdownMenuItem(
                            text = { Text("Filter from r/all & front page") }, leadingIcon = { Icon(Icons.Default.FilterList, null) },
                            onClick = { overflow = false; onFilterSub() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { overflow = false; onSettings() },
                    )
                }
            }
        },
    )
}
