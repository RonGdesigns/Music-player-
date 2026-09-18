package com.irondigital.spindle.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes a full-screen overlay behave like one.
 *
 * A composable drawn on top of another does not stop touches reaching it. Hit
 * testing walks siblings from the top down and stops at the first one that
 * claims the pointer, so an overlay with no pointer handler of its own is
 * transparent to touch: a tap on empty space in the player goes straight
 * through to the track list still composed behind it and starts a different
 * song.
 *
 * Claiming the pointer is all this has to do. Being a hit target is what stops
 * the traversal reaching the screen underneath; the tap handler itself does
 * nothing, and because it waits for a press nobody else has taken, every
 * button, slider and list inside the overlay still gets first refusal.
 *
 * The obvious-looking alternative — consuming every change on every pass — is
 * what this replaced, and it was wrong twice over. Consuming pointer *moves*
 * cancels gestures that are still in progress, because a detector waiting for
 * the finger to lift treats a consumed change as the gesture being taken away
 * from it; that is a button needing two or three presses to register. And
 * wrapping it in [androidx.compose.ui.composed] defeated modifier comparison
 * on the root of a screen that recomposes on every position tick.
 */
fun Modifier.consumeTouches(): Modifier = pointerInput(Unit) {
    detectTapGestures { /* Swallowed. The point is that nothing below sees it. */ }
}
