package com.irondigital.spindle.data.tagfiles

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** What to write. A null field is left exactly as the file has it. */
data class TagChanges(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && year == null && trackNumber == null
}

/** How writing one file into its prepared copy went. */
sealed interface PrepareResult {
    /** [work] holds the edited file, and every check passed. */
    data class Ready(val work: File) : PrepareResult

    /** Not attempted: the file is of a kind this will not touch. */
    data class Refused(val reason: String) : PrepareResult

    /** Attempted on the copy and rejected. The original was never involved. */
    data class Rejected(val reason: String) : PrepareResult
}

/**
 * Writes corrected tags into a private copy of an audio file and proves the
 * copy is sound.
 *
 * The original is never opened for writing here. [prepare] takes a copy of the
 * original's bytes, writes the tags into a second copy, and then checks that
 * second copy three ways before calling it ready:
 *
 * 1. **The audio is byte-for-byte what it was.** A fingerprint of the sound
 *    alone — every tag region excluded — is taken before and after, and must
 *    match. See [AudioPayload].
 * 2. **The new tags read back.** The copy is opened fresh and each field that
 *    was written has to come out as it went in.
 * 3. **Nothing else moved.** Every field that was not asked for — genre,
 *    composer, lyrics, disc number, track total, album artist — and the
 *    embedded cover art must be exactly as they were.
 *
 * Any failure rejects the copy, and the caller writes nothing back. Only when
 * all three hold is the copy offered as a replacement for the original, and
 * the caller keeps a backup of the original even then.
 */
object TagFileWriter {

