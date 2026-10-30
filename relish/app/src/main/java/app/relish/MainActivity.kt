package app.relish

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.relish.ui.MainViewModel
import app.relish.ui.nav.RelishNavHost
import app.relish.ui.theme.RelishTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            RelishTheme(settings) {
                RelishNavHost(mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** OAuth redirect when login was completed in an external browser / Custom Tab. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if ((data.scheme == "relish" && data.host == "oauth") || (data.scheme == "redditisfun" && data.host == "auth")) {
            mainViewModel.completeLogin(data)
        }
    }
}
