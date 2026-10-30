package app.relish.ui.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.relish.data.model.PostMedia
import app.relish.data.prefs.PreferencesDataStore
import app.relish.data.repo.RedditRepository
import app.relish.ui.nav.mediaJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import javax.inject.Inject

sealed interface MediaState {
    data object Loading : MediaState
    data class Ready(val media: PostMedia) : MediaState
    data class Failed(val message: String, val fallbackUrl: String?) : MediaState
}

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: RedditRepository,
    prefs: PreferencesDataStore,
) : ViewModel() {

    val title: String = savedState.get<String>("title").orEmpty()
    val settings = prefs.current

    private val original: PostMedia? = runCatching {
        mediaJson.decodeFromString<PostMedia>(checkNotNull(savedState.get<String>("data")))
    }.getOrNull()

    private val _state = MutableStateFlow<MediaState>(MediaState.Loading)
    val state: StateFlow<MediaState> = _state.asStateFlow()

    /** The URL to share / open externally for this media. */
    val shareUrl: String? = when (val m = original) {
        is PostMedia.Image -> m.url
        is PostMedia.Video -> m.fallbackUrl ?: m.url
        is PostMedia.Audio -> m.url
        is PostMedia.Gallery -> m.items.firstOrNull()?.url
        is PostMedia.Redgifs -> m.pageUrl
        is PostMedia.Link -> m.url
        else -> null
    }

    init {
        when (val m = original) {
            null -> _state.value = MediaState.Failed("Unsupported media", null)
            is PostMedia.Redgifs -> viewModelScope.launch {
                runCatching { repo.resolveRedgifs(m.id) }
                    .onSuccess { _state.value = MediaState.Ready(PostMedia.Video(it, isGif = false)) }
                    .onFailure { _state.value = MediaState.Failed("Couldn't load Redgifs video: ${it.message}", m.pageUrl) }
            }
            else -> _state.value = MediaState.Ready(m)
        }
    }
}