    init {
        // The library logs every frame it reads at INFO. Useful to its authors,
        // noise to everyone else.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Call once on Android so cover art is handled without desktop classes. */
    fun useAndroidImaging() {
        TagOptionSingleton.getInstance().isAndroid = true
    }

    /**
     * Writes [changes] into [work], a fresh copy of [original].
     *
     * [original] must already be a private copy; it is only ever read. [work]
     * is overwritten. Both must keep the file's real extension, because the
     * library chooses its reader by it.
     */
    fun prepare(original: File, work: File, changes: TagChanges): PrepareResult {
        val container = AudioContainer.forFileName(original.name)
            ?: return PrepareResult.Refused(
                "Only MP3, FLAC and M4A files can have their tags saved — this is " +
                    "a .${original.extension.ifBlank { "?" }} file."
            )
        if (changes.isEmpty) return PrepareResult.Refused("There is nothing to change.")

        val audioBefore = AudioPayload.digest(original, container)
            ?: return PrepareResult.Refused(
                "Spindle could not find the audio inside this file with enough " +
                    "certainty to prove it would come through untouched, so it " +
                    "will not write to it."
            )

        val before = runCatching { snapshot(AudioFileIO.read(original)) }.getOrElse {
            return PrepareResult.Refused("The file's existing tags could not be read.")
        }

        original.copyTo(work, overwrite = true)

        runCatching {
            val file = AudioFileIO.read(work)
            apply(file, changes, before)
            file.commit()
        }.onFailure {
            return PrepareResult.Rejected("Writing the tags failed: ${it.message ?: it.javaClass.simpleName}")
        }

        // --- 1. the sound ------------------------------------------------
        val audioAfter = AudioPayload.digest(work, container)
        if (audioAfter == null || !audioAfter.contentEquals(audioBefore)) {
            return PrepareResult.Rejected(
                "Writing the tags would have changed the audio itself, so the " +
                    "change was thrown away."
            )
        }

        // --- 2 & 3. what reads back ----------------------------------------
        val after = runCatching { snapshot(AudioFileIO.read(work)) }.getOrElse {
            return PrepareResult.Rejected("The edited copy could not be read back.")
        }

        val mismatch = verify(before, after, changes)
        if (mismatch != null) return PrepareResult.Rejected(mismatch)

        return PrepareResult.Ready(work)
    }

    // ------------------------------------------------------------------------

    /** Everything worth comparing, read once. */
    private data class Snapshot(
        val fields: Map<FieldKey, String>,
        val artwork: List<ByteArray>,
        val sampleRate: Int,
        val channels: String,
        val lengthSeconds: Int,
    )

    private val WRITTEN = listOf(FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.YEAR, FieldKey.TRACK)

    /** Fields that must come through a write unchanged unless they were the target. */
    private val WATCHED = WRITTEN + listOf(
        FieldKey.ALBUM_ARTIST,
        FieldKey.TRACK_TOTAL,
        FieldKey.DISC_NO,
        FieldKey.GENRE,
        FieldKey.COMPOSER,
        FieldKey.LYRICS,
        FieldKey.COMMENT,
    )

    private fun snapshot(file: AudioFile): Snapshot {
        val tag = file.tag
        val fields = WATCHED.associateWith { key ->
            if (tag == null) "" else runCatching { tag.getFirst(key) }.getOrDefault("").trim()
        }
        val artwork = runCatching { tag?.artworkList.orEmpty().map { it.binaryData ?: ByteArray(0) } }
            .getOrDefault(emptyList())
        val header = file.audioHeader
        return Snapshot(
            fields = fields,
            artwork = artwork,
            sampleRate = header.sampleRateAsNumber,
            channels = header.channels.orEmpty(),
            lengthSeconds = header.trackLength,
        )
    }

    private fun apply(file: AudioFile, changes: TagChanges, before: Snapshot) {
        val tag = file.tagOrCreateAndSetDefault
        write(tag, changes, before)

        // An MP3 can carry an old ID3v1 tag alongside the modern one, and some
        // players and car stereos still read it. Leaving it holding the old
        // title would mean the correction only half happened.
        if (file is MP3File && file.hasID3v1Tag()) {
            file.iD3v1Tag?.let { v1 -> write(v1, changes, before, bestEffort = true) }
        }
    }

    private fun write(tag: Tag, changes: TagChanges, before: Snapshot, bestEffort: Boolean = false) {
        fun set(key: FieldKey, value: String?) {
            if (value == null) return
            if (bestEffort) runCatching { tag.setField(key, value) } else tag.setField(key, value)
        }

        set(FieldKey.TITLE, changes.title)
        set(FieldKey.ARTIST, changes.artist)
        set(FieldKey.ALBUM, changes.album)
        set(FieldKey.YEAR, changes.year?.toString())

        if (changes.trackNumber != null) {
            set(FieldKey.TRACK, changes.trackNumber.toString())
            // Setting a track number rebuilds the field that also holds the
            // track *total* in several formats, which would silently drop
            // "of 12". Put back what was there.
            // If a format cannot hold it, the unchanged-fields check below
            // catches the loss and rejects the copy.
            val total = before.fields[FieldKey.TRACK_TOTAL].orEmpty()
            if (total.isNotBlank()) runCatching { tag.setField(FieldKey.TRACK_TOTAL, total) }
        }
    }

    /** Returns a description of the first thing that is wrong, or null. */
    private fun verify(before: Snapshot, after: Snapshot, changes: TagChanges): String? {
        if (before.sampleRate != after.sampleRate || before.channels != after.channels ||
            kotlin.math.abs(before.lengthSeconds - after.lengthSeconds) > 1
        ) {
            return "The edited copy no longer reports the same sound — sample rate, " +
                "channels or length changed — so it was thrown away."
        }

        val expected = mapOf(
            FieldKey.TITLE to changes.title,
            FieldKey.ARTIST to changes.artist,
            FieldKey.ALBUM to changes.album,
            FieldKey.YEAR to changes.year?.toString(),
            FieldKey.TRACK to changes.trackNumber?.toString(),
        )

        for ((key, want) in expected) {
            if (want == null) continue
            val got = after.fields[key].orEmpty()
            val ok = when (key) {
                // Stored as a full date in some formats; the year is what was set.
                FieldKey.YEAR -> got.startsWith(want)
                // "3" and "3/12" and "03" are all track three.
                FieldKey.TRACK -> got.substringBefore('/').trim().toIntOrNull() == want.toInt()
                else -> got == want.trim()
            }
            if (!ok) return "The new ${label(key)} did not read back from the edited copy."
        }

        val changed = expected.filterValues { it != null }.keys
        for (key in WATCHED) {
            if (key in changed) continue
            if (before.fields[key] != after.fields[key]) {
                return "Saving would have changed the file's ${label(key)}, which was " +
                    "not asked for, so nothing was written."
            }
        }

        if (before.artwork.size != after.artwork.size ||
            before.artwork.zip(after.artwork).any { (a, b) -> !a.contentEquals(b) }
        ) {
            return "Saving would have altered the embedded cover art, so nothing was written."
        }

        return null
    }

    private fun label(key: FieldKey): String = when (key) {
        FieldKey.TITLE -> "title"
        FieldKey.ARTIST -> "artist"
        FieldKey.ALBUM -> "album"
        FieldKey.YEAR -> "year"
        FieldKey.TRACK -> "track number"
        FieldKey.TRACK_TOTAL -> "track total"
        FieldKey.ALBUM_ARTIST -> "album artist"
        FieldKey.DISC_NO -> "disc number"
        FieldKey.GENRE -> "genre"
        FieldKey.COMPOSER -> "composer"
        FieldKey.LYRICS -> "lyrics"
        FieldKey.COMMENT -> "comment"
        else -> key.name.lowercase()
    }
}
