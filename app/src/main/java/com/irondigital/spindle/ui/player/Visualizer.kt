package com.irondigital.spindle.ui.player

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import com.irondigital.spindle.data.settings.VisualizerMode
import com.irondigital.spindle.ui.theme.ArtworkColors
import com.irondigital.spindle.ui.theme.Ground
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The moving ground behind the now-playing screen.
 *
 * It is built as a plane stack rather than a single animated gradient, because
 * depth needs more than one channel to read: the back field moves least and
 * carries the least contrast, the mid blobs move and are the subject, and the
 * grain sits in front of both at full sharpness. Three planes is the working
 * number — four when the content has four, five reads as a diorama.
 *
 * Crucially the depth is the subject's own. Every color here comes out of the
 * cover art currently playing, so the layers *are* the content. A generic
 * mountain scene behind a record would be borrowed scenery, and borrowing is
 * the tell.
 *
 * In [VisualizerMode.AUDIO_REACTIVE] the mid plane breathes with the low bands
 * and a spectrum silhouette rises along the bottom edge. In the default
 * [VisualizerMode.ARTWORK] mode nothing is measured and nothing is asked for —
 * it simply drifts.
 */
@Composable
fun ArtworkVisualizer(
    colors: ArtworkColors,
    mode: VisualizerMode,
    /**
     * Taken as State, not as the array itself. In the reactive mode these
     * arrive many times a second, and a caller that unwraps them first
     * recomposes the whole player screen at that rate — for a value only this
     * drawing ever looks at.
     */
    levels: State<FloatArray>,
    modifier: Modifier = Modifier,
) {
    if (mode == VisualizerMode.OFF) {
        Canvas(modifier) { drawRect(Ground.Deep) }
        return
    }

    val context = LocalContext.current

    // The platform's own reduced-motion signal. Somebody who has turned
    // animations off system-wide has already told us; asking again in our
    // settings would be pretending not to have heard.
    val animationsDisabled = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val transition = rememberInfiniteTransition(label = "visualizer")
    // Held as State and read inside the draw block rather than unwrapped here
    // with `by`. Unwrapping at composition scope makes this composable
    // invalidate on every frame of the animation — sixty recompositions a
    // second behind whatever is on top of it, which is what made the lyrics
    // stutter as they scrolled. Read in the draw phase, only the drawing is
    // invalidated, which is all that ever needed to change.
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DRIFT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )

    val grain = rememberGrain(colors.dominant)

    // Everything that depends only on the colors and the size of the screen is
    // built once here and reused every frame. Before, each frame rebuilt the
    // background gradient, a gradient and two colour copies per blob, a path
    // and a gradient for the spectrum, and a list just to average four
    // numbers — a few hundred short-lived objects a second at sixty frames,
    // every one of them garbage for the collector to sweep up while the
    // lyrics were scrolling over the top. The cache is rebuilt only when the
    // size or the record changes. The animated values are read inside the
    // draw block, never in the cache block, or the cache would be rebuilt on
    // every frame and save nothing.
    Spacer(
        modifier.drawWithCache {
            val backField = Brush.verticalGradient(
                colors = listOf(
                    colors.dominant,
                    lerpToward(colors.dominant, Ground.Deep, 0.55f),
                    Ground.Deep,
                ),
                startY = 0f,
                endY = size.height,
            )

            // Unit-sized and centred on the origin, then moved and scaled into
            // place each frame. The blobs change position and size constantly
            // but never shape, so there is no reason to rebuild what they look
            // like — only where they are.
            val vibrantBlob = unitBlob(colors.vibrant, alpha = 0.34f)
            val mutedBlob = unitBlob(colors.muted, alpha = 0.30f)

            val silhouetteHeight = size.height * SILHOUETTE_HEIGHT
            val silhouette = Brush.verticalGradient(
                colors = listOf(colors.vibrant.copy(alpha = 0.30f), colors.vibrant.copy(alpha = 0.06f)),
                startY = size.height - silhouetteHeight,
                endY = size.height,
            )
            val contour = Path()

            onDrawBehind {
                // Reduced motion resolves to a composed still, not to an empty screen.
                val slow = if (animationsDisabled) STILL_PHASE else drift.value
                val phase = slow * 2f * PI.toFloat()
                val bands = levels.value

                drawRect(brush = backField)
                drawMidPlane(vibrantBlob, mutedBlob, phase, bands, mode)
                drawSpectrumSilhouette(contour, silhouette, silhouetteHeight, bands, mode)
                // Grain last: it is the plane nearest the eye, and the earliest proof a
                // person made this rather than accepting a flat fill.
                drawRect(brush = grain)
            }
        }
    )
}

