package com.irondigital.spindle.data.backup

import com.irondigital.spindle.data.db.Favorite
import com.irondigital.spindle.data.db.FavoritesDao
import com.irondigital.spindle.data.db.Playlist
import com.irondigital.spindle.data.db.PlaylistDao
import com.irondigital.spindle.data.db.PlayStat
import com.irondigital.spindle.data.db.StatsDao
import com.irondigital.spindle.data.db.TrackEdit
import com.irondigital.spindle.data.db.TrackEditDao
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.repo.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a restore actually managed to put back. */
data class RestoreReport(
    val tracksInFile: Int,
    val tracksMatched: Int,
    val historyRestored: Int,
    val favoritesRestored: Int,
    val editsRestored: Int,
    val playlistsRestored: Int,
    val playlistItemsMatched: Int,
    val playlistItemsInFile: Int,
) {
    val unmatched: Int get() = tracksInFile - tracksMatched
}

/**
 * Exports and restores everything the app knows that the files themselves do not:
 * play counts, favorites, playlists and metadata corrections.
 *
 * Cloud backup already covers the ordinary case, but it does not survive a
 * factory reset with backup disabled, a move to a phone from another maker, or a
 * reinstall after clearing data — and the play counts are the whole reason Most
 * Played works. An explicit file the user holds covers all of those.
 *
 * Restore matches on the recording rather than on MediaStore ids, because those
 * ids do not survive any of the events this feature exists for.
 */
class BackupRepository(
    private val library: LibraryRepository,
    private val statsDao: StatsDao,
    private val favoritesDao: FavoritesDao,
    private val playlistDao: PlaylistDao,
    private val trackEditDao: TrackEditDao,
) {

    suspend fun export(): Backup = withContext(Dispatchers.IO) {
        val stats = statsDao.getAll().associateBy { it.mediaId }
        val favorites = favoritesDao.getAll().map { it.mediaId }.toSet()
        val edits = trackEditDao.getAll().associateBy { it.mediaId }

        // Every id mentioned anywhere, so a favorited track with no plays and an
        // edited track never played both still make it into the file.
        val mentioned = buildSet {
            addAll(stats.keys)
            addAll(favorites)
            addAll(edits.keys)
        }

        val keyByMediaId = HashMap<String, String>(mentioned.size)
        val tracks = mentioned.mapNotNull { mediaId ->
            val track = library.trackFor(mediaId) ?: return@mapNotNull null
            val key = keyFor(track)
            keyByMediaId[mediaId] = key

            val stat = stats[mediaId]
            val edit = edits[mediaId]
            BackupTrack(
                key = key,
                title = track.title,
                artist = track.artist,
                durationMs = track.durationMs,
                playCount = stat?.playCount ?: 0,
                skipCount = stat?.skipCount ?: 0,
                lastPlayedAt = stat?.lastPlayedAt ?: 0,
                firstPlayedAt = stat?.firstPlayedAt ?: 0,
                msListened = stat?.msListened ?: 0,
                favorite = mediaId in favorites,
                editedTitle = edit?.title,
                editedArtist = edit?.artist,
                editedAlbum = edit?.album,
                editedYear = edit?.year,
                editedTrackNumber = edit?.trackNumber,
            )
        }

        val playlists = playlistDao.getAllPlaylists().map { playlist ->
            val itemKeys = playlistDao.getItems(playlist.id).mapNotNull { mediaId ->
                keyByMediaId[mediaId] ?: library.trackFor(mediaId)?.let(::keyFor)
            }
            BackupPlaylist(playlist.name, playlist.createdAt, itemKeys)
        }

        Backup(exportedAt = System.currentTimeMillis(), tracks = tracks, playlists = playlists)
    }

    suspend fun restore(backup: Backup): RestoreReport = withContext(Dispatchers.IO) {
        // One pass over the library to build the reverse index. Two files can
        // share a key (the same track twice); the first wins, deterministically.
        val mediaIdByKey = HashMap<String, String>()
        for (track in library.tracks.value) {
            mediaIdByKey.putIfAbsent(keyFor(track), track.mediaId)
        }

        var matched = 0
        var history = 0
        var favorites = 0
        var edits = 0

        for (entry in backup.tracks) {
            val mediaId = mediaIdByKey[entry.key] ?: continue
            matched++

            if (entry.hasHistory) {
                // Merged rather than overwritten: restoring onto a phone that has
                // already been played should not throw away the newer listening.
                val existing = statsDao.get(mediaId)
                statsDao.upsert(
                    PlayStat(
                        mediaId = mediaId,
                        playCount = maxOf(existing?.playCount ?: 0, entry.playCount),
                        skipCount = maxOf(existing?.skipCount ?: 0, entry.skipCount),
                        lastPlayedAt = maxOf(existing?.lastPlayedAt ?: 0, entry.lastPlayedAt),
                        firstPlayedAt = listOfNotNull(
                            existing?.firstPlayedAt?.takeIf { it > 0 },
                            entry.firstPlayedAt.takeIf { it > 0 },
                        ).minOrNull() ?: 0,
                        msListened = maxOf(existing?.msListened ?: 0, entry.msListened),
                    )
                )
                history++
            }

            if (entry.favorite) {
                favoritesDao.add(Favorite(mediaId, System.currentTimeMillis()))
                favorites++
            }

            if (entry.hasEdit) {
                trackEditDao.upsert(
                    TrackEdit(
                        mediaId = mediaId,
                        title = entry.editedTitle,
                        artist = entry.editedArtist,
                        album = entry.editedAlbum,
                        year = entry.editedYear,
                        trackNumber = entry.editedTrackNumber,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                edits++
            }
        }

        val existingNames = playlistDao.getAllPlaylists().map { it.name }.toSet()
        var playlistsRestored = 0
        var itemsMatched = 0
        var itemsInFile = 0

        for (playlist in backup.playlists) {
            itemsInFile += playlist.itemKeys.size
            val mediaIds = playlist.itemKeys.mapNotNull { mediaIdByKey[it] }
            itemsMatched += mediaIds.size
            if (mediaIds.isEmpty()) continue

            // A restore should never silently merge into a list the user has been
            // building since, so a clashing name gets a suffix instead.
            val name = if (playlist.name in existingNames) "${playlist.name} (restored)" else playlist.name
            val now = System.currentTimeMillis()
            val id = playlistDao.insertPlaylist(
                Playlist(name = name, createdAt = playlist.createdAt.takeIf { it > 0 } ?: now, updatedAt = now)
            )
            playlistDao.append(id, mediaIds, now)
            playlistsRestored++
        }

        RestoreReport(
            tracksInFile = backup.tracks.size,
            tracksMatched = matched,
            historyRestored = history,
            favoritesRestored = favorites,
            editsRestored = edits,
            playlistsRestored = playlistsRestored,
            playlistItemsMatched = itemsMatched,
            playlistItemsInFile = itemsInFile,
        )
    }

    private fun keyFor(track: Track): String =
        BackupCodec.keyFor(track.title, track.artist, track.durationMs)
}
