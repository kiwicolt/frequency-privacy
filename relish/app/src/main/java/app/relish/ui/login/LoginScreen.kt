package app.relish.ui.login

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    fun loginUrl(): Result<String> = runCatching { repo.loginUrl() }
    fun isRedirect(url: String) = repo.isOAuthRedirect(url)
}

/**
 * OAuth login. Defaults to the system browser (Custom Tab): Google/Apple sign-in and an existing
 * reddit.com session work there, and MainActivity receives the relish://oauth redirect. The
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
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Log in to Reddit") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                clientId.isBlank() -> ClientIdSetup(onSave = vm::setClientId)
                embedded -> {
                    val url = remember(clientId) { vm.loginUrl().getOrNull() }
                    if (url != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
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
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("Log in with your Reddit account", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reddit's login page opens in your browser. Approve access there and you'll be brought " +
                            "back here automatically. This works with Google/Apple sign-in and any Reddit session " +
                            "already open in your browser.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = ::openBrowser, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInBrowser, null)
                        Text("  Log in with browser")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { embedded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Use in-app login instead (username + password only)")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "If Reddit shows an error page, the API app for this client id may not be approved yet. " +
                            "Browsing still works without logging in.",
                        style = MetaTextStyle,
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ClientIdSetup(onSave: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("One-time setup", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Reddit requires every client to use its own API key. This is free and takes a minute:\n\n" +
                "1. Open reddit.com/prefs/apps (desktop site) while logged in.\n" +
                "2. Click \"create another app…\" and choose the type \"installed app\".\n" +
                "3. Set the redirect URI to exactly:  relish://oauth\n" +
                "4. Copy the client id shown under the app name and paste it below.\n\n" +
                "Anonymous NSFW browsing works without this (via the web JSON layer); logging in unlocks " +
                "voting, saving, commenting, subscriptions, inbox and multireddits.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(id, { id = it.trim() }, label = { Text("Client id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Stored encrypted on this device only.", style = MetaTextStyle)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSave(id) }, enabled = id.length >= 10) { Text("Save and continue") }
    }
}
