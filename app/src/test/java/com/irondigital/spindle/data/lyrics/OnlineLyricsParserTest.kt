package com.irondigital.spindle.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsParserTest {

    private fun candidate(
        title: String = "Dreams",
        artist: String = "Fleetwood Mac",
        durationMs: Long = 257_000,
        instrumental: Boolean = false,
        synced: String? = "[00:11.77] Now here you go again",
        plain: String? = "Now here you go again",
    ) = LyricsCandidate(title, artist, durationMs, instrumental, synced, plain)

    // ------------------------------------------------------------- reading

    @Test
    fun `a response is read into a candidate`() {
        val json = """
            {"trackName":"Dreams","artistName":"Fleetwood Mac","duration":256.0,
             "instrumental":false,"plainLyrics":"Now here you go again",
             "syncedLyrics":"[00:11.77] Now here you go again"}
        """.trimIndent()

        val parsed = OnlineLyricsParser.parseOne(json)!!
        assertEquals("Dreams", parsed.trackName)
        assertEquals("Fleetwood Mac", parsed.artistName)
        // Sent as fractional seconds, held as milliseconds.
        assertEquals(256_000L, parsed.durationMs)
        assertTrue(parsed.hasAnything)
    }

    @Test
    fun `nonsense is refused rather than half read`() {
        assertNull(OnlineLyricsParser.parseOne(""))
        assertNull(OnlineLyricsParser.parseOne("not json"))
        assertTrue(OnlineLyricsParser.parseMany("not json").isEmpty())
        assertTrue(OnlineLyricsParser.parseMany("{}").isEmpty())
    }

    @Test
    fun `a missing lyrics field is absent rather than blank`() {
        val parsed = OnlineLyricsParser.parseOne(
            """{"trackName":"X","artistName":"Y","duration":100.0,"syncedLyrics":null}"""
        )!!
        assertNull(parsed.syncedLyrics)
    }

    // ------------------------------------------------------- choosing well

    @Test
    fun `the closest length wins`() {
        val best = OnlineLyricsParser.chooseBest(
            listOf(
                candidate(durationMs = 259_000),
                candidate(durationMs = 257_200),
            ),
            durationMs = 257_000,
        )!!
        assertEquals(257_200L, best.durationMs)
    }

    @Test
    fun `synced beats unsynced even when slightly further off`() {
        val best = OnlineLyricsParser.chooseBest(
            listOf(
                candidate(durationMs = 257_000, synced = null),
                candidate(durationMs = 258_500, synced = "[00:01.00] words"),
            ),
            durationMs = 257_000,
        )!!
        assertEquals(258_500L, best.durationMs)
    }

    @Test
    fun `a different recording of the same song is refused`() {
        // This is the one that matters. A live cut is the same title by the
        // same artist, and synced lyrics from the wrong take look correct and
        // then drift, which is worse than showing nothing at all.
        assertNull(
            OnlineLyricsParser.chooseBest(
                listOf(candidate(durationMs = 340_000)),
                durationMs = 257_000,
            )
        )
    }

    @Test
    fun `nothing offered means nothing chosen`() {
        assertNull(OnlineLyricsParser.chooseBest(emptyList(), durationMs = 257_000))
    }

    @Test
    fun `a candidate with words beats an instrumental of the same length`() {
        val best = OnlineLyricsParser.chooseBest(
            listOf(
                candidate(instrumental = true, synced = null, plain = null),
                candidate(),
            ),
            durationMs = 257_000,
        )!!
        assertTrue(best.hasAnything)
    }

    @Test
    fun `a candidate that reports no length is still considered`() {
        // Some entries carry no duration at all. Discarding them would throw
        // away a perfectly good match for a field that was never filled in.
        val best = OnlineLyricsParser.chooseBest(
            listOf(candidate(durationMs = 0)),
            durationMs = 257_000,
        )
        assertTrue(best != null)
    }

    // ------------------------------------------------------- the verdict

    @Test
    fun `synced lyrics come back as synced`() {
        val result = OnlineLyricsParser.toLookup(candidate()) as LyricsLookup.Found
        assertTrue(result.synced)
        assertTrue(result.lrc.startsWith("[00:11.77]"))
    }

    @Test
    fun `plain lyrics come back unsynced`() {
        val result = OnlineLyricsParser.toLookup(candidate(synced = null)) as LyricsLookup.Found
        assertEquals(false, result.synced)
        assertEquals("Now here you go again", result.lrc)
    }

    @Test
    fun `an instrumental is a real answer, not a failure`() {
        // It means "there is nothing to show", which is worth remembering so
        // the track is not looked up again on every play.
        assertEquals(
            LyricsLookup.NotFound,
            OnlineLyricsParser.toLookup(
                candidate(instrumental = true, synced = null, plain = null)
            ),
        )
    }

    @Test
    fun `no candidate is not found`() {
        assertEquals(LyricsLookup.NotFound, OnlineLyricsParser.toLookup(null))
    }
}
