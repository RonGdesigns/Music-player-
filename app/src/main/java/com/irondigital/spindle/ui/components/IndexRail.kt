package com.irondigital.spindle.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * The fast-scroll rail: drag down the right edge to travel the list.
 *
 * It is drawn as a vertical engraved scale — the same faceplate language as the
 * seek bar's tick scale, rotated — because that is exactly what it is: a ruler
 * against the length of the library. The stop you are currently at lights amber,
 * the same "this is live" signal the transport and the queue use.
 *
 * Two things it deliberately does not do. It does not invent an A–Z when the
 * list is not alphabetical (see [com.irondigital.spindle.ui.library.ListIndex]),
 * and it does not appear at all on a list short enough to flick through, where a
 * rail would be pure decoration.
 *
 * It is strictly an accelerator. The list scrolls normally without it, which
 * matters because a 14dp rail label cannot meet a 44dp touch target and a rail
 * is therefore never allowed to be the only way to reach a row.
 */
@Composable
fun IndexRail(
    entries: List<IndexRailEntry>,
    activeEntryIndex: Int,
    onJump: (itemIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.size < MIN_ENTRIES) return

    val haptics = LocalHapticFeedback.current
    var railHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableIntStateOf(-1) }

    // While the finger is down the rail follows the finger; otherwise it follows
    // the list. Letting the list drive during a drag makes the puck fight the
    // thumb, which feels broken even though both values are "correct".
    val highlighted = if (dragging && draggedIndex >= 0) draggedIndex else activeEntryIndex

    fun jumpFromY(y: Float) {
        if (railHeightPx <= 0) return
        val fraction = (y / railHeightPx).coerceIn(0f, 1f)
        val index = (fraction * entries.size).toInt().coerceIn(entries.indices)
        if (index != draggedIndex) {
            draggedIndex = index
            // A tick per stop is what makes a blind drag usable.
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onJump(entries[index].itemIndex)
        }
    }

    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The puck. Sits to the left of the rail so a thumb on the rail is not
        // covering the one thing it is there to read.
        AnimatedVisibility(
            visible = dragging && highlighted in entries.indices,
            enter = fadeIn(tween(Motion.LAMP_ON_MS, easing = Motion.Primary)),
            exit = fadeOut(tween(Motion.LAMP_OFF_MS, easing = Motion.Exit)),
        ) {
            Box(
                modifier = Modifier
                    .padding(end = Space.s)
                    .background(Lamp.Bright)
                    .padding(horizontal = Space.m, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entries.getOrNull(highlighted)?.spokenLabel.orEmpty(),
                    style = SpindleType.DisplaySmall,
                    color = Ink.OnLamp,
                    maxLines = 1,
                )
            }
        }

        Column(
            modifier = Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .background(if (dragging) Ground.Plate else Ground.Deep.copy(alpha = 0f))
                .onSizeChanged { railHeightPx = it.height }
                .semantics {
                    contentDescription =
                        "Fast scroll. Drag to jump through the list. The list also " +
                            "scrolls normally."
                }
                .pointerInput(entries) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            draggedIndex = -1
                            jumpFromY(offset.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            jumpFromY(change.position.y)
                        },
                    )
                }
                .pointerInput(entries) {
                    // A separate detector so a plain tap lands immediately rather
                    // than waiting out the drag slop.
                    detectTapGestures { offset ->
                        draggedIndex = -1
                        jumpFromY(offset.y)
                    }
                }
                .padding(vertical = Space.s),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            entries.forEachIndexed { index, entry ->
                val lit = index == highlighted
                val color by animateColorAsState(
                    targetValue = if (lit) Lamp.Bright else Steel.Dim,
                    animationSpec = if (lit) Motion.lampOn() else Motion.lampOff(),
                    label = "rail-$index",
                )
                Text(
                    text = entry.shortLabel,
                    style = SpindleType.Data,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A rail stop. [shortLabel] is what fits on the rail; [spokenLabel] is the fuller
 * form the puck and the screen reader use.
 */
data class IndexRailEntry(
    val shortLabel: String,
    val spokenLabel: String,
    val itemIndex: Int,
)

/** Below this the list is a flick away and a rail would be decoration. */
private const val MIN_ENTRIES = 4
private val RAIL_WIDTH = 30.dp
