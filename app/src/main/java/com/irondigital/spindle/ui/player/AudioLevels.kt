package com.irondigital.spindle.ui.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.log10

/**
 * Band energies from the actual audio signal.
 *
 * The platform Visualizer is gated behind RECORD_AUDIO because it can, in
 * principle, read the output mix. That is a real microphone-grade permission
 * and a music player has no business demanding it at launch, so this path is
 * strictly opt-in: the default visualiser is artwork-driven and asks for
 * nothing. If the permission is absent or the effect cannot be created — some
 * devices refuse it outright — this returns silence and the caller falls back.
 */
const val BAND_COUNT = 24

@Composable
fun rememberAudioLevels(
    enabled: Boolean,
    audioSessionId: Int,
): State<FloatArray> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val levels = remember { mutableStateOf(FloatArray(BAND_COUNT)) }

    DisposableEffect(enabled, audioSessionId) {
        if (!enabled || audioSessionId == 0 || !hasRecordPermission(context)) {
            levels.value = FloatArray(BAND_COUNT)
            return@DisposableEffect onDispose { }
        }

        val visualizer = runCatching {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (fft != null) levels.value = fft.toBands(levels.value)
                        }
                    },
                    // Half the maximum rate. The eye cannot use more than this
                    // and the callback runs on a binder thread.
                    Visualizer.getMaxCaptureRate() / 2,
                    /* waveform = */ false,
                    /* fft = */ true,
                )
                // setEnabled returns a status code rather than void, so this
                // is a method call, not a property assignment.
                setEnabled(true)
            }
        }.getOrNull()

        if (visualizer == null) levels.value = FloatArray(BAND_COUNT)

        onDispose {
            runCatching {
                visualizer?.setEnabled(false)
                visualizer?.release()
            }
        }
    }

    return levels
}

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Folds the raw FFT into [BAND_COUNT] logarithmically-spaced bands.
 *
 * Linear bands would put almost everything in the first two buckets, because
 * musical energy is concentrated at the bottom — which is why a naive
 * visualiser looks like one twitching bar and twenty dead ones. The result is
 * smoothed against the previous frame so bars fall away rather than snapping,
 * which is both easier to look at and cheaper to redraw.
 */
private fun ByteArray.toBands(previous: FloatArray): FloatArray {
    val out = FloatArray(BAND_COUNT)
    val binCount = size / 2
    if (binCount <= 1) return out

    var bin = 1
    for (band in 0 until BAND_COUNT) {
        // Each band is ~1.35x wider than the last: roughly a third of an octave.
        val bandEnd = (binCount * Math.pow((band + 1) / BAND_COUNT.toDouble(), 2.4)).toInt()
            .coerceIn(bin + 1, binCount)

        var peak = 0f
        for (i in bin until bandEnd) {
            val real = this[i * 2].toFloat()
            val imaginary = this[i * 2 + 1].toFloat()
            val magnitude = hypot(real, imaginary)
            if (magnitude > peak) peak = magnitude
        }
        bin = bandEnd

        // Decibels, then normalised. Amplitude alone would leave everything
        // hugging the floor apart from the occasional transient.
        val db = if (peak > 0f) 20f * log10(peak) else 0f
        val normalised = (db / 48f).coerceIn(0f, 1f)

        val last = previous.getOrElse(band) { 0f }
        out[band] = if (normalised > last) {
            // Rise almost immediately: a transient you see late is a transient
            // that looks unrelated to the music.
            normalised
        } else {
            last * 0.82f + normalised * 0.18f
        }
    }
    return out
}
