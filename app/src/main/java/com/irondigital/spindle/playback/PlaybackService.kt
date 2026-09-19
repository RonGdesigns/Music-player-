package com.irondigital.spindle.playback

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.irondigital.spindle.data.personal.availableSession
import com.irondigital.spindle.data.personal.LoopRegion
import com.google.common.util.concurrent.SettableFuture
import com.irondigital.spindle.MainActivity
import com.irondigital.spindle.R
import com.irondigital.spindle.data.settings.Settings
import com.irondigital.spindle.widget.NowPlayingWidget
import com.irondigital.spindle.spindle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single owner of playback.
 *
 * Everything that plays audio goes through this service: the app UI, the
 * notification, the widget, a Bluetooth remote and a car head unit all connect
 * to the same MediaSession, so there is exactly one player and exactly one
 * queue no matter which surface you touch.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var autoLibrary: AutoMediaLibrary
    private lateinit var tracker: PlayCountTracker
    private lateinit var loudness: LoudnessController
    private lateinit var effects: AudioEffectsController

    private var settings: Settings = Settings()
    private var sleepTimerJob: Job? = null
    private var sleepAtEndOfTrack = false

    /** Coalesces the burst of player callbacks a single action produces. */
    private var snapshotJob: Job? = null
    private var restoring = true
    private var activeSessionId: String? = null
    private var loop: LoopRegion? = null

    override fun onCreate() {
        super.onCreate()

        val app = spindle
        autoLibrary = AutoMediaLibrary(app)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pausing when the headphones come out is not optional behavior.
            .setHandleAudioBecomingNoisy(true)
            .build()

        tracker = PlayCountTracker(serviceScope, app.stats) { settings }
        tracker.attach(player)

        loudness = LoudnessController(serviceScope, player, app.gains) { settings }
        loudness.attach()

        effects = AudioEffectsController(player, { settings }, app.equalizerCapabilities)

        player.addListener(PlayerWatcher())
        // The session id is what every audio effect attaches to, and it is
        // reassigned whenever the audio sink is rebuilt — a change the ordinary
        // Player.Listener never reports.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int,
            ) {
                effects.apply()
            }
        })

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()

        app.settingsStore.settings
            .onEach { updated ->
                val previous = settings
                settings = updated
                player.skipSilenceEnabled = updated.skipSilence

                if (previous.normalizationMode != updated.normalizationMode ||
                    previous.normalizationPreampDb != updated.normalizationPreampDb
                ) {
                    loudness.onSettingsChanged()
                }

                // Cheap when nothing it cares about moved, so it does not need
                // its own set of field comparisons to guard it.
                effects.apply()
            }
            .launchIn(serviceScope)

        serviceScope.launch {
            while (isActive) {
                delay(80)
                val region = loop ?: continue
                if (region.mediaId != player.currentMediaItem?.mediaId) { loop = null; publishSnapshot() }
                else if (region.shouldSeek(player.currentMediaItem?.mediaId, player.currentPosition, player.isPlaying)) player.seekTo(region.startMs)
            }
        }
        serviceScope.launch {
            try {
                restoreQueueIfEmpty()
            } finally {
                restoring = false
                publishSnapshot()
            }
            // Android Auto can be the first surface that starts the app after
            // boot, so make sure the browse tree has current MediaStore rows.
            if (app.library.tracks.value.isEmpty()) {
                runCatching { app.library.refresh() }
                mediaSession?.let { session ->
                    session.notifyChildrenChanged(
                        AutoMediaLibrary.SONGS_ID,
                        app.library.tracks.value.size,
                        null,
                    )
                    session.notifyChildrenChanged(
                        AutoMediaLibrary.ALBUMS_ID,
                        app.library.albums.value.size,
                        null,
                    )
                    session.notifyChildrenChanged(
                        AutoMediaLibrary.ARTISTS_ID,
                        app.library.artists.value.size,
                        null,
                    )
                    session.notifyChildrenChanged(
                        AutoMediaLibrary.FOLDERS_ID,
                        app.library.folders.value.size,
                        null,
                    )
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * When the user swipes the app away, playback that is actually happening
     * should survive — that is the whole point of a background player — but a
     * paused, forgotten session should not linger in the notification shade.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sleepTimerJob?.cancel()
        effects.release()
        loudness.detach()
        tracker.detach()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- session

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SAVE_SESSION, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_RESUME_SESSION, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_LOOP, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(favoriteButton()))
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(autoLibrary.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = autoLibrary.item(mediaId)
                ?: return Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                )
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = autoLibrary.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(all.subList(from, to), params)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val count = autoLibrary.search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = autoLibrary.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(all.subList(from, to), params)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(autoLibrary.resolvePlayable(mediaItems))

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            activeSessionId = null
            loop = null
            if (mediaItems.size == 1) {
                val requestedId = mediaItems.first().mediaId
                val queue = autoLibrary.queueForSelection(requestedId)
                if (queue != null) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            queue.first,
                            queue.second,
                            if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs,
                        )
                    )
                }
            }

            val resolved = autoLibrary.resolvePlayable(mediaItems)
            val safeIndex = when {
                resolved.isEmpty() -> C.INDEX_UNSET
                startIndex == C.INDEX_UNSET -> C.INDEX_UNSET
                else -> startIndex.coerceIn(0, resolved.lastIndex)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    safeIndex,
                    startPositionMs,
                )
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction in setOf(COMMAND_SAVE_SESSION, COMMAND_RESUME_SESSION, COMMAND_SET_LOOP)
                && controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            when (customCommand.customAction) {
                COMMAND_SAVE_SESSION, COMMAND_RESUME_SESSION -> {
                    val result = SettableFuture.create<SessionResult>()
                    serviceScope.launch {
                        try {
                            val app = spindle
                            val id = args.getString("id") ?: error("Missing session")
                            if (customCommand.customAction == COMMAND_SAVE_SESSION) {
                                val snapshot = writeSnapshot()
                                app.listening.saveSession(id, args.getString("name").orEmpty(), snapshot)
                                activeSessionId = id
                            } else {
                                writeSnapshot()
                                if (app.library.tracks.value.isEmpty()) app.library.refresh()
                                val saved = app.listening.data.first().sessions.firstOrNull { it.id == id }
                                    ?: error("That session is no longer available")
                                val snapshot = saved.snapshot.availableSession(app.library.tracks.value.map { it.mediaId }.toSet())
                                require(snapshot.queue.isNotEmpty()) { "The tracks in this session are unavailable" }
                                loop = null
                                applySnapshot(snapshot)
                                activeSessionId = id
                                app.listening.update { it.copy(activeSessionId = id) }
                                player.play()
                                publishSnapshot()
                            }
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) { result.cancel(false); throw error }
                            result.set(SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("message", error.message) }))
                        }
                    }
                    return result
                }
                COMMAND_SET_LOOP -> {
                    val start = args.getLong("start", -1)
                    val end = args.getLong("end", -1)
                    val proposed = LoopRegion(args.getString("mediaId").orEmpty(), start, end)
                    if (start < 0) loop = null
                    else if (proposed.validFor(player.currentMediaItem?.mediaId, player.duration)) {
                        loop = proposed
                        player.seekTo(start)
                    } else return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    publishSnapshot(immediate = true)
                }
                COMMAND_TOGGLE_FAVORITE -> {
                    val mediaId = player.currentMediaItem?.mediaId
                    if (mediaId != null) {
                        serviceScope.launch {
                            val app = spindle
                            val current = app.collections.favoriteIdSet.first()
                            app.collections.setFavorite(mediaId, mediaId !in current)
                            session.setCustomLayout(controller, ImmutableList.of(favoriteButton()))
                            publishSnapshot(immediate = true)
                        }
                    }
                }

                COMMAND_SET_SLEEP_TIMER -> {
                    val minutes = args.getInt(EXTRA_SLEEP_MINUTES, 0)
                    val endOfTrack = args.getBoolean(EXTRA_SLEEP_END_OF_TRACK, false)
                    startSleepTimer(minutes, endOfTrack)
                }

                COMMAND_CANCEL_SLEEP_TIMER -> cancelSleepTimer()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun favoriteButton(): CommandButton = CommandButton.Builder()
        .setDisplayName("Favorite")
        .setIconResId(R.drawable.ic_notification_favorite)
        .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
        .build()

    // --------------------------------------------------------- sleep timer

    private fun startSleepTimer(minutes: Int, endOfTrack: Boolean) {
        cancelSleepTimer()
        sleepAtEndOfTrack = endOfTrack
        if (minutes <= 0 && !endOfTrack) return

        sleepTimerJob = serviceScope.launch {
            if (minutes > 0) {
                delay(minutes * 60_000L)
                if (!isActive) return@launch
                if (sleepAtEndOfTrack) {
                    // Wait out the current track rather than cutting it off.
                    awaitTrackEnd()
                }
                player.pause()
            } else {
                awaitTrackEnd()
                player.pause()
            }
            sleepTimerJob = null
        }
    }

    private suspend fun awaitTrackEnd() {
        while (currentCoroutineContext().isActive && player.isPlaying) {
            val remaining = (player.duration - player.currentPosition).coerceAtLeast(0)
            if (player.duration <= 0) {
                delay(1_000)
                continue
            }
            if (remaining <= 250) return
            delay(minOf(remaining, 1_000L))
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtEndOfTrack = false
    }

    // ------------------------------------------------------------ snapshot

    private inner class PlayerWatcher : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                )
            ) {
                publishSnapshot()
            }

            // A device that refuses to build an equalizer before the audio
            // track exists will succeed here, on the first transition to ready.
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                effects.apply()
            }
        }
    }

    /**
     * One action — pressing next, say — fires half a dozen player callbacks.
     * Debouncing means one disk write and one widget update instead of six.
     */
    private fun publishSnapshot(immediate: Boolean = false) {
        if (restoring) return
        snapshotJob?.cancel()
        snapshotJob = serviceScope.launch {
            if (!immediate) delay(SNAPSHOT_DEBOUNCE_MS)
            writeSnapshot()
        }
    }

    private suspend fun writeSnapshot(): PlaybackSnapshot {
        val app = spindle
        val favorites = app.collections.favoriteIdSet.first()
        val currentItem = player.currentMediaItem

        val queue = ArrayList<QueueEntry>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            queue += QueueEntry(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                durationMs = item.mediaMetadata.durationMs ?: 0L,
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                artUri = item.mediaMetadata.artworkUri?.toString(),
                uri = item.localConfiguration?.uri?.toString(),
            )
        }

        val snapshot = PlaybackSnapshot(
            isPlaying = player.isPlaying,
            currentMediaId = currentItem?.mediaId,
            title = player.mediaMetadata.title?.toString().orEmpty(),
            artist = player.mediaMetadata.artist?.toString().orEmpty(),
            album = player.mediaMetadata.albumTitle?.toString().orEmpty(),
            artUri = (player.mediaMetadata.artworkUri ?: currentItem?.mediaMetadata?.artworkUri)?.toString(),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            queue = queue,
            currentIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            isFavorite = currentItem?.mediaId in favorites,
            audioSessionId = player.audioSessionId,
            updatedAt = System.currentTimeMillis(),
            loopStartMs = loop?.startMs,
            loopEndMs = loop?.endMs,
            playbackOrder = buildList {
                val timeline = player.currentTimeline
                var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
                while (index != C.INDEX_UNSET && size < timeline.windowCount) {
                    add(index)
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                }
            },
        )

        app.snapshotStore.write(snapshot)
        val sessionId = activeSessionId
        try {
            app.listening.update { data -> data.copy(activeSessionId = sessionId?.takeIf { id -> data.sessions.any { it.id == id } }, sessions = data.sessions.map {
                if (it.id == sessionId) it.copy(snapshot = snapshot.copy(isPlaying = false), updatedAt = System.currentTimeMillis()) else it
            }) }
        } catch (error: Exception) {
            if(error is kotlinx.coroutines.CancellationException) throw error
            android.util.Log.w("SpindleSessions", "Could not update saved listening position", error)
        }
        NowPlayingWidget.refresh(applicationContext)
        return snapshot
    }

    /**
     * Puts the last queue back so the widget's play button works after the
     * process has been killed — which, for a widget that may sit on a home
     * screen for days between uses, is the normal case rather than an edge one.
     *
     * The media id is the MediaStore row id, so the content URI can be rebuilt
     * without waiting for a library scan.
     */
    private suspend fun restoreQueueIfEmpty() {
        if (player.mediaItemCount > 0) return
        val snapshot = spindle.snapshotStore.snapshot.first()
        // Reading DataStore suspends. A phone or car may have supplied a queue meanwhile.
        if (player.mediaItemCount > 0) return
        activeSessionId = try { spindle.listening.data.first().activeSessionId }
        catch (error: Exception) { if(error is kotlinx.coroutines.CancellationException) throw error; null }
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: PlaybackSnapshot) {
        val restored = snapshot.restoration() ?: return
        val items = restored.entries.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.mediaId)
                .setUri(entry.uri?.let(Uri::parse) ?: ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, entry.mediaId.toLong(),
                ))
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.artist)
                    .setAlbumTitle(entry.album)
                    .setArtworkUri(entry.artUri?.let(Uri::parse))
                    .setDurationMs(entry.durationMs.takeIf { it > 0 })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build())
                .build()
        }
        player.setMediaItems(items, restored.currentIndex, restored.positionMs)
        player.setShuffleOrder(DefaultShuffleOrder(restored.order.toIntArray(), System.nanoTime()))
        player.shuffleModeEnabled = restored.shuffleEnabled
        player.repeatMode = restored.repeatMode
        player.prepare()
    }

    companion object {
        const val COMMAND_SAVE_SESSION = "com.irondigital.spindle.SAVE_SESSION"
        const val COMMAND_RESUME_SESSION = "com.irondigital.spindle.RESUME_SESSION"
        const val COMMAND_SET_LOOP = "com.irondigital.spindle.SET_LOOP"
        const val COMMAND_TOGGLE_FAVORITE = "com.irondigital.spindle.TOGGLE_FAVORITE"
        const val COMMAND_SET_SLEEP_TIMER = "com.irondigital.spindle.SET_SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER = "com.irondigital.spindle.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_SLEEP_END_OF_TRACK = "sleep_end_of_track"

        private const val SNAPSHOT_DEBOUNCE_MS = 120L
    }
}
