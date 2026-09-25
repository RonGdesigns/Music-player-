package com.irondigital.spindle.data.tagfiles

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Runs the real tag library against real audio files — an MP3, a FLAC and an
 * M4A made with ffmpeg, each carrying junk tags, a track total, a genre, a
 * composer and embedded cover art, the way downloaded files tend to arrive.
 */
class TagFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val formats = listOf("tone.mp3", "tone.flac", "tone.m4a")

    private fun fixture(name: String): File {
        val source = requireNotNull(javaClass.classLoader?.getResource("tagfixtures/$name")) { name }
        return File(tmp.newFolder(), name).also { target ->
            source.openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun workFor(original: File) = File(tmp.newFolder(), original.name)

    private fun sha(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun field(file: File, key: FieldKey): String =
        AudioFileIO.read(file).tag.getFirst(key).trim()

    private fun ready(result: PrepareResult, format: String): File = when (result) {
        is PrepareResult.Ready -> result.work
        else -> { fail("$format: expected Ready, got $result"); error("unreachable") }
    }

    // ------------------------------------------------------ the edit lands

    @Test
    fun `corrected fields are written and read back, in every format`() {
        for (name in formats) {
            val original = fixture(name)
            val work = ready(
                TagFileWriter.prepare(original, workFor(original), TagChanges(title = "Dreams", artist = "Fleetwood Mac")),
                name,
            )
            assertEquals(name, "Dreams", field(work, FieldKey.TITLE))
            assertEquals(name, "Fleetwood Mac", field(work, FieldKey.ARTIST))
        }
    }

    @Test
    fun `album and year are written`() {
        for (name in formats) {
            val original = fixture(name)
            val work = ready(
                TagFileWriter.prepare(original, workFor(original), TagChanges(album = "Rumours", year = 1977)),
                name,
            )
            assertEquals(name, "Rumours", field(work, FieldKey.ALBUM))
            assertTrue(name, field(work, FieldKey.YEAR).startsWith("1977"))
        }
    }

    @Test
    fun `accented and non-latin text survives the round trip`() {
        for (name in formats) {
            val original = fixture(name)
            val title = "Déjà vu — 夜に駆ける"
            val work = ready(TagFileWriter.prepare(original, workFor(original), TagChanges(title = title)), name)
            assertEquals(name, title, field(work, FieldKey.TITLE))
        }
    }

    // ---------------------------------------------- nothing else is harmed

    @Test
    fun `the audio is byte for byte what it was`() {
        for (name in formats) {
            val original = fixture(name)
            val container = AudioContainer.forFileName(name)!!
            val before = AudioPayload.digest(original, container)
            assertNotNull(name, before)

            val work = ready(
                TagFileWriter.prepare(original, workFor(original), TagChanges(title = "A much longer title than the one the file started with, so the tag has to grow")),
                name,
            )
            assertArrayEquals(name, before, AudioPayload.digest(work, container))
        }
    }

    @Test
    fun `fields that were not asked for are left exactly as they were`() {
        val untouched = listOf(
            FieldKey.ALBUM, FieldKey.YEAR, FieldKey.TRACK, FieldKey.TRACK_TOTAL,
            FieldKey.GENRE, FieldKey.COMPOSER,
        )
        for (name in formats) {
            val original = fixture(name)
            val beforeTag = AudioFileIO.read(original).tag
            val work = ready(TagFileWriter.prepare(original, workFor(original), TagChanges(title = "Dreams")), name)
            val afterTag = AudioFileIO.read(work).tag
            for (key in untouched) {
                assertEquals("$name $key", beforeTag.getFirst(key), afterTag.getFirst(key))
            }
        }
    }

    @Test
    fun `embedded cover art comes through untouched`() {
        for (name in formats) {
            val original = fixture(name)
            val before = AudioFileIO.read(original).tag.artworkList.map { it.binaryData }
            assertTrue("$name fixture should carry art", before.isNotEmpty())

            val work = ready(TagFileWriter.prepare(original, workFor(original), TagChanges(artist = "Fleetwood Mac")), name)
            val after = AudioFileIO.read(work).tag.artworkList.map { it.binaryData }
            assertEquals(name, before.size, after.size)
            before.zip(after).forEach { (a, b) -> assertArrayEquals(name, a, b) }
        }
    }

    @Test
    fun `changing the track number keeps the track total`() {
        // "3 of 12" becoming "5" rather than "5 of 12" is exactly the kind of
        // quiet damage this is meant to rule out.
        for (name in formats) {
            val original = fixture(name)
            val work = ready(TagFileWriter.prepare(original, workFor(original), TagChanges(trackNumber = 5)), name)
            assertEquals(name, "5", field(work, FieldKey.TRACK).substringBefore('/'))
            assertEquals(name, "12", field(work, FieldKey.TRACK_TOTAL))
        }
    }

    @Test
    fun `the file it was given is never modified`() {
        for (name in formats) {
            val original = fixture(name)
            val before = sha(original)
            TagFileWriter.prepare(original, workFor(original), TagChanges(title = "Dreams", trackNumber = 5))
            assertArrayEquals(name, before, sha(original))
        }
    }

    @Test
    fun `an MP3's old-style tag is corrected too`() {
        // Some car stereos still read ID3v1. Leaving it holding the old title
        // would mean the correction only half happened.
        val original = fixture("tone.mp3")
        assertTrue((AudioFileIO.read(original) as MP3File).hasID3v1Tag())

        val work = ready(TagFileWriter.prepare(original, workFor(original), TagChanges(title = "Dreams")), "tone.mp3")
        val v1 = (AudioFileIO.read(work) as MP3File).iD3v1Tag
        assertEquals("Dreams", v1.getFirst(FieldKey.TITLE).trim())
    }

    // -------------------------------------------------- what is refused

    @Test
    fun `an unsupported format is refused before anything is copied`() {
        val original = fixture("tone.opus")
        val work = workFor(original)
        val result = TagFileWriter.prepare(original, work, TagChanges(title = "Dreams"))
        assertTrue(result.toString(), result is PrepareResult.Refused)
        assertFalse(work.exists())
    }

    @Test
    fun `a file that only claims to be audio is refused and never touched`() {
        val original = fixture("not-audio.mp3")
        val before = sha(original)
        val work = workFor(original)

        val result = TagFileWriter.prepare(original, work, TagChanges(title = "Dreams"))
        assertTrue(result.toString(), result is PrepareResult.Refused)
        assertFalse(work.exists())
        assertArrayEquals(before, sha(original))
    }

    @Test
    fun `nothing to change is refused`() {
        val original = fixture("tone.mp3")
        assertTrue(TagFileWriter.prepare(original, workFor(original), TagChanges()) is PrepareResult.Refused)
    }

    // ---------------------------------------- the safety check has teeth

    @Test
    fun `the audio fingerprint notices a change to the sound`() {
        // A guard that could never fail would prove nothing. Damage one byte
        // in the middle of the audio and the fingerprint must change.
        for (name in formats) {
            val file = fixture(name)
            val container = AudioContainer.forFileName(name)!!
            val before = AudioPayload.digest(file, container)!!

            val bytes = file.readBytes()
            val middle = insideTheAudio(bytes, container)
            bytes[middle] = (bytes[middle].toInt() xor 0x55).toByte()
            file.writeBytes(bytes)

            assertFalse(name, before.contentEquals(AudioPayload.digest(file, container)))
        }
    }

    /**
     * An offset that is certainly sound rather than tag. MP3 and FLAC end in
     * audio (bar a 128-byte ID3v1 tag); an M4A written by ffmpeg keeps its
     * index *after* the samples, so the end of that file is tag, not sound.
     */
    private fun insideTheAudio(bytes: ByteArray, container: AudioContainer): Int {
        if (container != AudioContainer.M4A) return bytes.size - 400
        val box = String(bytes, Charsets.ISO_8859_1).indexOf("mdat") - 4
        assertTrue("fixture should have an mdat box", box >= 0)
        val size = java.nio.ByteBuffer.wrap(bytes, box, 4).int
        return box + size / 2
    }

    @Test
    fun `the audio fingerprint ignores a change to a tag`() {
        // And the other way round: editing the tag text directly must not look
        // like an audio change, or every real save would be rejected.
        for (name in formats) {
            val file = fixture(name)
            val container = AudioContainer.forFileName(name)!!
            val before = AudioPayload.digest(file, container)!!

            val bytes = file.readBytes()
            val at = String(bytes, Charsets.ISO_8859_1).indexOf("Old Title")
            assertTrue("$name should contain the title text", at >= 0)
            bytes[at] = 'N'.code.toByte()
            file.writeBytes(bytes)

            assertArrayEquals(name, before, AudioPayload.digest(file, container))
        }
    }
}
