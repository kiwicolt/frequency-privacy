package app.relish.ui.login

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.data.prefs.SecureStore
import app.relish.data.repo.RedditRepository
import app.relish.ui.MainViewModel
import app.relish.ui.common.rememberClientIdImporter
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.MetaTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repo: RedditRepository,
    private val secureStore: SecureStore,
) : ViewModel() {
    val clientId = secureStore.clientId
    fun setClientId(id: String) = secureStore.setClientId(id)
    fun loginUrl(page: Int = 0): Result<String> = runCatching { repo.loginUrl(page) }
    val pageCount = app.relish.data.remote.OAuthManager.AUTHORIZE_PAGES.size
    fun isRedirect(url: String) = repo.isOAuthRedirect(url)
}

/**
 * OAuth login. Defaults to the system browser (Custom Tab): Google/Apple sign-in and an existing
 * reddit.com session work there, and MainActivity receives the OAuth redirect. The
 * embedded WebView (redirect intercepted directly) is offered as a fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(main: MainViewModel, vm: LoginViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val clientId by vm.clientId.collectAsStateWithLifecycle()
    val username by main.username.collectAsStateWithLifecycle()
    val busy by main.loginInProgress.collectAsStateWithLifecycle()
    val initialUser = remember { username }
    var embedded by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    BackHandler(enabled = embedded) { embedded = false }
    val importClientId = rememberClientIdImporter { result ->
        result.onSuccess { vm.setClientId(it); main.showMessage("Client id set to $it"); embedded = false }
            .onFailure { main.showMessage(it.message ?: "Import failed") }
    }

    // Pop once the login completes (either path).
    LaunchedEffect(username) { if (username != null && username != initialUser) nav.back() }

    fun openBrowser() {
        vm.loginUrl()
            .onSuccess { url ->
                runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
                    .onFailure { embedded = true }
            }
            .onFailure { main.showMessage(RedditRepository.friendlyError(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (embedded) embedded = false else nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Log in to Reddit") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                clientId.isBlank() -> ClientIdSetup(onSave = vm::setClientId, onImport = importClientId, onDemo = { main.setDemoMode(true); nav.back() })
                embedded -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Login page ${page + 1} of ${vm.pageCount}", style = MetaTextStyle, modifier = Modifier.weight(1f))
                        TextButton(onClick = { page = (page + 1) % vm.pageCount }) { Text("Blank page? Try another") }
                    }
                    // Re-created per page so each attempt gets a fresh OAuth state and a clean load.
                    key(page) {
                        val url = remember(clientId, page) { vm.loginUrl(page).getOrNull() }
                        if (url != null) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                                if (vm.isRedirect(request.url.toString())) {
                                                    main.completeLogin(request.url)
                                                    return true
                                                }
                                                return false
                                            }
                                        }
                                        loadUrl(url)
                                    }
                                },
                                onRelease = { it.destroy() },
                            )
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("Log in with your Reddit account", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sign in with your Reddit username and password, then tap Allow. You'll be brought " +
                            "back here automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { embedded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Log in")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = ::openBrowser, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInBrowser, null)
                        Text("  Use browser instead (for Google/Apple sign-in)")
                    }
                    Text(
                        "With the browser, Android may ask which app should finish the login if Reddit is Fun " +
                            "is also installed. Choose Relish.",
                        style = MetaTextStyle,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { main.setDemoMode(true); nav.back() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("No client id yet? Try demo mode (offline sample content)")
                    }
                    Text("Client id: $clientId", style = MetaTextStyle)
                    TextButton(onClick = importClientId, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Import client id from file (reddit_client_id_revanced.txt)")
                    }
                    Text(
                        "If Reddit says \"Invalid or missing client_id\", the id above doesn't match your Reddit app. " +
                            "Import it from the ReVanced file, or copy it from reddit.com/prefs/apps.",
                        style = MetaTextStyle,
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ClientIdSetup(onSave: (String) -> Unit, onImport: () -> Unit, onDemo: () -> Unit) {
    var id by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("One-time setup", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Reddit requires every client to use its own API key. This is free and takes a minute:\n\n" +
                "1. Open reddit.com/prefs/apps (desktop site) while logged in.\n" +
                "2. Click \"create another app…\" and choose the type \"installed app\".\n" +
                "3. Set the redirect URI to exactly:  redditisfun://auth\n" +
                "4. Copy the client id shown under the app name and paste it below.\n\n" +
                "Reddit blocks logged-out access from apps, so Relish needs this to load anything.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(id, { id = it.trim() }, label = { Text("Client id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Stored encrypted on this device only.", style = MetaTextStyle)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSave(id) }, enabled = id.length >= 10) { Text("Save and continue") }
        TextButton(onClick = onImport) { Text("Or import it from a file (e.g. reddit_client_id_revanced.txt)") }
        TextButton(onClick = onDemo) { Text("No client id yet? Try demo mode (offline sample content)") }
    }
}
