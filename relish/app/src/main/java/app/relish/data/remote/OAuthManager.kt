package app.relish.data.remote

import android.net.Uri
import app.relish.data.prefs.OAuthToken
import app.relish.data.prefs.SecureStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class OAuthException(message: String) : IOException(message)

/**
 * OAuth2 for a Reddit "installed app" (no client secret):
 *  - authorization-code flow with `duration=permanent` for the user account,
 *  - `installed_client` application-only grant when browsing logged out through OAuth.
 *
 * Register an app of type *installed app* at https://www.reddit.com/prefs/apps with redirect URI
 * `relish://oauth`, then paste its client id into Settings › Account.
 */
@Singleton
class OAuthManager @Inject constructor(
    private val store: SecureStore,
    private val auth: RedditAuthService,
) {
    private val mutex = Mutex()

    val hasClientId: Boolean get() = store.clientId.value.isNotBlank()
    val isLoggedIn: Boolean get() = store.username.value != null && store.token?.isUser == true

    fun currentAccessToken(): String? = store.token?.accessToken

    fun authorizeUrl(page: Int = 0): String {
        val state = UUID.randomUUID().toString().also { store.pendingOAuthState = it }
        return Uri.parse(AUTHORIZE_PAGES[page.mod(AUTHORIZE_PAGES.size)]).buildUpon()
            .appendQueryParameter("client_id", store.clientId.value)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("redirect_uri", store.redirectUri)
            .appendQueryParameter("duration", "permanent")
            .appendQueryParameter("scope", SCOPES)
            .build().toString()
    }

    fun isRedirect(url: String): Boolean = url.startsWith(store.redirectUri)

    /** Handles `relish://oauth?state=…&code=…`. Returns normally on success. */
    suspend fun completeLogin(redirect: Uri) {
        redirect.getQueryParameter("error")?.let {
            throw OAuthException(
                if (it == "access_denied") "Login cancelled" else "Reddit rejected the login ($it). Check the app's client id and redirect URI (relish://oauth)."
            )
        }
        val state = redirect.getQueryParameter("state")
        if (state == null || state != store.pendingOAuthState) throw OAuthException("OAuth state mismatch; please try again")
        val code = redirect.getQueryParameter("code") ?: throw OAuthException("No authorization code returned")
        store.pendingOAuthState = null
        mutex.withLock {
            val res = try {
                auth.accessToken(
                    basic(),
                    mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to store.redirectUri),
                )
            } catch (e: retrofit2.HttpException) {
                throw OAuthException("Reddit refused the token exchange (HTTP ${e.code()}). The API app may not be approved yet.")
            }
            store.token = res.toToken(isUser = true)
        }
    }

    suspend fun validToken(forceRefresh: Boolean): String = mutex.withLock {
        val current = store.token
        if (current != null && !forceRefresh && !current.isExpired()) return current.accessToken
        if (!hasClientId) throw OAuthException("No Reddit client id configured")

        val fresh = if (current?.isUser == true && current.refreshToken != null) {
            auth.accessToken(basic(), mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken))
                .toToken(isUser = true)
        } else {
            auth.accessToken(
                basic(),
                mapOf("grant_type" to "https://oauth.reddit.com/grants/installed_client", "device_id" to store.deviceId),
            ).toToken(isUser = false)
        }
        store.token = fresh
        fresh.accessToken
    }

    /**
     * For OkHttp interceptors. Anything other than an IOException thrown from an interceptor would
     * crash OkHttp's dispatcher thread, so HTTP errors from the token endpoint are wrapped.
     */
    fun validTokenBlocking(forceRefresh: Boolean): String = try {
        runBlocking { validToken(forceRefresh) }
    } catch (e: IOException) {
        throw e
    } catch (e: retrofit2.HttpException) {
        throw OAuthException(
            if (e.code() == 401) "Reddit rejected the client id (401). The API app may not be approved yet."
            else "Token request failed (HTTP ${e.code()})"
        )
    } catch (e: Exception) {
        throw OAuthException("Token request failed: ${e.message}")
    }

    suspend fun logout() {
        val token = store.token
        if (token?.refreshToken != null && hasClientId) {
            runCatching { auth.revoke(basic(), token.refreshToken, "refresh_token") }
        }
        store.clearToken()
    }

    private fun basic() = Credentials.basic(store.clientId.value, "")

    private fun TokenResponse.toToken(isUser: Boolean): OAuthToken {
        if (accessToken == null) {
            throw OAuthException(
                if (error == "unauthorized_client" || error == "invalid_grant") "Reddit rejected client id (\"$error\"). The API app may not be approved yet."
                else "Token request failed: ${error ?: "unknown error"}"
            )
        }
        return OAuthToken(accessToken, refreshToken, System.currentTimeMillis() + expiresIn * 1000, isUser)
    }

    companion object {
        /**
         * Reddit's authorization page, in order of preference. The first is the classic HTML form
         * (the same host ReVanced's Reddit is Fun uses); the modern page sometimes renders blank
         * in WebViews, so the login screen lets you switch.
         */
        val AUTHORIZE_PAGES = listOf(
            "https://ssl.reddit.com/api/v1/authorize.compact",
            "https://www.reddit.com/api/v1/authorize",
            "https://old.reddit.com/api/v1/authorize.compact",
        )

        const val SCOPES = "identity edit history mysubreddits privatemessages read report save submit subscribe vote account"
    }
}
