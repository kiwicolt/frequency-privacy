package app.relish.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.relish.data.local.FilterEntity
import app.relish.data.local.FilterType
import app.relish.data.model.CommentSort
import app.relish.data.model.Sort
import app.relish.data.prefs.AppSettings
import app.relish.data.prefs.ContentRouting
import app.relish.data.prefs.FontSize
import app.relish.data.prefs.PostLayout
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.SecureStore
import app.relish.data.prefs.ThemeMode
import app.relish.data.prefs.ThumbnailMode
import app.relish.data.repo.RedditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesDataStore,
    private val secure: SecureStore,
    private val repo: RedditRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.current
    val clientId: StateFlow<String> = secure.clientId
    val sessionCookie: StateFlow<String> = secure.sessionCookie
    val redirectUri: StateFlow<String> = secure.redirectUriFlow
    val filters: StateFlow<List<FilterEntity>> = repo.filters().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun io(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // Appearance
    fun setTheme(v: ThemeMode) = io { prefs.setTheme(v) }
    fun setAccent(v: Long) = io { prefs.setAccent(v) }
    fun setDepthColors(v: List<Long>) = io { prefs.setDepthColors(v) }
    fun setDepthColorsRaw(raw: String) = io {
        val parsed = PreferencesDataStore.parseColors(raw)
        if (parsed.isEmpty()) _events.send("No valid colours found. Use hex like #2196F3, #4CAF50") else prefs.setDepthColors(parsed)
    }
    fun setFontSize(v: FontSize) = io { prefs.setFontSize(v) }
    fun setCompact(v: Boolean) = io { prefs.setCompactMode(v) }

    // Layout & media
    fun setThumbnails(v: ThumbnailMode) = io { prefs.setThumbnails(v) }
    fun setLayout(v: PostLayout) = io { prefs.setPostLayout(v) }
    fun setInlinePreviews(v: Boolean) = io { prefs.setInlinePreviews(v) }
    fun setAutoMute(v: Boolean) = io { prefs.setAutoMute(v) }
    fun setLoop(v: Boolean) = io { prefs.setLoopVideos(v) }
    fun setInternalMedia(v: Boolean) = io { prefs.setInternalMediaViewer(v) }
    fun setInternalBrowser(v: Boolean) = io { prefs.setInternalBrowser(v) }

    // NSFW & filtering
    fun setShowNsfw(v: Boolean) = io { prefs.setShowNsfw(v); repo.clearHttpCache() }
    fun setBlurNsfw(v: Boolean) = io { prefs.setBlurNsfw(v) }
    fun setBypassAge(v: Boolean) = io { prefs.setBypassAgePrompt(v); repo.clearHttpCache() }
    fun setBlurSpoilers(v: Boolean) = io { prefs.setBlurSpoilers(v) }
    fun setRouting(v: ContentRouting) = io { prefs.setRouting(v); repo.clearHttpCache() }
    fun addFilter(type: FilterType, value: String) = io { repo.addFilter(type, value) }
    fun deleteFilter(id: Long) = io { repo.deleteFilter(id) }

    fun enableAccountNsfw() = io {
        runCatching { repo.enableAccountNsfw() }
            .onSuccess { repo.clearHttpCache(); _events.send("NSFW enabled on your Reddit account") }
            .onFailure { _events.send(RedditRepository.friendlyError(it)) }
    }

    // Behaviour
    fun setDefaultSort(v: Sort) = io { prefs.setDefaultSort(v) }
    fun setCommentSort(v: CommentSort) = io { prefs.setDefaultCommentSort(v) }
    fun setCompressComments(v: Boolean) = io { prefs.setCompressComments(v) }
    fun setDemoMode(v: Boolean) = io { prefs.setDemoMode(v) }
    fun setStartup(v: String) = io { prefs.setStartupSubreddit(v) }

    // History & privacy
    fun setMarkRead(v: Boolean) = io { prefs.setMarkRead(v) }
    fun setHideRead(v: Boolean) = io { prefs.setHideRead(v) }
    fun setSaveSearch(v: Boolean) = io { prefs.setSaveSearchHistory(v) }
    fun clearSearchHistory() = io { repo.clearSearchHistory(); _events.send("Search history cleared") }
    fun clearReadHistory() = io { repo.clearReadHistory(); _events.send("Read & hidden history cleared") }
    fun clearCache() = io { repo.clearAllCaches(); _events.send("Cache cleared") }

    // Account
    fun setClientId(v: String) = secure.setClientId(v)
    fun setRedirectUri(v: String) = secure.setRedirectUri(v)
    fun setSessionCookie(v: String) = io { secure.setSessionCookie(v); repo.clearHttpCache() }
    fun syncSubscriptions() = io {
        runCatching { repo.syncSubscriptions() }
            .onSuccess { _events.send("Subscriptions synced") }
            .onFailure { _events.send(RedditRepository.friendlyError(it)) }
    }
}
