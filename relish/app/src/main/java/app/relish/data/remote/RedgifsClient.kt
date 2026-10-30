package app.relish.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Resolves a Redgifs id to a direct mp4 using their public temporary-token API. */
@Singleton
class RedgifsClient @Inject constructor(
    @Named("media") private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var tokenAt = 0L

    suspend fun resolve(id: String): String = withContext(Dispatchers.IO) {
        val result = runCatching { fetch(id, authToken(false)) }
            .recoverCatching { fetch(id, authToken(true)) }
        result.getOrThrow()
    }

    private fun fetch(id: String, bearer: String): String {
        val req = Request.Builder().url("https://api.redgifs.com/v2/gifs/$id")
            .header("Authorization", "Bearer $bearer")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Redgifs HTTP ${res.code}")
            val body = json.parseToJsonElement(res.body!!.string())
            val urls = body.obj("gif")?.obj("urls")
            return urls.str("hd") ?: urls.str("sd") ?: throw IOException("Redgifs returned no video")
        }
    }

    private suspend fun authToken(force: Boolean): String = mutex.withLock {
        val cached = token
        if (!force && cached != null && System.currentTimeMillis() - tokenAt < 20 * 60 * 60 * 1000L) return cached
        val req = Request.Builder().url("https://api.redgifs.com/v2/auth/temporary").header("User-Agent", USER_AGENT).build()
        val fresh = client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Redgifs auth HTTP ${res.code}")
            (json.parseToJsonElement(res.body!!.string()) as JsonObject).str("token") ?: throw IOException("No Redgifs token")
        }
        token = fresh
        tokenAt = System.currentTimeMillis()
        fresh
    }
}
