package com.irondigital.spindle.data.repo

import android.content.Context
import com.irondigital.spindle.data.db.TrackEdit
import com.irondigital.spindle.data.db.TrackEditDao
import com.irondigital.spindle.data.media.MediaStoreScanner
import com.irondigital.spindle.data.model.AlbumGroup
import com.irondigital.spindle.data.model.ArtistGroup
import com.irondigital.spindle.data.model.FolderGroup
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the whole library in memory.
 *
 * A local library is tens of thousands of rows at the outside — a few megabytes
 * of Track objects. Keeping it resident means grouping, searching and sorting
 * are plain collection operations instead of a query layer, and the widget and
 * the player can both resolve a media id to a track synchronously.
 */
class LibraryRepository(
    context: Context,
    private val settingsStore: SettingsStore,
    private val trackEditDao: TrackEditDao,
    scope: CoroutineScope,
) {
    private val scanner = MediaStoreScanner(context)
    private val scanMutex = Mutex()

    /**
     * Guards the two inputs to the effective library — the raw scan and the
     * user's corrections — which arrive from independent coroutines.
     */
    private val composeMutex = Mutex()

    /** Straight from MediaStore, before the user's corrections are laid over it. */
    private var scanned: List<Track> = emptyList()
    private var edits: Map<String, TrackEdit> = emptyMap()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Media id to track, rebuilt on each scan. The hot path for every lookup. */
    private val _byId = MutableStateFlow<Map<String, Track>>(emptyMap())

    val albums: StateFlow<List<AlbumGroup>> = _tracks
        .map { tracks -> groupAlbums(tracks) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistGroup>> = _tracks
        .map { tracks -> groupArtists(tracks) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderGroup>> = _tracks
        .map { tracks -> groupFolders(tracks) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Corrections are applied over the scan rather than baked into it, so
        // editing a title does not require rescanning the device.
        trackEditDao.observeAll()
            .onEach { rows ->
                composeMutex.withLock {
                    edits = rows.filterNot { it.isEmpty }.associateBy { it.mediaId }
                    publish()
                }
            }
            .launchIn(scope)
    }

    suspend fun refresh() = scanMutex.withLock {
        _scanState.value = ScanState.SCANNING
        val settings = settingsStore.settings.first()
        val scanned = scanner.scan(
            minDurationMs = settings.minTrackDurationSec * 1_000L,
            excludedFolders = settings.excludedFolders,
            includeNonMusicAudio = settings.includeNonMusicAudio,
        )
        composeMutex.withLock {
            this.scanned = scanned
            publish()
        }
        _scanState.value = if (scanned.isEmpty()) ScanState.EMPTY else ScanState.READY
    }

    /** Lays the corrections over the scan. Callers must hold [composeMutex]. */
    private fun publish() {
        val effective =
            if (edits.isEmpty()) scanned
            else scanned.map { track -> edits[track.mediaId]?.applyTo(track) ?: track }
        _tracks.value = effective
        _byId.value = effective.associateBy { it.mediaId }
    }

    fun trackFor(mediaId: String): Track? = _byId.value[mediaId]

    suspend fun editFor(mediaId: String): TrackEdit? = trackEditDao.get(mediaId)

    suspend fun saveEdit(edit: TrackEdit) {
        // An edit that corrects nothing is a deletion, not a row of nulls.
        if (edit.isEmpty) trackEditDao.delete(edit.mediaId) else trackEditDao.upsert(edit)
    }

    suspend fun clearEdit(mediaId: String) = trackEditDao.delete(mediaId)

    fun tracksFor(mediaIds: List<String>): List<Track> {
        val index = _byId.value
        return mediaIds.mapNotNull { index[it] }
    }

    fun tracksInAlbum(albumId: Long): List<Track> = _tracks.value
        .filter { it.albumId == albumId }
        .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))

    fun tracksByArtist(artist: String): List<Track> = _tracks.value
        // Matches either credit, so opening an artist from an album still finds
        // the tracks where they are only the track artist, and the other way round.
        .filter {
            it.effectiveAlbumArtist.equals(artist, ignoreCase = true) ||
                it.artist.equals(artist, ignoreCase = true)
        }
        .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }))

    fun tracksInFolder(path: String): List<Track> = _tracks.value
        .filter { it.folderPath == path }
        .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))

    /**
     * Substring search over title, artist and album. Deliberately not fuzzy:
     * on a library you assembled yourself, you usually know the exact word.
     */
    fun search(query: String): List<Track> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        return _tracks.value.filter {
            it.title.contains(needle, true) ||
                it.artist.contains(needle, true) ||
                it.album.contains(needle, true)
        }
    }

    private fun groupAlbums(tracks: List<Track>): List<AlbumGroup> =
        tracks.groupBy { it.albumId }
            .map { (albumId, group) ->
                val first = group.first()
                AlbumGroup(
                    albumId = albumId,
                    name = first.album,
                    // The album artist settles this when the file carries one,
                    // which is the whole reason it exists: an album with a guest
                    // feature on two tracks is not a compilation. Only when
                    // nothing agrees does it fall back to Various artists.
                    artist = group.mapNotNull { it.albumArtist.takeIf { name -> name.isNotBlank() } }
                        .distinct().singleOrNull()
                        ?: group.map { it.artist }.distinct().singleOrNull()
                        ?: "Various artists",
                    artUri = first.albumArtUri,
                    trackCount = group.size,
                    totalDurationMs = group.sumOf { it.durationMs },
                    year = group.maxOf { it.year },
                )
            }
            .sortedBy { it.name.lowercase() }

    /**
     * Grouped by album artist, falling back to the track artist.
     *
     * Grouping on the track artist alone turns one compilation into forty
     * entries in the Artists list, which is how a library assembled from
     * downloads becomes unbrowsable.
     */
    private fun groupArtists(tracks: List<Track>): List<ArtistGroup> =
        tracks.groupBy { it.effectiveAlbumArtist }
            .map { (artist, group) ->
                ArtistGroup(
                    name = artist,
                    trackCount = group.size,
                    albumCount = group.map { it.albumId }.distinct().size,
                    totalDurationMs = group.sumOf { it.durationMs },
                )
            }
            .sortedBy { it.name.lowercase() }

    private fun groupFolders(tracks: List<Track>): List<FolderGroup> =
        tracks.mapNotNull { track -> track.folderPath?.let { it to track } }
            .groupBy({ it.first }, { it.second })
            .map { (path, group) ->
                FolderGroup(
                    path = path,
                    name = path.substringAfterLast('/'),
                    trackCount = group.size,
                    totalDurationMs = group.sumOf { it.durationMs },
                )
            }
            .sortedBy { it.name.lowercase() }

    enum class ScanState { IDLE, SCANNING, READY, EMPTY }
}

/** Applies a correction, leaving untouched fields as the file reported them. */
private fun TrackEdit.applyTo(track: Track): Track = track.copy(
    title = title?.takeIf { it.isNotBlank() } ?: track.title,
    artist = artist?.takeIf { it.isNotBlank() } ?: track.artist,
    album = album?.takeIf { it.isNotBlank() } ?: track.album,
    year = year ?: track.year,
    trackNumber = trackNumber ?: track.trackNumber,
)
