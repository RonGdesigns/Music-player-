package com.irondigital.spindle.data.tagfiles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Reads and replaces the bytes of one library file.
 *
 * On a phone this is a MediaStore content URI; in tests it is a plain file. The
 * engine never needs anything else, which is what lets every failure path below
 * be exercised on the JVM rather than trusted.
 */
interface MediaFiles {
    /** Copies the file's current bytes into [target]. Throws if it cannot. */
    fun read(id: String, target: File)

    /** Replaces the file's bytes with [source]'s. Throws if it cannot. */
    fun write(id: String, source: File)
}

/** One file to correct. [fileName] supplies the extension the format is judged by. */
data class SaveItem(
    val id: String,
    val fileName: String,
    val changes: TagChanges,
)

sealed interface SaveOutcome {
    val id: String

    /** The file now carries the new tags, and reading it back proved it. */
    data class Saved(override val id: String) : SaveOutcome

    /** Nothing was written. The file is exactly as it was. */
    data class Skipped(override val id: String, val reason: String) : SaveOutcome

    /**
     * A write was attempted and did not verify, and the original was put back
     * and verified in turn. The file is exactly as it was.
     */
    data class RolledBack(override val id: String, val reason: String) : SaveOutcome

    /**
     * A write did not verify and putting the original back did not verify
     * either. The original is still held in the backup, and the entry stays
     * in the journal until [TagSaveEngine.recover] succeeds.
     */
    data class NeedsRecovery(override val id: String) : SaveOutcome
}

/** A file the journal knows about. */
data class JournalEntry(
    val id: String,
    val fileName: String,
    val originalSha: String,
    val newSha: String,
    val state: State,
) {
    enum class State {
        /**
         * The file was being written — a save, or an undo — when this was
         * recorded, and nobody has since confirmed what it holds.
         */
        IN_PROGRESS,

        /** Saved and verified. The backup is kept so the save can be undone. */
        SAVED,
    }
}

/**
 * Carries a verified copy back over the original, with a way home at every step.
 *
 * [TagFileWriter] proves the new copy is sound. This makes sure replacing the
 * original with it cannot lose the original, whatever happens part way:
 *
 * 1. The original is copied out, and a backup of it is taken and checked.
 * 2. The journal records the file as in progress, with the fingerprint of the
 *    bytes it held and of the bytes it is about to hold — *before* anything is
 *    written.
 * 3. The new bytes are written, then read back from the file itself and
 *    compared against what was meant to land.
 * 4. If they differ, the backup is written back and read back in turn.
 *
 * If the phone dies during step 3 or 4, the journal still says in progress, and
 * [recover] settles it next time by looking at what the file actually holds:
 * the new bytes, the old bytes, or neither — in which case the backup goes back.
 *
 * Backups of a batch are kept until the next batch starts, which is what
 * makes [undo] possible without ever reconstructing a file from its tags.
 */
