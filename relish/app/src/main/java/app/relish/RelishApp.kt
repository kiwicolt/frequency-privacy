package app.relish

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class RelishApp : Application(), ImageLoaderFactory {

    @Inject @Named("media") lateinit var mediaClient: OkHttpClient

    /** Images bypass the API rate limiter: they come from CDNs (i.redd.it, preview.redd.it, imgur). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(mediaClient)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
