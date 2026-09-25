package com.irondigital.spindle.data.tagfiles

import com.irondigital.spindle.data.db.TrackEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCorrectionsTest {

    private val file = FileTags(
        title = "www.somemusicsite.com - Dreams",
        artist = "Unknown artist",
        album = "Rumours",
        year = 0,
        trackNumber = 3,
    )

    @Test
    fun `only fields that disagree with the file are written`() {
        val edit = TrackEdit(
            mediaId = "1",
            title = "Dreams",
            artist = "Fleetwood Mac",
            album = "Rumours",
            year = 1977,
            trackNumber = 3,
        )
        assertEquals(
            TagChanges(title = "Dreams", artist = "Fleetwood Mac", year = 1977),
            FileCorrections.changes(file, edit),
        )
    }

    @Test
    fun `a correction the file already carries writes nothing`() {
        val edit = TrackEdit(mediaId = "1", album = "Rumours", trackNumber = 3)
        assertTrue(FileCorrections.changes(file, edit).isEmpty)
    }

    @Test
    fun `blank and out-of-range values are never written`() {
        val edit = TrackEdit(mediaId = "1", title = "   ", year = 0, trackNumber = 5000)
        assertTrue(FileCorrections.changes(file, edit).isEmpty)
    }

    @Test
    fun `the diff says what the file holds now, including nothing`() {
        val changes = TagChanges(title = "Dreams", year = 1977)
        assertEquals(
            listOf(
                FieldDiff("Title", "www.somemusicsite.com - Dreams", "Dreams"),
                FieldDiff("Year", "nothing", "1977"),
            ),
            FileCorrections.diffs(file, changes),
        )
    }
}
