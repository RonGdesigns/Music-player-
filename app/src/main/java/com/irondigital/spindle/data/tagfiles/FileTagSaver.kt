package com.irondigital.spindle.data.tagfiles

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.repo.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** A track whose Spindle correction is not yet in the file itself. */
data class FileCorrection(
    val track: Track,
    val changes: TagChanges,
    val diffs: List<FieldDiff>,
    /** Why this one cannot be saved, or null if it can. */
    val blockedBy: String?,
)

/** Where a batch has got to. */
data class SaveProgress(val done: Int, val total: Int)

/** How a batch went, in the terms the report shows. */
data class SaveReport(
    val saved: Int,
    val skipped: List<Pair<String, String>>,
    val rolledBack: Int,
    val needsRecovery: Int,
    /** Saved files whose modified date Android would not let Spindle put back. */
    val datesNotKept: Int = 0,
)

/**
 * The phone side of saving corrections into files.
 *
 * [TagSaveEngine] does the careful part; this supplies it with MediaStore
 * files, runs everything on the application scope so leaving a screen never
 * abandons a file half way, and tells the media scanner once files change.
 *
 * Writing to a file you did not create takes the user's consent on Android 11
 * and later, which is asked for through [writeRequest] before any batch. Before
 * Android 11 there is no such consent to ask for, so saving is not offered.
 */
