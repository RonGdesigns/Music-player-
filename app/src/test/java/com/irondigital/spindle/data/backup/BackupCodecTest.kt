package com.irondigital.spindle.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupKeyTest {

    @Test
    fun `the same recording produces the same key`() {
        assertEquals(
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000),
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000),
        )
    }

    @Test
    fun `a few milliseconds of drift does not break the key`() {
        // Two scans of one file can disagree slightly; a key that disagrees is a
        // key that silently restores nothing.
        assertEquals(
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000),
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_400),
        )
    }

    @Test
    fun `case and punctuation do not affect the key`() {
        assertEquals(
            BackupCodec.keyFor("Don't Stop", "Fleetwood Mac", 190_000),
            BackupCodec.keyFor("dont stop", "FLEETWOOD MAC", 190_000),
        )
    }

    @Test
    fun `upload noise does not affect the key`() {
        assertEquals(
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000),
            BackupCodec.keyFor("Dreams (Official Video)", "Fleetwood Mac", 250_000),
        )
    }

    @Test
    fun `different recordings get different keys`() {
        val studio = BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000)
        assertNotEquals(studio, BackupCodec.keyFor("Dreams (Live)", "Fleetwood Mac", 250_000))
        assertNotEquals(studio, BackupCodec.keyFor("Dreams", "Someone Else", 250_000))
        assertNotEquals(studio, BackupCodec.keyFor("Dreams", "Fleetwood Mac", 400_000))
    }

    @Test
    fun `a second of difference is enough to separate two keys`() {
        assertNotEquals(
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 250_000),
            BackupCodec.keyFor("Dreams", "Fleetwood Mac", 251_600),
        )
    }
}

class BackupCodecTest {

    private fun track(
        title: String = "Dreams",
        artist: String = "Fleetwood Mac",
        durationMs: Long = 250_000,
        playCount: Int = 12,
        favorite: Boolean = true,
        editedArtist: String? = null,
    ) = BackupTrack(
        key = BackupCodec.keyFor(title, artist, durationMs),
        title = title,
        artist = artist,
        durationMs = durationMs,
        playCount = playCount,
        skipCount = 2,
        lastPlayedAt = 1_700_000_000_000,
        firstPlayedAt = 1_600_000_000_000,
        msListened = 3_000_000,
        favorite = favorite,
        editedArtist = editedArtist,
    )

    private fun backup(
        tracks: List<BackupTrack> = listOf(track()),
        playlists: List<BackupPlaylist> = emptyList(),
    ) = Backup(exportedAt = 1_700_000_000_000, tracks = tracks, playlists = playlists)

    @Test
    fun `a backup survives a round trip intact`() {
        val original = backup(
            tracks = listOf(track(), track(title = "Go Your Own Way", playCount = 3)),
            playlists = listOf(
                BackupPlaylist(
                    name = "Driving",
                    createdAt = 1_600_000_000_000,
                    itemKeys = listOf(track().key, track(title = "Go Your Own Way").key),
                )
            ),
        )

        val restored = BackupCodec.decode(BackupCodec.encode(original))!!

        assertEquals(original.version, restored.version)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.tracks, restored.tracks)
        assertEquals(original.playlists, restored.playlists)
    }

    @Test
    fun `play counts and listening time come back exactly`() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup()))!!
        val track = restored.tracks.single()

        assertEquals(12, track.playCount)
        assertEquals(2, track.skipCount)
        assertEquals(3_000_000L, track.msListened)
        assertEquals(1_700_000_000_000L, track.lastPlayedAt)
        assertEquals(1_600_000_000_000L, track.firstPlayedAt)
        assertTrue(track.favorite)
    }

    @Test
    fun `metadata corrections come back`() {
        val original = backup(tracks = listOf(track(editedArtist = "Corrected Artist")))
        val restored = BackupCodec.decode(BackupCodec.encode(original))!!

        assertEquals("Corrected Artist", restored.tracks.single().editedArtist)
        assertTrue(restored.tracks.single().hasEdit)
    }

    @Test
    fun `playlist order is preserved`() {
        val keys = listOf("a", "b", "c", "d")
        val original = backup(
            playlists = listOf(BackupPlaylist("Ordered", 0, keys))
        )
        val restored = BackupCodec.decode(BackupCodec.encode(original))!!
        assertEquals(keys, restored.playlists.single().itemKeys)
    }

    @Test
    fun `an empty backup round trips`() {
        val restored = BackupCodec.decode(BackupCodec.encode(backup(tracks = emptyList())))!!
        assertTrue(restored.tracks.isEmpty())
        assertTrue(restored.playlists.isEmpty())
    }

    // -------------------------------------------------------- refusing junk

    @Test
    fun `nonsense is refused rather than half read`() {
        assertNull(BackupCodec.decode(""))
        assertNull(BackupCodec.decode("not json at all"))
        assertNull(BackupCodec.decode("[]"))
        assertNull(BackupCodec.decode("""{"hello":"world"}"""))
    }

    @Test
    fun `a backup from a newer version is refused`() {
        // Reading only the fields this build recognizes would quietly drop the
        // rest, which is the worst possible failure for a restore.
        val future = """{"version": 99, "exportedAt": 1, "tracks": [], "playlists": []}"""
        assertNull(BackupCodec.decode(future))
    }

    @Test
    fun `a track with no key is skipped rather than failing the whole file`() {
        val partial = """
            {"version":1,"exportedAt":1,
             "tracks":[{"title":"No Key"},{"key":"k","title":"Fine","artist":"A","durationMs":1}],
             "playlists":[]}
        """.trimIndent()

        val restored = BackupCodec.decode(partial)!!
        assertEquals(1, restored.tracks.size)
        assertEquals("Fine", restored.tracks.single().title)
    }

    @Test
    fun `missing sections default to empty`() {
        val minimal = """{"version":1,"exportedAt":1}"""
        val restored = BackupCodec.decode(minimal)!!

        assertTrue(restored.tracks.isEmpty())
        assertTrue(restored.playlists.isEmpty())
    }

    @Test
    fun `the encoded file is readable text a person could inspect`() {
        val text = BackupCodec.encode(backup())
        assertTrue(text.contains("\"tracks\""))
        assertTrue(text.contains("Fleetwood Mac"))
        assertTrue("should be pretty printed", text.contains("\n"))
    }
}
