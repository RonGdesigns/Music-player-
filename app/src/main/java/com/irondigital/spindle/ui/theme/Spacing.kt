package com.irondigital.spindle.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Every margin, padding and gap in the app comes from here. No magic numbers —
 * this is the one measurable difference between a layout with rhythm and one
 * that was nudged into place.
 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val huge = 64.dp

    /** Side gutter. Held constant so every screen shares one left edge. */
    val gutter = 20.dp

    /** Minimum interactive size. Non-negotiable. */
    val tap = 48.dp
}

/**
 * Machined, not rounded. 2dp is a decision: it reads as a milled edge rather
 * than a soft card, and it is the same value everywhere so nothing looks like
 * it came from a different kit.
 */
object Corner {
    val edge = 2.dp
    val plate = 3.dp
}
