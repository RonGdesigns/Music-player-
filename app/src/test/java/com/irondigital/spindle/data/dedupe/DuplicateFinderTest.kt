package com.irondigital.spindle.data.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private var nextId = 0

    private fun candidate(
        title: String = "Dreams",
        artist: String = "Fleetwood Mac",
        durationMs: Long = 250_000,
        bitrateBps: Int = 192_000,
        sizeBytes: Long = 6_000_000,
        mediaId: String = "id${nextId++}",
    ) = DedupeCandidate(mediaId, title, artist, durationMs, bitrateBps, sizeBytes)

    // ------------------------------------------------------------ matching

    @Test
    fun `nothing to compare produces no groups`() {
        assertTrue(DuplicateFinder.find(emptyList()).isEmpty())
        assertTrue(DuplicateFinder.find(listOf(candidate())).isEmpty())
    }

    @Test
    fun `two encodes of one recording group together`() {
        val groups = DuplicateFinder.find(
            listOf(
                candidate(bitrateBps = 320_000, sizeBytes = 10_000_000),
                candidate(bitrateBps = 128_000, sizeBytes = 4_000_000),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().all.size)
    }

    @Test
    fun `upload noise in the title does not stop a match`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(title = "Dreams"), candidate(title = "Dreams (Official Video)"))
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `punctuation and case do not stop a match`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(title = "Don't Stop"), candidate(title = "dont stop"))
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `a small difference in length is tolerated`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(durationMs = 250_000), candidate(durationMs = 251_500))
        )
        assertEquals(1, groups.size)
    }

    // ------------------------------------------------- the dangerous mistakes

    @Test
    fun `a remix is never grouped with the original`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(title = "Dreams"), candidate(title = "Dreams (Remix)"))
        )
        assertTrue("a remix must never be offered for deletion", groups.isEmpty())
    }

    @Test
    fun `a live version is never grouped with the studio cut`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(title = "Dreams"), candidate(title = "Dreams (Live)"))
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `the same title by different artists is not a duplicate`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(artist = "Fleetwood Mac"), candidate(artist = "Someone Else"))
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a long edit is not a duplicate of a short one`() {
        val groups = DuplicateFinder.find(
            listOf(candidate(durationMs = 210_000), candidate(durationMs = 600_000))
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a chain of small steps does not merge the two ends`() {
        // Each is two seconds past the last; without anchoring to the cluster's
        // first member this would swallow a run of genuinely different lengths.
        val groups = DuplicateFinder.find(
            listOf(
                candidate(durationMs = 200_000),
                candidate(durationMs = 202_000),
                candidate(durationMs = 204_000),
                candidate(durationMs = 206_000),
                candidate(durationMs = 208_000),
            )
        )
        assertTrue(
            "no group may span more than the tolerance",
            groups.all { group ->
                val lengths = group.all.map { it.durationMs }
                (lengths.max() - lengths.min()) <= DuplicateFinder.TOLERANCE_MS
            }
        )
    }

    @Test
    fun `a blank title is never matched against another blank one`() {
        val groups = DuplicateFinder.find(listOf(candidate(title = ""), candidate(title = "")))
        assertTrue(groups.isEmpty())
    }

    // -------------------------------------------------------- which to keep

    @Test
    fun `the better encode is the one kept`() {
        val best = candidate(bitrateBps = 320_000, sizeBytes = 10_000_000)
        val worst = candidate(bitrateBps = 128_000, sizeBytes = 4_000_000)

        val group = DuplicateFinder.find(listOf(worst, best)).single()
        assertEquals(best.mediaId, group.keep.mediaId)
        assertEquals(listOf(worst.mediaId), group.others.map { it.mediaId })
    }

    @Test
    fun `at equal bitrate the larger file wins`() {
        val bigger = candidate(bitrateBps = 192_000, sizeBytes = 9_000_000)
        val smaller = candidate(bitrateBps = 192_000, sizeBytes = 5_000_000)

        assertEquals(bigger.mediaId, DuplicateFinder.find(listOf(smaller, bigger)).single().keep.mediaId)
    }

    @Test
    fun `the choice does not depend on input order`() {
        val a = candidate(bitrateBps = 192_000, sizeBytes = 6_000_000, mediaId = "a")
        val b = candidate(bitrateBps = 192_000, sizeBytes = 6_000_000, mediaId = "b")

        val forward = DuplicateFinder.find(listOf(a, b)).single().keep.mediaId
        val backward = DuplicateFinder.find(listOf(b, a)).single().keep.mediaId
        assertEquals(forward, backward)
    }

    @Test
    fun `reclaimable bytes counts everything but the keeper`() {
        val group = DuplicateFinder.find(
            listOf(
                candidate(bitrateBps = 320_000, sizeBytes = 10_000_000),
                candidate(bitrateBps = 192_000, sizeBytes = 6_000_000),
                candidate(bitrateBps = 128_000, sizeBytes = 4_000_000),
            )
        ).single()

        assertEquals(10_000_000L, group.reclaimableBytes)
    }

    @Test
    fun `groups are ordered by how much space they would free`() {
        val groups = DuplicateFinder.find(
            listOf(
                candidate(title = "Small", sizeBytes = 1_000_000, bitrateBps = 320_000),
                candidate(title = "Small", sizeBytes = 1_000_000, bitrateBps = 128_000),
                candidate(title = "Large", sizeBytes = 20_000_000, bitrateBps = 320_000),
                candidate(title = "Large", sizeBytes = 20_000_000, bitrateBps = 128_000),
            )
        )
        assertEquals(2, groups.size)
        assertTrue(groups.first().reclaimableBytes > groups.last().reclaimableBytes)
    }

    @Test
    fun `three copies leave one keeper and two others`() {
        val group = DuplicateFinder.find(
            listOf(candidate(), candidate(), candidate())
        ).single()

        assertEquals(2, group.others.size)
        assertEquals(3, group.all.size)
    }
}
