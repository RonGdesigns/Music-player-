package com.irondigital.spindle.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.irondigital.spindle.SpindleApp
import com.irondigital.spindle.data.model.Track

/**
 * Small, synchronous media tree for Android Auto / other MediaBrowser clients.
 *
 * Android Auto renders its own driver-safe UI from these nodes. Keeping this
 * object synchronous is intentional: legacy Auto clients expect browse
 * callbacks to return promptly on the media service thread.
 */
@OptIn(UnstableApi::class)
class AutoMediaLibrary(private val app: SpindleApp) {

    fun root(): MediaItem =
        browsable(ROOT_ID, "Spindle", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> root()
        mediaId == SONGS_ID -> browsable(SONGS_ID, "Songs")
        mediaId == MOST_PLAYED_ID ->
            browsable(MOST_PLAYED_ID, "Most Played", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST)
        mediaId == FAVORITES_ID ->
            browsable(FAVORITES_ID, "Favorites", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST)
        mediaId == RECENT_ID ->
            browsable(RECENT_ID, "Recently Played", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST)
        mediaId == ADDED_ID ->
            browsable(ADDED_ID, "Recently Added", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST)
        mediaId == ALBUMS_ID ->
            browsable(ALBUMS_ID, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        mediaId == ARTISTS_ID ->
            browsable(ARTISTS_ID, "Artists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        mediaId == FOLDERS_ID -> browsable(FOLDERS_ID, "Folders")
        mediaId.startsWith(ALBUM_PREFIX) -> albumItem(mediaId)
        mediaId.startsWith(ARTIST_PREFIX) -> artistItem(mediaId)
        mediaId.startsWith(FOLDER_PREFIX) -> folderItem(mediaId)
        else -> app.library.trackFor(mediaId)?.toMediaItem()
    }

    fun children(parentId: String): List<MediaItem> = when {
        // The auto-curated lists come first, because they are the reason this
        // app exists and the car is where reaching for a specific album is
        // exactly what you should not be doing.
        parentId == ROOT_ID -> listOf(
            browsable(MOST_PLAYED_ID, "Most Played", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            browsable(FAVORITES_ID, "Favorites", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            browsable(RECENT_ID, "Recently Played", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            browsable(ADDED_ID, "Recently Added", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            browsable(SONGS_ID, "Songs", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsable(ALBUMS_ID, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(ARTISTS_ID, "Artists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            browsable(FOLDERS_ID, "Folders", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )

        parentId == SONGS_ID -> app.library.tracks.value
            .sortedBy { it.title.lowercase() }
            .map { it.toMediaItem() }

        // Built from what is already resident rather than from the smart
        // playlist queries, which are flows and would have to be awaited on a
        // thread Auto expects to answer immediately.
        parentId == MOST_PLAYED_ID -> {
            val stats = app.playStats.value
            app.library.tracks.value
                .filter { (stats[it.mediaId]?.playCount ?: 0) > 0 }
                .sortedWith(
                    compareByDescending<Track> { stats[it.mediaId]?.playCount ?: 0 }
                        .thenByDescending { stats[it.mediaId]?.lastPlayedAt ?: 0L }
                )
                .take(CURATED_LIMIT)
                .map { it.toMediaItem() }
        }

        parentId == FAVORITES_ID -> {
            val favorites = app.favoriteIds.value
            app.library.tracks.value
                .filter { it.mediaId in favorites }
                .map { it.toMediaItem() }
        }

        parentId == RECENT_ID -> {
            val stats = app.playStats.value
            app.library.tracks.value
                .filter { (stats[it.mediaId]?.lastPlayedAt ?: 0L) > 0 }
                .sortedByDescending { stats[it.mediaId]?.lastPlayedAt ?: 0L }
                .take(CURATED_LIMIT)
                .map { it.toMediaItem() }
        }

        parentId == ADDED_ID -> app.library.tracks.value
            .sortedByDescending { it.dateAddedSec }
            .take(CURATED_LIMIT)
            .map { it.toMediaItem() }

        parentId == ALBUMS_ID -> app.library.tracks.value
            .groupBy { it.albumId }
            .values
            .mapNotNull { group ->
                val first = group.firstOrNull() ?: return@mapNotNull null
                MediaItem.Builder()
                    .setMediaId(ALBUM_PREFIX + first.albumId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(first.album)
                            .setArtist(
                                group.map { it.artist }.distinct().singleOrNull()
                                    ?: "Various artists"
                            )
                            .setArtworkUri(first.albumArtUri)
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
            }
            .sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }

        parentId == ARTISTS_ID -> app.library.tracks.value
            .groupBy { it.artist }
            .keys
            .sortedBy { it.lowercase() }
            .map { artist ->
                browsable(ARTIST_PREFIX + Uri.encode(artist), artist)
            }

        parentId == FOLDERS_ID -> app.library.tracks.value
            .mapNotNull { it.folderPath }
            .distinct()
            .sortedBy { it.substringAfterLast('/').lowercase() }
            .map { path ->
                browsable(
                    FOLDER_PREFIX + Uri.encode(path),
                    path.substringAfterLast('/').ifBlank { path },
                    path,
                )
            }

        parentId.startsWith(ALBUM_PREFIX) -> {
            val albumId = parentId.removePrefix(ALBUM_PREFIX).toLongOrNull()
                ?: return emptyList()
            app.library.tracksInAlbum(albumId).map { it.toMediaItem() }
        }

        parentId.startsWith(ARTIST_PREFIX) -> {
            val artist = Uri.decode(parentId.removePrefix(ARTIST_PREFIX))
            app.library.tracksByArtist(artist).map { it.toMediaItem() }
        }

        parentId.startsWith(FOLDER_PREFIX) -> {
            val path = Uri.decode(parentId.removePrefix(FOLDER_PREFIX))
            app.library.tracksInFolder(path).map { it.toMediaItem() }
        }

        else -> emptyList()
    }

    fun search(query: String): List<MediaItem> =
        app.library.search(query).map { it.toMediaItem() }

    fun resolvePlayable(requested: List<MediaItem>): List<MediaItem> =
        requested.mapNotNull { request ->
            app.library.trackFor(request.mediaId)?.toMediaItem()
                ?: request.takeIf { it.localConfiguration != null }
        }

    /**
     * Legacy Android Auto often sends only the selected media id. Give it a
     * useful queue instead of a one-song timeline so next/previous still work.
     */
    fun queueForSelection(mediaId: String): Pair<List<MediaItem>, Int>? {
        val tracks = app.library.tracks.value
        val index = tracks.indexOfFirst { it.mediaId == mediaId }
        if (index < 0) return null
        return tracks.map { it.toMediaItem() } to index
    }

    private fun albumItem(mediaId: String): MediaItem? {
        val albumId = mediaId.removePrefix(ALBUM_PREFIX).toLongOrNull() ?: return null
        val tracks = app.library.tracksInAlbum(albumId)
        val first = tracks.firstOrNull() ?: return null
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(first.album)
                    .setArtist(
                        tracks.map { it.artist }.distinct().singleOrNull()
                            ?: "Various artists"
                    )
                    .setArtworkUri(first.albumArtUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun artistItem(mediaId: String): MediaItem? {
        val artist = Uri.decode(mediaId.removePrefix(ARTIST_PREFIX))
        return artist.takeIf { it.isNotBlank() }?.let {
            browsable(mediaId, it)
        }
    }

    private fun folderItem(mediaId: String): MediaItem? {
        val path = Uri.decode(mediaId.removePrefix(FOLDER_PREFIX))
        return path.takeIf { it.isNotBlank() }?.let {
            browsable(mediaId, it.substringAfterLast('/').ifBlank { it }, it)
        }
    }

    private fun browsable(
        id: String,
        title: String,
        subtitle: String? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                // Auto builds its own UI from these nodes and uses the type to
                // decide how to render each one. Without it every row is a
                // generic entry with no artwork treatment.
                .setMediaType(mediaType)
                .build()
        )
        .build()

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri)
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    companion object {
        const val ROOT_ID = "spindle:auto:root"
        const val SONGS_ID = "spindle:auto:songs"
        const val MOST_PLAYED_ID = "spindle:auto:most-played"
        const val FAVORITES_ID = "spindle:auto:favorites"
        const val RECENT_ID = "spindle:auto:recent"
        const val ADDED_ID = "spindle:auto:added"

        /** Long enough to last a drive, short enough to scroll on a car screen. */
        private const val CURATED_LIMIT = 100
        const val ALBUMS_ID = "spindle:auto:albums"
        const val ARTISTS_ID = "spindle:auto:artists"
        const val FOLDERS_ID = "spindle:auto:folders"

        private const val ALBUM_PREFIX = "spindle:auto:album:"
        private const val ARTIST_PREFIX = "spindle:auto:artist:"
        private const val FOLDER_PREFIX = "spindle:auto:folder:"
    }
}
