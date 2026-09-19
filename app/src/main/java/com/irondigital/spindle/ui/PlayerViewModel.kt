package com.irondigital.spindle.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import com.irondigital.spindle.playback.toMediaItem
import com.irondigital.spindle.playback.QueueEntry
import com.irondigital.spindle.spindle
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    private var connectJob: Job? = null
    private val lyricsRequest = LatestRequest(viewModelScope)

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueRowState>>(emptyList())
    val queue: StateFlow<List<QueueRowState>> = _queue.asStateFlow()
    private var queueRevision = 0L
    private var queueTimeline: Timeline? = null

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
        ensureConnected()

        // Lyrics follow the track, and loading them touches the disk, so this
        // is deliberately off the transition path.
        currentTrack
            .onEach { track ->
                lyricsRequest.cancel()
                _lyrics.value = Lyrics.NONE
                _lyricsLookup.value = LyricsLookupState.Idle
                if (track != null) loadLyrics(track)
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

    /**
     * The controller, but only while it is actually usable.
     *
     * A MediaController survives its service dying — it just stops being
     * connected, and every command on it becomes a silent no-op. Reading
     * through this rather than the field is what stops a dead controller
     * looking exactly like a broken button.
     */
    private val liveController: MediaController?
        get() = controller?.takeIf { it.isConnected }

    /**
     * Connects, retrying a few times.
     *
     * The service may not be up yet on a cold start, and the first attempt can
     * simply lose that race. Giving up after one failure left every transport
     * control permanently dead for the life of the screen while the rest of the
     * UI carried on working, which is indistinguishable from the buttons being
     * broken — and the failure was swallowed, so there was nothing in the log
     * to say otherwise.
     */
    private suspend fun connect() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java),
        )

        repeat(CONNECT_ATTEMPTS) { attempt ->
            val attemptResult = runCatching {
                MediaController.Builder(getApplication<Application>(), token)
                    .setListener(ConnectionListener())
                    .buildAsync()
                    .await()
            }

            val connected = attemptResult.getOrNull()
            if (connected != null && connected.isConnected) {
                controller = connected
                connected.addListener(ControllerListener())
                syncFromController()
                return
            }

            // Released rather than leaked: a controller that connected and then
            // dropped still holds a binding.
            connected?.let { runCatching { it.release() } }
            Log.w(TAG, "Could not reach the player (attempt ${attempt + 1})", attemptResult.exceptionOrNull())
            if (attempt < CONNECT_ATTEMPTS - 1) delay(RECONNECT_DELAY_MS * (attempt + 1))
        }
    }

    /** Starts a connection attempt unless one is already usable or running. */
    private fun ensureConnected() {
        if (liveController != null) return
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { connect() }
    }

    private suspend fun awaitController(): MediaController? {
        ensureConnected()
        connectJob?.join()
        return liveController
    }

    /**
     * Runs a transport command, reconnecting first if the session has gone.
     *
     * The press is carried across the reconnect rather than dropped. Losing the
     * first tap and working on the second is precisely the behavior that makes
     * an app feel broken.
     */
    private fun command(block: (MediaController) -> Unit) {
        val ready = liveController
        if (ready != null) {
            block(ready)
            return
        }
        viewModelScope.launch { awaitController()?.let(block) }
    }

    private inner class ConnectionListener : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            // The service was stopped — swiped away while paused, most often.
            // Dropping the reference here is what lets the next press rebuild it.
            if (this@PlayerViewModel.controller === controller) {
                this@PlayerViewModel.controller = null
                queueTimeline = null
                queueRevision++
            }
        }
    }

    override fun onCleared() {
        positionJob?.cancel()
        connectJob?.cancel()
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
        if (tracks.isEmpty()) return
        command { controller ->
            controller.setMediaItems(
                tracks.map { it.toMediaItem() },
                startIndex.coerceIn(0, tracks.lastIndex),
                0L,
            )
            controller.prepare()
            controller.play()
        }
    }

    fun shufflePlay(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        command { it.shuffleModeEnabled = true }
        play(tracks, tracks.indices.random())
    }

    fun playPause() = command { controller ->
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() = command { it.seekToNextMediaItem() }
    fun previous() = command { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs) }
    fun seekToQueueEntry(row: QueueRowState) = queueCommand(row) { it.seekTo(row.index, 0L) }

    private fun queueCommand(row: QueueRowState, action: (MediaController) -> Unit) = command { player ->
        // A queued command may resume after reconnecting or after another surface
        // edits the timeline. Never apply an old row to the replacement queue.
        resolveQueue()
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (row.matches(queueRevision, ids)) action(player)
    }

    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = command { controller ->
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Appends to the end of the queue without disturbing what is playing. */
    fun addToQueue(tracks: List<Track>) = command { controller ->
        controller.addMediaItems(tracks.map { it.toMediaItem() })
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
    }

    /** Inserts directly after the current track. */
    fun playNext(tracks: List<Track>) = command { controller ->
        val insertAt = (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
        controller.addMediaItems(insertAt, tracks.map { it.toMediaItem() })
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
    }

    fun removeFromQueue(row: QueueRowState) = queueCommand(row) { it.removeMediaItem(row.index) }

    fun moveInQueue(row: QueueRowState, to: Int) = queueCommand(row) { controller ->
        if (to in 0 until controller.mediaItemCount) controller.moveMediaItem(row.index, to)
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
        command { controller ->
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply {
                    putInt(PlaybackService.EXTRA_SLEEP_MINUTES, minutes)
                    putBoolean(PlaybackService.EXTRA_SLEEP_END_OF_TRACK, endOfTrack)
                },
            )
        }
        _playback.value = _playback.value.copy(sleepTimerMinutes = minutes)
    }

    fun cancelSleepTimer() {
        command {
            it.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }
        _playback.value = _playback.value.copy(sleepTimerMinutes = 0)
    }

    private fun loadLyrics(track: Track, forceLookup: Boolean = false) {
        if (_playback.value.mediaId != track.mediaId) return
        lyricsRequest.submit(load = {
            try {
                var lyrics = app.lyrics.load(track)
                var state: LyricsLookupState = LyricsLookupState.Idle
                if (forceLookup || (lyrics.isEmpty && settings.value.lyricsLookupEnabled &&
                        !app.lyrics.alreadyLookedUp(track))) {
                    if (_playback.value.mediaId == track.mediaId) _lyricsLookup.value = LyricsLookupState.Searching
                    state = when (val result = app.lyrics.lookUpOnline(track)) {
                        is LyricsLookup.Found -> {
                            lyrics = app.lyrics.load(track)
                            LyricsLookupState.Idle
                        }
                        LyricsLookup.NotFound -> LyricsLookupState.NotFound
                        is LyricsLookup.Failed -> LyricsLookupState.Failed(result.reason)
                    }
                }
                lyrics to state
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Exception) {
                Lyrics.NONE to LyricsLookupState.Failed(error.message ?: "Could not load lyrics")
            }
        }, publish = { (lyrics, state) ->
            if (_playback.value.mediaId == track.mediaId) {
                _lyrics.value = lyrics
                _lyricsLookup.value = state
            }
        })
    }

    fun saveLyrics(track: Track, content: String) {
        lyricsRequest.cancel()
        viewModelScope.launch {
            app.lyrics.saveOverride(track, content)
            if (_playback.value.mediaId == track.mediaId) loadLyrics(track)
        }
    }

    fun lookUpLyrics(track: Track) {
        if (_playback.value.mediaId != track.mediaId || _lyricsLookup.value == LyricsLookupState.Searching) return
        _lyricsLookup.value = LyricsLookupState.Searching
        loadLyrics(track, forceLookup = true)
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
        if (_playback.value.mediaId != track.mediaId) return
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
        val controller = liveController ?: return

        if (_playback.value.mediaId != controller.currentMediaItem?.mediaId) {
            lyricsRequest.cancel()
            _lyrics.value = Lyrics.NONE
            _lyricsLookup.value = LyricsLookupState.Idle
        }
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
        val controller = liveController ?: return
        if (queueTimeline != controller.currentTimeline) {
            queueTimeline = controller.currentTimeline
            queueRevision++
        }
        val entries = (0 until controller.mediaItemCount).map { index ->
            val item = controller.getMediaItemAt(index)
            QueueEntry(
                item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.durationMs ?: 0L,
            )
        }
        _queue.value = resolveQueueRows(entries, queueRevision) { id ->
            app.library.trackFor(id)?.let { QueueTrackDetails(it.title, it.artist, it.durationMs) }
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

private const val TAG = "SpindlePlayer"

/** Connection retries, and how long to wait between them. */
private const val CONNECT_ATTEMPTS = 3
private const val RECONNECT_DELAY_MS = 400L

/** What a lyrics lookup is doing, for the one line the pane shows about it. */
sealed interface LyricsLookupState {
    data object Idle : LyricsLookupState
    data object Searching : LyricsLookupState
    data object NotFound : LyricsLookupState
    data class Failed(val reason: String) : LyricsLookupState
}
