package com.irondigital.spindle.ui.tools

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondigital.spindle.data.backup.BackupCodec
import com.irondigital.spindle.data.backup.RestoreReport
import com.irondigital.spindle.data.db.TrackEdit
import com.irondigital.spindle.data.dedupe.DedupeCandidate
import com.irondigital.spindle.data.dedupe.DuplicateFinder
import com.irondigital.spindle.data.dedupe.DuplicateGroup
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.tagging.TagCandidate
import com.irondigital.spindle.data.tagging.TagProposal
import com.irondigital.spindle.data.tagging.TagProposals
import com.irondigital.spindle.spindle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a long-running tool has got to, for the one line of status each screen shows. */
sealed interface ToolState {
    data object Idle : ToolState
    data object Working : ToolState
    data class Done(val message: String) : ToolState
    data class Failed(val message: String) : ToolState
}

/**
 * The library maintenance tools: reading names out of filenames, finding the
 * same recording twice, correcting a batch of tracks at once, and taking the
 * listening history somewhere safe.
 *
 * Deliberately separate from LibraryViewModel. None of this is on the path
 * between opening the app and hearing music, and folding it into the view model
 * that *is* would mean every browse screen carried the cost of scanning for
 * duplicates.
 */
class ToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.spindle

    val tracks: StateFlow<List<Track>> = app.library.tracks

    /**
     * Comparing every track against every other is real work on a large
     * library, so both of these run off the main thread and only while a screen
     * is actually looking at them.
     */
    val proposals: StateFlow<List<TagProposal>> = app.library.tracks
        .map { list ->
            TagProposals.propose(
                list.map { TagCandidate(it.mediaId, it.title, it.artist, it.displayName) }
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val duplicates: StateFlow<List<DuplicateGroup>> = app.library.tracks
        .map { list ->
            DuplicateFinder.find(
                list.map {
                    DedupeCandidate(
                        mediaId = it.mediaId,
                        title = it.title,
                        artist = it.artist,
                        durationMs = it.durationMs,
                        bitrateBps = it.bitrateBps,
                        sizeBytes = it.sizeBytes,
                    )
                }
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<ToolState>(ToolState.Idle)
    val state: StateFlow<ToolState> = _state.asStateFlow()

    private val _restoreReport = MutableStateFlow<RestoreReport?>(null)
    val restoreReport: StateFlow<RestoreReport?> = _restoreReport.asStateFlow()

    fun clearState() {
        _state.value = ToolState.Idle
        _restoreReport.value = null
    }

    fun refreshLibrary() {
        viewModelScope.launch { app.library.refresh() }
    }

    // ------------------------------------------------------------ auto-tag

    fun applyProposals(selected: List<TagProposal>) {
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.value = ToolState.Working
            val now = System.currentTimeMillis()
            for (proposal in selected) {
                val existing = app.library.editFor(proposal.mediaId)
                app.library.saveEdit(
                    TrackEdit(
                        mediaId = proposal.mediaId,
                        // Fields the filename had nothing to say about keep
                        // whatever correction was already there.
                        title = proposal.title ?: existing?.title,
                        artist = proposal.artist ?: existing?.artist,
                        album = existing?.album,
                        year = existing?.year,
                        trackNumber = proposal.trackNumber ?: existing?.trackNumber,
                        updatedAt = now,
                    )
                )
            }
            _state.value = ToolState.Done(
                if (selected.size == 1) "1 track renamed" else "${selected.size} tracks renamed"
            )
        }
    }

    // ---------------------------------------------------------- batch edit

    /**
     * Sets the same artist, album or year across a selection. A blank field is
     * not an instruction to erase — it means "leave this one alone", which is
     * what makes it safe to fix only the album across forty tracks.
     */
    fun applyBatch(mediaIds: Set<String>, artist: String?, album: String?, year: Int?) {
        if (mediaIds.isEmpty()) return
        if (artist == null && album == null && year == null) return

        viewModelScope.launch {
            _state.value = ToolState.Working
            val now = System.currentTimeMillis()
            for (mediaId in mediaIds) {
                val existing = app.library.editFor(mediaId)
                app.library.saveEdit(
                    TrackEdit(
                        mediaId = mediaId,
                        title = existing?.title,
                        artist = artist ?: existing?.artist,
                        album = album ?: existing?.album,
                        year = year ?: existing?.year,
                        trackNumber = existing?.trackNumber,
                        updatedAt = now,
                    )
                )
            }
            _state.value = ToolState.Done(
                if (mediaIds.size == 1) "1 track updated" else "${mediaIds.size} tracks updated"
            )
        }
    }

    // ---------------------------------------------------------- duplicates

    fun urisFor(mediaIds: Collection<String>): List<Uri> =
        mediaIds.mapNotNull { app.library.trackFor(it)?.uri }

    /**
     * The consent dialog Android shows for deleting media the app does not own.
     * It exists from API 30; below that the system has no such dialog, so
     * [deleteDirectly] is the only route and it may simply be refused.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(mediaIds: Collection<String>): IntentSender? {
        val uris = urisFor(mediaIds)
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris)
                .intentSender
        }.getOrNull()
    }

    fun deleteDirectly(mediaIds: Collection<String>) {
        viewModelScope.launch {
            _state.value = ToolState.Working
            val resolver = getApplication<Application>().contentResolver
            val uris = urisFor(mediaIds)
            val deleted = withContext(Dispatchers.IO) {
                uris.count { uri -> runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0 }
            }
            app.library.refresh()
            _state.value = when {
                deleted == uris.size -> ToolState.Done("$deleted files deleted")
                deleted > 0 -> ToolState.Failed(
                    "$deleted of ${uris.size} deleted. Android refused the rest — " +
                        "remove those with a file manager."
                )
                else -> ToolState.Failed(
                    "Android would not let Spindle delete these. Remove them with a " +
                        "file manager instead."
                )
            }
        }
    }

    fun onDeleteConfirmed(count: Int) {
        viewModelScope.launch {
            app.library.refresh()
            _state.value = ToolState.Done(
                if (count == 1) "1 file deleted" else "$count files deleted"
            )
        }
    }

    // ------------------------------------------------------ backup, restore

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _state.value = ToolState.Working
            val backup = runCatching { app.backup.export() }.getOrNull()
            if (backup == null) {
                _state.value = ToolState.Failed("Could not read your history.")
                return@launch
            }

            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    // "wt" truncates: without it, exporting over a longer file
                    // leaves the tail of the old one behind and the result will
                    // not parse.
                    resolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(BackupCodec.encode(backup).toByteArray())
                    } ?: error("no output stream")
                    true
                }.getOrDefault(false)
            }

            _state.value = if (written) {
                ToolState.Done(
                    "${backup.tracks.size} tracks and ${backup.playlists.size} playlists saved"
                )
            } else {
                ToolState.Failed("Could not write to that file.")
            }
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            _state.value = ToolState.Working

            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (text == null) {
                _state.value = ToolState.Failed("Could not read that file.")
                return@launch
            }

            val backup = BackupCodec.decode(text)
            if (backup == null) {
                _state.value = ToolState.Failed(
                    "That is not a Spindle backup, or it was written by a newer version."
                )
                return@launch
            }

            val report = runCatching { app.backup.restore(backup) }.getOrNull()
            if (report == null) {
                _state.value = ToolState.Failed("The restore did not finish.")
                return@launch
            }

            _restoreReport.value = report
            _state.value = ToolState.Idle
        }
    }

    /** A filename nobody has to think about, and one that sorts by date. */
    fun suggestedBackupName(): String {
        val now = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US,
            "spindle-backup-%04d-%02d-%02d.json",
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
}
