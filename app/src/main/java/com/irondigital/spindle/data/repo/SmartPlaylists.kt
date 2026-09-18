package com.irondigital.spindle.data.repo

import com.irondigital.spindle.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Playlists nobody has to maintain. Each one is a live query over the library
 * plus the stats table, so they are correct the moment a track finishes rather
 * than on some rebuild schedule.
 */
enum class SmartPlaylist(val title: String, val subtitle: String) {
    MOST_PLAYED(
        "Most Played",
        "Your top tracks by play count, kept current automatically",
    ),
    MOST_PLAYED_MONTH(
        "On Repeat",
        "What you have actually been playing for the last 30 days",
    ),
    RECENTLY_PLAYED(
        "Recently Played",
        "In the order you last heard them",
    ),
    RECENTLY_ADDED(
        "Recently Added",
        "New arrivals in your library",
    ),
    FAVORITES(
        "Favourites",
        "Everything you marked",
    ),
    NEVER_PLAYED(
        "Never Played",
        "In your library, never once heard",
    ),
    FORGOTTEN(
        "Forgotten Favourites",
        "You played these a lot, then stopped",
    ),
}

class SmartPlaylistProvider(
    private val library: LibraryRepository,
    private val stats: StatsRepository,
    private val collections: CollectionsRepository,
) {

    fun tracksIn(playlist: SmartPlaylist, limit: Int): Flow<List<Track>> = when (playlist) {
        SmartPlaylist.MOST_PLAYED ->
            combine(stats.mostPlayed(limit), library.tracks) { ranked, _ ->
                library.tracksFor(ranked.map { it.mediaId })
            }

        SmartPlaylist.MOST_PLAYED_MONTH ->
            combine(
                stats.mostPlayedSince(System.currentTimeMillis() - THIRTY_DAYS, limit),
                library.tracks,
            ) { ranked, _ -> library.tracksFor(ranked.map { it.mediaId }) }

        SmartPlaylist.RECENTLY_PLAYED ->
            combine(stats.recentlyPlayed(limit), library.tracks) { ranked, _ ->
                library.tracksFor(ranked.map { it.mediaId })
            }

        SmartPlaylist.RECENTLY_ADDED ->
            library.tracks.map { tracks ->
                tracks.sortedByDescending { it.dateAddedSec }.take(limit)
            }

        SmartPlaylist.FAVORITES ->
            combine(collections.favoriteIds, library.tracks) { ids, _ ->
                library.tracksFor(ids)
            }

        SmartPlaylist.NEVER_PLAYED ->
            combine(stats.playedIds(), library.tracks) { played, tracks ->
                tracks.filter { it.mediaId !in played }.take(limit)
            }

        // High historical count, silent for three months. The one shelf that
        // reliably turns up something you forgot you loved.
        SmartPlaylist.FORGOTTEN ->
            combine(stats.statsByMediaId, library.tracks) { statsMap, _ ->
                val cutoff = System.currentTimeMillis() - NINETY_DAYS
                statsMap.values
                    .filter { it.playCount >= FORGOTTEN_MIN_PLAYS && it.lastPlayedAt in 1 until cutoff }
                    .sortedByDescending { it.playCount }
                    .take(limit)
                    .let { ranked -> library.tracksFor(ranked.map { it.mediaId }) }
            }
    }

    private companion object {
        val THIRTY_DAYS = TimeUnit.DAYS.toMillis(30)
        val NINETY_DAYS = TimeUnit.DAYS.toMillis(90)
        const val FORGOTTEN_MIN_PLAYS = 5
    }
}
