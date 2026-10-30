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
 * OAuth login in an embedded WebView (the redirect is intercepted, so no browser hand-off is
 * needed). "Use browser instead" opens a Custom Tab; MainActivity catches relish://oauth.
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

    // Pop once the login completes (either path).
    LaunchedEffect(username) { if (username != null && username != initialUser) nav.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Log in to Reddit") },
                actions = {
                    if (clientId.isNotBlank()) {
                        TextButton(onClick = {
                            vm.loginUrl().onSuccess { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) }
                        }) {
                            Icon(Icons.Default.OpenInBrowser, null)
                            Text(" Browser")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (clientId.isBlank()) {
                ClientIdSetup(onSave = vm::setClientId)
            } else {
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
                                        val target = request.url.toString()
                                        if (vm.isRedirect(target)) {
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
