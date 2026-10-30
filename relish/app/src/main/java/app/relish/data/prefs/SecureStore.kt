package app.relish.data.prefs

import android.content.Context
import android.content.SharedPreferences
import app.relish.BuildConfig
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class OAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    /** false for an application-only ("userless") token. */
    val isUser: Boolean,
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = now >= expiresAtMillis - 60_000
}

/**
 * Credentials, OAuth tokens and session cookies, encrypted at rest with an AndroidKeyStore-backed
 * AES-256 master key. Never backed up (see data_extraction_rules.xml).
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "relish_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, null))
    /** Logged-in Reddit username, or null when browsing anonymously. */
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT_ID, null)?.ifBlank { null } ?: BuildConfig.REDDIT_CLIENT_ID)
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    private val _sessionCookie = MutableStateFlow(prefs.getString(KEY_COOKIE, null).orEmpty())
    /** Optional raw `Cookie` header copied from a logged-in browser session (e.g. `reddit_session=...`). */
    val sessionCookie: StateFlow<String> = _sessionCookie.asStateFlow()

    val redirectUri: String get() = prefs.getString(KEY_REDIRECT, null) ?: DEFAULT_REDIRECT

    /** Stable per-install id required by the installed_client grant. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    /** A blank value restores the built-in client id. */
    fun setClientId(id: String) {
        val trimmed = id.trim().ifBlank { BuildConfig.REDDIT_CLIENT_ID }
        if (trimmed != _clientId.value) {
            prefs.edit { putString(KEY_CLIENT_ID, trimmed) }
            _clientId.value = trimmed
            clearToken() // tokens are bound to the client id
        }
    }

    fun setRedirectUri(uri: String) = prefs.edit { putString(KEY_REDIRECT, uri.trim().ifBlank { DEFAULT_REDIRECT }) }

    fun setSessionCookie(cookie: String) {
        prefs.edit { putString(KEY_COOKIE, cookie.trim()) }
        _sessionCookie.value = cookie.trim()
    }

    @get:Synchronized
    @set:Synchronized
    var token: OAuthToken?
        get() {
            val access = prefs.getString(KEY_ACCESS, null) ?: return null
            return OAuthToken(
                accessToken = access,
                refreshToken = prefs.getString(KEY_REFRESH, null),
                expiresAtMillis = prefs.getLong(KEY_EXPIRES, 0),
                isUser = prefs.getBoolean(KEY_IS_USER, false),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_ACCESS); remove(KEY_REFRESH); remove(KEY_EXPIRES); remove(KEY_IS_USER)
            } else {
                putString(KEY_ACCESS, value.accessToken)
                // Refresh responses omit the refresh token; keep the one we have.
                putString(KEY_REFRESH, value.refreshToken ?: prefs.getString(KEY_REFRESH, null))
                putLong(KEY_EXPIRES, value.expiresAtMillis)
                putBoolean(KEY_IS_USER, value.isUser)
            }
        }

    /** CSRF `state` for an in-flight OAuth login; persisted so it survives process death. */
    var pendingOAuthState: String?
        get() = prefs.getString(KEY_STATE, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_STATE) else putString(KEY_STATE, value) }

    fun setUsername(name: String?) {
        prefs.edit { if (name == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, name) }
        _username.value = name
    }

    fun clearToken() {
        token = null
        setUsername(null)
    }

    companion object {
        const val DEFAULT_REDIRECT = "relish://oauth"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_REDIRECT = "redirect_uri"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_IS_USER = "is_user"
        private const val KEY_USERNAME = "username"
        private const val KEY_COOKIE = "session_cookie"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_STATE = "oauth_state"
    }
}
