package app.relish.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.model.FeedSource
import app.relish.ui.MainViewModel
import app.relish.ui.feed.Badge
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish
import coil.compose.AsyncImage

@Composable
fun AppDrawer(main: MainViewModel, currentSource: FeedSource, onNavigate: () -> Unit) {
    val nav = LocalNavigator.current
    val account by main.account.collectAsStateWithLifecycle()
    val username by main.username.collectAsStateWithLifecycle()
    val subs by main.subscriptions.collectAsStateWithLifecycle()
    val multis by main.multis.collectAsStateWithLifecycle()
    val shortcuts by main.nsfwShortcuts.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    var addShortcut by remember { mutableStateOf(false) }

    fun go(source: FeedSource) {
        onNavigate()
        if (source != currentSource) nav.openFeed(source)
    }
    fun goTo(action: () -> Unit) { onNavigate(); action() }

    val filtered = remember(subs, filter) {
        val q = filter.trim().removePrefix("r/")
        if (q.isEmpty()) subs else subs.filter { it.name.contains(q, ignoreCase = true) }
    }
    val nsfwSubs = remember(subs, shortcuts) { subs.filter { it.over18 && shortcuts.none { s -> s.equals(it.name, true) } } }

    ModalDrawerSheet(Modifier.width(300.dp)) {
        LazyColumn {
            // ---- Account header -------------------------------------------------------------
            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)
                ) {
                    if (username != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = account?.iconUrl
                            if (icon != null) {
                                AsyncImage(icon, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.AccountCircle, null, Modifier.size(40.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("u/$username", style = MaterialTheme.typography.titleMedium)
                                account?.let {
                                    Text("${it.linkKarma} link · ${it.commentKarma} comment karma", style = MetaTextStyle, color = Relish.colors.subtle)
                                }
                            }
                        }
                    } else {
                        Text("Browsing anonymously", style = MaterialTheme.typography.titleMedium)
                        Text("NSFW via web JSON + over18 cookie", style = MetaTextStyle, color = Relish.colors.subtle)
                        TextButton(onClick = { goTo(nav::openLogin) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Login, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Log in with Reddit")
                        }
                    }
                }
            }

            // ---- Primary destinations ------------------------------------------------------
            item { DrawerRow(Icons.Default.Home, "Front page", currentSource == FeedSource.FrontPage) { go(FeedSource.FrontPage) } }
            item { DrawerRow(Icons.Default.Public, "r/all", currentSource == FeedSource.Subreddit("all")) { go(FeedSource.Subreddit("all")) } }
            item { DrawerRow(Icons.AutoMirrored.Filled.TrendingUp, "r/popular", currentSource == FeedSource.Subreddit("popular")) { go(FeedSource.Subreddit("popular")) } }
            username?.let { user ->
                item { DrawerRow(Icons.Default.AccountCircle, "Profile", currentSource == FeedSource.User(user)) { go(FeedSource.User(user)) } }
                item {
                    DrawerRow(Icons.Default.Email, "Inbox", false, trailing = account?.inboxCount?.takeIf { it > 0 }?.toString()) {
                        goTo(nav::openInbox)
                    }
                }
                item { DrawerRow(Icons.Default.Bookmark, "Saved", currentSource is FeedSource.Saved) { go(FeedSource.Saved(user)) } }
            }
            item { DrawerRow(Icons.Default.Search, "Search", false) { goTo { nav.openSearch(null) } } }
            item { DrawerRow(Icons.Default.Settings, "Settings", false) { goTo(nav::openSettings) } }

            // ---- NSFW quick shortcuts ------------------------------------------------------
            if (settings.showNsfw) {
                item {
                    SectionHeader("NSFW shortcuts") {
                        IconButton(onClick = { addShortcut = true }, Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Add shortcut", Modifier.size(18.dp)) }
                    }
                }
                items(shortcuts, key = { "nsfw-$it" }) { name ->
                    DrawerRow(
                        Icons.Default.Whatshot, "r/$name", currentSource == FeedSource.Subreddit(name),
                        badge = true, onLongClick = { main.removeNsfwShortcut(name) },
                    ) { go(FeedSource.Subreddit(name)) }
                }
                items(nsfwSubs, key = { "nsfwsub-${it.name}" }) { sub ->
                    DrawerRow(Icons.Default.Whatshot, "r/${sub.name}", currentSource == FeedSource.Subreddit(sub.name), badge = true) {
                        go(FeedSource.Subreddit(sub.name))
                    }
                }
                if (shortcuts.isEmpty() && nsfwSubs.isEmpty()) {
                    item { Hint("Tap + or use a subreddit's overflow menu to pin NSFW subs here. Long-press to remove.") }
                }
            }

            // ---- Multireddits --------------------------------------------------------------
            if (multis.isNotEmpty()) {
                item { SectionHeader("Multireddits") }
                items(multis, key = { "multi-${it.path}" }) { m ->
                    val owner = m.path.split('/').getOrNull(1) ?: username.orEmpty()
                    DrawerRow(Icons.Default.Layers, m.displayName, currentSource == FeedSource.Multi(owner, m.name), badge = m.over18) {
                        go(FeedSource.Multi(owner, m.name))
                    }
                }
            }

            // ---- Subscriptions with quick filter -------------------------------------------
            item { SectionHeader("Subscriptions (${subs.size})") }
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter or go to subreddit") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = if (filter.isNotEmpty()) {
                        { IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear") } }
                    } else null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            val typed = filter.trim().removePrefix("r/")
            if (typed.isNotEmpty() && filtered.none { it.name.equals(typed, true) }) {
                item { DrawerRow(Icons.Default.Tag, "Go to r/$typed", false) { filter = ""; go(FeedSource.Subreddit(typed)) } }
            }
            items(filtered, key = { "sub-${it.name}-${it.local}" }) { sub ->
                DrawerRow(
                    Icons.Default.Tag, "r/${sub.name}", currentSource == FeedSource.Subreddit(sub.name),
                    badge = sub.over18, iconUrl = sub.iconUrl, local = sub.local,
                ) { go(FeedSource.Subreddit(sub.name)) }
            }
            if (subs.isEmpty()) item { Hint("No subscriptions yet. Open a subreddit and choose Subscribe from its menu.") }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (addShortcut) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addShortcut = false },
            title = { Text("Add NSFW shortcut") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Subreddit (e.g. a+b for a combo)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { main.addNsfwShortcut(name); addShortcut = false }, enabled = name.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addShortcut = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Boolean = false,
    trailing: String? = null,
    iconUrl: String? = null,
    local: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconUrl != null) {
            AsyncImage(iconUrl, null, Modifier.size(20.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else Relish.colors.subtle)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge) { Spacer(Modifier.width(6.dp)); Badge("NSFW", Relish.colors.nsfw) }
        if (local) { Spacer(Modifier.width(6.dp)); Text("local", fontSize = 10.sp, color = Relish.colors.subtle) }
        Spacer(Modifier.weight(1f))
        trailing?.let { Badge(it, MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Column {
        HorizontalDivider(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MetaTextStyle, color = Relish.colors.subtle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}
