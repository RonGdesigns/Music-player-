package com.irondigital.spindle.playback

import com.irondigital.spindle.data.media.ReplayGainValues
import com.irondigital.spindle.data.settings.NormalizationMode
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How much to turn a track up or down, and by what means.
 *
 * Two mechanisms, because the player can only ever attenuate: [playerVolume] is
 * a 0..1 multiplier, so a track that needs to come *down* is handled there,
 * while a track that needs to come *up* needs the platform's LoudnessEnhancer,
 * which takes millibels.
 */
data class NormalizationResult(
    val playerVolume: Float,
    val boostMillibels: Int,
) {
    companion object {
        val UNITY = NormalizationResult(playerVolume = 1f, boostMillibels = 0)
    }
}

/**
 * The ReplayGain calculation.
 *
 * Isolated from the player entirely so the arithmetic — which is where volume
 * normalisation actually goes wrong — can be tested without an audio session.
 */
object Normalization {

    /**
     * The platform's LoudnessEnhancer will happily accept an enormous target and
     * hand back mush. Nothing musical needs more than this.
     */
    const val MAX_BOOST_MILLIBELS = 1_500

    fun compute(
        mode: NormalizationMode,
        values: ReplayGainValues,
        preampDb: Float,
    ): NormalizationResult {
        if (mode == NormalizationMode.OFF) return NormalizationResult.UNITY

        // Album mode keeps the relative loudness *within* an album intact, which
        // is the whole point of an album that was mastered as one piece. It falls
        // back to the track value when the album was never scanned as a set.
        val gain = when (mode) {
            NormalizationMode.ALBUM -> values.albumGainDb ?: values.trackGainDb
            else -> values.trackGainDb ?: values.albumGainDb
        } ?: return NormalizationResult.UNITY

        val peak = when (mode) {
            NormalizationMode.ALBUM -> values.albumPeak ?: values.trackPeak
            else -> values.trackPeak ?: values.albumPeak
        }

        var total = gain + preampDb

        // Clipping prevention. If the file already peaks at 0.95 of full scale,
        // there is only 0.45 dB of headroom, and applying +3 dB because the track
        // is quiet on average would just square off the transients.
        if (peak != null && peak > 0f) {
            val headroomDb = -20f * log10(peak)
            if (total > headroomDb) total = headroomDb
        }

        if (total == 0f) return NormalizationResult.UNITY

        return if (total < 0f) {
            NormalizationResult(
                playerVolume = decibelsToLinear(total).coerceIn(0f, 1f),
                boostMillibels = 0,
            )
        } else {
            NormalizationResult(
                playerVolume = 1f,
                boostMillibels = (total * 100).roundToInt().coerceIn(0, MAX_BOOST_MILLIBELS),
            )
        }
    }

    fun decibelsToLinear(db: Float): Float = 10f.pow(db / 20f)
}
