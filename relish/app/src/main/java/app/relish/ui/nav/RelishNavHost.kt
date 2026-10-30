package app.relish.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.relish.data.model.FeedSource
import app.relish.ui.MainViewModel
import app.relish.ui.comments.CommentsScreen
import app.relish.ui.feed.FeedScreen
import app.relish.ui.inbox.InboxScreen
import app.relish.ui.login.LoginScreen
import app.relish.ui.media.MediaViewerScreen
import app.relish.ui.search.SearchScreen
import app.relish.ui.settings.FilterScreen
import app.relish.ui.settings.SettingsScreen
import app.relish.ui.web.WebViewScreen

/** Lets any screen post a global snackbar without plumbing a host through every Scaffold. */
val LocalMessenger = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun RelishNavHost(main: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val navigator = remember(nav) { AppNavigator(nav, context) { main.settings.value } }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        main.messages.collect { snackbar.showSnackbar(it) }
    }

    val startSource = remember {
        main.settings.value.startupSubreddit.takeIf { it.isNotBlank() }?.let { FeedSource.Subreddit(it) } ?: FeedSource.FrontPage
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalMessenger provides { main.showMessage(it) }) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = Routes.FEED) {
                composable(
                    Routes.FEED,
                    arguments = listOf(navArgument("src") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    val src = entry.arguments?.getString("src")
                    FeedScreen(main = main, source = src?.let(FeedSource::decode) ?: startSource)
                }
                composable(
                    Routes.COMMENTS,
                    arguments = listOf(
                        navArgument("postId") { type = NavType.StringType },
                        navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { CommentsScreen() }
                composable(
                    Routes.MEDIA,
                    arguments = listOf(
                        navArgument("data") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { MediaViewerScreen() }
                composable(Routes.WEB, arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
                    WebViewScreen(url = entry.arguments?.getString("url").orEmpty())
                }
                composable(Routes.LOGIN) { LoginScreen(main) }
                composable(Routes.SETTINGS) { SettingsScreen(main) }
                composable(Routes.FILTERS) { FilterScreen() }
                composable(Routes.INBOX) { InboxScreen() }
                composable(
                    Routes.SEARCH,
                    arguments = listOf(navArgument("sub") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { SearchScreen() }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
