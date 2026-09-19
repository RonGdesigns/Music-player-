package com.irondigital.spindle.widget

import com.irondigital.spindle.data.personal.*
import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutTest {
    @Test fun minimumAndOneRowHostsUseOneRowTransport() {
        for (height in listOf(70f, 88f, 96f)) for (scale in listOf(1f, 1.3f, 1.6f, 2f)) {
            assertTrue("$height dp at font scale $scale", WidgetLayout.calculate(height, scale, WidgetAppearance()).tiny)
        }
    }
    @Test fun shortArtworkFallsBackBeforeClippingTransport() {
        val layout = WidgetLayout.calculate(230f, 1.6f, WidgetAppearance(WidgetStyle.ARTWORK))
        assertFalse(layout.tiny)
        assertEquals(0f, layout.artworkHeight, 0f)
        assertTrue(layout.showProgress)
    }
    @Test fun shortQueueKeepsReadableRowsByDroppingProgress() {
        val layout = WidgetLayout.calculate(230f, 1.6f, WidgetAppearance())
        assertTrue(layout.showQueue)
        assertFalse(layout.showProgress)
        assertTrue(layout.queueRowHeight >= 63f)
    }
    @Test fun tallArtworkReservesTextAndTransportAtEveryFontScale() {
        for (scale in listOf(1f, 1.3f, 1.6f, 2f)) {
            val layout = WidgetLayout.calculate(340f, scale, WidgetAppearance(WidgetStyle.ARTWORK))
            assertTrue(layout.artworkHeight >= 64f)
            val occupied = 16f + layout.artworkHeight + 8f + 40f * scale + 6f + 7f + 18f * scale + 6f + 48f
            assertTrue("$occupied exceeds 340 dp at font scale $scale", occupied <= 340f)
        }
    }
}
