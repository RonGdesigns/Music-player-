package com.irondigital.spindle.data.repo

import com.irondigital.spindle.data.db.Favorite
import com.irondigital.spindle.data.db.FavoritesDao
import com.irondigital.spindle.data.db.Playlist
import com.irondigital.spindle.data.db.PlaylistDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Favourites and hand-built playlists. */
class CollectionsRepository(
    private val favoritesDao: FavoritesDao,
    private val playlistDao: PlaylistDao,
) {

    val favoriteIds: Flow<List<String>> = favoritesDao.observeIds()
    val favoriteIdSet: Flow<Set<String>> = favoritesDao.observeIds().map { it.toSet() }

    fun isFavorite(mediaId: String): Flow<Boolean> = favoritesDao.observeIsFavorite(mediaId)

    suspend fun setFavorite(mediaId: String, favorite: Boolean) {
        if (favorite) favoritesDao.add(Favorite(mediaId, System.currentTimeMillis()))
        else favoritesDao.remove(mediaId)
    }

    val playlists: Flow<List<Playlist>> = playlistDao.observePlaylists()
    val playlistCounts: Flow<Map<Long, Int>> =
        playlistDao.observeCounts().map { rows -> rows.associate { it.playlistId to it.count } }

    fun playlist(id: Long): Flow<Playlist?> = playlistDao.observePlaylist(id)
    fun playlistItems(id: Long): Flow<List<String>> = playlistDao.observeItems(id)

    suspend fun createPlaylist(name: String, seed: List<String> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val id = playlistDao.insertPlaylist(Playlist(name = name, createdAt = now, updatedAt = now))
        if (seed.isNotEmpty()) playlistDao.append(id, seed, now)
        return id
    }

    suspend fun addTo(playlistId: Long, mediaIds: List<String>) =
        playlistDao.append(playlistId, mediaIds, System.currentTimeMillis())

    suspend fun removeFrom(playlistId: Long, mediaId: String) =
        playlistDao.removeItem(playlistId, mediaId)

    suspend fun reorder(playlistId: Long, mediaIds: List<String>) =
        playlistDao.replaceItems(playlistId, mediaIds, System.currentTimeMillis())

    suspend fun rename(playlistId: Long, name: String) =
        playlistDao.rename(playlistId, name, System.currentTimeMillis())

    suspend fun delete(playlistId: Long) {
        playlistDao.clearItems(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }
}
