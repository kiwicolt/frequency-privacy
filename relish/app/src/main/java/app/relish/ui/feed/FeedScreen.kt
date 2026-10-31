package app.relish.ui.feed

import androidx.compose.foundation.background
import app.relish.data.prefs.ThemeMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
    val username by main.username.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    var themeDialog by remember { mutableStateOf(false) }
    var quitDialog by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    val isRoot = remember { nav.nav.previousBackStackEntry == null }
    val home = remember(settings.startupSubreddit) {
        settings.startupSubreddit.takeIf { it.isNotBlank() }?.let { FeedSource.Subreddit(it) } ?: FeedSource.FrontPage
    }

    // RIF "Back goes home" + "Quit confirmation" on the root screen.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = isRoot && !drawerState.isOpen && ((settings.backGoesHome && source != home) || settings.quitConfirmation)) {
        if (settings.backGoesHome && source != home) nav.goHome(home)
        else quitDialog = true
    }

    LaunchedEffect(Unit) { vm.events.collect { messenger(it) } }

    // Infinite scroll (RIF "Auto-load next page"; otherwise a button in the footer).
    LaunchedEffect(listState, settings.autoLoadNextPage) {
        if (!settings.autoLoadNextPage) return@LaunchedEffect
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
                    username = username,
                    demoMode = settings.demoMode,
                    visibleTabs = settings.visibleSortTabs,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSort = vm::setSort,
                    onRefresh = vm::refresh,
                    onAction = { action ->
                        val subName = (source as? FeedSource.Subreddit)?.name
                        when (action) {
                            OverflowAction.PROFILE -> username?.let(nav::openUser)
                            OverflowAction.SEARCH -> nav.openSearch(subName)
                            OverflowAction.INBOX -> nav.openInbox()
                            OverflowAction.SETTINGS -> nav.openSettings()
                            OverflowAction.THEME -> themeDialog = true
                            OverflowAction.BROWSER -> nav.openInBrowser(webUrl(source))
                            OverflowAction.DEMO -> main.setDemoMode(!settings.demoMode)
                            OverflowAction.LOGIN -> nav.openLogin()
                            OverflowAction.LOGOUT -> main.logout()
                            OverflowAction.SUBSCRIBE, OverflowAction.UNSUBSCRIBE ->
                                subName?.let { main.setSubscribed(it, action == OverflowAction.SUBSCRIBE, ui.state.subreddit?.over18 ?: false) }
                            OverflowAction.SHORTCUT -> subName?.let { main.addNsfwShortcut(it); messenger("Added to NSFW shortcuts") }
                            OverflowAction.FILTER -> subName?.let { vm.filterSubreddit(it) }
                        }
                    },
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
                      Column {
                        val pageIndex = ui.state.pageStarts.indexOf(item.key)
                        if (pageIndex >= 0) {
                            PageBar(
                                page = pageIndex + 2,
                                onPrev = {
                                    val target = ui.state.pageStarts.getOrNull(pageIndex - 1)
                                    val idx = if (target == null) 0 else ui.visible.indexOfFirst { it.key == target }
                                    scope.launch { listState.animateScrollToItem(idx.coerceAtLeast(0)) }
                                },
                                onNext = {
                                    val target = ui.state.pageStarts.getOrNull(pageIndex + 1)
                                    val idx = if (target == null) ui.visible.size else ui.visible.indexOfFirst { it.key == target }
                                    scope.launch { listState.animateScrollToItem(idx.coerceAtLeast(0)) }
                                },
                            )
                        }
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
                                    actions = remember(post, vm, nav) { postActions(post, vm, nav) },
                                )
                            }
                            is FeedItem.CommentEntry -> UserCommentRow(
                                comment = item.comment,
                                onClick = { nav.openComments(item.comment.linkId, item.comment.id) },
                                onLinkClick = nav::openLink,
                            )
                        }
                      }
                    }
                    item(key = "footer") { FeedFooter(ui, onRetry = vm::retry, onLoadMore = if (settings.autoLoadNextPage) null else vm::loadMore) }
                }
            }
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable { main.setTheme(mode); themeDialog = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.theme == mode, onClick = null)
                            Text("  ${mode.label}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Close") } },
        )
    }
    if (quitDialog) {
        AlertDialog(
            onDismissRequest = { quitDialog = false },
            title = { Text("Quit Relish?") },
            confirmButton = { TextButton(onClick = { quitDialog = false; activity?.finish() }) { Text("Quit") } },
            dismissButton = { TextButton(onClick = { quitDialog = false }) { Text("Cancel") } },
        )
    }
}

