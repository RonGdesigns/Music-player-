package com.irondigital.spindle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A media id paired with its play count, for building ranked playlists. */
data class RankedMedia(val mediaId: String, val playCount: Int, val lastPlayedAt: Long)

@Dao
interface StatsDao {

    @Query("SELECT * FROM play_stats WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): PlayStat?

    @Query("SELECT * FROM play_stats")
    fun observeAll(): Flow<List<PlayStat>>

    /** A one-shot read for export, where a Flow would just have to be canceled. */
    @Query("SELECT * FROM play_stats")
    suspend fun getAll(): List<PlayStat>

    @Upsert
    suspend fun upsert(stat: PlayStat)

    @Insert
    suspend fun insertEvent(event: PlayEvent)

    /**
     * The Most Played list. Ties break on recency so a freshly-rediscovered
     * track outranks one with the same count from two years ago.
     */
    @Query(
        """
        SELECT mediaId, playCount, lastPlayedAt FROM play_stats
        WHERE playCount > 0
        ORDER BY playCount DESC, lastPlayedAt DESC
        LIMIT :limit
        """
    )
    fun observeMostPlayed(limit: Int): Flow<List<RankedMedia>>

    /** Most played within a window, e.g. the last 30 days. */
    @Query(
        """
        SELECT mediaId, COUNT(*) AS playCount, MAX(playedAt) AS lastPlayedAt
        FROM play_events
        WHERE playedAt >= :since
        GROUP BY mediaId
        ORDER BY playCount DESC, lastPlayedAt DESC
        LIMIT :limit
        """
    )
    fun observeMostPlayedSince(since: Long, limit: Int): Flow<List<RankedMedia>>

    @Query(
        """
        SELECT mediaId, playCount, lastPlayedAt FROM play_stats
        WHERE lastPlayedAt > 0
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<RankedMedia>>

    /**
     * Tracks in the library that have never been counted as played. Powers the
     * "Never played" shelf, which is the only honest way to find what you
     * imported and then forgot about.
     */
    @Query("SELECT mediaId FROM play_stats WHERE playCount > 0")
    fun observePlayedIds(): Flow<List<String>>

    /** Total counted plays, all time. */
    @Query("SELECT COUNT(*) FROM play_events")
    fun observeTotalPlays(): Flow<Int>

    @Query("SELECT COALESCE(SUM(msListened), 0) FROM play_stats")
    fun observeTotalListenedMs(): Flow<Long>

    @Query("SELECT COUNT(*) FROM play_stats WHERE playCount > 0")
    fun observeDistinctPlayedCount(): Flow<Int>

    /**
     * Raw timestamps rather than SQL date grouping. Bucketing by day or by hour
     * has to happen in the device's own time zone, including its DST rules, and
     * SQLite's date functions work in UTC unless coaxed — which is exactly the
     * kind of coaxing that silently puts every play in the wrong bucket for half
     * the year.
     */
    @Query("SELECT playedAt FROM play_events WHERE playedAt >= :since ORDER BY playedAt ASC")
    fun observeEventTimesSince(since: Long): Flow<List<Long>>

    @Query("DELETE FROM play_events WHERE mediaId = :mediaId")
    suspend fun deleteEventsFor(mediaId: String)

    @Query("DELETE FROM play_stats WHERE mediaId = :mediaId")
    suspend fun deleteStatFor(mediaId: String)

    @Transaction
    suspend fun resetStatsFor(mediaId: String) {
        deleteEventsFor(mediaId)
        deleteStatFor(mediaId)
    }

    @Query("DELETE FROM play_events")
    suspend fun clearAllEvents()

    @Query("DELETE FROM play_stats")
    suspend fun clearAllStats()
}

@Dao
interface FavoritesDao {

    @Query("SELECT mediaId FROM favorites ORDER BY addedAt DESC")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT * FROM favorites")
    suspend fun getAll(): List<Favorite>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mediaId = :mediaId)")
    fun observeIsFavorite(mediaId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE mediaId = :mediaId")
    suspend fun remove(mediaId: String)
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY createdAt ASC")
    suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT mediaId FROM playlist_items WHERE playlistId = :id ORDER BY position ASC")
    suspend fun getItems(id: Long): List<String>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<Playlist?>

    @Query("SELECT mediaId FROM playlist_items WHERE playlistId = :id ORDER BY position ASC")
    fun observeItems(id: Long): Flow<List<String>>

    @Query("SELECT playlistId, COUNT(*) AS count FROM playlist_items GROUP BY playlistId")
    fun observeCounts(): Flow<List<PlaylistCount>>

    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun clearItems(id: Long)

    @Insert
    suspend fun insertItems(items: List<PlaylistItem>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeItem(playlistId: Long, mediaId: String)

    @Transaction
    suspend fun append(playlistId: Long, mediaIds: List<String>, now: Long) {
        var next = maxPosition(playlistId) + 1
        insertItems(mediaIds.map { PlaylistItem(playlistId = playlistId, mediaId = it, position = next++) })
        touch(playlistId, now)
    }

    /** Wholesale reorder: simpler and safer than shuffling individual positions. */
    @Transaction
    suspend fun replaceItems(playlistId: Long, mediaIds: List<String>, now: Long) {
        clearItems(playlistId)
        insertItems(mediaIds.mapIndexed { i, id -> PlaylistItem(playlistId = playlistId, mediaId = id, position = i) })
        touch(playlistId, now)
    }
}

data class PlaylistCount(val playlistId: Long, val count: Int)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics_overrides WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): LyricsOverride?

    @Upsert
    suspend fun upsert(override: LyricsOverride)

    @Query("UPDATE lyrics_overrides SET offsetMs = :offsetMs WHERE mediaId = :mediaId")
    suspend fun updateOffset(mediaId: String, offsetMs: Long): Int

    /**
     * An offset can apply to lyrics that live in the file, where there is no
     * row yet — so this makes one carrying nothing but the correction.
     */
    @Transaction
    suspend fun setOffset(mediaId: String, offsetMs: Long) {
        if (updateOffset(mediaId, offsetMs) == 0) {
            upsert(
                LyricsOverride(
                    mediaId = mediaId,
                    content = "",
                    synced = false,
                    updatedAt = System.currentTimeMillis(),
                    offsetMs = offsetMs,
                    source = LyricsOverride.SOURCE_USER,
                )
            )
        }
    }

    @Query("DELETE FROM lyrics_overrides WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)
}

@Dao
interface GainDao {

    @Query("SELECT * FROM track_gain WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): TrackGain?

    @Upsert
    suspend fun upsert(gain: TrackGain)

    @Query("DELETE FROM track_gain")
    suspend fun clear()
}

@Dao
interface TrackEditDao {

    @Query("SELECT * FROM track_edits")
    fun observeAll(): Flow<List<TrackEdit>>

    @Query("SELECT * FROM track_edits")
    suspend fun getAll(): List<TrackEdit>

    @Query("SELECT * FROM track_edits WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): TrackEdit?

    @Upsert
    suspend fun upsert(edit: TrackEdit)

    @Query("DELETE FROM track_edits WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)
}
