package com.irondigital.spindle.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Steel

/**
 * A milled channel fader.
 *
 * Built rather than borrowed for one reason: this screen is the most literal
 * piece of the whole faceplate idea, and a row of rotated Material sliders
 * would be the moment the object stopped being an object. The scale down each
 * side is the app's own tick scale turned on its end, and the travel lights
 * from the center detent outward — the same lamp that marks everything live
 * everywhere else.
 *
 * Touch only: there is no hover on a phone, so the whole interaction resolves
 * on press and drag, and the fader is fully operable from TalkBack through the
 * progress semantics rather than through an approximation of a cursor.
 */
@Composable
fun VerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    readout: String,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 170.dp,
    detent: Float = 0.5f,
) {
    val handleHeight = 12.dp

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .semantics {
                contentDescription = label
                stateDescription = readout
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    onValueChange(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange(positionToValue(offset.y, size.height.toFloat(), handleHeight.toPx()))
                }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onValueChange(
                        positionToValue(change.position.y, size.height.toFloat(), handleHeight.toPx())
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val handlePx = handleHeight.toPx()
            val inset = handlePx / 2f
            val travel = (size.height - handlePx).coerceAtLeast(1f)
            val centerX = size.width / 2f

            val clamped = value.coerceIn(0f, 1f)
            val handleY = inset + (1f - clamped) * travel
            val detentY = inset + (1f - detent.coerceIn(0f, 1f)) * travel

            // The scale: eleven ticks down each side, the middle one full width.
            // Same rhythm as the tick scale under the seek bar, stood upright.
            val ticks = 10
            for (i in 0..ticks) {
                val y = inset + (i.toFloat() / ticks) * travel
                val major = i == 0 || i == ticks / 2 || i == ticks
                val reach = if (major) size.width * 0.34f else size.width * 0.22f
                drawLine(
                    color = Steel.Engrave,
                    start = Offset(centerX - reach, y),
                    end = Offset(centerX - size.width * 0.12f, y),
                    strokeWidth = if (major) 1.5f else 1f,
                )
                drawLine(
                    color = Steel.Engrave,
                    start = Offset(centerX + size.width * 0.12f, y),
                    end = Offset(centerX + reach, y),
                    strokeWidth = if (major) 1.5f else 1f,
                )
            }

            // The groove itself.
            val grooveWidth = 3f.coerceAtLeast(size.width * 0.06f)
            drawRoundRect(
                color = Ground.Deep,
                topLeft = Offset(centerX - grooveWidth / 2f, inset),
                size = Size(grooveWidth, travel),
                cornerRadius = CornerRadius(grooveWidth / 2f),
            )
            drawRoundRect(
                color = Steel.EngraveLight,
                topLeft = Offset(centerX - grooveWidth / 2f, inset),
                size = Size(grooveWidth, travel),
                cornerRadius = CornerRadius(grooveWidth / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
            )

            // Lit from the detent to wherever the fader has been pushed, so a
            // flat band shows no light at all and a boost and a cut read apart
            // at a glance rather than by reading the numbers.
            if (handleY != detentY) {
                drawRoundRect(
                    color = Lamp.Bright,
                    topLeft = Offset(centerX - grooveWidth / 2f, minOf(handleY, detentY)),
                    size = Size(grooveWidth, kotlin.math.abs(handleY - detentY)),
                    cornerRadius = CornerRadius(grooveWidth / 2f),
                )
            }

            // The handle: a machined bar with the lamp line across its face.
            val handleWidth = size.width * 0.72f
            drawRoundRect(
                color = Steel.Bright,
                topLeft = Offset(centerX - handleWidth / 2f, handleY - handlePx / 2f),
                size = Size(handleWidth, handlePx),
                cornerRadius = CornerRadius(1.5f),
            )
            drawLine(
                color = Lamp.Warm,
                start = Offset(centerX - handleWidth / 2f + 2f, handleY),
                end = Offset(centerX + handleWidth / 2f - 2f, handleY),
                strokeWidth = 2f,
            )
        }
    }
}

/**
 * Screen position to a 0..1 value, with the travel inset by half a handle at
 * each end so the handle never runs off its own groove.
 */
private fun positionToValue(y: Float, heightPx: Float, handlePx: Float): Float {
    val inset = handlePx / 2f
    val travel = (heightPx - handlePx).coerceAtLeast(1f)
    return (1f - (y - inset) / travel).coerceIn(0f, 1f)
}
