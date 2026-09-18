package com.irondigital.spindle.ui.library

import com.irondigital.spindle.data.settings.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the rail's bucketing through [ListIndex.trackKey] and
 * [ListIndex.forItems] rather than through Track, which carries an android.net.Uri
 * and would drag the whole framework into a test of string bucketing.
 */
class ListIndexTest {

    private fun key(
        sort: LibrarySort,
        title: String = "Title",
        artist: String = "Artist",
        album: String = "Album",
        dateAddedSec: Long = 0,
        playCount: Int = 0,
        durationMs: Long = 180_000,
    ) = ListIndex.trackKey(sort, title, artist, album, dateAddedSec, playCount, durationMs)

    // ------------------------------------------------------------- grouping

    @Test
    fun `collapses runs of equal keys into one stop each`() {
        val entries = ListIndex.forItems(listOf("Alpha", "Anvil", "Bridge", "Crown")) {
            it.first().toString()
        }
        assertEquals(listOf("A", "B", "C"), entries.map { it.label })
        assertEquals(listOf(0, 2, 3), entries.map { it.itemIndex })
    }

    @Test
    fun `a key that recurs after a gap opens a new stop`() {
        // The index describes a sorted list, so a repeat means the caller handed
        // us something unsorted; the rail must still point at the right rows.
        val entries = ListIndex.forItems(listOf("A", "B", "A")) { it }
        assertEquals(listOf(0, 1, 2), entries.map { it.itemIndex })
    }

    @Test
    fun `empty input produces no stops`() {
        assertTrue(ListIndex.forItems(emptyList<String>()) { it }.isEmpty())
        assertTrue(ListIndex.forLabels(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ alpha keys

    @Test
    fun `title sort keys off the title and artist sort keys off the artist`() {
        assertEquals("Z", key(LibrarySort.TITLE, title = "Zebra", artist = "Adams"))
        assertEquals("A", key(LibrarySort.ARTIST, title = "Zebra", artist = "Adams"))
        assertEquals("Q", key(LibrarySort.ALBUM, album = "Quarry"))
    }

    @Test
    fun `case is folded so upper and lower share a stop`() {
        assertEquals(key(LibrarySort.TITLE, title = "apple"), key(LibrarySort.TITLE, title = "Apricot"))
    }

    @Test
    fun `leading whitespace does not create its own stop`() {
        assertEquals("A", key(LibrarySort.TITLE, title = "   Alpha"))
    }

    @Test
    fun `numerals and punctuation collect under one bucket`() {
        assertEquals(ListIndex.NON_ALPHA, key(LibrarySort.TITLE, title = "13 Steps"))
        assertEquals(ListIndex.NON_ALPHA, key(LibrarySort.TITLE, title = "(Intro)"))
        assertEquals(ListIndex.NON_ALPHA, key(LibrarySort.TITLE, title = ""))
    }

    @Test
    fun `an accented letter keeps its own stop rather than falling to the hash`() {
        // Ø is a letter, so it belongs on the rail, not in the junk bucket.
        assertEquals("Ø", key(LibrarySort.TITLE, title = "Øresund"))
    }

    // ------------------------------------------------------------ band keys

    @Test
    fun `play count bands are descending and inclusive at the boundary`() {
        assertEquals("500", key(LibrarySort.PLAY_COUNT, playCount = 600))
        assertEquals("500", key(LibrarySort.PLAY_COUNT, playCount = 500))
        assertEquals("200", key(LibrarySort.PLAY_COUNT, playCount = 499))
        assertEquals("100", key(LibrarySort.PLAY_COUNT, playCount = 120))
        assertEquals("5", key(LibrarySort.PLAY_COUNT, playCount = 7))
        assertEquals("1", key(LibrarySort.PLAY_COUNT, playCount = 1))
        assertEquals("0", key(LibrarySort.PLAY_COUNT, playCount = 0))
    }

    @Test
    fun `duration bands cover the boundaries`() {
        assertEquals("20+", key(LibrarySort.DURATION, durationMs = 25 * 60_000L))
        assertEquals("20+", key(LibrarySort.DURATION, durationMs = 20 * 60_000L))
        assertEquals("10", key(LibrarySort.DURATION, durationMs = 19 * 60_000L))
        assertEquals("5", key(LibrarySort.DURATION, durationMs = 6 * 60_000L))
        assertEquals("<2", key(LibrarySort.DURATION, durationMs = 90_000L))
        assertEquals("<2", key(LibrarySort.DURATION, durationMs = 0L))
    }

    @Test
    fun `a missing date added does not produce a bogus year`() {
        assertEquals("—", key(LibrarySort.DATE_ADDED, dateAddedSec = 0))
    }

    // -------------------------------------------------------------- sampling

    @Test
    fun `sampling keeps both ends and respects the cap`() {
        val entries = (0 until 100).map { IndexEntry("L$it", it) }
        val sampled = ListIndex.sampleForDisplay(entries, maxLabels = 10)

        assertEquals(10, sampled.size)
        assertEquals(entries.first(), sampled.first())
        assertEquals(entries.last(), sampled.last())
    }

    @Test
    fun `sampling leaves a short index untouched`() {
        val entries = (0 until 5).map { IndexEntry("L$it", it) }
        assertEquals(entries, ListIndex.sampleForDisplay(entries, maxLabels = 28))
    }

    @Test
    fun `sampled stops stay in ascending row order`() {
        val entries = (0 until 250).map { IndexEntry("L$it", it * 3) }
        val sampled = ListIndex.sampleForDisplay(entries, maxLabels = 28)

        // A rail whose stops ran backwards would scroll the wrong way mid-drag.
        assertEquals(sampled.sortedBy { it.itemIndex }, sampled)
    }

    @Test
    fun `sampling an index the size of the cap is a no-op`() {
        val entries = (0 until 28).map { IndexEntry("L$it", it) }
        assertEquals(entries, ListIndex.sampleForDisplay(entries, maxLabels = 28))
    }

    // ---------------------------------------------------------------- labels

    @Test
    fun `spoken labels read as thresholds not bare numbers`() {
        assertEquals("Never played", ListIndex.spokenLabel(IndexEntry("0", 0), LibrarySort.PLAY_COUNT))
        assertEquals("50+ plays", ListIndex.spokenLabel(IndexEntry("50", 0), LibrarySort.PLAY_COUNT))
        assertEquals("Under 2 minutes", ListIndex.spokenLabel(IndexEntry("<2", 0), LibrarySort.DURATION))
        assertEquals("7 minutes", ListIndex.spokenLabel(IndexEntry("7", 0), LibrarySort.DURATION))
    }

    @Test
    fun `a year is shortened for the rail but spoken in full`() {
        val entry = IndexEntry("2026", 0)
        assertEquals("26", ListIndex.displayLabel(entry, LibrarySort.DATE_ADDED))
        assertEquals("2026", ListIndex.spokenLabel(entry, LibrarySort.DATE_ADDED))
    }
}