/** RIF's "▲ page 2 ▼" divider between loaded pages. */
@Composable
private fun PageBar(page: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Relish.colors.flairBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ArrowDropUp, "Previous page") }
        Text("page $page", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = onNext) { Icon(Icons.Default.ArrowDropDown, "Next page") }
    }
}

enum class OverflowAction { PROFILE, SEARCH, INBOX, SETTINGS, THEME, BROWSER, DEMO, LOGIN, LOGOUT, SUBSCRIBE, UNSUBSCRIBE, SHORTCUT, FILTER }

private fun webUrl(source: FeedSource): String = "https://www.reddit.com/" + when (source) {
    FeedSource.FrontPage -> ""
    is FeedSource.Subreddit -> "r/${source.name}/"
    is FeedSource.Multi -> "user/${source.owner}/m/${source.name}/"
    is FeedSource.User -> "user/${source.name}/"
    is FeedSource.Saved -> "user/${source.user}/saved/"
    is FeedSource.Search -> "search/?q=" + android.net.Uri.encode(source.query)
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
    onUser = { nav.openUser(post.author) },
)

@Composable
private fun FeedFooter(ui: FeedUi, onRetry: () -> Unit, onLoadMore: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            ui.state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MetaTextStyle)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            ui.state.loading && !ui.state.refreshing -> CircularProgressIndicator(Modifier.padding(8.dp))
            ui.state.endReached && ui.visible.isEmpty() -> Text("Nothing here", color = Relish.colors.subtle)
            ui.state.endReached -> Text("End of listing", color = Relish.colors.subtle, style = MetaTextStyle)
            onLoadMore != null && ui.state.items.isNotEmpty() -> TextButton(onClick = onLoadMore) { Text("Load more") }
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
    username: String?,
    demoMode: Boolean,
    visibleTabs: Set<Sort>,
    onMenu: () -> Unit,
    onSort: (Sort, TimeFilter?) -> Unit,
    onRefresh: () -> Unit,
    onAction: (OverflowAction) -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior?,
) {
    var timeMenuFor by remember { mutableStateOf<Sort?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val colors = Relish.colors

    Column(Modifier.background(colors.appBar)) {
    TopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.appBar,
            titleContentColor = colors.onAppBar,
            navigationIconContentColor = colors.onAppBar,
            actionIconContentColor = colors.onAppBar,
        ),
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open drawer") } },
        title = {
            Column {
                Text(source.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (source.supportsSort && sort.hasTime) {
                    Text("${sort.label} · ${time.label}", style = MaterialTheme.typography.labelSmall, color = colors.subtle)
                }
            }
        },
        actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    @Composable
                    fun Item(label: String, action: OverflowAction) =
                        DropdownMenuItem(text = { Text(label) }, onClick = { overflow = false; onAction(action) })
                    if (username != null) Item("$username's profile", OverflowAction.PROFILE)
                    Item("Search posts", OverflowAction.SEARCH)
                    if (username != null || demoMode) Item("Inbox", OverflowAction.INBOX)
                    if (isSubscribed != null) {
                        Item(if (isSubscribed) "Unsubscribe" else "Subscribe", if (isSubscribed) OverflowAction.UNSUBSCRIBE else OverflowAction.SUBSCRIBE)
                        Item("Add to NSFW shortcuts", OverflowAction.SHORTCUT)
                        Item("Filter from front page & r/all", OverflowAction.FILTER)
                    }
                    Item("Settings", OverflowAction.SETTINGS)
                    Item("Theme", OverflowAction.THEME)
                    Item("reddit in browser", OverflowAction.BROWSER)
                    Item(if (demoMode) "Exit demo mode" else "Demo mode", OverflowAction.DEMO)
                    if (username != null) Item("Logout: $username", OverflowAction.LOGOUT) else Item("Log in", OverflowAction.LOGIN)
                }
            }
        },
    )
    if (source.supportsSort) {
        val tabs = remember(source, visibleTabs) {
            Sort.entries.filter { (it in visibleTabs || it == sort) && (it != Sort.BEST || source == FeedSource.FrontPage) }
        }
        Box {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabs.indexOf(sort).coerceAtLeast(0),
                containerColor = colors.appBar,
                contentColor = colors.onAppBar,
                edgePadding = 0.dp,
                divider = {},
            ) {
                tabs.forEach { s ->
                    Tab(
                        selected = s == sort,
                        onClick = { if (s.hasTime) timeMenuFor = s else onSort(s, null) },
                        selectedContentColor = colors.onAppBar,
                        unselectedContentColor = colors.subtle,
                        text = { Text(s.label.uppercase(), style = MaterialTheme.typography.titleSmall, maxLines = 1) },
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
    }
}
