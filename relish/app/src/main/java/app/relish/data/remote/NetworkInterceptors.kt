package app.relish.data.remote

import android.os.SystemClock
import app.relish.BuildConfig
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.SecureStore
import app.relish.util.Connectivity
import okhttp3.Authenticator
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val USER_AGENT = "android:app.relish:v${BuildConfig.VERSION_NAME} (personal client)"

/**
 * The public `.json` pages are served to browsers; Reddit refuses them (403) for API-style user
 * agents, so the web transport identifies as the phone's mobile browser would.
 */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/**
 * Client-side sliding-window limiter shared by every API request (both transports): at most
 * [maxRequests] per [windowMs]. Also honours Reddit's `x-ratelimit-*` headers when the server says
 * the budget is exhausted. Blocking by design — it runs on OkHttp's dispatcher threads.
 */
@Singleton
class RateLimiter(private val maxRequests: Int, private val windowMs: Long) {
    @Inject constructor() : this(60, 60_000)

    private val stamps = ArrayDeque<Long>()
    private var serverResetAt = 0L

    fun acquire() {
        while (true) {
            val wait: Long
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (serverResetAt > now) {
                    wait = serverResetAt - now
                } else {
                    while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
                    if (stamps.size < maxRequests) {
                        stamps.addLast(now)
                        return
                    }
                    wait = windowMs - (now - stamps.first())
                }
            }
            try {
                Thread.sleep(wait.coerceIn(10, windowMs))
            } catch (e: InterruptedException) {
                throw InterruptedIOException("Rate limiter interrupted")
            }
        }
    }

    @Synchronized
    fun onServerBudget(remaining: Double?, resetSeconds: Long?) {
        if (remaining != null && resetSeconds != null && remaining < 1.0) {
            serverResetAt = SystemClock.elapsedRealtime() + resetSeconds * 1000
        }
    }
}

class RateLimitInterceptor(private val limiter: RateLimiter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Requests answered from the local cache never hit Reddit, but we can't know that yet;
        // being conservative keeps us safely under 60/min.
        limiter.acquire()
        val response = chain.proceed(chain.request())
        limiter.onServerBudget(
            response.header("x-ratelimit-remaining")?.toDoubleOrNull(),
            response.header("x-ratelimit-reset")?.toLongOrNull(),
        )
        return response
    }
}

class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
}

/** `raw_json=1` stops Reddit from HTML-escaping `&`, `<` and `>` inside JSON strings. */
class RawJsonInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET") return chain.proceed(req)
        val url = req.url.newBuilder().setQueryParameter("raw_json", "1").build()
        return chain.proceed(req.newBuilder().url(url).build())
    }
}

/**
 * The NSFW bypass layer for the [PublicApi] transport:
 *  - rewrites `/r/x/hot` → `/r/x/hot.json` (the website's JSON rendering),
 *  - sends `over18=1` plus the quarantine/gated opt-in `_options` cookie so age-gated and NSFW
 *    listings are returned instead of an interstitial,
 *  - appends any user-supplied browser session cookie (e.g. `reddit_session=…`) for
 *    account-personalised results without OAuth.
 */
class PublicJsonInterceptor(
    private val secureStore: SecureStore,
    private val prefs: PreferencesDataStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        val segments = url.pathSegments
        val last = segments.lastOrNull().orEmpty()
        val newUrl = if (req.method == "GET" && last.isNotEmpty() && !last.endsWith(".json")) {
            url.newBuilder().setPathSegment(segments.size - 1, "$last.json").build()
        } else url

        val settings = prefs.current.value
        val cookies = buildList {
            if (settings.showNsfw && settings.bypassAgePrompt) {
                add("over18=1")
                add("_options=$OPTIN_OPTIONS")
            }
            secureStore.sessionCookie.value.takeIf { it.isNotBlank() }?.let { add(it.trim().trimEnd(';')) }
        }
        val builder = req.newBuilder().url(newUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies.joinToString("; "))
        return chain.proceed(builder.build())
    }

    companion object {
        /** URL-encoded `{"pref_quarantine_optin": true, "pref_gated_sub_optin": true}`. */
        const val OPTIN_OPTIONS = "%7B%22pref_quarantine_optin%22%3A%20true%2C%20%22pref_gated_sub_optin%22%3A%20true%7D"
    }
}

/** Adds the bearer token for the [OAuthApi] transport, obtaining/refreshing it as needed. */
class OAuthInterceptor(private val oauth: OAuthManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = oauth.validTokenBlocking(forceRefresh = false)
        return chain.proceed(chain.request().newBuilder().header("Authorization", "bearer $token").build())
    }
}

/** Retries once with a fresh token when Reddit answers 401. */
class TokenAuthenticator(private val oauth: OAuthManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried
        val sent = response.request.header("Authorization")?.removePrefix("bearer ")
        val token = try {
            // Another thread may already have refreshed; only force when ours is the stale one.
            oauth.validTokenBlocking(forceRefresh = sent == oauth.currentAccessToken())
        } catch (e: IOException) {
            return null
        }
        return response.request.newBuilder().header("Authorization", "bearer $token").build()
    }
}

/** Serves stale cached responses when the device is offline. */
class OfflineCacheInterceptor(private val connectivity: Connectivity) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var req = chain.request()
        if (req.method == "GET" && !connectivity.isOnline) {
            req = req.newBuilder()
                .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(7, TimeUnit.DAYS).build())
                .build()
        }
        return chain.proceed(req)
    }
}

/**
 * Network interceptor: Reddit marks API responses `no-store`. Override that for successful GETs so
 * the OkHttp disk cache can answer repeat requests for [maxAgeSeconds] and serve them offline.
 */
class ResponseCacheInterceptor(private val maxAgeSeconds: Int = 60) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().method != "GET" || !response.isSuccessful) return response
        return response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "private, max-age=$maxAgeSeconds")
            .build()
    }
}
