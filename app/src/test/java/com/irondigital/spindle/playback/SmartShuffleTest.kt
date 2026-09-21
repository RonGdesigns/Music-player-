package com.irondigital.spindle.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartShuffleTest {

    private fun candidates(vararg artists: String) =
        artists.mapIndexed { i, a -> ShuffleCandidate(artist = a, album = "Album of $a $i") }

    private fun order(
        candidates: List<ShuffleCandidate>,
        startIndex: Int = -1,
        favorUnheard: Boolean = false,
        seed: Int = 1,
    ) = SmartShuffle.order(
        candidates = candidates,
        startIndex = startIndex,
        favorUnheard = favorUnheard,
        now = 1_000L * 60 * 60 * 24 * 400,
        random = Random(seed),
    )

    private fun adjacentArtistRepeats(result: IntArray, candidates: List<ShuffleCandidate>): Int =
        result.toList().zipWithNext().count { (a, b) ->
            candidates[a].artist.equals(candidates[b].artist, ignoreCase = true)
        }

    private fun sameAlbumRepeats(result: IntArray, candidates: List<ShuffleCandidate>): Int =
        result.toList().zipWithNext().count { (a, b) ->
            candidates[a].album.equals(candidates[b].album, ignoreCase = true)
        }

    // ------------------------------------ it must never lose or invent a track

    @Test
    fun `the result is always a permutation of the input`() {
        val input = candidates(*Array(60) { "Artist ${it % 7}" })
        repeat(20) { seed ->
            val result = order(input, seed = seed)
            assertEquals(input.size, result.size)
            assertEquals(input.indices.toSet(), result.toSet())
        }
    }

    @Test
    fun `a queue of one artist is returned whole rather than thinned`() {
        // Every constraint is unsatisfiable here. The queue still has to come
        // back complete — giving up on the spacing is fine, losing tracks is not.
        val input = candidates(*Array(25) { "The Only Band" })
        val result = order(input)
        assertEquals(input.indices.toSet(), result.toSet())
    }

    @Test
    fun `empty and single queues are handled`() {
        assertEquals(0, SmartShuffle.order(emptyList()).size)
        assertEquals(listOf(0), SmartShuffle.order(candidates("Solo")).toList())
    }

    // ----------------------------------------------------- the actual point

    @Test
    fun `the same artist is never played twice in a row when it can be avoided`() {
        val input = candidates(*Array(60) { "Artist ${it % 6}" })
        repeat(20) { seed ->
            assertEquals(0, adjacentArtistRepeats(order(input, seed = seed), input))
        }
    }

    @Test
    fun `one dominant artist is spread as far as arithmetic allows`() {
        // Forty of one artist among sixty tracks cannot be fully separated:
        // keeping forty apart needs thirty-nine other tracks between them and
        // there are twenty, so nineteen adjacent pairs are unavoidable. The
        // test is that it hits that floor rather than merely beating chance.
        val input = candidates(
            *Array(40) { "Everywhere" },
            *Array(20) { "Artist ${it % 4}" },
        )
        val unavoidable = 40 - 20 - 1

        repeat(10) { seed ->
            val repeats = adjacentArtistRepeats(order(input, seed = seed), input)
            assertEquals("seed $seed", unavoidable, repeats)
        }
    }

    @Test
    fun `a queue with room to separate everything separates everything`() {
        val input = candidates(*Array(30) { "Artist ${it % 5}" })
        repeat(10) { seed ->
            assertEquals(0, adjacentArtistRepeats(order(input, seed = seed), input))
        }
    }

    @Test
    fun `it beats a uniform shuffle on the same queue`() {
        val input = candidates(*Array(80) { "Artist ${it % 8}" })
        val uniform = input.indices.shuffled(Random(7)).toIntArray()

        assertTrue(
            adjacentArtistRepeats(order(input, seed = 7), input) <
                adjacentArtistRepeats(uniform, input)
        )
    }

    @Test
    fun `whole albums queued together come apart completely`() {
        // The ordinary case: several records, each by one artist. Measured
        // against uniform shuffling of the same queue, which averages nearly
        // three adjacent same-album pairs and about eleven same-artist ones.
        val input = (0 until 5).flatMap { a ->
            (0 until 3).flatMap { r ->
                List(4) { ShuffleCandidate("Artist $a", "Artist $a record $r") }
            }
        }
        repeat(10) { seed ->
            val result = order(input, seed = seed)
            assertEquals("artists, seed $seed", 0, adjacentArtistRepeats(result, input))
            assertEquals("albums, seed $seed", 0, sameAlbumRepeats(result, input))
        }
    }

    @Test
    fun `a shuffled library separates both artist and album`() {
        val input = (0 until 200).map {
            ShuffleCandidate("Artist ${it % 17}", "Artist ${it % 17} record ${it % 3}")
        }
        val result = order(input)
        assertEquals(0, adjacentArtistRepeats(result, input))
        assertEquals(0, sameAlbumRepeats(result, input))
    }

    @Test
    fun `a compilation splits by artist, and album spacing gives way to it`() {
        // One record credited to several artists is the case the two rules
        // genuinely fight over: separating the artists forces a rotation that
        // keeps bringing the shared album back round. Artist spacing wins,
        // and album spacing here is no better than chance — recorded rather
        // than papered over, because the algorithm does not solve it.
        val input = List(12) { i ->
            ShuffleCandidate(artist = "Artist ${i % 4}", album = if (i < 6) "One Record" else "Another $i")
        }
        repeat(10) { seed ->
            assertEquals(0, adjacentArtistRepeats(order(input, seed = seed), input))
        }
    }

    // ------------------------------------------------------- what plays first

    @Test
    fun `the track already playing stays first`() {
        val input = candidates(*Array(30) { "Artist ${it % 5}" })
        assertEquals(17, order(input, startIndex = 17).first())
    }

    @Test
    fun `an out of range start index is ignored rather than crashing`() {
        val input = candidates(*Array(10) { "Artist $it" })
        assertEquals(input.indices.toSet(), order(input, startIndex = 99).toSet())
        assertEquals(input.indices.toSet(), order(input, startIndex = -5).toSet())
    }

    // --------------------------------------------------------- favoring the old

    @Test
    fun `what has not been heard lately comes earlier when asked for`() {
        val now = 1_000L * 60 * 60 * 24 * 400
        val day = 24L * 60 * 60 * 1000
        // One artist, so the pile's own order is the queue order.
        val input = List(20) { i ->
            ShuffleCandidate(
                artist = "One Band",
                album = "Record $i",
                lastPlayedAt = if (i < 10) now - day else 0L,
            )
        }

        val result = SmartShuffle.order(input, favorUnheard = true, now = now, random = Random(3))
        val neverPlayedPositions = result.toList()
            .withIndex()
            .filter { input[it.value].lastPlayedAt == 0L }
            .map { it.index }

        // All ten never-played tracks should occupy the first ten places.
        assertEquals((0..9).toList(), neverPlayedPositions)
    }

    @Test
    fun `without that flag recency is ignored`() {
        val now = 1_000L * 60 * 60 * 24 * 400
        val input = List(20) { i ->
            ShuffleCandidate("One Band", "Record $i", lastPlayedAt = if (i < 10) 0L else now)
        }
        val result = SmartShuffle.order(input, favorUnheard = false, now = now, random = Random(3))
        val firstTen = result.take(10).map { input[it].lastPlayedAt }
        assertTrue("should be mixed, not sorted", firstTen.any { it != 0L })
    }

    // ------------------------------------------------------------ randomness

    @Test
    fun `the same seed gives the same order and different seeds do not`() {
        val input = candidates(*Array(40) { "Artist ${it % 6}" })
        assertEquals(order(input, seed = 5).toList(), order(input, seed = 5).toList())
        assertNotEquals(order(input, seed = 5).toList(), order(input, seed = 6).toList())
    }
}
