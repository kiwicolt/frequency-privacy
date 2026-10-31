package app.relish.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.relish.data.repo.AccountPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.BuildConfig
import app.relish.data.model.CommentSort
import app.relish.data.model.Sort
import app.relish.data.prefs.AppSettings
import app.relish.data.prefs.ContentRouting
import app.relish.data.prefs.FontSize
import app.relish.data.prefs.PostLayout
import app.relish.data.prefs.ThemeMode
import app.relish.data.prefs.ThumbnailMode
import app.relish.ui.MainViewModel
import app.relish.ui.common.rememberClientIdImporter
import app.relish.ui.nav.LocalMessenger
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import app.relish.ui.theme.Relish

/** Dialog kinds the settings list can open; one at a time. */
private sealed interface SettingsDialog {
    data class Choice<T>(val title: String, val options: List<T>, val label: (T) -> String, val selected: T, val onPick: (T) -> Unit) : SettingsDialog
    data class TextInput(val title: String, val initial: String, val hint: String, val onSave: (String) -> Unit) : SettingsDialog
    data class Confirm(val title: String, val message: String, val onConfirm: () -> Unit) : SettingsDialog
    data object Accent : SettingsDialog
    data object DepthColors : SettingsDialog
}

/** RIF's settings categories; each opens its own screen. */
enum class SettingsSection(val route: String, val title: String) {
    GENERAL("general", "General settings"),
    BROWSER("browser", "Browser"),
    DATA("data", "Data usage"),
    FILTERS("filters", "Content filters"),
    HISTORY("history", "History"),
    APPEARANCE("appearance", "Appearance"),
    ACCOUNT("account", "Account"),
    WEB_PREFS("webprefs", "reddit web preferences"),
    BACKUP("backup", "Backup"),
    ABOUT("about", "About"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(main: MainViewModel, section: SettingsSection? = null, vm: SettingsViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val messenger = LocalMessenger.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val clientId by vm.clientId.collectAsStateWithLifecycle()
    val cookie by vm.sessionCookie.collectAsStateWithLifecycle()
    val redirect by vm.redirectUri.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val username by main.username.collectAsStateWithLifecycle()
    val accountPrefs by vm.accountPrefs.collectAsStateWithLifecycle()
    val accountPrefsError by vm.accountPrefsError.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var sortTabsDialog by remember { mutableStateOf(false) }
    val importClientId = rememberClientIdImporter { result ->
        result.onSuccess { vm.setClientId(it); messenger("Client id set to $it") }
            .onFailure { messenger(it.message ?: "Import failed") }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = vm.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            }.onSuccess { vm.notify("Settings exported") }.onFailure { vm.notify("Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            }.onSuccess { vm.importBackup(it) }.onFailure { vm.notify("Couldn't read that file") }
        }
    }

    LaunchedEffect(Unit) { vm.events.collect { messenger(it) } }
    LaunchedEffect(section, username) { if (section == SettingsSection.WEB_PREFS) vm.loadAccountPrefs() }

    fun <T> choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
        dialog = SettingsDialog.Choice(title, options, label, selected, onPick)
    }

    Scaffold(
        topBar = {
            val colors = Relish.colors
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.appBar,
                    titleContentColor = colors.onAppBar,
                    navigationIconContentColor = colors.onAppBar,
                ),
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(section?.title ?: "Relish settings")
                        if (section == SettingsSection.WEB_PREFS && username != null) {
                            Text("/u/$username", style = MaterialTheme.typography.labelMedium, color = colors.subtle)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                null -> items(SettingsSection.entries) { sec ->
                    Text(
                        sec.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { nav.openSettings(sec) }.padding(horizontal = 18.dp, vertical = 18.dp),
                    )
                }

                SettingsSection.GENERAL -> {
                    item {
                        ClickPref("Homepage", s.startupSubreddit.ifBlank { "reddit front page" }) {
                            dialog = SettingsDialog.TextInput("Homepage", s.startupSubreddit, "Blank = front page; a+b combos allowed", vm::setStartup)
                        }
                    }
                    item { SwitchPref("Back goes home", "Back button goes to homepage before quitting", s.backGoesHome, vm::setBackGoesHome) }
                    item { SwitchPref("Quit confirmation", null, s.quitConfirmation, vm::setQuitConfirmation) }
                    item { SwitchPref("Auto-load next page", null, s.autoLoadNextPage, vm::setAutoLoad) }
                    item { ClickPref("Default sort", s.defaultSort.label) { choice("Default sort", Sort.entries, s.defaultSort, { it.label }, vm::setDefaultSort) } }
                    item {
                        ClickPref("Default comment sort", s.defaultCommentSort.label) {
                            choice("Default comment sort", CommentSort.entries, s.defaultCommentSort, { it.label }, vm::setCommentSort)
                        }
                    }
                }

                SettingsSection.BROWSER -> {
                    item { SwitchPref("Prefer external browser", "Use a Custom Tab unless an \"in-app\" setting applies below", !s.internalBrowser) { vm.setInternalBrowser(!it) } }
                    item { SwitchPref("In-app browser for images", "For images, GIF, GIFV", s.internalMediaViewer, vm::setInternalMedia) }
                    item { SwitchPref("In-app browser for videos", "For v.redd.it, MP4, WebM, Redgifs, audio", s.internalVideos, vm::setInternalVideos) }
                    item { SwitchPref("In-app native image albums", "For Reddit galleries", s.internalAlbums, vm::setInternalAlbums) }
                    item { SwitchPref("Auto-mute videos", "Start videos muted", s.autoMuteVideos, vm::setAutoMute) }
                    item { SwitchPref("Loop videos", null, s.loopVideos, vm::setLoop) }
                    item { ClickPref("YouTube player", "YouTube app") {} }
                }

                SettingsSection.DATA -> {
                    item { ClickPref("Thumbnails", s.thumbnails.label) { choice("Thumbnails", ThumbnailMode.entries, s.thumbnails, { it.label }, vm::setThumbnails) } }
                    item { SwitchPref("Image previews in cards", "Large previews when using card layout", s.inlinePreviewsInCards, vm::setInlinePreviews) }
                    item { ClickPref("Clear local cache", "Cached API responses and images", onClick = vm::clearCache) }
                }

                SettingsSection.FILTERS -> {
                    item { SwitchPref("Show NSFW content", "Master switch for over-18 posts", s.showNsfw, vm::setShowNsfw) }
                    item { SwitchPref("Blur NSFW thumbnails", "Blur (Android 12+) or show a red NSFW placeholder", s.blurNsfw, vm::setBlurNsfw) }
                    item { SwitchPref("Skip age confirmation", "Send the over18 cookie so age-gated content loads directly", s.bypassAgePrompt, vm::setBypassAge) }
                    item { SwitchPref("Blur spoilers", null, s.blurSpoilers, vm::setBlurSpoilers) }
                    item { ClickPref("Keyword, subreddit, user & domain filters", if (filters.isEmpty()) "None" else "${filters.size} active") { nav.openFilters() } }
                }

                SettingsSection.HISTORY -> {
                    item { SwitchPref("Mark read posts", "Dim posts you've opened; stored locally", s.markRead, vm::setMarkRead) }
                    item { SwitchPref("Hide read posts", null, s.hideReadPosts, vm::setHideRead) }
                    item { SwitchPref("Save search history", null, s.saveSearchHistory, vm::setSaveSearch) }
                    item {
                        ClickPref("Clear search history", null) {
                            dialog = SettingsDialog.Confirm("Clear search history?", "This can't be undone.", vm::clearSearchHistory)
                        }
                    }
                    item {
                        ClickPref("Clear read & hidden history", null) {
                            dialog = SettingsDialog.Confirm("Clear read history?", "Read marks and locally hidden posts will be forgotten.", vm::clearReadHistory)
                        }
                    }
                }

                SettingsSection.APPEARANCE -> {
                    item { Header("General appearance") }
                    item { ClickPref("Theme", s.theme.label) { choice("Theme", ThemeMode.entries, s.theme, { it.label }, vm::setTheme) } }
                    item { ClickPref("Text size", s.fontSize.label) { choice("Text size", FontSize.entries, s.fontSize, { it.label }, vm::setFontSize) } }
                    item { ClickPref("Accent colour", null, trailing = { Swatch(Color(s.accentColor)) }) { dialog = SettingsDialog.Accent } }
                    item { SwitchPref("Compact mode", "Reduce padding to maximum density", s.compactMode, vm::setCompact) }
                    item { Header("Screen") }
                    item { SwitchPref("Keep screen on", "Prevent device from sleeping", s.keepScreenOn, vm::setKeepScreenOn) }
                    item { Header("Display posts as cards") }
                    item { SwitchPref("Cards", "Large cards instead of the compact list", s.postLayout == PostLayout.LARGE_CARDS) { vm.setLayout(if (it) PostLayout.LARGE_CARDS else PostLayout.COMPACT_LIST) } }
                    item { Header("Posts appearance") }
                    item { SwitchPref("Left handed mode", "Swap vote arrows and thumbnail", s.leftHanded, vm::setLeftHanded) }
                    item { SwitchPref("Link flair", null, s.showLinkFlair, vm::setShowLinkFlair) }
                    item { ClickPref("Show/hide sort tabs", s.visibleSortTabs.sortedBy { it.ordinal }.joinToString { it.label }) { sortTabsDialog = true } }
                    item { Header("Comments appearance") }
                    item { SwitchPref("Comment flair", null, s.showCommentFlair, vm::setShowCommentFlair) }
                    item { SwitchPref("Comment guide lines", "Coloured depth bars", s.commentGuideLines, vm::setGuideLines) }
                    item {
                        ClickPref("Comment guide line colours", "${s.depthColors.size} levels", trailing = {
                            Row { s.depthColors.take(6).forEach { Swatch(Color(it), 14); Spacer(Modifier.width(2.dp)) } }
                        }) { dialog = SettingsDialog.DepthColors }
                    }
                    item { SwitchPref("Collapse child comments", "Show \"N more comments [+]\" under top-level comments", s.compressComments, vm::setCompressComments) }
                }

                SettingsSection.ACCOUNT -> {
                    item {
                        if (username != null) {
                            ClickPref("Logout: $username", "Tokens are revoked and removed from this device") {
                                dialog = SettingsDialog.Confirm("Log out?", "Tokens will be revoked and removed from this device.") { main.logout() }
                            }
                        } else {
                            ClickPref("Log in", "Required for voting, saving, replying, inbox and subscriptions") { nav.openLogin() }
                        }
                    }
                    item { SwitchPref("Demo mode", "Offline sample content instead of Reddit", s.demoMode) { main.setDemoMode(it) } }
                    item {
                        ClickPref("Reddit API client id", clientId.ifBlank { "Not set" }) {
                            dialog = SettingsDialog.TextInput("Client id", clientId, "Installed-app client id", vm::setClientId)
                        }
                    }
                    item { ClickPref("Import client id from file", "e.g. reddit_client_id_revanced.txt", onClick = importClientId) }
                    item {
                        ClickPref("OAuth redirect URI", "$redirect (must match the Reddit app registration)") {
                            dialog = SettingsDialog.TextInput("Redirect URI", redirect, "redditisfun://auth", vm::setRedirectUri)
                        }
                    }
                    item {
                        ClickPref("Browser session cookie", if (cookie.isBlank()) "Optional. Sent with web JSON requests" else "Set (${cookie.length} chars)") {
                            dialog = SettingsDialog.TextInput("Cookie header", cookie, "reddit_session=…", vm::setSessionCookie)
                        }
                    }
                    item { ClickPref("Content routing", s.routing.label) { choice("Content routing", ContentRouting.entries, s.routing, { it.label }, vm::setRouting) } }
                    if (username != null) {
                        item { ClickPref("Sync subscriptions", "Refresh the drawer from your account", onClick = vm::syncSubscriptions) }
                    }
                }

                SettingsSection.WEB_PREFS -> {
                    val prefs = accountPrefs
                    when {
                        username == null && !s.demoMode -> item { Hint("Log in to view and change your Reddit account preferences.") }
                        accountPrefsError != null -> item { Hint(accountPrefsError!!) }
                        prefs == null -> item { Hint("Loading…") }
                        else -> AccountPref.entries.groupBy { it.section }.forEach { (sectionName, list) ->
                            item(key = "h-$sectionName") { Header(sectionName) }
                            list.filter { prefs[it.key] is JsonPrimitive }.forEach { p ->
                                item(key = p.key) {
                                    val checked = (prefs[p.key] as? JsonPrimitive)?.booleanOrNull == true
                                    SwitchPref(p.label, p.summary, checked) { vm.setAccountPref(p.key, it) }
                                }
                            }
                        }
                    }
                }

                SettingsSection.BACKUP -> {
                    item { ClickPref("Export settings", "Preferences, filters, local subscriptions, stars and shortcuts. No account info.") {
                        exportLauncher.launch("relish-settings.json")
                    } }
                    item { ClickPref("Import settings", "WARNING: Will overwrite ALL settings") {
                        dialog = SettingsDialog.Confirm("Import settings?", "This overwrites all current settings.") { importLauncher.launch(arrayOf("application/json", "*/*")) }
                    } }
                }

                SettingsSection.ABOUT -> {
                    item { ClickPref("Relish ${BuildConfig.VERSION_NAME}", "Personal, non-commercial Reddit client in the style of Reddit is Fun") {} }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (sortTabsDialog) SortTabsDialog(s.visibleSortTabs, onSave = { vm.setSortTabs(it); sortTabsDialog = false }) { sortTabsDialog = false }

    when (val d = dialog) {
        null -> Unit
        is SettingsDialog.Choice<*> -> ChoiceDialog(d) { dialog = null }
        is SettingsDialog.TextInput -> TextInputDialog(d) { dialog = null }
        is SettingsDialog.Confirm -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { d.onConfirm(); dialog = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SettingsDialog.Accent -> AccentDialog(s.accentColor, onPick = { vm.setAccent(it); dialog = null }) { dialog = null }
        SettingsDialog.DepthColors -> DepthColorsDialog(s.depthColors, onPalette = { vm.setDepthColors(it); dialog = null }, onCustom = {
            vm.setDepthColorsRaw(it); dialog = null
        }) { dialog = null }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MetaTextStyle, color = Relish.colors.subtle, modifier = Modifier.padding(18.dp))
}

@Composable
private fun SortTabsDialog(current: Set<Sort>, onSave: (Set<Sort>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show/hide sort tabs") },
        text = {
            Column {
                Sort.entries.forEach { sort ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (sort in picked) picked - sort else picked + sort }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sort in picked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(sort.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked) }, enabled = picked.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Header(title: String) {
    Column {
        HorizontalDivider()
        Text(
            title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ClickPref(title: String, summary: String?, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MetaTextStyle, color = Relish.colors.subtle) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchPref(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MetaTextStyle, color = Relish.colors.subtle) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Swatch(color: Color, sizeDp: Int = 24, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun ChoiceDialog(d: SettingsDialog.Choice<*>, onDismiss: () -> Unit) {
    val dialog = d as SettingsDialog.Choice<Any?>
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column {
                dialog.options.forEach { opt ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = opt == dialog.selected, role = Role.RadioButton) { dialog.onPick(opt); onDismiss() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = opt == dialog.selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(dialog.label(opt))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TextInputDialog(d: SettingsDialog.TextInput, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(d.hint) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { d.onSave(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentDialog(current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accent colour") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppSettings.ACCENTS.forEach { c -> Swatch(Color(c), 40, selected = c == current) { onPick(c) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DepthColorsDialog(current: List<Long>, onPalette: (List<Long>) -> Unit, onCustom: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(current.joinToString(", ") { "#%06X".format(it and 0xFFFFFFL) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comment depth colours") },
        text = {
            Column {
                AppSettings.DEPTH_PALETTES.forEach { (name, colors) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPalette(colors) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.width(100.dp))
                        colors.forEach { Box(Modifier.width(6.dp).height(22.dp).background(Color(it))); Spacer(Modifier.width(4.dp)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Custom (one hex per depth level, cycles):", style = MetaTextStyle)
                OutlinedTextField(custom, { custom = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCustom(custom) }) { Text("Apply custom") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
