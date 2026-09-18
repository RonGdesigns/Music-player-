package com.irondigital.spindle.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondigital.spindle.data.db.Playlist
import com.irondigital.spindle.data.db.TrackEdit
import com.irondigital.spindle.data.media.ImportResult
import com.irondigital.spindle.data.media.YoutubeDownloadResult
import com.irondigital.spindle.data.model.AlbumGroup
import com.irondigital.spindle.data.model.ArtistGroup
import com.irondigital.spindle.data.model.FolderGroup
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.repo.LibraryRepository
import com.irondigital.spindle.data.repo.SmartPlaylist
import com.irondigital.spindle.data.settings.LibrarySort
import com.irondigital.spindle.spindle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.spindle

    val scanState: StateFlow<LibraryRepository.ScanState> = app.library.scanState

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    val settings = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.irondigital.spindle.data.settings.Settings())

    /** All tracks, ordered by the user's chosen sort. */
    val tracks: StateFlow<List<Track>> = combine(
        app.library.tracks,
        settings.map { it.librarySort },
        app.stats.statsByMediaId,
    ) { tracks, sort, stats ->
        when (sort) {
            LibrarySort.TITLE -> tracks.sortedBy { it.title.lowercase() }
            LibrarySort.ARTIST -> tracks.sortedWith(
                compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNumber })
            )
            LibrarySort.ALBUM -> tracks.sortedWith(
                compareBy({ it.album.lowercase() }, { it.discNumber }, { it.trackNumber })
            )
            LibrarySort.DATE_ADDED -> tracks.sortedByDescending { it.dateAddedSec }
            LibrarySort.DURATION -> tracks.sortedByDescending { it.durationMs }
            LibrarySort.PLAY_COUNT -> tracks.sortedByDescending { stats[it.mediaId]?.playCount ?: 0 }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumGroup>> = app.library.albums
    val artists: StateFlow<List<ArtistGroup>> = app.library.artists
    val folders: StateFlow<List<FolderGroup>> = app.library.folders

    val playlists: StateFlow<List<Playlist>> = app.collections.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistCounts: StateFlow<Map<Long, Int>> = app.collections.playlistCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val searchResults: StateFlow<List<Track>> = combine(
        _search,
        app.library.tracks,
    ) { query, _ -> app.library.search(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch { app.library.refresh() }
    }

    fun setSearch(query: String) {
        _search.value = query
    }

    fun tracksInAlbum(albumId: Long) = app.library.tracksInAlbum(albumId)
    fun tracksByArtist(artist: String) = app.library.tracksByArtist(artist)
    fun tracksInFolder(path: String) = app.library.tracksInFolder(path)

    fun albumFor(albumId: Long): AlbumGroup? = albums.value.firstOrNull { it.albumId == albumId }

    fun smartPlaylist(playlist: SmartPlaylist, limit: Int) =
        app.smartPlaylists.tracksIn(playlist, limit)

    /**
     * Cold on purpose. Calling stateIn here would launch a sharing coroutine in
     * viewModelScope on every invocation, and these are called from composition
     * — so the caller remembers the flow and collects it instead.
     */
    fun playlistTracks(playlistId: Long): Flow<List<Track>> =
        combine(app.collections.playlistItems(playlistId), app.library.tracks) { ids, _ ->
            app.library.tracksFor(ids)
        }

    // ------------------------------------------------------------- import

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _youtubeImportState = MutableStateFlow<YoutubeImportState>(YoutubeImportState.Idle)
    val youtubeImportState: StateFlow<YoutubeImportState> = _youtubeImportState.asStateFlow()

    val importSupported: Boolean get() = app.importer.isSupported

    init {
        // A share arrives on the activity and is parked on the application, so
        // it is picked up here as soon as anything is listening.
        app.pendingImports
            .onEach { pending ->
                if (pending.isNotEmpty() && _importState.value !is ImportState.Running) {
                    importFiles(app.consumePendingImports())
                }
            }
            .launchIn(viewModelScope)
    }

    fun importFiles(sources: List<Uri>) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            _importState.value = ImportState.Running(sources.size)
            val results = app.importer.import(sources)
            // The library is scanned from MediaStore, so the new rows only
            // become tracks once it has been re-read.
            app.library.refresh()
            _importState.value = ImportState.Done(results)
        }
    }

    fun dismissImport() {
        _importState.value = ImportState.Idle
    }

    fun downloadYoutubeAudio(url: String) {
        if (url.isBlank() || _youtubeImportState.value !is YoutubeImportState.Idle) return

        viewModelScope.launch {
            _youtubeImportState.value = YoutubeImportState.Running(percent = 0f, etaSeconds = 0L)

            when (
                val result = app.youtubeDownloader.download(url) { progress ->
                    _youtubeImportState.value = YoutubeImportState.Running(
                        percent = progress.percent,
                        etaSeconds = progress.etaSeconds,
                    )
                }
            ) {
                is YoutubeDownloadResult.Success -> {
                    _youtubeImportState.value = YoutubeImportState.Finalizing
                    val imported = try {
                        app.importer.importLocalFiles(result.download.files)
                    } finally {
                        app.youtubeDownloader.cleanUp(result.download)
                    }

                    app.library.refresh()
                    _youtubeImportState.value = YoutubeImportState.Idle
                    _importState.value = ImportState.Done(imported)
                }

                is YoutubeDownloadResult.Failed -> {
                    _youtubeImportState.value = YoutubeImportState.Failed(result.reason)
                }
            }
        }
    }

    fun dismissYoutubeImportError() {
        if (_youtubeImportState.value is YoutubeImportState.Failed) {
            _youtubeImportState.value = YoutubeImportState.Idle
        }
    }

    suspend fun editFor(mediaId: String) = app.library.editFor(mediaId)

    fun saveEdit(edit: TrackEdit) {
        viewModelScope.launch { app.library.saveEdit(edit) }
    }

    fun clearEdit(mediaId: String) {
        viewModelScope.launch { app.library.clearEdit(mediaId) }
    }

    fun createPlaylist(name: String, seed: List<String> = emptyList()) {
        viewModelScope.launch { app.collections.createPlaylist(name, seed) }
    }

    fun addToPlaylist(playlistId: Long, mediaIds: List<String>) {
        viewModelScope.launch { app.collections.addTo(playlistId, mediaIds) }
    }

    fun removeFromPlaylist(playlistId: Long, mediaId: String) {
        viewModelScope.launch { app.collections.removeFrom(playlistId, mediaId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch { app.collections.rename(playlistId, name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { app.collections.delete(playlistId) }
    }
}

/** Where an import has got to, so the UI can show progress and then a summary. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val fileCount: Int) : ImportState
    data class Done(val results: List<ImportResult>) : ImportState {
        val added: List<ImportResult.Added> get() = results.filterIsInstance<ImportResult.Added>()
        val failed: List<ImportResult.Failed> get() = results.filterIsInstance<ImportResult.Failed>()
    }
}

/** Progress for the paste-a-YouTube-link converter. */
sealed interface YoutubeImportState {
    data object Idle : YoutubeImportState
    data class Running(
        val percent: Float,
        val etaSeconds: Long,
    ) : YoutubeImportState
    data object Finalizing : YoutubeImportState
    data class Failed(val reason: String) : YoutubeImportState
}
