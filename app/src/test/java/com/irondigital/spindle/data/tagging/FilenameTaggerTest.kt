package com.irondigital.spindle.data.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameTaggerTest {

    // ------------------------------------------------------------ the basics

    @Test
    fun `splits artist and title on a spaced hyphen`() {
        val tags = FilenameTagger.parse("Fleetwood Mac - Dreams.mp3")
        assertEquals("Fleetwood Mac", tags.artist)
        assertEquals("Dreams", tags.title)
        assertNull(tags.trackNumber)
    }

    @Test
    fun `accepts en dash and em dash separators`() {
        assertEquals("Dreams", FilenameTagger.parse("Fleetwood Mac – Dreams.mp3").title)
        assertEquals("Dreams", FilenameTagger.parse("Fleetwood Mac — Dreams.mp3").title)
    }

    @Test
    fun `a hyphen inside a name is not a separator`() {
        // The spaces are what make a hyphen a separator; without them it is a name.
        val tags = FilenameTagger.parse("Jay-Z - Song Title.mp3")
        assertEquals("Jay-Z", tags.artist)
        assertEquals("Song Title", tags.title)
    }

    @Test
    fun `underscores stand in for spaces`() {
        val tags = FilenameTagger.parse("Fleetwood_Mac_-_Dreams.mp3")
        assertEquals("Fleetwood Mac", tags.artist)
        assertEquals("Dreams", tags.title)
    }

    @Test
    fun `a title with no artist is still recovered`() {
        val tags = FilenameTagger.parse("Dreams.mp3")
        assertNull(tags.artist)
        assertEquals("Dreams", tags.title)
    }

    // ----------------------------------------------------------- track number

    @Test
    fun `reads a leading track number in its common spellings`() {
        listOf("01 - Dreams.mp3", "01. Dreams.mp3", "01 Dreams.mp3", "1) Dreams.mp3").forEach { name ->
            val tags = FilenameTagger.parse(name)
            assertEquals("track number from '$name'", 1, tags.trackNumber)
            assertEquals("title from '$name'", "Dreams", tags.title)
        }
    }

    @Test
    fun `track number survives an artist split`() {
        val tags = FilenameTagger.parse("04 - Fleetwood Mac - Dreams.mp3")
        assertEquals(4, tags.trackNumber)
        assertEquals("Fleetwood Mac", tags.artist)
        assertEquals("Dreams", tags.title)
    }

    @Test
    fun `a bare number is a title, not a track number with no track`() {
        val tags = FilenameTagger.parse("07.mp3")
        assertNull(tags.trackNumber)
        assertEquals("07", tags.title)
    }

    @Test
    fun `a year-like leading number is not mistaken for a track`() {
        // Out of the 1..999 range, so it stays part of the title.
        val tags = FilenameTagger.parse("1979 - Song.mp3")
        assertNull(tags.trackNumber)
        assertEquals("1979", tags.artist)
    }

    // ------------------------------------------------------------ the noise

    @Test
    fun `strips parentheticals that describe the upload`() {
        assertEquals("Dreams", FilenameTagger.parse("Dreams (Official Video).mp3").title)
        assertEquals("Dreams", FilenameTagger.parse("Dreams [Official Music Video].mp3").title)
        assertEquals("Dreams", FilenameTagger.parse("Dreams (Lyrics).mp3").title)
        assertEquals("Dreams", FilenameTagger.parse("Dreams (Official Audio).mp3").title)
    }

    @Test
    fun `strips trailing noise that is not bracketed`() {
        assertEquals("Dreams", FilenameTagger.parse("Dreams - Official Video.mp3").title)
        assertEquals("Dreams", FilenameTagger.parse("Dreams HD.mp3").title)
    }

    @Test
    fun `keeps parentheticals that describe the recording`() {
        // These are the whole reason the noise list is explicit rather than
        // "anything in brackets" — each names a different recording.
        assertEquals("Dreams (Live)", FilenameTagger.parse("Dreams (Live).mp3").title)
        assertEquals("Dreams (Remix)", FilenameTagger.parse("Dreams (Remix).mp3").title)
        assertEquals("Dreams (Acoustic)", FilenameTagger.parse("Dreams (Acoustic).mp3").title)
        assertEquals("Dreams (Remastered 2011)", FilenameTagger.parse("Dreams (Remastered 2011).mp3").title)
        assertEquals("Dreams (Radio Edit)", FilenameTagger.parse("Dreams (Radio Edit).mp3").title)
        assertEquals("Dreams (feat. Someone)", FilenameTagger.parse("Dreams (feat. Someone).mp3").title)
    }

    @Test
    fun `strips a trailing eleven-character id`() {
        val tags = FilenameTagger.parse("Fleetwood Mac - Dreams [dQw4w9WgXcQ].mp3")
        assertEquals("Fleetwood Mac", tags.artist)
        assertEquals("Dreams", tags.title)
    }

    @Test
    fun `a bracketed word of another length is left alone`() {
        assertEquals("Dreams [Live 1994]", FilenameTagger.parse("Dreams [Live 1994].mp3").title)
    }

    // ------------------------------------------------------------ odd shapes

    @Test
    fun `with three or more parts the ends are taken`() {
        val tags = FilenameTagger.parse("Fleetwood Mac - Rumours - 02 - Dreams.mp3")
        assertEquals("Fleetwood Mac", tags.artist)
        assertEquals("Dreams", tags.title)
    }

    @Test
    fun `an extensionless name still parses`() {
        val tags = FilenameTagger.parse("Fleetwood Mac - Dreams")
        assertEquals("Dreams", tags.title)
    }

    @Test
    fun `a dot inside the name is not treated as an extension`() {
        // Only a short trailing run counts, so "Vol. 2" keeps its text.
        assertEquals("Track Vol. 2", FilenameTagger.parse("Track Vol. 2").title)
    }

    @Test
    fun `a path is reduced to its file name`() {
        val tags = FilenameTagger.parse("/storage/emulated/0/Download/Artist - Title.mp3")
        assertEquals("Artist", tags.artist)
        assertEquals("Title", tags.title)
    }

    @Test
    fun `blank input yields nothing rather than blank tags`() {
        assertTrue(FilenameTagger.parse("").isEmpty)
        assertTrue(FilenameTagger.parse("   ").isEmpty)
        assertTrue(FilenameTagger.parse(".mp3").isEmpty)
    }

    @Test
    fun `noise alone does not produce an empty title`() {
        // "(Official Video).mp3" strips to nothing; better no title than "".
        val tags = FilenameTagger.parse("(Official Video).mp3")
        assertNull(tags.title)
    }

    // -------------------------------------------------------- worth retagging

    @Test
    fun `an unknown artist marks a track as untagged`() {
        assertTrue(FilenameTagger.looksUntagged("Dreams", "Unknown artist", "Artist - Dreams.mp3"))
        assertTrue(FilenameTagger.looksUntagged("Dreams", "<unknown>", "Artist - Dreams.mp3"))
        assertTrue(FilenameTagger.looksUntagged("Dreams", "", "Artist - Dreams.mp3"))
    }

    @Test
    fun `a title that is just the file name marks a track as untagged`() {
        assertTrue(
            FilenameTagger.looksUntagged("Artist - Dreams", "Real Artist", "Artist - Dreams.mp3")
        )
        assertTrue(
            FilenameTagger.looksUntagged("Artist - Dreams", "Real Artist", "Artist_-_Dreams.mp3")
        )
    }

    @Test
    fun `a properly tagged track is left alone`() {
        assertFalse(
            FilenameTagger.looksUntagged("Dreams", "Fleetwood Mac", "01 - Dreams.mp3")
        )
    }
}

class TitleNormalizerTest {

    @Test
    fun `match key folds case and drops punctuation`() {
        assertEquals(
            TitleNormalizer.matchKey("Don't Stop"),
            TitleNormalizer.matchKey("dont stop"),
        )
        assertEquals(
            TitleNormalizer.matchKey("Go Your Own Way!"),
            TitleNormalizer.matchKey("go your own way"),
        )
    }

    @Test
    fun `match key ignores upload noise`() {
        assertEquals(
            TitleNormalizer.matchKey("Dreams"),
            TitleNormalizer.matchKey("Dreams (Official Video)"),
        )
    }

    @Test
    fun `match key keeps recording qualifiers distinct`() {
        // The whole safety property of the duplicate finder rests on this.
        assertTrue(
            TitleNormalizer.matchKey("Dreams") != TitleNormalizer.matchKey("Dreams (Live)")
        )
        assertTrue(
            TitleNormalizer.matchKey("Dreams") != TitleNormalizer.matchKey("Dreams (Remix)")
        )
    }
}
