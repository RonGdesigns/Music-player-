package com.irondigital.spindle.data.tagging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagProposalsTest {

    private fun candidate(
        title: String,
        artist: String = "Unknown artist",
        fileName: String,
    ) = TagCandidate("1", title, artist, fileName)

    @Test
    fun `a downloaded file gives up both its artist and its title`() {
        val proposal = TagProposals.proposeFor(
            candidate(
                title = "Fleetwood Mac - Dreams",
                fileName = "Fleetwood Mac - Dreams.mp3",
            )
        )!!

        assertEquals("Fleetwood Mac", proposal.artist)
        assertEquals("Dreams", proposal.title)
    }

    @Test
    fun `upload noise is dropped on the way through`() {
        val proposal = TagProposals.proposeFor(
            candidate(
                title = "Fleetwood Mac - Dreams (Official Video)",
                fileName = "Fleetwood Mac - Dreams (Official Video).mp3",
            )
        )!!

        assertEquals("Dreams", proposal.title)
    }

    @Test
    fun `a track that names its own recording keeps that name`() {
        // "(Live)" is not noise: it is the difference between two recordings,
        // and a tagger that eats it makes the library less true, not more.
        val proposal = TagProposals.proposeFor(
            candidate(
                title = "Fleetwood Mac - Dreams (Live)",
                fileName = "Fleetwood Mac - Dreams (Live).mp3",
            )
        )!!

        assertEquals("Dreams (Live)", proposal.title)
    }

    @Test
    fun `a leading track number is read and removed`() {
        val proposal = TagProposals.proposeFor(
            candidate(
                title = "03 - Fleetwood Mac - Dreams",
                fileName = "03 - Fleetwood Mac - Dreams.mp3",
            )
        )!!

        assertEquals(3, proposal.trackNumber)
        assertEquals("Dreams", proposal.title)
        assertEquals("Fleetwood Mac", proposal.artist)
    }

    @Test
    fun `underscores are read as spaces`() {
        val proposal = TagProposals.proposeFor(
            candidate(
                title = "Fleetwood_Mac_-_Dreams",
                fileName = "Fleetwood_Mac_-_Dreams.mp3",
            )
        )!!

        assertEquals("Fleetwood Mac", proposal.artist)
        assertEquals("Dreams", proposal.title)
    }

    // ------------------------------------------------- what must be left alone

    @Test
    fun `a properly tagged track is never touched`() {
        assertNull(
            TagProposals.proposeFor(
                TagCandidate("1", "Dreams", "Fleetwood Mac", "01 Whatever The File Is Called.mp3")
            )
        )
    }

    @Test
    fun `a filename that adds nothing produces no row`() {
        // Title already equals the filename and there is no artist in it, so
        // there is nothing to correct — and a row here would be pure noise.
        assertNull(TagProposals.proposeFor(candidate(title = "Dreams", fileName = "Dreams.mp3")))
    }

    @Test
    fun `a track number alone is not enough to propose a change`() {
        assertNull(TagProposals.proposeFor(candidate(title = "Dreams", fileName = "03 Dreams.mp3")))
    }

    @Test
    fun `a nameless file produces no row`() {
        assertNull(TagProposals.proposeFor(candidate(title = "", fileName = ".mp3")))
    }

    @Test
    fun `only the tracks worth fixing come back`() {
        val proposals = TagProposals.propose(
            listOf(
                TagCandidate("1", "Dreams", "Fleetwood Mac", "dreams.mp3"),
                TagCandidate("2", "A - B", "Unknown artist", "A - B.mp3"),
                TagCandidate("3", "Go Your Own Way", "Unknown artist", "Go Your Own Way.mp3"),
            )
        )

        assertEquals(1, proposals.size)
        assertEquals("2", proposals.single().mediaId)
        assertTrue(proposals.single().artist == "A")
    }
}
