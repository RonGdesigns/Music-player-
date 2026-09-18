package com.irondigital.spindle.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.irondigital.spindle.data.lyrics.Lyrics
import com.irondigital.spindle.data.lyrics.LyricsLookup
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.settings.Settings
import com.irondigital.spindle.playback.PlaybackService
import com.irondigital.spindle.playback.await
import com.irondigital.spindle.spindle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Playback state for the UI.
 *
 * The UI never owns a player. It connects to the same MediaSession the widget
 * and the notification use, so there is no second source of truth to keep in
 * step — what the screen shows is, by construction, what is coming out of the
 * speakers.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.spindle

    private var controller: MediaController? = null
    private var positionJob: Job? = null

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _lyrics = MutableStateFlow(Lyrics.NONE)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    private val _lyricsLookup = MutableStateFlow<LyricsLookupState>(LyricsLookupState.Idle)
    val lyricsLookup: StateFlow<LyricsLookupState> = _lyricsLookup.asStateFlow()

    val settings: StateFlow<Settings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val favoriteIds: StateFlow<Set<String>> = app.collections.favoriteIdSet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val playCounts: StateFlow<Map<String, Int>> = app.stats.statsByMediaId
        .map { stats -> stats.mapValues { it.value.playCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The current track resolved against the library, for art and file details. */
    val currentTrack: StateFlow<Track?> = combine(
        _playback.map { it.mediaId },
        app.library.tracks,
    ) { mediaId, _ -> mediaId?.let { app.library.trackFor(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { connect() }

        // Lyrics follow the track, and loading them touches the disk, so this
        // is deliberately off the transition path.
        currentTrack
            .onEach { track ->
                _lyrics.value = Lyrics.NONE
                _lyricsLookup.value = LyricsLookupState.Idle
                if (track == null) return@onEach

                _lyrics.value = app.lyrics.load(track)

                // Only when the user has switched lookup on, only when the file
                // itself had nothing, and only once per track — the repository
                // remembers an answer of "there are none" so a track without
                // lyrics is not asked about every time it plays.
                if (_lyrics.value.isEmpty &&
                    settings.value.lyricsLookupEnabled &&
                    !app.lyrics.alreadyLookedUp(track)
                ) {
                    lookUpLyrics(track)
                }
            }
            .launchIn(viewModelScope)

        // The queue is stored as media ids and resolved against the library, so
        // a queue restored before the first scan finishes would render empty and
        // stay that way until the next player event. Re-resolve when the scan
        // lands instead.
        app.library.tracks
            .onEach { resolveQueue() }
            .launchIn(viewModelScope)

        // The audio session id only exists on the player inside the service,
        // so it reaches the UI through the same snapshot the widget reads.
        app.snapshotStore.snapshot
            .onEach { snapshot ->
                if (snapshot.audioSessionId != _playback.value.audioSessionId) {
                    _playback.value = _playback.value.copy(audioSessionId = snapshot.audioSessionId)
                }
            }
            .launchIn(viewModelScope)

        favoriteIds
            .onEach { favorites ->
                _playback.value = _playback.value.copy(
                    isFavorite = _playback.value.mediaId in favorites
                )
            }
            .launchIn(viewModelScope)
    }

    private suspend fun connect() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java),
        )
        val connected = runCatching {
            MediaController.Builder(getApplication<Application>(), token).buildAsync().await()
        }.getOrNull() ?: return

        controller = connected
        connected.addListener(ControllerListener())
        syncFromController()
    }

    override fun onCleared() {
        positionJob?.cancel()
        controller?.release()
        controller = null
        super.onCleared()
    }

    // --------------------------------------------------------------- intent

    /**
     * Starts a queue. [startIndex] is the track tapped; everything in [tracks]
     * becomes the queue, which is what makes "play this album from track 7"
     * behave the way people expect.
     */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        val controller = controller ?: return
        if (tracks.isEmpty()) return
        controller.setMediaItems(
            tracks.map { it.toMediaItem() },
            startIndex.coerceIn(0, tracks.lastIndex),
            0L,
        )
        controller.prepare()
        controller.play()
    }

    fun shufflePlay(tracks: List<Track>) {
        val controller = controller ?: return
        if (tracks.isEmpty()) return
        controller.shuffleModeEnabled = true
        play(tracks, tracks.indices.random())
    }

    fun playPause() {
        val controller = controller ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun seekToQueueIndex(index: Int) = controller?.seekTo(index, 0L)

    fun toggleShuffle() {
        val controller = controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val controller = controller ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Appends to the end of the queue without disturbing what is playing. */
    fun addToQueue(tracks: List<Track>) {
        val controller = controller ?: return
        controller.addMediaItems(tracks.map { it.toMediaItem() })
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
    }

    /** Inserts directly after the current track. */
    fun playNext(tracks: List<Track>) {
        val controller = controller ?: return
        val insertAt = (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
        controller.addMediaItems(insertAt, tracks.map { it.toMediaItem() })
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
    }

    fun removeFromQueue(index: Int) {
        val controller = controller ?: return
        if (index in 0 until controller.mediaItemCount) controller.removeMediaItem(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        val controller = controller ?: return
        if (from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount) {
            controller.moveMediaItem(from, to)
        }
    }

    fun toggleFavorite() {
        val mediaId = _playback.value.mediaId ?: return
        viewModelScope.launch {
            app.collections.setFavorite(mediaId, mediaId !in favoriteIds.value)
        }
    }

    fun setFavorite(mediaId: String, favorite: Boolean) {
        viewModelScope.launch { app.collections.setFavorite(mediaId, favorite) }
    }

    fun setSleepTimer(minutes: Int, endOfTrack: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply {
                putInt(PlaybackService.EXTRA_SLEEP_MINUTES, minutes)
                putBoolean(PlaybackService.EXTRA_SLEEP_END_OF_TRACK, endOfTrack)
            },
        )
        _playback.value = _playback.value.copy(sleepTimerMinutes = minutes)
    }

    fun cancelSleepTimer() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        _playback.value = _playback.value.copy(sleepTimerMinutes = 0)
    }

    fun saveLyrics(track: Track, content: String) {
        viewModelScope.launch {
            app.lyrics.saveOverride(track, content)
            _lyrics.value = app.lyrics.load(track)
        }
    }

    /**
     * Asks the online database for this track's lyrics.
     *
     * A failure is reported rather than swallowed, because the difference
     * between "this song has no lyrics anywhere" and "the server was busy"
     * decides whether trying again is worth the user's time.
     */
    fun lookUpLyrics(track: Track) {
        if (_lyricsLookup.value == LyricsLookupState.Searching) return
        viewModelScope.launch {
            _lyricsLookup.value = LyricsLookupState.Searching
            _lyricsLookup.value = when (val result = app.lyrics.lookUpOnline(track)) {
                is LyricsLookup.Found -> {
                    _lyrics.value = app.lyrics.load(track)
                    LyricsLookupState.Idle
                }
                LyricsLookup.NotFound -> LyricsLookupState.NotFound
                is LyricsLookup.Failed -> LyricsLookupState.Failed(result.reason)
            }
        }
    }

    /** Turns lookup on and immediately uses it, which is the same gesture. */
    fun enableLookupAndSearch(track: Track) {
        viewModelScope.launch {
            app.settingsStore.setLyricsLookupEnabled(true)
            lookUpLyrics(track)
        }
    }

    /**
     * Nudges the lyric timing. Applied live so the effect is visible while the
     * track plays, which is the only way to judge whether it is right.
     */
    fun nudgeLyricsOffset(track: Track, deltaMs: Long) {
        val target = (_lyrics.value.offsetMs + deltaMs)
            .coerceIn(-Lyrics.MAX_OFFSET_MS, Lyrics.MAX_OFFSET_MS)
        setLyricsOffset(track, target)
    }

    fun setLyricsOffset(track: Track, offsetMs: Long) {
        _lyrics.value = _lyrics.value.copy(offsetMs = offsetMs)
        viewModelScope.launch { app.lyrics.setOffset(track, offsetMs) }
    }

    // ---------------------------------------------------------------- state

    private inner class ControllerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }
    }

    private fun syncFromController() {
        val controller = controller ?: return

        _playback.value = PlaybackUiState(
            mediaId = controller.currentMediaItem?.mediaId,
            isPlaying = controller.isPlaying,
            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: 0L,
            shuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
            queueIndex = controller.currentMediaItemIndex,
            queueSize = controller.mediaItemCount,
            isFavorite = controller.currentMediaItem?.mediaId in favoriteIds.value,
            sleepTimerMinutes = _playback.value.sleepTimerMinutes,
            audioSessionId = _playback.value.audioSessionId,
        )

        resolveQueue()

        if (controller.isPlaying) startPositionTicker() else stopPositionTicker()
    }

    private fun resolveQueue() {
        val controller = controller ?: return
        _queue.value = (0 until controller.mediaItemCount).mapNotNull { index ->
            app.library.trackFor(controller.getMediaItemAt(index).mediaId)
        }
    }

    /**
     * The playhead is polled rather than pushed, because the player has no
     * position callback. Four times a second is enough for a progress bar and a
     * lyric line to look continuous without waking the CPU for nothing.
     */
    private fun startPositionTicker() {
        if (positionJob?.isActive == true) return
        positionJob = viewModelScope.launch {
            while (true) {
                val controller = controller ?: break
                _playback.value = _playback.value.copy(
                    positionMs = controller.currentPosition.coerceAtLeast(0),
                    durationMs = controller.duration.takeIf { it > 0 } ?: 0L,
                )
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        positionJob?.cancel()
        positionJob = null
    }

    private companion object {
        const val POSITION_TICK_MS = 250L
    }
}

data class PlaybackUiState(
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val isFavorite: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    val audioSessionId: Int = 0,
) {
    val hasContent: Boolean get() = mediaId != null
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** What a lyrics lookup is doing, for the one line the pane shows about it. */
sealed interface LyricsLookupState {
    data object Idle : LyricsLookupState
    data object Searching : LyricsLookupState
    data object NotFound : LyricsLookupState
    data class Failed(val reason: String) : LyricsLookupState
}

@OptIn(UnstableApi::class)
fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artUri)
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setDurationMs(durationMs.takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()
