package com.irondigital.spindle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Steel

/**
 * The app's textural signature: a milled tick scale, the kind engraved on a
 * tuner faceplate or a mixing desk.
 *
 * It is here for a specific reason. A page divided only by hairlines reads as
 * undivided, because a division you can predict stops registering — and a 1px
 * rule is the most predictable division there is. A tick scale is still a
 * horizontal division, but it carries a rhythm, so the eye actually stops at
 * it. It also happens to be the one element on screen that proves a person drew
 * this rather than accepting a divider component.
 *
 * Used at section edges and beneath the seek bar, where the [progress] argument
 * lights the ticks already played.
 */
@Composable
fun TickScale(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    height: Dp = 10.dp,
    spacing: Dp = 6.dp,
    majorEvery: Int = 5,
    tickColor: Color = Steel.Engrave,
    litColor: Color = Lamp.Bright,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val spacingPx = spacing.toPx()
        if (spacingPx <= 0f) return@Canvas
        val count = (size.width / spacingPx).toInt()
        val litUntil = size.width * progress.coerceIn(0f, 1f)

        for (i in 0..count) {
            val x = i * spacingPx
            val isMajor = i % majorEvery == 0
            // Major ticks run the full height, minor ones just over half. The
            // ratio is what makes it read as a scale instead of a dotted line.
            val tickHeight = if (isMajor) size.height else size.height * 0.55f
            val lit = x <= litUntil
            drawLine(
                color = if (lit) litColor else tickColor,
                start = Offset(x, size.height - tickHeight),
                end = Offset(x, size.height),
                strokeWidth = if (isMajor) 1.5f else 1f,
                cap = StrokeCap.Butt,
                alpha = if (lit) 1f else if (isMajor) 1f else 0.7f,
            )
        }
    }
}

/**
 * A plain groove, for divisions that should stay quiet. Deliberately not the
 * only divider in the app — see [TickScale].
 */
@Composable
fun Groove(
    modifier: Modifier = Modifier,
    color: Color = Steel.Engrave,
    thickness: Dp = 1.dp,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(thickness)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
        )
    }
}