/** MID — the subject. Two soft bodies on slow, unequal orbits. */
private fun DrawScope.drawMidPlane(
    vibrantBlob: Brush,
    mutedBlob: Brush,
    phase: Float,
    levels: FloatArray,
    mode: VisualizerMode,
) {
    // Low-band energy, which is what actually corresponds to the pulse a
    // listener feels. Mid and top bands would make it twitch. Summed by hand:
    // take(4).average() built a new list every frame to add four numbers.
    val bass = if (mode == VisualizerMode.AUDIO_REACTIVE && levels.isNotEmpty()) {
        val count = minOf(4, levels.size)
        var sum = 0f
        for (i in 0 until count) sum += levels[i]
        sum / count
    } else {
        0f
    }
    val swell = 1f + bass * 0.45f

    val shortest = minOf(size.width, size.height)

    // Unequal periods, so the two never settle into a visible loop.
    drawBlob(
        brush = vibrantBlob,
        centerX = size.width * (0.28f + 0.16f * sin(phase)),
        centerY = size.height * (0.30f + 0.10f * sin(phase * 0.77f + 1.1f)),
        radius = shortest * 0.62f * swell,
    )

    drawBlob(
        brush = mutedBlob,
        centerX = size.width * (0.74f + 0.14f * sin(phase * 0.63f + 2.4f)),
        centerY = size.height * (0.62f + 0.12f * sin(phase * 0.91f)),
        radius = shortest * 0.54f * (1f + bass * 0.28f),
    )
}

/** A soft body of radius one at the origin, to be moved and scaled into place. */
private fun unitBlob(color: Color, alpha: Float): Brush = Brush.radialGradient(
    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
    center = Offset.Zero,
    radius = 1f,
)

/**
 * Draws a unit blob at a position and size. The transform carries the
 * gradient with it, so the result is pixel for pixel what building a fresh
 * gradient at that position and radius would have drawn.
 */
private fun DrawScope.drawBlob(brush: Brush, centerX: Float, centerY: Float, radius: Float) {
    if (radius <= 0f) return
    withTransform({
        translate(centerX, centerY)
        scale(radius, radius, pivot = Offset.Zero)
    }) {
        drawCircle(brush = brush, radius = 1f, center = Offset.Zero)
    }
}

/**
 * FORE — a spectrum silhouette along the bottom edge, drawn as one filled
 * contour rather than separate bars. Bars would be a graph; a contour is a
 * horizon, which is what a foreground plane needs to be.
 */
private fun DrawScope.drawSpectrumSilhouette(
    contour: Path,
    brush: Brush,
    maxHeight: Float,
    levels: FloatArray,
    mode: VisualizerMode,
) {
    if (mode != VisualizerMode.AUDIO_REACTIVE || levels.isEmpty()) return
    if (levels.all { it <= 0.01f }) return

    val step = size.width / (levels.size - 1).coerceAtLeast(1)

    // One path, reset and redrawn, rather than a new one every frame.
    contour.reset()
    contour.moveTo(0f, size.height)
    contour.lineTo(0f, size.height - levels[0] * maxHeight)
    for (i in 1 until levels.size) {
        val x = i * step
        val y = size.height - levels[i] * maxHeight
        val previousX = (i - 1) * step
        val previousY = size.height - levels[i - 1] * maxHeight
        // Smoothed through the midpoint, so the contour has no hard corners
        // even at twenty-four bands.
        contour.quadraticTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
    }
    contour.lineTo(size.width, size.height)
    contour.close()

    drawPath(path = contour, brush = brush)
}

/**
 * A tiled noise tile, tinted to the ground rather than neutral black — neutral
 * grain over a colored field reads as dirt on the screen. Around 4% strength:
 * present, never noticeable as grain.
 */
@Composable
private fun rememberGrain(tint: Color): ShaderBrush = remember(tint) {
    val size = 96
    val pixels = IntArray(size * size)
    val random = Random(20260918)
    val base = tint

    for (i in pixels.indices) {
        val n = random.nextFloat()
        val alpha = (n * 0.10f * 255).toInt().coerceIn(0, 255)
        val r = (base.red * 255).toInt()
        val g = (base.green * 255).toInt()
        val b = (base.blue * 255).toInt()
        pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    ShaderBrush(
        ImageShader(
            bitmap.asImageBitmap(),
            TileMode.Repeated,
            TileMode.Repeated,
        )
    )
}

private fun lerpToward(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
    alpha = 1f,
)

/** Drift period. Slow enough that it is never the thing you are looking at. */
private const val DRIFT_PERIOD_MS = 42_000

/** Where the drift is frozen when the system asks for reduced motion. */
private const val STILL_PHASE = 0.22f

/** How tall the spectrum silhouette may rise, as a share of the screen. */
private const val SILHOUETTE_HEIGHT = 0.22f