class FileTagSaver(
    private val context: Context,
    private val library: LibraryRepository,
    private val nowPlaying: StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val mutex = Mutex()
    private val engine = TagSaveEngine(File(context.filesDir, "tag-saves"), ResolverFiles())

    private val _progress = MutableStateFlow<SaveProgress?>(null)
    val progress: StateFlow<SaveProgress?> = _progress.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    /** Files left in an unconfirmed state by an interrupted save. Normally zero. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _undoableCount = MutableStateFlow(0)
    val undoableCount: StateFlow<Int> = _undoableCount.asStateFlow()

    /** Saves waiting for their song to stop playing, by media id. */
    private val waiting = mutableMapOf<String, SaveItem>()

    init {
        TagFileWriter.useAndroidImaging()
        scope.launch(Dispatchers.IO) { mutex.withLock { publishCounts() } }
        scope.launch { nowPlaying.collect { saveWaiting(it) } }
    }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Every correction that differs from what its file says. */
    fun corrections(): List<FileCorrection> {
        val playing = nowPlaying.value
        return library.corrections().mapNotNull { (mediaId, edit) ->
            val file = library.scannedTrackFor(mediaId) ?: return@mapNotNull null
            val tags = tagsOf(file)
            val changes = FileCorrections.changes(tags, edit)
            if (changes.isEmpty) return@mapNotNull null
            FileCorrection(
                track = library.trackFor(mediaId) ?: file,
                changes = changes,
                diffs = FileCorrections.diffs(tags, changes),
                blockedBy = when {
                    AudioContainer.forFileName(file.displayName) == null ->
                        "Only MP3, FLAC and M4A files can be saved into."
                    mediaId == playing -> "Playing right now. Save it once the song changes."
                    else -> null
                },
            )
        }.sortedBy { it.track.title.lowercase() }
    }

    /** What a correction would write into this file, if anything. */
    fun changesFor(mediaId: String, edit: com.irondigital.spindle.data.db.TrackEdit): TagChanges? {
        val file = library.scannedTrackFor(mediaId) ?: return null
        if (AudioContainer.forFileName(file.displayName) == null) return null
        return FileCorrections.changes(tagsOf(file), edit).takeUnless { it.isEmpty }
    }

    /** Android's own dialog asking to let Spindle modify these files. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun writeRequest(mediaIds: Collection<String>): IntentSender? {
        if (mediaIds.isEmpty()) return null
        return runCatching {
            MediaStore.createWriteRequest(resolver, mediaIds.map(::uriFor)).intentSender
        }.getOrNull()
    }

    suspend fun pendingIds(): List<String> = locked { engine.pending().map { it.id } }

    suspend fun undoableIds(): List<String> = locked { engine.undoable().map { it.id } }

    /** Saves a batch. Call only after [writeRequest] was granted for these ids. */
    fun save(items: List<SaveItem>, onDone: (SaveReport) -> Unit = {}) {
        scope.launch {
            val report = run(items)
            onDone(report)
        }
    }

    /**
     * Saves one file once it is no longer playing — straight away if it is
     * not playing now. The consent must already have been given.
     */
    fun saveWhenFree(item: SaveItem) {
        synchronized(waiting) { waiting[item.id] = item }
        scope.launch { saveWaiting(nowPlaying.value) }
    }

    fun recover(onDone: (unsettled: Int) -> Unit = {}) {
        scope.launch {
            val ids = mutableListOf<String>()
            val unsettled = locked {
                for (entry in engine.pending()) {
                    ids += entry.id
                    engine.recover(entry)
                }
                engine.pending().size
            }
            afterWrites(ids)
            onDone(unsettled)
        }
    }

    /** Puts every file from the last batch back as it was. */
    fun undoLast(onDone: (undone: Int, changedSince: Int, failed: Int) -> Unit = { _, _, _ -> }) {
        scope.launch {
            var undone = 0
            var changedSince = 0
            var failed = 0
            val ids = mutableListOf<String>()
            locked {
                for (entry in engine.undoable()) {
                    when (engine.undo(entry)) {
                        TagSaveEngine.UndoOutcome.Undone -> { undone++; ids += entry.id }
                        TagSaveEngine.UndoOutcome.ChangedSince -> changedSince++
                        TagSaveEngine.UndoOutcome.NeedsRecovery -> failed++
                    }
                }
            }
            afterWrites(ids)
            onDone(undone, changedSince, failed)
        }
    }

    // ------------------------------------------------------------------------

    private suspend fun run(items: List<SaveItem>): SaveReport {
        var saved = 0
        var rolledBack = 0
        var needsRecovery = 0
        var datesNotKept = 0
        val skipped = mutableListOf<Pair<String, String>>()
        val savedIds = mutableListOf<String>()

        _progress.value = SaveProgress(0, items.size)
        locked {
            if (!engine.beginBatch()) {
                items.forEach { skipped += nameOf(it) to "An earlier save was interrupted. Settle that first." }
                return@locked
            }
            items.forEachIndexed { index, item ->
                if (item.id == nowPlaying.value) {
                    skipped += nameOf(item) to "It started playing. Save it once the song changes."
                } else {
                    when (val outcome = engine.save(item)) {
                        is SaveOutcome.Saved -> {
                            saved++
                            savedIds += item.id
                            if (!outcome.keptDate) datesNotKept++
                        }
                        is SaveOutcome.Skipped -> skipped += nameOf(item) to outcome.reason
                        is SaveOutcome.RolledBack -> rolledBack++
                        is SaveOutcome.NeedsRecovery -> needsRecovery++
                    }
                }
                _progress.value = SaveProgress(index + 1, items.size)
            }
        }
        afterWrites(savedIds)
        _progress.value = null
        return SaveReport(saved, skipped, rolledBack, needsRecovery, datesNotKept)
    }

    private suspend fun saveWaiting(playing: String?) {
        val ready = synchronized(waiting) {
            waiting.values.filter { it.id != playing }.also { list -> list.forEach { waiting.remove(it.id) } }
        }
        if (ready.isEmpty()) return
        val report = run(ready)
        withContext(Dispatchers.Main) {
            val message = when {
                report.saved == ready.size -> {
                    val saved = if (ready.size == 1) "Saved into the file" else "Saved into ${report.saved} files"
                    if (report.datesNotKept > 0) "$saved. Android would not keep the modified date." else saved
                }
                report.skipped.isNotEmpty() -> "Not saved into the file: ${report.skipped.first().second}"
                else -> "Not saved into the file. It is exactly as it was."
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Tells the media scanner which files changed, then rereads the library. */
    private suspend fun afterWrites(ids: List<String>) {
        val paths = ids.mapNotNull { library.scannedTrackFor(it)?.filePath }
        if (paths.isNotEmpty()) {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val remaining = AtomicInteger(paths.size)
                    MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                        if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
        if (ids.isNotEmpty()) library.refresh()
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block().also { publishCounts() } }
    }

    private fun publishCounts() {
        _pendingCount.value = runCatching { engine.pending().size }.getOrDefault(0)
        _undoableCount.value = runCatching { engine.undoable().size }.getOrDefault(0)
    }

    private fun nameOf(item: SaveItem): String = library.trackFor(item.id)?.title ?: item.fileName

    private fun uriFor(mediaId: String): Uri =
        library.scannedTrackFor(mediaId)?.uri
            ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.toLong())

    private fun tagsOf(track: Track) = FileTags(
        title = track.title,
        artist = track.artist,
        album = track.album,
        year = track.year,
        trackNumber = track.trackNumber,
    )

    /** Library files through MediaStore. */
    private inner class ResolverFiles : MediaFiles {
        private fun fileFor(id: String): File? = library.scannedTrackFor(id)?.filePath?.let(::File)

        override fun modifiedTime(id: String): Long? =
            fileFor(id)?.lastModified()?.takeIf { it > 0 }
                ?: library.scannedTrackFor(id)?.dateModifiedSec?.takeIf { it > 0 }?.times(1000)

        /**
         * Through the file's path, which Android allows once the user has
         * granted write access to it. Read back afterwards, because on some
         * storage a refusal comes back as success; two seconds of slack covers
         * cards whose filesystem only keeps even seconds.
         */
        override fun setModifiedTime(id: String, millis: Long): Boolean {
            val file = fileFor(id) ?: return false
            return runCatching {
                file.setLastModified(millis) && kotlin.math.abs(file.lastModified() - millis) <= 2_000
            }.getOrDefault(false)
        }

        override fun read(id: String, target: File) {
            val input = resolver.openInputStream(uriFor(id)) ?: throw IOException("no input stream")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        }

        /**
         * Overwrites in place and then trims to length, rather than truncating
         * first, so there is never a moment when the file is empty. The last
         * step forces the bytes to storage before anything reads them back.
         */
        override fun write(id: String, source: File) {
            val descriptor = resolver.openFileDescriptor(uriFor(id), "rw") ?: throw IOException("no descriptor")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                source.inputStream().use { it.copyTo(out) }
                out.flush()
                out.channel.truncate(source.length())
                out.fd.sync()
            }
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
    }
}
