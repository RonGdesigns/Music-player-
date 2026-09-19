package com.irondigital.spindle.widget

import com.irondigital.spindle.data.personal.WidgetAppearance
import com.irondigital.spindle.data.personal.WidgetDensity
import com.irondigital.spindle.data.personal.WidgetStyle
import kotlin.math.max

/** Reserve transport and scaled text before spending height on optional content. */
internal data class WidgetLayout(
    val tiny: Boolean,
    val showProgress: Boolean,
    val showQueue: Boolean,
    val artworkHeight: Float,
    val queueRowHeight: Float,
) {
    companion object {
        fun calculate(height: Float, fontScale: Float, appearance: WidgetAppearance): WidgetLayout {
            val scale = fontScale.coerceAtLeast(1f)
            val row = max(if (appearance.density == WidgetDensity.COMPACT) 48f else 56f, 32f * scale + 12f)
            val compactBase = 16f + max(40f, 40f * scale) + 2f + 48f
            if (height < compactBase) return WidgetLayout(true, false, false, 0f, row)
            val progressHeight = 7f + 18f * scale
            val fullBase = 16f + max(52f, 40f * scale) + 6f + progressHeight + 6f + 48f
            val wantsQueue = appearance.style == WidgetStyle.QUEUE && height >= 230f
            // At short queue sizes, the queue is more useful than the progress readout.
            val progress = height >= fullBase + if (wantsQueue) 15f + row else 0f
            val base = if (progress) fullBase else compactBase
            val queue = wantsQueue && height >= base + 15f + row
            val artBudget = height - (16f + 40f * scale + 8f + 48f +
                if (progress) 6f + progressHeight + 6f else 2f)
            val art = if (appearance.style == WidgetStyle.ARTWORK && height >= 230f && artBudget >= 64f) {
                minOf(180f, artBudget, height * if (appearance.density == WidgetDensity.COMPACT) .32f else .40f)
            } else 0f
            return WidgetLayout(false, progress, queue, art, row)
        }
    }
}
