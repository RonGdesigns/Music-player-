package com.irondigital.spindle.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.irondigital.spindle.data.repo.StatsRepository
import com.irondigital.spindle.data.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decides when a track counts as played.
 *
 * The rule is the one scrobbling has used for twenty years, and it is the right
 * one: a play counts once you have heard either half the track or four minutes,
 * whichever comes first. Counting on start would make Most Played a list of
 * what you skip past; counting only on completion would never count the ten
 * minute tracks.
 *
 * Time is accumulated from actual playing wall-clock, not from the playhead, so
 * a paused track sitting on screen for an hour earns nothing, and looping the
 * same thirty seconds does not silently rack up plays.
 */
class PlayCountTracker(
    private val scope: CoroutineScope,
    private val stats: StatsRepository,
    private val settingsProvider: () -> Settings,
) : Player.Listener {

    private var player: Player? = null

    private var currentMediaId: String? = null
    private var currentDurationMs: Long = 0
    private var accumulatedMs: Long = 0
    private var resumedAtRealtime: Long = 0
    private var counted: Boolean = false

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)
    }

    fun detach() {
        // Whatever was in progress still counts if it earned it.
        flush(completed = false)
        player?.removeListener(this)
        player = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val completedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        flush(completed = completedNaturally)
        startTracking(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            if (currentMediaId == null) startTracking(player?.currentMediaItem)
            resumedAtRealtime = SystemClock.elapsedRealtime()
        } else {
            accumulate()
            // Crossing the threshold mid-track is recorded immediately rather
            // than at transition, so a play still counts if the process is
            // killed while the track is paused.
            maybeCount(completed = false)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            flush(completed = true)
        }
    }

    private fun startTracking(mediaItem: MediaItem?) {
        currentMediaId = mediaItem?.mediaId
        currentDurationMs = player?.duration?.takeIf { it > 0 } ?: 0
        accumulatedMs = 0
        counted = false
        resumedAtRealtime = if (player?.isPlaying == true) SystemClock.elapsedRealtime() else 0
    }

    private fun accumulate() {
        if (resumedAtRealtime > 0) {
            accumulatedMs += SystemClock.elapsedRealtime() - resumedAtRealtime
            resumedAtRealtime = 0
        }
    }

    private fun flush(completed: Boolean) {
        accumulate()
        val mediaId = currentMediaId ?: return
        val listened = accumulatedMs

        if (!maybeCount(completed) && listened > 0 && !counted) {
            val settings = settingsProvider()
            if (settings.countPlaysEnabled) {
                val duration = resolvedDuration()
                // Abandoning a track in its first fifth is a skip worth noting.
                if (duration > 0 && listened < duration * SKIP_FRACTION) {
                    scope.launch { stats.recordSkip(mediaId, listened) }
                }
            }
        }

        currentMediaId = null
        accumulatedMs = 0
        counted = false
    }

    /** Returns true when this call is what tipped the track over the threshold. */
    private fun maybeCount(completed: Boolean): Boolean {
        if (counted) return false
        val mediaId = currentMediaId ?: return false
        val settings = settingsProvider()
        if (!settings.countPlaysEnabled) return false

        val duration = resolvedDuration()
        if (duration <= 0) return false

        val byFraction = duration * settings.playThresholdPercent / 100L
        val threshold = minOf(byFraction, ABSOLUTE_THRESHOLD_MS)
        if (accumulatedMs < threshold) return false

        counted = true
        val listened = accumulatedMs
        scope.launch { stats.recordPlay(mediaId, listened, completed) }
        return true
    }

    /**
     * Duration is often unknown at transition time and only resolves once the
     * new item is prepared, so it is re-read rather than trusted from the start.
     */
    private fun resolvedDuration(): Long {
        if (currentDurationMs > 0) return currentDurationMs
        val fromPlayer = player?.duration ?: 0
        if (fromPlayer > 0) currentDurationMs = fromPlayer
        return currentDurationMs
    }

    private companion object {
        const val ABSOLUTE_THRESHOLD_MS = 4 * 60 * 1000L
        const val SKIP_FRACTION = 0.2
    }
}
