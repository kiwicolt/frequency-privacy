@file:OptIn(UnstableApi::class)

package app.relish.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.relish.data.model.PostMedia
import app.relish.data.remote.USER_AGENT
import app.relish.ui.nav.LocalNavigator
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerScreen(vm: MediaViewerViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pageLabel by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            MediaState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is MediaState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = Color.White)
                s.fallbackUrl?.let { url -> TextButton(onClick = { nav.openExternalOrWeb(url) }) { Text("Open in browser") } }
            }
            is MediaState.Ready -> when (val m = s.media) {
                is PostMedia.Image -> ZoomableImage(m.url)
                is PostMedia.Video -> VideoPlayer(m.url, m.fallbackUrl, settings.autoMuteVideos, settings.loopVideos || m.isGif, showControls = !m.isGif)
                is PostMedia.Audio -> VideoPlayer(m.url, null, startMuted = false, loop = false, showControls = true)
                is PostMedia.Gallery -> {
                    val pager = rememberPagerState { m.items.size }
                    LaunchedEffect(pager.currentPage) {
                        val item = m.items[pager.currentPage]
                        pageLabel = "${pager.currentPage + 1} / ${m.items.size}" + (item.caption?.let { " · $it" } ?: "")
                    }
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
                        val item = m.items[page]
                        if (item.isVideo) {
                            VideoPlayer(item.url, null, startMuted = true, loop = true, showControls = false, active = pager.currentPage == page)
                        } else {
                            ZoomableImage(item.url)
                        }
                    }
                }
                is PostMedia.Redgifs, is PostMedia.Link, PostMedia.None -> LaunchedEffect(m) {
                    vm.shareUrl?.let(nav::openExternalOrWeb)
                }
            }
        }

        // Top chrome
        Row(
            Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                vm.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            vm.shareUrl?.let { url ->
                IconButton(onClick = { nav.share(vm.title, url) }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
                IconButton(onClick = { nav.openInBrowser(url) }) { Icon(Icons.Default.OpenInBrowser, "Open in browser", tint = Color.White) }
            }
        }
        pageLabel?.let {
            Text(
                it, color = Color.White, maxLines = 3,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000))
                    .navigationBarsPadding().padding(12.dp),
            )
        }
    }
}

/** Pinch/double-tap zoom that leaves single-finger swipes to an enclosing pager when not zoomed. */
@Composable
fun ZoomableImage(url: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - tap) * 1.5f, scale)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(url).size(2560, 2560).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Failed to load image", color = Color.White) } },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

/**
 * ExoPlayer for v.redd.it (HLS/DASH with audio), mp4/webm, imgur gifv, Redgifs and audio files.
 * Falls back to [fallbackUrl] (silent mp4) if the adaptive stream fails.
 */
@Composable
fun VideoPlayer(
    url: String,
    fallbackUrl: String?,
    startMuted: Boolean,
    loop: Boolean,
    showControls: Boolean,
    active: Boolean = true,
) {
    val context = LocalContext.current
    var muted by remember { mutableStateOf(startMuted) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember(url) {
        val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                volume = if (startMuted) 0f else 1f
                prepare()
            }
    }

    DisposableEffect(player) {
        var triedFallback = false
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (!triedFallback && fallbackUrl != null && fallbackUrl != url) {
                    triedFallback = true
                    player.setMediaItem(MediaItem.fromUri(fallbackUrl))
                    player.prepare()
                } else {
                    error = e.errorCodeName
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(active) { player.playWhenReady = active }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    val isActive by rememberUpdatedState(active)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (isActive) player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = showControls
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = { muted = !muted },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 8.dp)
                .background(Color(0x66000000), shape = androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White)
        }
        error?.let {
            Text("Playback error: $it", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}
