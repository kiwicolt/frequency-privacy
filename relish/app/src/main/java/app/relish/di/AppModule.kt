package app.relish.di

import android.content.Context
import androidx.room.Room
import app.relish.BuildConfig
import app.relish.data.local.AppDatabase
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.prefs.SecureStore
import app.relish.data.remote.OAuthApi
import app.relish.data.remote.OAuthInterceptor
import app.relish.data.remote.OAuthManager
import app.relish.data.remote.OfflineCacheInterceptor
import app.relish.data.remote.PublicApi
import app.relish.data.remote.PublicJsonInterceptor
import app.relish.data.remote.RateLimitInterceptor
import app.relish.data.remote.RateLimiter
import app.relish.data.remote.RawJsonInterceptor
import app.relish.data.remote.RedditApiService
import app.relish.data.remote.RedditAuthService
import app.relish.data.remote.ResponseCacheInterceptor
import app.relish.data.remote.TokenAuthenticator
import app.relish.data.remote.UserAgentInterceptor
import app.relish.util.Connectivity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides @Singleton
    fun httpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024)

    /** Base client: shared connection pool/dispatcher, no API-specific behaviour. Used for media. */
    @Provides @Singleton @Named("media")
    fun mediaClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor())
        .build()

    /** Shared stack for both Reddit API transports: UA, 60 req/min limiter, raw_json, HTTP cache. */
    @Provides @Singleton @Named("api")
    fun apiClient(
        @Named("media") base: OkHttpClient,
        cache: Cache,
        limiter: RateLimiter,
        connectivity: Connectivity,
    ): OkHttpClient = base.newBuilder()
        .cache(cache)
        .addInterceptor(OfflineCacheInterceptor(connectivity))
        .addInterceptor(RateLimitInterceptor(limiter))
        .addInterceptor(RawJsonInterceptor())
        .addNetworkInterceptor(ResponseCacheInterceptor())
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .build()

    @Provides @Singleton
    fun authService(@Named("api") client: OkHttpClient, json: Json): RedditAuthService = Retrofit.Builder()
        .baseUrl(RedditApiService.PUBLIC_BASE_URL)
        .client(client.newBuilder().cache(null).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RedditAuthService::class.java)

    @Provides @Singleton @OAuthApi
    fun oauthApi(@Named("api") client: OkHttpClient, oauth: OAuthManager, json: Json): RedditApiService = Retrofit.Builder()
        .baseUrl(RedditApiService.OAUTH_BASE_URL)
        .client(
            client.newBuilder()
                .addInterceptor(OAuthInterceptor(oauth))
                .authenticator(TokenAuthenticator(oauth))
                .build()
        )
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RedditApiService::class.java)

    @Provides @Singleton @PublicApi
    fun publicApi(
        @Named("api") client: OkHttpClient,
        secureStore: SecureStore,
        prefs: PreferencesDataStore,
        json: Json,
    ): RedditApiService = Retrofit.Builder()
        .baseUrl(RedditApiService.PUBLIC_BASE_URL)
        .client(client.newBuilder().addInterceptor(PublicJsonInterceptor(secureStore, prefs)).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RedditApiService::class.java)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "relish.db").build()

    @Provides fun readPostDao(db: AppDatabase) = db.readPosts()
    @Provides fun hiddenPostDao(db: AppDatabase) = db.hiddenPosts()
    @Provides fun filterDao(db: AppDatabase) = db.filters()
    @Provides fun searchHistoryDao(db: AppDatabase) = db.searchHistory()
    @Provides fun subscriptionDao(db: AppDatabase) = db.subscriptions()
    @Provides fun nsfwShortcutDao(db: AppDatabase) = db.nsfwShortcuts()
}
