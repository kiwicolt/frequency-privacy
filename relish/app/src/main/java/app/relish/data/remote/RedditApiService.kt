package app.relish.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Qualifier

/** Retrofit bound to `https://oauth.reddit.com/` with a bearer token (user or application-only). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class OAuthApi

/**
 * Retrofit bound to `https://www.reddit.com/` with no token. [PublicJsonInterceptor] appends `.json`
 * to every path and sends the `over18=1` (+ optional browser session) cookie, so NSFW listings that
 * the third-party OAuth API suppresses are returned the same way the website serves them.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PublicApi

/**
 * One interface, two transports. Reddit serves identical paths from `oauth.reddit.com/<path>` and
 * `www.reddit.com/<path>.json`, so the same declarations back both the [OAuthApi] and [PublicApi]
 * instances. The repository decides which instance to use per request (see `ContentRouting`).
 *
 * Read endpoints take an optional `Cache-Control` header: pass `no-cache` for a user-initiated
 * refresh to bypass the local HTTP cache.
 */
interface RedditApiService {

    // ---------------------------------------------------------------------------------------
    // Browsing — valid on both transports
    // ---------------------------------------------------------------------------------------

    @GET("{sort}")
    suspend fun frontPage(
        @Path("sort") sort: String,
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    /** [subreddit] may be a single name, `all`, `popular`, or a `a+b+c` combination. */
    @GET("r/{subreddit}/{sort}")
    suspend fun subreddit(
        @Path("subreddit") subreddit: String,
        @Path("sort") sort: String,
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    @GET("user/{owner}/m/{multi}/{sort}")
    suspend fun multireddit(
        @Path("owner") owner: String,
        @Path("multi") multi: String,
        @Path("sort") sort: String,
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    /** [where] = overview | submitted | comments | upvoted | downvoted | hidden | saved | gilded. */
    @GET("user/{user}/{where}")
    suspend fun user(
        @Path("user") user: String,
        @Path("where") where: String,
        @Query("sort") sort: String? = null,
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("sort") sort: String = "relevance",
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Query("type") type: String = "link",
        @Query("include_over_18") includeOver18: String = "on",
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    @GET("r/{subreddit}/search")
    suspend fun searchSubreddit(
        @Path("subreddit") subreddit: String,
        @Query("q") query: String,
        @Query("restrict_sr") restrict: String = "on",
        @Query("sort") sort: String = "relevance",
        @Query("t") time: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Query("include_over_18") includeOver18: String = "on",
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    /** Returns `[postListing, commentListing]`. */
    @GET("comments/{id}")
    suspend fun comments(
        @Path("id") postId: String,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int = 500,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    /** A single comment thread ("context" view) with [context] parent levels. */
    @GET("comments/{id}/_/{comment}")
    suspend fun commentThread(
        @Path("id") postId: String,
        @Path("comment") commentId: String,
        @Query("context") context: Int = 3,
        @Query("sort") sort: String? = null,
        @Header("Cache-Control") cacheControl: String? = null,
    ): JsonElement

    @GET("api/morechildren")
    suspend fun moreChildren(
        @Query("link_id") linkFullname: String,
        @Query("children") children: String,
        @Query("sort") sort: String? = null,
        @Query("api_type") apiType: String = "json",
        @Query("limit_children") limitChildren: Boolean = false,
    ): JsonElement

    @GET("r/{subreddit}/about")
    suspend fun subredditAbout(@Path("subreddit") subreddit: String): JsonElement

    @GET("subreddits/search")
    suspend fun searchSubreddits(
        @Query("q") query: String,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Query("include_over_18") includeOver18: String = "on",
    ): JsonElement

    // ---------------------------------------------------------------------------------------
    // Account — OAuth transport only (user token required)
    // ---------------------------------------------------------------------------------------

    @GET("api/v1/me")
    suspend fun me(): JsonElement

    @GET("api/v1/me/prefs")
    suspend fun getPrefs(): JsonElement

    /** Account-level content prefs. Used to flip `over_18`/`search_include_over_18` on at login. */
    @PATCH("api/v1/me/prefs")
    suspend fun updatePrefs(@Body prefs: JsonObject): JsonElement

    @GET("subreddits/mine/subscriber")
    suspend fun mySubscriptions(@Query("after") after: String? = null, @Query("limit") limit: Int = 100): JsonElement

    @GET("api/multi/mine")
    suspend fun myMultireddits(): JsonElement

    /** [where] = inbox | unread | messages | comments | selfreply | mentions | sent. */
    @GET("message/{where}")
    suspend fun inbox(
        @Path("where") where: String,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = PAGE_SIZE,
        @Query("mark") mark: Boolean = false,
        @Header("Cache-Control") cacheControl: String? = "no-cache",
    ): JsonElement

    @FormUrlEncoded
    @POST("api/read_message")
    suspend fun readMessage(@Field("id") fullnames: String): Response<Unit>

    /** [dir] is 1, 0 or -1. */
    @FormUrlEncoded
    @POST("api/vote")
    suspend fun vote(@Field("id") fullname: String, @Field("dir") dir: Int): Response<Unit>

    @FormUrlEncoded
    @POST("api/save")
    suspend fun save(@Field("id") fullname: String): Response<Unit>

    @FormUrlEncoded
    @POST("api/unsave")
    suspend fun unsave(@Field("id") fullname: String): Response<Unit>

    @FormUrlEncoded
    @POST("api/hide")
    suspend fun hide(@Field("id") fullnames: String): Response<Unit>

    @FormUrlEncoded
    @POST("api/unhide")
    suspend fun unhide(@Field("id") fullnames: String): Response<Unit>

    /** [action] = sub | unsub. */
    @FormUrlEncoded
    @POST("api/subscribe")
    suspend fun subscribe(
        @Field("action") action: String,
        @Field("sr_name") subredditName: String,
        @Field("skip_initial_defaults") skipDefaults: Boolean = true,
    ): Response<Unit>

    /** Reply to a post (t3_) or comment (t1_). */
    @FormUrlEncoded
    @POST("api/comment")
    suspend fun comment(
        @Field("thing_id") parentFullname: String,
        @Field("text") markdown: String,
        @Field("api_type") apiType: String = "json",
    ): JsonElement

    companion object {
        const val PAGE_SIZE = 50
        const val OAUTH_BASE_URL = "https://oauth.reddit.com/"
        const val PUBLIC_BASE_URL = "https://www.reddit.com/"
        const val NO_CACHE = "no-cache"
    }
}

/** Token endpoint on www.reddit.com, authenticated with HTTP Basic `client_id:` (installed apps have no secret). */
interface RedditAuthService {
    @FormUrlEncoded
    @POST("api/v1/access_token")
    suspend fun accessToken(
        @Header("Authorization") basicAuth: String,
        @FieldMap fields: Map<String, String>,
    ): TokenResponse

    @FormUrlEncoded
    @POST("api/v1/revoke_token")
    suspend fun revoke(
        @Header("Authorization") basicAuth: String,
        @Field("token") token: String,
        @Field("token_type_hint") hint: String,
    ): Response<Unit>
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("scope") val scope: String? = null,
    @SerialName("error") val error: String? = null,
)
