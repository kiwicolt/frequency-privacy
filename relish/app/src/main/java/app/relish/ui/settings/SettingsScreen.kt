package app.relish.ui.settings

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(main: MainViewModel, vm: SettingsViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val messenger = LocalMessenger.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val clientId by vm.clientId.collectAsStateWithLifecycle()
    val cookie by vm.sessionCookie.collectAsStateWithLifecycle()
    val redirect by vm.redirectUri.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val username by main.username.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val importClientId = rememberClientIdImporter { result ->
        result.onSuccess { vm.setClientId(it); messenger("Client id set to $it") }
            .onFailure { messenger(it.message ?: "Import failed") }
    }

    LaunchedEffect(Unit) { vm.events.collect { messenger(it) } }

    fun <T> choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
        dialog = SettingsDialog.Choice(title, options, label, selected, onPick)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // ------------------------------------------------------------------ Account
            item { Header("Account") }
            item {
                SwitchPref("Demo mode", "Offline sample posts (images, GIFs, videos, YouTube, galleries…) instead of Reddit",
                    s.demoMode, vm::setDemoMode)
            }
            item {
                if (username != null) {
                    ClickPref("Logged in as u/$username", "Tap to log out") {
                        dialog = SettingsDialog.Confirm("Log out?", "Tokens will be revoked and removed from this device.") { main.logout() }
                    }
                } else {
                    ClickPref("Log in", "Required for voting, saving, replying, inbox and account subscriptions") { nav.openLogin() }
                }
            }
            item {
                ClickPref("Reddit API client id", clientId.ifBlank { "Not set" }) {
                    dialog = SettingsDialog.TextInput("Client id", clientId, "Installed-app client id", vm::setClientId)
                }
            }
            item {
                ClickPref("Import client id from file", "e.g. reddit_client_id_revanced.txt from ReVanced", onClick = importClientId)
            }
            item {
                ClickPref("OAuth redirect URI", "$redirect (must match the Reddit app registration)") {
                    dialog = SettingsDialog.TextInput("Redirect URI", redirect, "redditisfun://auth", vm::setRedirectUri)
                }
            }
            item {
                ClickPref("Browser session cookie", if (cookie.isBlank()) "Optional. Sent with web JSON requests" else "Set (${cookie.length} chars)") {
                    dialog = SettingsDialog.TextInput("Cookie header", cookie, "reddit_session=…; token_v2=…", vm::setSessionCookie)
                }
            }
            item {
                ClickPref("Content routing", s.routing.label) {
                    choice("Content routing", ContentRouting.entries, s.routing, { it.label }, vm::setRouting)
                }
            }
            if (username != null) {
                item { ClickPref("Enable NSFW on Reddit account", "Sets over_18 and NSFW search prefs so OAuth listings include NSFW", onClick = vm::enableAccountNsfw) }
                item { ClickPref("Sync subscriptions", "Refresh the drawer from your account", onClick = vm::syncSubscriptions) }
            }

            // ------------------------------------------------------------------ Appearance
            item { Header("Appearance") }
            item { ClickPref("Theme", s.theme.label) { choice("Theme", ThemeMode.entries, s.theme, { it.label }, vm::setTheme) } }
            item {
                ClickPref("Accent colour", null, trailing = { Swatch(Color(s.accentColor)) }) { dialog = SettingsDialog.Accent }
            }
            item {
                ClickPref("Comment depth colours", "${s.depthColors.size} levels", trailing = {
                    Row { s.depthColors.take(6).forEach { Swatch(Color(it), 14) ; Spacer(Modifier.width(2.dp)) } }
                }) { dialog = SettingsDialog.DepthColors }
            }
            item { ClickPref("Font size", s.fontSize.label) { choice("Font size", FontSize.entries, s.fontSize, { it.label }, vm::setFontSize) } }
            item { SwitchPref("Compact mode", "Reduce padding to maximum density", s.compactMode, vm::setCompact) }

            // ------------------------------------------------------------------ Post layout & media
            item { Header("Post layout & media") }
            item { ClickPref("Thumbnails", s.thumbnails.label) { choice("Thumbnails", ThumbnailMode.entries, s.thumbnails, { it.label }, vm::setThumbnails) } }
            item { ClickPref("Post layout", s.postLayout.label) { choice("Post layout", PostLayout.entries, s.postLayout, { it.label }, vm::setLayout) } }
            item { SwitchPref("Image previews in cards", "Show large previews in card layout", s.inlinePreviewsInCards, vm::setInlinePreviews) }
            item { SwitchPref("Auto-mute videos", "Start videos muted", s.autoMuteVideos, vm::setAutoMute) }
            item { SwitchPref("Loop videos", null, s.loopVideos, vm::setLoop) }
            item { SwitchPref("Internal media viewer", "Open images, GIFs, videos, albums and audio in-app", s.internalMediaViewer, vm::setInternalMedia) }
            item { SwitchPref("Internal browser", "Open links in the in-app WebView instead of a Custom Tab", s.internalBrowser, vm::setInternalBrowser) }

            // ------------------------------------------------------------------ Content filtering & NSFW
            item { Header("Content filtering & NSFW") }
            item { SwitchPref("Show NSFW content", "Master switch for over-18 posts", s.showNsfw, vm::setShowNsfw) }
            item { SwitchPref("Blur NSFW thumbnails", "Blur (Android 12+) or show a red NSFW placeholder", s.blurNsfw, vm::setBlurNsfw) }
            item { SwitchPref("Skip age confirmation", "Send the over18 cookie so age-gated content loads directly", s.bypassAgePrompt, vm::setBypassAge) }
            item { SwitchPref("Blur spoilers", null, s.blurSpoilers, vm::setBlurSpoilers) }
            item {
                ClickPref("Keyword & subreddit filters", if (filters.isEmpty()) "None" else "${filters.size} active") { nav.openFilters() }
            }

            // ------------------------------------------------------------------ Behaviour
            item { Header("Behaviour") }
            item { ClickPref("Default sort", s.defaultSort.label) { choice("Default sort", Sort.entries, s.defaultSort, { it.label }, vm::setDefaultSort) } }
            item {
                ClickPref("Default comment sort", s.defaultCommentSort.label) {
                    choice("Default comment sort", CommentSort.entries, s.defaultCommentSort, { it.label }, vm::setCommentSort)
                }
            }
            item {
                SwitchPref("Collapse replies by default", "Show top-level comments with \"N more comments [+]\" like RIF",
                    s.compressComments, vm::setCompressComments)
            }
            item {
                ClickPref("Startup subreddit", s.startupSubreddit.ifBlank { "Front page" }) {
                    dialog = SettingsDialog.TextInput("Startup subreddit", s.startupSubreddit, "Blank = front page; a+b combos allowed", vm::setStartup)
                }
            }

            // ------------------------------------------------------------------ History & privacy
            item { Header("History & privacy") }
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
            item { ClickPref("Clear local cache", "Cached API responses and images", onClick = vm::clearCache) }

            item { Header("About") }
            item { ClickPref("Relish ${BuildConfig.VERSION_NAME}", "Personal, non-commercial Reddit client") {} }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

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
