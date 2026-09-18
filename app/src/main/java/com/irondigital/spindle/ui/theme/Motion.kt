package com.irondigital.spindle.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween

/**
 * One primary curve and one exit curve, drawn once and used everywhere.
 *
 * The signature is the lamp. Anything that becomes live — the play button, the
 * queue row that is now playing, the selected tab, the active lyric — warms up
 * to amber over [LAMP_ON_MS] and cools back over [LAMP_OFF_MS], slower off than
 * on, because that is how a filament behaves and it is what makes the whole
 * interface feel like one object rather than a set of screens.
 *
 * It is one idea applied consistently. Six different hover behaviors would be
 * a settings file, not a signature.
 */
object Motion {

    /** Rises fast, settles without overshoot. No spring: this is machined, not bouncy. */
    val Primary: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Leaves quickly and without ceremony. */
    val Exit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    const val LAMP_ON_MS = 180
    const val LAMP_OFF_MS = 260

    /** State feedback: press, selection, toggles. */
    const val STATE_MS = 160

    /** A single element moving or resizing. */
    const val ELEMENT_MS = 320

    /** A whole section changing. */
    const val COMPOSITION_MS = 520

    fun <T> lampOn() = tween<T>(durationMillis = LAMP_ON_MS, easing = Primary)
    fun <T> lampOff() = tween<T>(durationMillis = LAMP_OFF_MS, easing = Exit)
    fun <T> state() = tween<T>(durationMillis = STATE_MS, easing = Primary)
    fun <T> element() = tween<T>(durationMillis = ELEMENT_MS, easing = Primary)
}
