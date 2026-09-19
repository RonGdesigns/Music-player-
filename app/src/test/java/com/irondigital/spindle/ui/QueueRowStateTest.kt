package com.irondigital.spindle.ui

import org.junit.Assert.*
import org.junit.Test
import com.irondigital.spindle.playback.QueueEntry

class QueueRowStateTest {
    @Test fun `unavailable entries do not shift later player indices`() {
        val rows = resolveQueueRows(listOf("A", "B", "C").map {
            QueueEntry(it, it, "Artist", 1000)
        }, 5) { id ->
            if (id == "B") null else QueueTrackDetails(id, "Artist", 1000)
        }
        assertEquals(3, rows.size)
        assertFalse(rows[1].available)
        assertTrue(rows[2].matches(5, listOf("A", "B", "C")))
        assertEquals(2, rows[2].index)
    }

    @Test fun `old row cannot remove a replacement queue entry`() {
        val row = QueueRowState(1, 5, "B", "B", "Artist", 1000, true)
        assertFalse(row.matches(6, listOf("A", "B", "C")))
        assertFalse(row.matches(5, listOf("A", "C")))
        assertFalse(row.matches(5, emptyList()))
    }

    @Test fun `repeated songs retain distinct positions and invalidate on reorder`() {
        val second = QueueRowState(2, 5, "A", "A", "Artist", 1000, true)
        assertTrue(second.matches(5, listOf("A", "B", "A")))
        assertEquals(2, second.index)
        assertFalse(second.matches(6, listOf("A", "A", "B")))
    }
}