class TagSaveEngine(
    private val root: File,
    private val files: MediaFiles,
    /** Bytes free where backups are kept. */
    private val freeSpace: () -> Long = { root.usableSpace },
) {
    private val workDir get() = File(root, "work")
    private val backupDir get() = File(root, "backups")
    private val journalFile get() = File(root, "journal.json")

    /** Entries whose file is in an unconfirmed state. Settle these before anything else. */
    fun pending(): List<JournalEntry> = journal().filter { it.state == JournalEntry.State.IN_PROGRESS }

    /** Files from the last batch that can be put back as they were. */
    fun undoable(): List<JournalEntry> = journal().filter { it.state == JournalEntry.State.SAVED }

    /**
     * Starts a new batch, letting go of the previous one's backups.
     *
     * Refuses while anything is pending: those backups are the only copy of an
     * original that is not currently safe, and must never be cleared by a new
     * save.
     */
    fun beginBatch(): Boolean {
        if (pending().isNotEmpty()) return false
        for (entry in undoable()) backupFor(entry.id, entry.fileName).delete()
        persist(emptyList())
        workDir.deleteRecursively()
        return true
    }

    fun save(item: SaveItem): SaveOutcome {
        val id = item.id
        if (journal().any { it.id == id }) {
            // Saved already in this batch, or pending. A second save would have
            // to replace the backup that undo and recovery depend on.
            return SaveOutcome.Skipped(id, "This file was already saved in this batch.")
        }
        val extension = item.fileName.substringAfterLast('.', "")
        if (AudioContainer.forFileName(item.fileName) == null) {
            return SaveOutcome.Skipped(
                id,
                "Only MP3, FLAC and M4A files can have their tags saved — this is a " +
                    ".${extension.ifBlank { "?" }} file.",
            )
        }

        workDir.mkdirs()
        val original = File(workDir, "original.$extension")
        val edited = File(workDir, "edited.$extension")
        try {
            runCatching { files.read(id, original) }.onFailure {
                return SaveOutcome.Skipped(id, "The file could not be read.")
            }

            // Room for the edited copy and the backup, with some to spare.
            if (freeSpace() < original.length() * 2 + SPACE_MARGIN) {
                return SaveOutcome.Skipped(id, "Not enough free space to keep a backup of this file.")
            }

            when (val prepared = TagFileWriter.prepare(original, edited, item.changes)) {
                is PrepareResult.Refused -> return SaveOutcome.Skipped(id, prepared.reason)
                is PrepareResult.Rejected -> return SaveOutcome.Skipped(id, prepared.reason)
                is PrepareResult.Ready -> Unit
            }

            val originalSha = sha(original)
            val newSha = sha(edited)
            if (originalSha == newSha) return SaveOutcome.Skipped(id, "The file already says this.")

            val backup = backupFor(id, item.fileName)
            backupDir.mkdirs()
            original.copyTo(backup, overwrite = true)
            if (sha(backup) != originalSha) {
                backup.delete()
                return SaveOutcome.Skipped(id, "A backup of the file could not be made.")
            }

            // Recorded before the first byte is written, so an interruption from
            // here on is always found and settled by recover().
            val entry = JournalEntry(id, item.fileName, originalSha, newSha, JournalEntry.State.IN_PROGRESS)
            persist(journal() + entry)

            val landed = runCatching { files.write(id, edited) }.isSuccess && holds(id, newSha)
            if (landed) {
                replace(entry.copy(state = JournalEntry.State.SAVED))
                return SaveOutcome.Saved(id)
            }

            return if (restore(entry)) {
                forget(entry)
                SaveOutcome.RolledBack(
                    id,
                    "The saved file did not read back as expected, so the original was put back.",
                )
            } else {
                SaveOutcome.NeedsRecovery(id)
            }
        } finally {
            original.delete()
            edited.delete()
        }
    }

    /**
     * Settles a file left in progress, by what it actually holds now.
     *
     * Returns true once the file is known to be whole — new or original.
     */
    fun recover(entry: JournalEntry): Boolean = when (currentSha(entry.id)) {
        entry.newSha -> { replace(entry.copy(state = JournalEntry.State.SAVED)); true }
        entry.originalSha -> { forget(entry); true }
        else -> restore(entry).also { if (it) forget(entry) }
    }

    /** Puts a saved file back exactly as it was before the save. */
    fun undo(entry: JournalEntry): UndoOutcome {
        when (currentSha(entry.id)) {
            entry.originalSha -> { forget(entry); return UndoOutcome.Undone }
            entry.newSha -> Unit
            // Something else wrote to it since — another tag editor, a sync. Put
            // back an old copy over that and their change would be lost.
            else -> return UndoOutcome.ChangedSince
        }
        replace(entry.copy(state = JournalEntry.State.IN_PROGRESS))
        return if (restore(entry)) {
            forget(entry)
            UndoOutcome.Undone
        } else {
            UndoOutcome.NeedsRecovery
        }
    }

    enum class UndoOutcome { Undone, ChangedSince, NeedsRecovery }

    // ------------------------------------------------------------------------

    /** Writes the backup over the file and proves it landed. */
    private fun restore(entry: JournalEntry): Boolean {
        val backup = backupFor(entry.id, entry.fileName)
        if (!backup.exists() || sha(backup) != entry.originalSha) return false
        return runCatching { files.write(entry.id, backup) }.isSuccess && holds(entry.id, entry.originalSha)
    }

    private fun holds(id: String, expected: String): Boolean = currentSha(id) == expected

    private fun currentSha(id: String): String? {
        workDir.mkdirs()
        val check = File(workDir, "check")
        return try {
            runCatching { files.read(id, check); sha(check) }.getOrNull()
        } finally {
            check.delete()
        }
    }

    private fun forget(entry: JournalEntry) {
        persist(journal().filterNot { it.id == entry.id })
        backupFor(entry.id, entry.fileName).delete()
    }

    private fun replace(entry: JournalEntry) {
        persist(journal().map { if (it.id == entry.id) entry else it })
    }

    private fun backupFor(id: String, fileName: String): File {
        val extension = fileName.substringAfterLast('.', "bin")
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(backupDir, "$safe.$extension")
    }

    private fun journal(): List<JournalEntry> {
        val file = journalFile
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            JournalEntry(
                id = o.getString("id"),
                fileName = o.getString("fileName"),
                originalSha = o.getString("originalSha"),
                newSha = o.getString("newSha"),
                state = JournalEntry.State.valueOf(o.getString("state")),
            )
        }
    }

    /**
     * Written to a side file and renamed into place, so the journal on disk is
     * always one whole version or the other — never half of each.
     */
    private fun persist(entries: List<JournalEntry>) {
        root.mkdirs()
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("fileName", e.fileName)
                    .put("originalSha", e.originalSha)
                    .put("newSha", e.newSha)
                    .put("state", e.state.name)
            )
        }
        val next = File(root, "journal.json.next")
        next.writeText(array.toString())
        if (!next.renameTo(journalFile)) {
            journalFile.delete()
            check(next.renameTo(journalFile)) { "Could not record the save journal." }
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
    }
}
