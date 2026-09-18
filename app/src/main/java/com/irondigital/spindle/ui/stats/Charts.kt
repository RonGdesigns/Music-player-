package com.irondigital.spindle.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * The charts on the listening screen.
 *
 * Every one of them is a single series, so none of them needs a legend or a
 * categorical palette — each is one hue (the lamp) against the plate, and the
 * bar length is the only thing carrying the value. Colouring bars darker where
 * they are bigger would double-encode length as hue and spend the one free
 * channel on information the chart already shows.
 *
 * Values are never reachable *only* by touching a bar: the axis carries the
 * scale, the peak is direct-labelled, and the ranked lists are literal tables.
 * A tooltip that gates the data would fail anyone using a screen reader.
 */

/** The headline number. Sans, proportional figures — never the mono. */
@Composable
fun HeroFigure(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = value, style = SpindleType.Display, color = Ink.Primary, maxLines = 1)
        Text(text = caption, style = SpindleType.Secondary, color = Steel.Bright, maxLines = 2)
    }
}

/**
 * A single number and its label. A one-bar bar chart is never the right form for
 * this; the number is the chart.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.width(16.dp).height(2.dp).background(Lamp.Bright))
        Spacer(Modifier.height(Space.s))
        Text(text = value, style = SpindleType.DisplaySmall, color = Ink.Primary, maxLines = 1)
        Text(text = label, style = SpindleType.Data, color = Steel.Dim, maxLines = 2)
    }
}

/**
 * A column chart over time or over a fixed cycle.
 *
 * Marks are thin with a 2dp gap and a 4dp rounded top anchored to the baseline.
 * Tapping a column selects it and prints its value above the chart — an
 * enhancement, since the peak is labelled anyway and the axis carries the rest.
 */
@Composable
fun ColumnChart(
    values: List<Int>,
    axisLabels: List<Pair<Int, String>>,
    valueLabel: (index: Int, value: Int) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    barColor: Color = Lamp.Bright,
    height: androidx.compose.ui.unit.Dp = 132.dp,
) {
    if (values.isEmpty()) return
    val peak = values.max()
    var selected by remember(values) { mutableIntStateOf(-1) }

    // Nothing has been played in the window, so there is no shape to draw. A
    // chart of thirty zero-height bars says less than one sentence does.
    if (peak <= 0) {
        Text(
            text = "Nothing counted in this window yet.",
            style = SpindleType.Secondary,
            color = Steel.Dim,
        )
        return
    }

    val peakIndex = values.indexOf(peak)
    val readoutIndex = if (selected >= 0) selected else peakIndex

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = valueLabel(readoutIndex, values[readoutIndex]),
            style = SpindleType.RowTitle,
            color = if (selected >= 0) Lamp.Bright else Steel.Bright,
            maxLines = 1,
        )

        Spacer(Modifier.height(Space.s))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / values.size
                        val index = (offset.x / slot).toInt().coerceIn(values.indices)
                        selected = if (selected == index) -1 else index
                    }
                }
        ) {
            val slot = size.width / values.size
            val gap = 2.dp.toPx()
            val barWidth = (slot - gap).coerceAtLeast(1f)
            val radius = minOf(4.dp.toPx(), barWidth / 2f)

            // One hairline at the peak. Solid, one shade off the plate — a dashed
            // grid reads as a threshold when it is only a grid.
            drawLine(
                color = Steel.Engrave,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1f,
            )
            drawLine(
                color = Steel.Engrave,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )

            values.forEachIndexed { index, value ->
                val fraction = value.toFloat() / peak
                val barHeight = (size.height * fraction).coerceAtLeast(if (value > 0) 2f else 0f)
                if (barHeight <= 0f) return@forEachIndexed

                val left = index * slot + gap / 2f
                val top = size.height - barHeight
                val lit = index == readoutIndex

                drawPath(
                    path = roundedTopBar(left, top, barWidth, barHeight, radius),
                    color = if (lit) barColor else barColor.copy(alpha = 0.55f),
                )
            }
        }

        Spacer(Modifier.height(Space.xs))

        // Selective ticks only. A label under every one of thirty columns is a
        // smear, and the readout above covers the specific value.
        Box(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            axisLabels.forEach { (index, label) ->
                val fraction = (index + 0.5f) / values.size
                Text(
                    text = label,
                    style = SpindleType.Data,
                    color = Steel.Dim,
                    maxLines = 1,
                    modifier = Modifier.layoutTickAt(fraction),
                )
            }
        }
    }
}

/**
 * Places a tick label centred on [fraction] of the width, clamped so the first
 * and last do not hang off the edge.
 */
private fun Modifier.layoutTickAt(fraction: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        val x = (constraints.maxWidth * fraction - placeable.width / 2f)
            .coerceIn(0f, (constraints.maxWidth - placeable.width).toFloat().coerceAtLeast(0f))
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(x.toInt(), 0)
        }
    }
)

private fun roundedTopBar(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: Float,
): Path {
    val r = minOf(radius, height)
    return Path().apply {
        moveTo(left, top + height)
        lineTo(left, top + r)
        quadraticBezierTo(left, top, left + r, top)
        lineTo(left + width - r, top)
        quadraticBezierTo(left + width, top, left + width, top + r)
        lineTo(left + width, top + height)
        close()
    }
}

/**
 * A ranked list drawn as horizontal bars. Every bar is the same hue — the
 * categories here (artists, tracks) have no natural order, so a value ramp would
 * be encoding length twice.
 *
 * It is also its own table: the name and the number are both in text.
 */
@Composable
fun RankedBars(
    entries: List<RankedEntry>,
    unit: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val peak = entries.maxOf { it.value }.coerceAtLeast(1)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        entries.forEach { entry ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.label,
                            style = SpindleType.RowTitle,
                            color = Ink.Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.secondary,
                            style = SpindleType.Data,
                            color = Steel.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        text = unit(entry.value),
                        style = SpindleType.DataEmphasis,
                        color = Lamp.Bright,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.height(Space.xs))

                Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                    drawRect(
                        color = Steel.Engrave,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, size.height),
                    )
                    val width = size.width * (entry.value.toFloat() / peak)
                    if (width > 0f) {
                        drawRect(
                            color = Lamp.Bright,
                            topLeft = Offset(0f, 0f),
                            size = Size(width, size.height),
                        )
                    }
                }
            }
        }
    }
}
