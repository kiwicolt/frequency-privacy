package app.relish.ui.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.remote.PublicJsonInterceptor
import app.relish.ui.nav.LocalNavigator
import app.relish.ui.theme.Relish
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WebViewModel @Inject constructor(prefs: PreferencesDataStore) : ViewModel() {
    val settings = prefs.current
}

/** Pre-seeds Reddit's age-gate cookies so NSFW pages render without the interstitial. */
fun seedRedditCookies(enabled: Boolean) {
    if (!enabled) return
    val cm = CookieManager.getInstance()
    listOf("https://www.reddit.com", "https://old.reddit.com", "https://reddit.com").forEach { origin ->
        cm.setCookie(origin, "over18=1; Domain=.reddit.com; Path=/")
        cm.setCookie(origin, "_options=${PublicJsonInterceptor.OPTIN_OPTIONS}; Domain=.reddit.com; Path=/")
    }
    cm.flush()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(url: String, vm: WebViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    var title by remember { mutableStateOf(url) }
    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Uri.parse(currentUrl).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Relish.colors.subtle, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.share(title, currentUrl) }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { nav.openInBrowser(currentUrl) }) { Icon(Icons.Default.OpenInBrowser, "Open in browser") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val s = vm.settings.value
                    seedRedditCookies(s.showNsfw && s.bypassAgePrompt)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme
                                if (scheme == "http" || scheme == "https") return false
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                            override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
