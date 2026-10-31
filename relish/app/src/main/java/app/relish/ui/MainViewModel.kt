package app.relish.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.relish.data.local.SubscriptionEntity
import app.relish.data.model.Account
import app.relish.data.model.Multireddit
import app.relish.data.prefs.AppSettings
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.repo.RedditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity-scoped state: settings, account, drawer contents, global snackbar messages. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: RedditRepository,
    private val prefs: PreferencesDataStore,
    private val rifImporter: app.relish.data.rif.RifBackupImporter,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.current

    val username: StateFlow<String?> = repo.username

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _multis = MutableStateFlow<List<Multireddit>>(emptyList())
    val multis: StateFlow<List<Multireddit>> = _multis.asStateFlow()

    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        repo.subscriptions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nsfwShortcuts: StateFlow<List<String>> =
        repo.nsfwShortcuts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _loginInProgress = MutableStateFlow(false)
    val loginInProgress: StateFlow<Boolean> = _loginInProgress.asStateFlow()

    init {
        refreshAccount(syncSubs = true)
    }

    fun refreshAccount(syncSubs: Boolean = false) = viewModelScope.launch {
        if (!repo.isLoggedIn) {
            _account.value = null
            _multis.value = emptyList()
            return@launch
        }
        _account.value = repo.account()
        runCatching { _multis.value = repo.multireddits() }
        if (syncSubs) runCatching { repo.syncSubscriptions() }
    }

    fun completeLogin(uri: Uri) = viewModelScope.launch {
        _loginInProgress.value = true
        runCatching { repo.completeLogin(uri) }
            .onSuccess { _messages.send("Logged in as u/${it.name}"); refreshAccount() }
            .onFailure { _messages.send(RedditRepository.friendlyError(it)) }
        _loginInProgress.value = false
    }

    fun logout() = viewModelScope.launch {
        repo.logout()
        _account.value = null
        _multis.value = emptyList()
        _messages.send("Logged out")
    }

    fun setDemoMode(on: Boolean) = viewModelScope.launch {
        prefs.setDemoMode(on)
        _messages.send(if (on) "Demo mode on: showing offline sample content" else "Demo mode off")
    }

    fun setTheme(mode: app.relish.data.prefs.ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }

    fun toggleNightMode(systemDark: Boolean) = viewModelScope.launch { prefs.toggleNightMode(systemDark) }

    fun setSubscribed(name: String, subscribed: Boolean, over18: Boolean = false) = viewModelScope.launch {
        runCatching { repo.setSubscribed(name, subscribed, over18) }
            .onSuccess { _messages.send(if (subscribed) "Subscribed to r/$name" else "Unsubscribed from r/$name") }
            .onFailure { _messages.send(RedditRepository.friendlyError(it)) }
    }

    fun setFavorite(name: String, favorite: Boolean) = viewModelScope.launch { repo.setFavorite(name, favorite) }

    fun filterSubreddit(name: String) = viewModelScope.launch {
        repo.addFilter(app.relish.data.local.FilterType.SUBREDDIT, name)
        _messages.send("r/$name filtered from front page & r/all")
    }

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Imports a Reddit is Fun backup zip (subscriptions, read history, filters, settings). */
    fun importRifBackup(uri: android.net.Uri) = viewModelScope.launch {
        _importing.value = true
        _messages.send("Importing Reddit is Fun backup…")
        runCatching { rifImporter.import(uri) }
            .onSuccess { _messages.send(it.toString()) }
            .onFailure { _messages.send(it.message ?: "Import failed") }
        _importing.value = false
    }

    fun importSubscriptions(text: String) = viewModelScope.launch {
        val n = runCatching { repo.importSubscriptions(text) }.getOrElse { _messages.send(RedditRepository.friendlyError(it)); return@launch }
        _messages.send(if (n == 0) "No new subreddits found in that text" else "Imported $n subreddit${if (n == 1) "" else "s"}")
    }

    fun addNsfwShortcut(name: String) = viewModelScope.launch { repo.addNsfwShortcut(name) }
    fun removeNsfwShortcut(name: String) = viewModelScope.launch { repo.removeNsfwShortcut(name) }

    fun showMessage(text: String) = viewModelScope.launch { _messages.send(text) }
}
