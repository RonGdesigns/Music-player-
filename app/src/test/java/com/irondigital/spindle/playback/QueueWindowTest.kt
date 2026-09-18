package com.irondigital.spindle.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window the widget lists from.
 *
 * A widget cannot be handed a five thousand track queue — everything it draws
 * crosses a Binder transaction with a hard limit — so the list is bounded, and
 * what matters is that the bound is honest: it starts at what is playing, and
 * the count of what is left over is right, because that number is shown.
 */
class QueueWindowTest {

    private fun snapshot(size: Int, currentIndex: Int) = PlaybackSnapshot(
        currentIndex = currentIndex,
        queue = (0 until size).map {
            QueueEntry(mediaId = "$it", title = "Track $it", artist = "A", durationMs = 1000)
        },
    )

    @Test
    fun `the window starts at what is playing`() {
        val window = snapshot(size = 100, currentIndex = 10).upcoming(5)
        assertEquals(listOf(10, 11, 12, 13, 14), window.map { it.index })
        assertEquals("Track 10", window.first().value.title)
    }

    @Test
    fun `a queue shorter than the window is not padded`() {
        val window = snapshot(size = 3, currentIndex = 0).upcoming(200)
        assertEquals(3, window.size)
        assertEquals(0, snapshot(size = 3, currentIndex = 0).remainingAfter(200))
    }

    @Test
    fun `what is left over is counted from the current track, not the start`() {
        // The window runs forward from what is playing, so the overflow has to
        // as well — counting from the head would overstate it by everything
        // already played.
        val state = snapshot(size = 500, currentIndex = 100)
        assertEquals(200, state.upcoming(200).size)
        assertEquals(200, state.remainingAfter(200))
    }

    @Test
    fun `near the end of a long queue nothing is left over`() {
        val state = snapshot(size = 500, currentIndex = 450)
        assertEquals(50, state.upcoming(200).size)
        assertEquals(0, state.remainingAfter(200))
    }

    @Test
    fun `an empty queue produces no window and no overflow`() {
        val empty = PlaybackSnapshot()
        assertTrue(empty.upcoming(200).isEmpty())
        assertEquals(0, empty.remainingAfter(200))
    }

    @Test
    fun `a queue with no current track produces nothing`() {
        val state = snapshot(size = 10, currentIndex = -1)
        assertTrue(state.upcoming(200).isEmpty())
        assertEquals(0, state.remainingAfter(200))
    }
}
