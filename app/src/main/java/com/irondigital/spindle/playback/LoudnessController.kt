package com.irondigital.spindle.playback

import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.irondigital.spindle.data.repo.GainRepository
import com.irondigital.spindle.data.settings.NormalizationMode
import com.irondigital.spindle.data.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Applies ReplayGain to the running player.
 *
 * Two mechanisms, because the player can only attenuate. A track that needs to
 * come down is handled with the player's own volume; one that needs to come up
 * goes through the platform's LoudnessEnhancer, attached to the player's audio
 * session. The enhancer is bound to a session we own, so it needs no permission.
 *
 * The arithmetic lives in [Normalization]; this class is only the plumbing —
 * which is deliberate, because the arithmetic is the part worth testing and the
 * plumbing is the part that needs an audio device.
 */
@OptIn(UnstableApi::class)
class LoudnessController(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val gains: GainRepository,
    private val settingsProvider: () -> Settings,
) : Player.Listener {

    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSessionId = 0
    private var pendingJob: Job? = null

    fun attach() {
        player.addListener(this)
        apply()
    }

    fun detach() {
        pendingJob?.cancel()
        player.removeListener(this)
        releaseEnhancer()
        player.volume = 1f
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = apply()

    /** Called when the user changes the mode or the pre-amp. */
    fun onSettingsChanged() = apply()

    private fun apply() {
        pendingJob?.cancel()

        val settings = settingsProvider()
        val item = player.currentMediaItem
        val uri = item?.localConfiguration?.uri

        if (settings.normalizationMode == NormalizationMode.OFF || item == null || uri == null) {
            resetToUnity()
            return
        }

        pendingJob = scope.launch {
            val values = gains.valuesFor(item.mediaId, uri)
            // The track may have moved on while the tag was being read.
            if (player.currentMediaItem?.mediaId != item.mediaId) return@launch

            val result = Normalization.compute(
                mode = settings.normalizationMode,
                values = values,
                preampDb = settings.normalizationPreampDb.toFloat(),
            )
            player.volume = result.playerVolume
            applyBoost(result.boostMillibels)
        }
    }

    private fun resetToUnity() {
        player.volume = 1f
        applyBoost(0)
    }

    private fun applyBoost(millibels: Int) {
        if (millibels <= 0) {
            // Disabled rather than released: the session is unchanged, and
            // rebuilding the effect on every quiet-to-loud transition is both
            // slower and more likely to fail on devices that ration effects.
            runCatching { enhancer?.enabled = false }
            return
        }

        val sessionId = player.audioSessionId
        if (sessionId == 0) return

        if (enhancer == null || enhancerSessionId != sessionId) {
            releaseEnhancer()
            enhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
            enhancerSessionId = sessionId
        }

        // Some devices refuse to host the effect at all. That is a reason to play
        // the track at unity, not a reason to crash.
        runCatching {
            enhancer?.setTargetGain(millibels)
            enhancer?.enabled = true
        }
    }

    private fun releaseEnhancer() {
        runCatching {
            enhancer?.enabled = false
            enhancer?.release()
        }
        enhancer = null
        enhancerSessionId = 0
    }
}
