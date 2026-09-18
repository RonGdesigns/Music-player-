package com.irondigital.spindle.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses timestamps into ordered synced lines`() {
        val lyrics = LrcParser.parse(
            """
            [00:12.00]First line
            [00:17.20]Second line
            [01:02.50]Third line
            """.trimIndent()
        )!!

        assertTrue(lyrics.synced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(12_000L, lyrics.lines[0].timeMs)
        assertEquals(17_200L, lyrics.lines[1].timeMs)
        assertEquals(62_500L, lyrics.lines[2].timeMs)
        assertEquals("Third line", lyrics.lines[2].text)
    }

    /** A chorus written once with several timestamps is extremely common. */
    @Test
    fun `expands a line carrying several timestamps`() {
        val lyrics = LrcParser.parse("[00:10.00][01:10.00][02:10.00]Chorus")!!

        assertEquals(3, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
        assertEquals(listOf(10_000L, 70_000L, 130_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `accepts two and three digit fractional seconds`() {
        val two = LrcParser.parse("[00:01.25]x")!!
        val three = LrcParser.parse("[00:01.250]x")!!
        assertEquals(1_250L, two.lines.single().timeMs)
        assertEquals(1_250L, three.lines.single().timeMs)
    }

    @Test
    fun `offset tag shifts timestamps earlier`() {
        val lyrics = LrcParser.parse(
            """
            [offset:500]
            [00:10.00]Line
            """.trimIndent()
        )!!
        assertEquals(9_500L, lyrics.lines.single().timeMs)
    }

    @Test
    fun `offset never pushes a line before zero`() {
        val lyrics = LrcParser.parse(
            """
            [offset:5000]
            [00:01.00]Line
            """.trimIndent()
        )!!
        assertEquals(0L, lyrics.lines.single().timeMs)
    }

    @Test
    fun `plain text with no timestamps is kept as unsynced lyrics`() {
        val lyrics = LrcParser.parse("Just some words\nOn two lines")!!

        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals("Just some words", lyrics.lines[0].text)
    }

    @Test
    fun `metadata tags are not treated as lyric content`() {
        val lyrics = LrcParser.parse(
            """
            [ti:Title]
            [ar:Artist]
            [00:05.00]Actual line
            """.trimIndent()
        )!!
        assertEquals(1, lyrics.lines.size)
        assertEquals("Actual line", lyrics.lines.single().text)
    }

    @Test
    fun `blank input yields nothing`() {
        assertNull(LrcParser.parse(""))
        assertNull(LrcParser.parse("   \n  "))
    }

    @Test
    fun `out of order timestamps are sorted`() {
        val lyrics = LrcParser.parse(
            """
            [00:30.00]Later
            [00:10.00]Earlier
            """.trimIndent()
        )!!
        assertEquals(listOf("Earlier", "Later"), lyrics.lines.map { it.text })
    }
}

class LyricsActiveLineTest {

    private val lyrics = Lyrics(
        lines = listOf(
            LyricLine(0L, "zero"),
            LyricLine(10_000L, "ten"),
            LyricLine(20_000L, "twenty"),
            LyricLine(30_000L, "thirty"),
        ),
        synced = true,
        source = LyricsSource.SIDECAR_LRC,
        raw = "",
    )

    @Test
    fun `finds the line covering the position`() {
        assertEquals(0, lyrics.activeIndexAt(0))
        assertEquals(0, lyrics.activeIndexAt(9_999))
        assertEquals(1, lyrics.activeIndexAt(10_000))
        assertEquals(2, lyrics.activeIndexAt(25_000))
        assertEquals(3, lyrics.activeIndexAt(999_999))
    }

    @Test
    fun `returns no line before the first timestamp`() {
        val delayed = lyrics.copy(
            lines = listOf(LyricLine(5_000L, "late start"))
        )
        assertEquals(-1, delayed.activeIndexAt(0))
        assertEquals(0, delayed.activeIndexAt(5_000))
    }

    @Test
    fun `unsynced lyrics never highlight a line`() {
        assertEquals(-1, lyrics.copy(synced = false).activeIndexAt(15_000))
    }
}
