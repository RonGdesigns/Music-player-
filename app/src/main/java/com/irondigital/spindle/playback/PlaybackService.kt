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
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
class PlaybackService : MediaSessionService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var tracker: PlayCountTracker
    private lateinit var loudness: LoudnessController
    private lateinit var effects: AudioEffectsController

    private var settings: Settings = Settings()
    private var sleepTimerJob: Job? = null
    private var sleepAtEndOfTrack = false

    /** Coalesces the burst of player callbacks a single action produces. */
    private var snapshotJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val app = spindle

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

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
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

        serviceScope.launch { restoreQueueIfEmpty() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(favoriteButton()))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
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
        snapshotJob?.cancel()
        snapshotJob = serviceScope.launch {
            if (!immediate) delay(SNAPSHOT_DEBOUNCE_MS)
            writeSnapshot()
        }
    }

    private suspend fun writeSnapshot() {
        val app = spindle
        val currentItem = player.currentMediaItem
        val favorites = runCatching { app.collections.favoriteIdSet.first() }.getOrDefault(emptySet())

        val queue = ArrayList<QueueEntry>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            queue += QueueEntry(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                durationMs = item.mediaMetadata.durationMs ?: 0L,
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
        )

        app.snapshotStore.write(snapshot)
        NowPlayingWidget.refresh(applicationContext)
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
        if (snapshot.queue.isEmpty() || snapshot.currentIndex < 0) return

        val items = snapshot.queue.mapNotNull { entry ->
            val id = entry.mediaId.toLongOrNull() ?: return@mapNotNull null
            MediaItem.Builder()
                .setMediaId(entry.mediaId)
                .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.artist)
                        .setDurationMs(entry.durationMs.takeIf { it > 0 })
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        if (items.isEmpty()) return

        // Restored paused and at the saved position: waking a phone up with
        // music because the launcher redrew a widget would be indefensible.
        player.setMediaItems(items, snapshot.currentIndex.coerceIn(0, items.lastIndex), snapshot.positionMs)
        player.prepare()
    }

    companion object {
        const val COMMAND_TOGGLE_FAVORITE = "com.irondigital.spindle.TOGGLE_FAVORITE"
        const val COMMAND_SET_SLEEP_TIMER = "com.irondigital.spindle.SET_SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER = "com.irondigital.spindle.CANCEL_SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_SLEEP_END_OF_TRACK = "sleep_end_of_track"

        private const val SNAPSHOT_DEBOUNCE_MS = 120L
    }
}
