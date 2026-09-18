package com.irondigital.spindle.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes a full-screen overlay behave like one.
 *
 * A composable drawn on top of another does not stop touches reaching it. Hit
 * testing walks siblings from the top down and only stops at the first one that
 * actually claims the pointer, so an overlay with no pointer handler of its own
 * is transparent to touch: a tap that lands on empty space in the player goes
 * straight through to the track list still composed behind it, and starts a
 * different song.
 *
 * Consuming on the Main pass is what makes this safe to put at the root. That
 * pass runs child first, so everything inside the overlay — buttons, the seek
 * bar, the scrolling queue — has already taken what it needs by the time this
 * claims the remainder.
 */
fun Modifier.consumeTouches(): Modifier = composed {
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
            }
        }
    }
}
