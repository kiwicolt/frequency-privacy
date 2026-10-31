package app.relish.ui.drawer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.local.SubscriptionEntity
import app.relish.data.model.FeedSource
import app.relish.ui.MainViewModel
import app.relish.ui.feed.Badge
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish

/**
 * RIF's drawer: a "Type a subreddit name" box with a settings gear, the fixed front page / popular /
 * all / random rows (with MULTIS and FILTER buttons), then subscriptions with a favourite star and
 * a ⋮ menu. Favourites sort first.
 */
@Composable
fun AppDrawer(main: MainViewModel, currentSource: FeedSource, onNavigate: () -> Unit) {
    val nav = LocalNavigator.current
    val username by main.username.collectAsStateWithLifecycle()
    val subs by main.subscriptions.collectAsStateWithLifecycle()
    val multis by main.multis.collectAsStateWithLifecycle()
    val shortcuts by main.nsfwShortcuts.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    var showMultis by remember { mutableStateOf(false) }
    var addShortcut by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    val colors = Relish.colors

    fun go(source: FeedSource) {
        onNavigate()
        typed = ""
        if (source != currentSource) nav.openFeed(source)
    }

    val query = typed.trim().removePrefix("r/").removePrefix("/r/")
    val filtered = remember(subs, query) { if (query.isEmpty()) subs else subs.filter { it.name.contains(query, ignoreCase = true) } }

    ModalDrawerSheet(Modifier.width(320.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Type a subreddit name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (query.isNotEmpty()) go(FeedSource.Subreddit(query)) }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onNavigate(); nav.openSettings() }) { Icon(Icons.Default.Settings, "Settings", tint = colors.subtle) }
        }

        LazyColumn {
            if (query.isNotEmpty()) {
                if (filtered.none { it.name.equals(query, true) }) {
                    item { FixedRow("Go to r/$query", selected = false) { go(FeedSource.Subreddit(query)) } }
                }
            } else {
                item {
                    FixedRow("reddit front page", currentSource == FeedSource.FrontPage, leftIcon = Icons.AutoMirrored.Filled.CallMerge,
                        leftLabel = "MULTIS", onLeft = { showMultis = !showMultis }) { go(FeedSource.FrontPage) }
                }
                if (showMultis) {
                    if (multis.isEmpty()) item { Hint(if (username == null) "Log in to see your multireddits." else "You have no multireddits.") }
                    items(multis, key = { "multi-${it.path}" }) { m ->
                        val owner = m.path.split('/').getOrNull(1) ?: username.orEmpty()
                        SubRow(m.displayName, "multireddit", m.over18, currentSource == FeedSource.Multi(owner, m.name)) { go(FeedSource.Multi(owner, m.name)) }
                    }
                }
                item {
                    FixedRow("popular", currentSource == FeedSource.Subreddit("popular"), leftIcon = Icons.Default.Edit,
                        leftLabel = "FILTER", onLeft = { onNavigate(); nav.openFilters() }) { go(FeedSource.Subreddit("popular")) }
                }
                item { FixedRow("all", currentSource == FeedSource.Subreddit("all")) { go(FeedSource.Subreddit("all")) } }
                item { FixedRow("random", false) { go(FeedSource.Subreddit("random")) } }

                if (settings.showNsfw && shortcuts.isNotEmpty()) {
                    items(shortcuts, key = { "nsfw-$it" }) { name ->
                        SubRow(name, "NSFW shortcut", over18 = true, selected = currentSource == FeedSource.Subreddit(name),
                            leading = { Icon(Icons.Default.Whatshot, null, tint = colors.nsfw) },
                            menu = { close -> DropdownMenuItem(text = { Text("Remove shortcut") }, onClick = { close(); main.removeNsfwShortcut(name) }) },
                        ) { go(FeedSource.Subreddit(name)) }
                    }
                }
            }

            items(filtered, key = { "sub-${it.name}" }) { sub ->
                SubscriptionRow(sub, currentSource == FeedSource.Subreddit(sub.name), main, onAddShortcutDone = {}) {
                    go(FeedSource.Subreddit(sub.name))
                }
            }

            if (query.isEmpty()) {
                if (subs.isEmpty()) item { Hint("No subscriptions yet. Open a subreddit and choose Subscribe from its menu, or import your list.") }
                item {
                    TextButton(onClick = { importDialog = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text(" Import subscriptions")
                    }
                }
                if (settings.showNsfw) item {
                    TextButton(onClick = { addShortcut = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Add NSFW shortcut")
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (importDialog) ImportSubscriptionsDialog(
        onImport = { main.importSubscriptions(it); importDialog = false },
        onRifBackup = { main.importRifBackup(it); importDialog = false },
    ) { importDialog = false }

    if (addShortcut) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addShortcut = false },
            title = { Text("Add NSFW shortcut") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Subreddit (a+b for a combo)") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { main.addNsfwShortcut(name); addShortcut = false }, enabled = name.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addShortcut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ImportSubscriptionsDialog(onImport: (String) -> Unit, onRifBackup: (android.net.Uri) -> Unit, onDismiss: () -> Unit) {
    val rifPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onRifBackup(uri) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { text = it.take(200_000) }
    }
    val found = remember(text) { app.relish.data.repo.RedditRepository.parseSubredditNames(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import subscriptions") },
        text = {
            Column {
                androidx.compose.material3.Button(
                    onClick = { rifPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("From Reddit is Fun backup (.zip)") }
                Text(
                    "RIF › Settings › Backup › Export settings. Imports subscriptions, read history, filters and settings.",
                    style = MetaTextStyle, color = Relish.colors.subtle,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Or paste your subscriptions multireddit link (old.reddit.com/subreddits/mine › sidebar), " +
                        "or a list of names (one per line, or comma separated).",
                    style = MetaTextStyle,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("reddit.com/r/AirReps+Android+…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                )
                TextButton(onClick = { picker.launch(arrayOf("text/*", "*/*")) }) { Text("From file…") }
                Text(if (found.isEmpty()) "No subreddits recognised yet" else "${found.size} found: " + found.take(6).joinToString() + if (found.size > 6) "…" else "",
                    style = MetaTextStyle, color = Relish.colors.subtle)
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = found.isNotEmpty()) { Text("Import ${found.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val RowHeight = 64.dp
private val LeftColumn = 96.dp

/** Fixed destinations; optionally with RIF's small labelled button on the left (MULTIS, FILTER). */
@Composable
private fun FixedRow(
    label: String,
    selected: Boolean,
    leftIcon: ImageVector? = null,
    leftLabel: String? = null,
    onLeft: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = Relish.colors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowHeight)
                .background(if (selected) colors.appBar.copy(alpha = 0.7f) else Color.Transparent)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(LeftColumn).fillMaxHeight().heightIn(min = RowHeight), contentAlignment = Alignment.Center) {
                if (leftIcon != null && onLeft != null) {
                    Column(
                        Modifier.clickable(onClick = onLeft).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(leftIcon, null, tint = colors.subtle, modifier = Modifier.size(22.dp))
                        leftLabel?.let { Text(it, fontSize = 11.sp, color = colors.subtle) }
                    }
                }
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SubscriptionRow(
    sub: SubscriptionEntity,
    selected: Boolean,
    main: MainViewModel,
    onAddShortcutDone: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = Relish.colors
    SubRow(
        name = sub.name,
        subtitle = if (sub.local) "local" else "frontpage",
        over18 = sub.over18,
        selected = selected,
        leading = {
            IconButton(onClick = { main.setFavorite(sub.name, !sub.favorite) }) {
                Icon(
                    if (sub.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (sub.favorite) "Unfavourite" else "Favourite",
                    tint = if (sub.favorite) Color(0xFFFFB300) else colors.subtle,
                )
            }
        },
        menu = { close ->
            DropdownMenuItem(text = { Text("Unsubscribe") }, onClick = { close(); main.setSubscribed(sub.name, false) })
            DropdownMenuItem(text = { Text("Add to NSFW shortcuts") }, onClick = { close(); main.addNsfwShortcut(sub.name); onAddShortcutDone() })
            DropdownMenuItem(text = { Text("Filter from front page & r/all") }, onClick = { close(); main.filterSubreddit(sub.name) })
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubRow(
    name: String,
    subtitle: String?,
    over18: Boolean,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = Relish.colors
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowHeight)
                .background(if (selected) colors.appBar.copy(alpha = 0.7f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = if (menu != null) ({ menuOpen = true }) else null),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.width(LeftColumn), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) leading() else Spacer(Modifier.size(48.dp))
                if (menu != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Options", tint = colors.subtle) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
                    }
                }
            }
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (over18) { Spacer(Modifier.width(6.dp)); Badge("NSFW", colors.nsfw) }
                }
                subtitle?.let { Text(it, style = MetaTextStyle, color = colors.subtle.copy(alpha = 0.7f)) }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MetaTextStyle, color = Relish.colors.subtle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}
