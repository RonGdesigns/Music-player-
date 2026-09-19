package com.irondigital.spindle.playback

import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import org.junit.Assert.*
import org.junit.Test

class QueueRestorationTest {
    private val entries = (0..3).map { QueueEntry("$it", "Track $it", "Artist", 10000,
        "Album", "content://art/$it", "content://media/external/audio/media/$it") }

    @Test fun `widget follows the same deterministic shuffle as the player`() {
        val order = listOf(2, 0, 3, 1)
        val playerOrder = DefaultShuffleOrder(order.toIntArray(), 42)
        val state = PlaybackSnapshot(queue = entries, currentIndex = 0, shuffleEnabled = true, playbackOrder = order)
        assertEquals(playerOrder.getNextIndex(0), state.upcoming(2)[1].index)
        assertEquals(listOf(0, 3, 1), state.upcoming(20).map { it.index })
        assertEquals(1, state.remainingAfter(2))
    }

    @Test fun `repeat all wraps once and repeat one shows the repeating song`() {
        val state = PlaybackSnapshot(queue = entries, currentIndex = 3, playbackOrder = listOf(2, 0, 3, 1))
        assertEquals(listOf(3, 1, 2, 0), state.copy(repeatMode = 2).upcoming(20).map { it.index })
        assertEquals(listOf(3), state.copy(repeatMode = 1).upcoming(20).map { it.index })
    }

    @Test fun `snapshot round trip restores modes position metadata and shuffle order`() {
        val state = PlaybackSnapshot(queue = entries, currentIndex = 2, positionMs = 1234,
            shuffleEnabled = true, repeatMode = 2, playbackOrder = listOf(2, 0, 3, 1))
        val restored = PlaybackSnapshot.fromJson(state.toJson()).restoration()!!
        assertEquals(entries, restored.entries)
        assertEquals(2, restored.currentIndex)
        assertEquals(1234L, restored.positionMs)
        assertEquals(listOf(2, 0, 3, 1), restored.order)
        assertTrue(restored.shuffleEnabled)
        assertEquals(2, restored.repeatMode)
    }

    @Test fun `old snapshots recover current artwork and invalid entries remap the start`() {
        val state = PlaybackSnapshot(queue = listOf(QueueEntry("invalid", "", "", 0),
            QueueEntry("10", "Song", "Artist", 2000)), currentIndex = 1,
            positionMs = 800, album = "Saved album", artUri = "content://art/10")
        val restored = PlaybackSnapshot.fromJson(state.toJson()).restoration()!!
        assertEquals(0, restored.currentIndex)
        assertEquals(800L, restored.positionMs)
        assertEquals("Saved album", restored.entries.single().album)
        assertEquals("content://art/10", restored.entries.single().artUri)
        assertEquals(listOf(0), restored.order)
    }

    @Test fun `invalid traversal falls back without losing or duplicating rows`() {
        val state = PlaybackSnapshot(queue = entries, currentIndex = 0, playbackOrder = listOf(0, 0, 8))
        assertEquals(listOf(0, 1, 2, 3), state.upcoming(10).map { it.index })
        assertTrue(state.upcoming(-1).isEmpty())
    }
}
