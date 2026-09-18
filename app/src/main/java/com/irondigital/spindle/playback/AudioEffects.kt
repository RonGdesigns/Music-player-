package com.irondigital.spindle.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.irondigital.spindle.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What this device's equalizer can actually do.
 *
 * None of it is fixed by Android: the band count, their center frequencies, the
 * gain range and the preset names all come from the device's own effect
 * implementation. A hard-coded five-band UI would be wrong on most phones, so
 * the screen is built from whatever this reports.
 */
data class EqualizerCapabilities(
    val centerFrequenciesHz: List<Int>,
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val presetNames: List<String>,
) {
    val bandCount: Int get() = centerFrequenciesHz.size
    val isUsable: Boolean get() = bandCount > 0 && maxLevelMb > minLevelMb

    companion object {
        /** No equalizer could be created — some devices genuinely refuse. */
        val UNAVAILABLE = EqualizerCapabilities(emptyList(), 0, 0, emptyList())
    }
}

/**
 * Equalizer, bass boost and virtualizer, attached to the player's own audio
 * session — the same session [LoudnessController] uses for ReplayGain, so none
 * of it needs a permission.
 *
 * Every call into the platform effects is guarded. Audio effects are one of the
 * least reliable corners of Android: manufacturers ration how many can exist at
 * once, some devices refuse particular effects outright, and a failure here must
 * cost the user their equalizer, never their music.
 *
 * [apply] is deliberately cheap to call repeatedly — it returns immediately when
 * neither the session nor the settings have moved — so the service can call it
 * on every settings emission and every session change without thinking about it.
 */
@OptIn(UnstableApi::class)
class AudioEffectsController(
    private val player: ExoPlayer,
    private val settingsProvider: () -> Settings,
    private val capabilities: MutableStateFlow<EqualizerCapabilities?>,
) {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    @Suppress("DEPRECATION")
    private var virtualizer: Virtualizer? = null
    private var sessionId = 0
    private var appliedSettings: Settings? = null

    fun apply() {
        val settings = settingsProvider()

        val session = player.audioSessionId
        if (session == 0) return
        if (session != sessionId) {
            releaseEffects()
            sessionId = session
            appliedSettings = null
        }

        // Probed even while the equalizer is switched off, because the screen
        // has to draw this device's own bands before there is anything to apply.
        if (capabilities.value == null) probeCapabilities()

        if (!settings.equalizerEnabled) {
            if (appliedSettings?.equalizerEnabled != false) {
                releaseEffects()
                appliedSettings = settings
            }
            return
        }

        if (appliedSettings == settings && equalizer != null) return

        applyEqualizer(settings)
        applyBassBoost(settings)
        applyVirtualizer(settings)
        appliedSettings = settings
    }

    fun release() {
        releaseEffects()
        sessionId = 0
        appliedSettings = null
    }

    @Suppress("DEPRECATION")
    private fun releaseEffects() {
        runCatching {
            equalizer?.enabled = false
            equalizer?.release()
        }
        runCatching {
            bassBoost?.enabled = false
            bassBoost?.release()
        }
        runCatching {
            virtualizer?.enabled = false
            virtualizer?.release()
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    /**
     * Opens an equalizer just long enough to ask what it can do, then hands the
     * slot straight back. Holding an allocated effect the user has switched off
     * would count against the device's effect budget for nothing.
     */
    private fun probeCapabilities() {
        val effect = runCatching { Equalizer(EFFECT_PRIORITY, sessionId) }.getOrNull()
        if (effect == null) {
            capabilities.value = EqualizerCapabilities.UNAVAILABLE
            return
        }
        capabilities.value = effect.readCapabilities()
        runCatching { effect.release() }
    }

    private fun applyEqualizer(settings: Settings) {
        val effect = equalizer ?: runCatching { Equalizer(EFFECT_PRIORITY, sessionId) }
            .getOrNull()
            ?.also { created ->
                equalizer = created
                capabilities.value = created.readCapabilities()
            }
            ?: run {
                capabilities.value = EqualizerCapabilities.UNAVAILABLE
                return
            }

        runCatching {
            effect.enabled = true

            val preset = settings.equalizerPreset
            if (preset >= 0 && preset < effect.numberOfPresets) {
                effect.usePreset(preset.toShort())
                return@runCatching
            }

            // Custom: apply whatever the user set, clamped to what the device
            // accepts. A saved curve from another phone may exceed this range.
            val range = effect.bandLevelRange
            val min = range[0]
            val max = range[1]
            val levels = settings.equalizerBands
            for (band in 0 until effect.numberOfBands) {
                val desired = levels.getOrNull(band) ?: 0
                effect.setBandLevel(
                    band.toShort(),
                    desired.coerceIn(min.toInt(), max.toInt()).toShort(),
                )
            }
        }
    }

    private fun applyBassBoost(settings: Settings) {
        if (settings.bassBoostStrength <= 0) {
            runCatching { bassBoost?.enabled = false }
            return
        }
        val effect = bassBoost ?: runCatching { BassBoost(EFFECT_PRIORITY, sessionId) }
            .getOrNull()?.also { bassBoost = it } ?: return

        runCatching {
            if (effect.strengthSupported) {
                effect.setStrength(settings.bassBoostStrength.coerceIn(0, 1000).toShort())
                effect.enabled = true
            }
        }
    }

    /**
     * Android deprecated Virtualizer in favor of Spatializer, which is a
     * different thing entirely — a system-owned spatial renderer an app asks
     * about rather than a strength an app sets. There is no replacement for
     * "widen the stereo image by this much", so the deprecated effect is the
     * only way to offer the control at all, and it still works everywhere.
     */
    @Suppress("DEPRECATION")
    private fun applyVirtualizer(settings: Settings) {
        if (settings.virtualizerStrength <= 0) {
            runCatching { virtualizer?.enabled = false }
            return
        }
        val effect = virtualizer ?: runCatching { Virtualizer(EFFECT_PRIORITY, sessionId) }
            .getOrNull()?.also { virtualizer = it } ?: return

        runCatching {
            if (effect.strengthSupported) {
                effect.setStrength(settings.virtualizerStrength.coerceIn(0, 1000).toShort())
                effect.enabled = true
            }
        }
    }

    private fun Equalizer.readCapabilities(): EqualizerCapabilities = runCatching {
        val range = bandLevelRange
        EqualizerCapabilities(
            // Reported in millihertz, which is not a unit anyone wants to read.
            centerFrequenciesHz = (0 until numberOfBands).map { getCenterFreq(it.toShort()) / 1000 },
            minLevelMb = range[0].toInt(),
            maxLevelMb = range[1].toInt(),
            presetNames = (0 until numberOfPresets).map { getPresetName(it.toShort()) },
        )
    }.getOrDefault(EqualizerCapabilities.UNAVAILABLE)

    private companion object {
        /**
         * Above zero so the effect outlives a transient grab by another app, but
         * well short of anything that would fight the system.
         */
        const val EFFECT_PRIORITY = 10
    }
}
