package com.irondigital.spindle.data.tagfiles

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Every way replacing a file can go wrong, made to go wrong on purpose.
 *
 * The library files here are plain files standing in for MediaStore entries,
 * and the stand-in can be told to fail a write half way, to write the wrong
 * bytes, or to stop answering altogether. In every case the file has to end up
 * either correctly saved or exactly as it was.
 */
class TagSaveEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: FakeFiles
    private lateinit var engine: TagSaveEngine
    private lateinit var root: File

    @Before
    fun setUp() {
        files = FakeFiles()
        root = File(tmp.newFolder(), "tag-saves")
        engine = TagSaveEngine(root, files)
    }

    private fun library(name: String, id: String = name): File {
        val source = requireNotNull(javaClass.classLoader?.getResource("tagfixtures/$name")) { name }
        val file = File(tmp.newFolder(), name)
        source.openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        // A song that has been on the phone for years.
        file.setLastModified(YEARS_AGO)
        files.paths[id] = file
        return file
    }

    private val retitle = TagChanges(title = "Dreams", artist = "Fleetwood Mac")

    private fun title(file: File) = AudioFileIO.read(file).tag.getFirst(FieldKey.TITLE).trim()

    // -------------------------------------------------------- the happy path

    @Test
    fun `a save lands, is recorded, and can be undone to the exact original bytes`() {
        for (name in listOf("tone.mp3", "tone.flac", "tone.m4a")) {
            setUp()
            val file = library(name)
            val before = file.readBytes()

            assertTrue(engine.beginBatch())
            val outcome = engine.save(SaveItem(name, name, retitle))
            assertEquals(name, SaveOutcome.Saved(name, keptDate = true), outcome)
            assertEquals(name, "Dreams", title(file))
            assertEquals("$name keeps its modified date", YEARS_AGO, file.lastModified())
            assertEquals(1, engine.undoable().size)
            assertTrue(engine.pending().isEmpty())

            assertEquals(TagSaveEngine.UndoOutcome.Undone, engine.undo(engine.undoable().single()))
            assertArrayEquals(name, before, file.readBytes())
            assertEquals("$name keeps its modified date through undo", YEARS_AGO, file.lastModified())
            assertTrue(engine.undoable().isEmpty())
            assertTrue("backup should be gone", File(root, "backups").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `a date Android will not let back is reported, and the save still stands`() {
        val file = library("tone.flac")
        files.refuseDates = true

        engine.beginBatch()
        assertEquals(
            SaveOutcome.Saved("tone.flac", keptDate = false),
            engine.save(SaveItem("tone.flac", "tone.flac", retitle)),
        )
        assertEquals("Dreams", title(file))
    }

    @Test
    fun `a rolled-back file keeps its modified date too`() {
        val file = library("tone.mp3")
        files.breakNextWrites(1) { _, target ->
            target.writeBytes(byteArrayOf(0))
            throw IOException("storage went away")
        }
        engine.beginBatch()
        assertTrue(engine.save(SaveItem("tone.mp3", "tone.mp3", retitle)) is SaveOutcome.RolledBack)
        assertEquals(YEARS_AGO, file.lastModified())
    }

    @Test
    fun `starting a new batch lets go of the last one's backups`() {
        library("tone.mp3")
        engine.beginBatch()
        engine.save(SaveItem("tone.mp3", "tone.mp3", retitle))
        assertEquals(1, File(root, "backups").listFiles()!!.size)

        assertTrue(engine.beginBatch())
        assertTrue(engine.undoable().isEmpty())
        assertEquals(0, File(root, "backups").listFiles()!!.size)
    }

    // ------------------------------------------------- failed writes roll back

    @Test
    fun `a write that dies half way is rolled back to the original`() {
        val file = library("tone.mp3")
        val before = file.readBytes()
        files.breakNextWrites(1) { source, target ->
            target.writeBytes(source.readBytes().copyOf(source.length().toInt() / 2))
            throw IOException("storage went away")
        }

        engine.beginBatch()
        val outcome = engine.save(SaveItem("tone.mp3", "tone.mp3", retitle))
        assertTrue(outcome.toString(), outcome is SaveOutcome.RolledBack)
        assertArrayEquals(before, file.readBytes())
        assertTrue(engine.pending().isEmpty())
        assertTrue(engine.undoable().isEmpty())
    }

    @Test
    fun `a write that lands the wrong bytes without complaint is caught and rolled back`() {
        // The case a plain "did it throw?" check would miss entirely.
        val file = library("tone.flac")
        val before = file.readBytes()
        files.breakNextWrites(1) { source, target ->
            val bytes = source.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            target.writeBytes(bytes)
        }

        engine.beginBatch()
        val outcome = engine.save(SaveItem("tone.flac", "tone.flac", retitle))
        assertTrue(outcome.toString(), outcome is SaveOutcome.RolledBack)
        assertArrayEquals(before, file.readBytes())
    }

    // --------------------------------------------- interruption and recovery

    @Test
    fun `when even the rollback fails, the original is kept and recovered later`() {
        val file = library("tone.m4a")
        val before = file.readBytes()
        files.breakNextWrites(2) { source, target ->
            target.writeBytes(source.readBytes().copyOf(100))
            throw IOException("storage went away")
        }

        engine.beginBatch()
        assertEquals(SaveOutcome.NeedsRecovery("tone.m4a"), engine.save(SaveItem("tone.m4a", "tone.m4a", retitle)))
        assertEquals(1, engine.pending().size)
        assertFalse("a new batch must not clear a pending backup", engine.beginBatch())

        // Storage comes back — as after a restart.
        val later = TagSaveEngine(root, files)
        assertTrue(later.recover(later.pending().single()))
        assertArrayEquals(before, file.readBytes())
        assertTrue(later.pending().isEmpty())
        assertTrue(later.beginBatch())
    }

    @Test
    fun `a save that landed before the phone lost track of it is recognized, not undone`() {
        // The new bytes were written, then reading back failed and so did the
        // rollback. The file is actually fine; recovery must see that rather
        // than blindly putting the old file back.
        val file = library("tone.mp3")
        files.failReadsAfterNextWrite = true
        files.breakNextWrites(2, skip = 1) { _, _ -> throw IOException("storage went away") }

        engine.beginBatch()
        assertEquals(SaveOutcome.NeedsRecovery("tone.mp3"), engine.save(SaveItem("tone.mp3", "tone.mp3", retitle)))
        assertEquals("Dreams", title(file))

        files.failReads = false
        assertTrue(engine.recover(engine.pending().single()))
        assertEquals("Dreams", title(file))
        assertEquals(1, engine.undoable().size)
    }

    @Test
    fun `undo will not overwrite a file something else has changed since`() {
        val file = library("tone.mp3")
        engine.beginBatch()
        engine.save(SaveItem("tone.mp3", "tone.mp3", retitle))

        file.appendBytes(byteArrayOf(1, 2, 3))
        val changed = file.readBytes()

        assertEquals(TagSaveEngine.UndoOutcome.ChangedSince, engine.undo(engine.undoable().single()))
        assertArrayEquals(changed, file.readBytes())
    }

    // ------------------------------------------------------- what is refused

    @Test
    fun `refusals leave the file untouched and nothing in the journal`() {
        val opus = library("tone.opus")
        val fake = library("not-audio.mp3")
        val mp3 = library("tone.mp3")
        val snapshots = listOf(opus, fake, mp3).associateWith { it.readBytes() }

        engine.beginBatch()
        assertTrue(engine.save(SaveItem("tone.opus", "tone.opus", retitle)) is SaveOutcome.Skipped)
        assertTrue(engine.save(SaveItem("not-audio.mp3", "not-audio.mp3", retitle)) is SaveOutcome.Skipped)

        val cramped = TagSaveEngine(root, files, freeSpace = { 1_000L })
        assertTrue(cramped.save(SaveItem("tone.mp3", "tone.mp3", retitle)) is SaveOutcome.Skipped)

        snapshots.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertTrue(engine.pending().isEmpty())
        assertTrue(engine.undoable().isEmpty())
    }

    @Test
    fun `a file is saved at most once per batch, so its backup is never replaced`() {
        val file = library("tone.mp3")
        val before = file.readBytes()
        engine.beginBatch()
        engine.save(SaveItem("tone.mp3", "tone.mp3", retitle))
        assertTrue(engine.save(SaveItem("tone.mp3", "tone.mp3", TagChanges(title = "Again"))) is SaveOutcome.Skipped)

        engine.undo(engine.undoable().single())
        assertArrayEquals(before, file.readBytes())
    }

    // ------------------------------------------------------------------------

    private companion object {
        /** 1 March 2019, on a whole second so every filesystem can hold it exactly. */
        const val YEARS_AGO = 1_551_398_400_000L
    }

    /** Library files as plain files, with failures on demand. */
    private class FakeFiles : MediaFiles {
        val paths = mutableMapOf<String, File>()
        var failReads = false
        var refuseDates = false
        var failReadsAfterNextWrite = false

        private var broken = 0
        private var skip = 0
        private var breakage: ((File, File) -> Unit)? = null

        fun breakNextWrites(count: Int, skip: Int = 0, how: (source: File, target: File) -> Unit) {
            broken = count
            this.skip = skip
            breakage = how
        }

        override fun read(id: String, target: File) {
            if (failReads) throw IOException("cannot read")
            paths.getValue(id).copyTo(target, overwrite = true)
        }

        override fun modifiedTime(id: String): Long = paths.getValue(id).lastModified()

        override fun setModifiedTime(id: String, millis: Long): Boolean =
            !refuseDates && paths.getValue(id).setLastModified(millis)

        override fun write(id: String, source: File) {
            val target = paths.getValue(id)
            if (failReadsAfterNextWrite) {
                failReadsAfterNextWrite = false
                failReads = true
            }
            if (skip > 0) {
                skip--
                source.copyTo(target, overwrite = true)
                return
            }
            if (broken > 0) {
                broken--
                breakage!!(source, target)
                return
            }
            source.copyTo(target, overwrite = true)
        }
    }
}
