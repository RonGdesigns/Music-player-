package com.irondigital.spindle.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-track listening statistics. This is the table the Most Played playlist is
 * built from, so it is the one piece of state a user would actually mourn —
 * hence it is included in cloud backup (see xml/data_extraction_rules.xml).
 */
@Entity(tableName = "play_stats")
data class PlayStat(
    @PrimaryKey val mediaId: String,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val firstPlayedAt: Long = 0L,
    /** Total wall-clock milliseconds actually heard, across all plays. */
    val msListened: Long = 0L,
)

/**
 * One row per counted play. Kept separately from [PlayStat] so "most played this
 * month" and the play-count sparkline are real queries rather than guesses.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("mediaId"), Index("playedAt")],
)
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val playedAt: Long,
    /** False when the play was counted by threshold but the track was then skipped. */
    val completed: Boolean,
)

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val mediaId: String,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    indices = [Index("playlistId"), Index(value = ["playlistId", "position"])],
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaId: String,
    val position: Int,
)

/**
 * Lyrics the user pasted or edited by hand. Sidecar .lrc files and embedded
 * tags are read live from disk and never copied in here — this table only holds
 * what the user typed, so it is always the highest-priority source.
 */
@Entity(tableName = "lyrics_overrides")
data class LyricsOverride(
    @PrimaryKey val mediaId: String,
    val content: String,
    val synced: Boolean,
    val updatedAt: Long,
)
