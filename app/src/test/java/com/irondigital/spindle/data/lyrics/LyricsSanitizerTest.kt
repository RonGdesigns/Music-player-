package com.irondigital.spindle.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song text in these is invented placeholder writing, not anyone's lyrics.
 * What is under test is the noise around it.
 */
class LyricsSanitizerTest {

    private fun lyrics(vararg lines: String, synced: Boolean = false) = Lyrics(
        lines = lines.mapIndexed { i, text ->
            LyricLine(if (synced) i * 3000L else -1L, text)
        },
        synced = synced,
        source = LyricsSource.EMBEDDED_TAG,
        raw = lines.joinToString("\n"),
    )

    // ------------------------------------------------------- spotting noise

    @Test
    fun `a web address is noise`() {
        assertTrue(LyricsSanitizer.isNoise("www.somemusicsite.com"))
        assertTrue(LyricsSanitizer.isNoise("https://somemusicsite.net/track/123"))
        assertTrue(LyricsSanitizer.isNoise("somemusicsite.org"))
    }

    @Test
    fun `a credit line is noise`() {
        assertTrue(LyricsSanitizer.isNoise("Downloaded from SomeMusicSite"))
        assertTrue(LyricsSanitizer.isNoise("Lyrics provided by SomeService"))
        assertTrue(LyricsSanitizer.isNoise("Encoded by someone"))
    }

    @Test
    fun `a timestamp does not hide noise`() {
        assertTrue(LyricsSanitizer.isNoise("[00:01.00] www.somemusicsite.com"))
    }

    // ------------------------------------- what must never be called noise

    @Test
    fun `ordinary writing is never noise`() {
        assertFalse(LyricsSanitizer.isNoise("And the morning came in slow"))
        assertFalse(LyricsSanitizer.isNoise("Mr. Jones is waiting by the door"))
        assertFalse(LyricsSanitizer.isNoise("Somewhere in the U.S.A."))
        assertFalse(LyricsSanitizer.isNoise(""))
    }

    @Test
    fun `a long line using a promotional word is left alone`() {
        // "free download" inside a real sentence is a sentence, not a credit,
        // so the phrase rule only applies to short lines.
        assertFalse(
            LyricsSanitizer.isNoise(
                "I would visit us all again if the year would only turn around for once"
            )
        )
    }

    // ------------------------------------------------------------ cleaning

    @Test
    fun `a credit line is stripped and the song survives`() {
        val cleaned = LyricsSanitizer.clean(
            lyrics(
                "And the morning came in slow",
                "With the curtains halfway drawn",
                "www.somemusicsite.com",
            )
        )!!

        assertEquals(2, cleaned.lines.size)
        assertFalse(cleaned.raw.contains("somemusicsite"))
    }

    @Test
    fun `a tag holding only a website counts as no lyrics at all`() {
        // This is the case that mattered: it used to display as though it were
        // the song, and because something was found the lookup never ran.
        assertNull(LyricsSanitizer.clean(lyrics("www.somemusicsite.com")))
        assertNull(
            LyricsSanitizer.clean(
                lyrics("Downloaded from SomeMusicSite", "www.somemusicsite.com")
            )
        )
    }

    @Test
    fun `a real song is returned untouched`() {
        val original = lyrics(
            "And the morning came in slow",
            "With the curtains halfway drawn",
            "I was counting all the reasons to stay",
        )
        val cleaned = LyricsSanitizer.clean(original)
        assertNotNull(cleaned)
        assertEquals(original.lines, cleaned!!.lines)
        assertEquals(original.raw, cleaned.raw)
    }

    @Test
    fun `timings survive the strip`() {
        val cleaned = LyricsSanitizer.clean(
            lyrics(
                "And the morning came in slow",
                "With the curtains halfway drawn",
                "Downloaded from SomeMusicSite",
                synced = true,
            )
        )!!

        assertTrue(cleaned.synced)
        assertEquals(listOf(0L, 3000L), cleaned.lines.map { it.timeMs })
    }

    @Test
    fun `a scrap too small to be a song is refused`() {
        assertNull(LyricsSanitizer.clean(lyrics("Hey")))
        assertNull(LyricsSanitizer.clean(lyrics("Oh", "Oh")))
    }
}
