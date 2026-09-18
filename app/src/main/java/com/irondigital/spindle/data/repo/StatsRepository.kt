package com.irondigital.spindle.data.repo

import com.irondigital.spindle.data.db.PlayEvent
import com.irondigital.spindle.data.db.PlayStat
import com.irondigital.spindle.data.db.RankedMedia
import com.irondigital.spindle.data.db.StatsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Listening statistics: the thing that makes an automatic Most Played list
 * possible, and the reason this app keeps a database at all.
 */
class StatsRepository(private val statsDao: StatsDao) {

    val statsByMediaId: Flow<Map<String, PlayStat>> =
        statsDao.observeAll().map { list -> list.associateBy { it.mediaId } }

    fun mostPlayed(limit: Int): Flow<List<RankedMedia>> = statsDao.observeMostPlayed(limit)

    fun mostPlayedSince(sinceMs: Long, limit: Int): Flow<List<RankedMedia>> =
        statsDao.observeMostPlayedSince(sinceMs, limit)

    fun recentlyPlayed(limit: Int): Flow<List<RankedMedia>> = statsDao.observeRecentlyPlayed(limit)

    fun playedIds(): Flow<Set<String>> = statsDao.observePlayedIds().map { it.toSet() }

    val totalPlays: Flow<Int> get() = statsDao.observeTotalPlays()
    val totalListenedMs: Flow<Long> get() = statsDao.observeTotalListenedMs()
    val distinctPlayedCount: Flow<Int> get() = statsDao.observeDistinctPlayedCount()

    fun eventTimesSince(since: Long): Flow<List<Long>> = statsDao.observeEventTimesSince(since)

    /**
     * Counts one play. Called by the tracker once a track has been heard past
     * the threshold — never on start, because starting a track and immediately
     * skipping it is not listening to it, and a Most Played list built from
     * starts is a list of what you skip past most.
     */
    suspend fun recordPlay(mediaId: String, msListened: Long, completed: Boolean) {
        val now = System.currentTimeMillis()
        val existing = statsDao.get(mediaId)
        statsDao.upsert(
            PlayStat(
                mediaId = mediaId,
                playCount = (existing?.playCount ?: 0) + 1,
                skipCount = existing?.skipCount ?: 0,
                lastPlayedAt = now,
                firstPlayedAt = existing?.firstPlayedAt?.takeIf { it > 0 } ?: now,
                msListened = (existing?.msListened ?: 0L) + msListened,
            )
        )
        statsDao.insertEvent(PlayEvent(mediaId = mediaId, playedAt = now, completed = completed))
    }

    /**
     * Records that a track was abandoned early. Skip counts never subtract from
     * play counts; they are their own signal, used to surface what you keep
     * skipping so you can decide whether it should still be in the library.
     */
    suspend fun recordSkip(mediaId: String, msListened: Long) {
        val existing = statsDao.get(mediaId)
        statsDao.upsert(
            PlayStat(
                mediaId = mediaId,
                playCount = existing?.playCount ?: 0,
                skipCount = (existing?.skipCount ?: 0) + 1,
                lastPlayedAt = existing?.lastPlayedAt ?: 0L,
                firstPlayedAt = existing?.firstPlayedAt ?: 0L,
                msListened = (existing?.msListened ?: 0L) + msListened,
            )
        )
    }

    suspend fun resetFor(mediaId: String) = statsDao.resetStatsFor(mediaId)

    suspend fun resetEverything() {
        statsDao.clearAllEvents()
        statsDao.clearAllStats()
    }
}
