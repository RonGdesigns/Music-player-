package com.irondigital.spindle.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.spindle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** One day's play count, with the day it belongs to. */
data class DayBucket(val dayStartMs: Long, val plays: Int)

/** One entry in a ranked list, with the number that put it there. */
data class RankedEntry(val label: String, val secondary: String, val value: Int)

data class StatsUiState(
    val totalPlays: Int = 0,
    val totalListenedMs: Long = 0,
    val distinctTracksPlayed: Int = 0,
    val librarySize: Int = 0,
    /** Consecutive days up to today with at least one counted play. */
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val dailyPlays: List<DayBucket> = emptyList(),
    /** 24 buckets, midnight-indexed, in the device's own time zone. */
    val playsByHour: List<Int> = List(24) { 0 },
    val topArtists: List<RankedEntry> = emptyList(),
    val topTracks: List<RankedEntry> = emptyList(),
) {
    val hasData: Boolean get() = totalPlays > 0
}

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.spindle

    val state: StateFlow<StatsUiState> = combine(
        app.stats.totalPlays,
        app.stats.totalListenedMs,
        app.stats.distinctPlayedCount,
        app.stats.eventTimesSince(System.currentTimeMillis() - WINDOW_MS),
        combine(app.stats.statsByMediaId, app.library.tracks) { stats, tracks -> stats to tracks },
    ) { totalPlays, totalMs, distinct, eventTimes, (statsMap, tracks) ->

        val days = bucketByDay(eventTimes, DAYS_SHOWN)
        val streaks = streaks(days)

        val byMediaId = tracks.associateBy { it.mediaId }
        val played = statsMap.values
            .filter { it.playCount > 0 }
            .mapNotNull { stat -> byMediaId[stat.mediaId]?.let { it to stat.playCount } }

        StatsUiState(
            totalPlays = totalPlays,
            totalListenedMs = totalMs,
            distinctTracksPlayed = distinct,
            librarySize = tracks.size,
            currentStreakDays = streaks.first,
            longestStreakDays = streaks.second,
            dailyPlays = days,
            playsByHour = bucketByHour(eventTimes),
            topArtists = topArtists(played),
            topTracks = topTracks(played),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun topArtists(played: List<Pair<Track, Int>>): List<RankedEntry> =
        played.groupBy { it.first.artist }
            .map { (artist, entries) ->
                RankedEntry(
                    label = artist,
                    secondary = "${entries.size} tracks",
                    value = entries.sumOf { it.second },
                )
            }
            .sortedByDescending { it.value }
            .take(TOP_N)

    private fun topTracks(played: List<Pair<Track, Int>>): List<RankedEntry> =
        played.sortedByDescending { it.second }
            .take(TOP_N)
            .map { (track, count) ->
                RankedEntry(label = track.title, secondary = track.artist, value = count)
            }

    private companion object {
        const val DAYS_SHOWN = 30
        const val TOP_N = 8
        val WINDOW_MS = TimeUnit.DAYS.toMillis(DAYS_SHOWN.toLong())
    }
}

/**
 * Buckets timestamps into the last [days] calendar days, ending today.
 *
 * Calendar rather than arithmetic on epoch millis, so that days are the user's
 * actual days: local midnight, their time zone, and 23- and 25-hour days across
 * a daylight-saving change.
 */
internal fun bucketByDay(times: List<Long>, days: Int): List<DayBucket> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // Walk back to the first day in the window, then forward, so each bucket
    // start is a real local midnight rather than a multiple of 86,400,000.
    calendar.add(Calendar.DAY_OF_YEAR, -(days - 1))

    val starts = ArrayList<Long>(days)
    repeat(days) {
        starts += calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    val windowEnd = calendar.timeInMillis

    val counts = IntArray(days)
    for (time in times) {
        if (time < starts.first() || time >= windowEnd) continue
        // Bucket boundaries ascend, so the last start not after the event wins.
        var index = starts.binarySearch { it.compareTo(time) }
        if (index < 0) index = -index - 2
        if (index in 0 until days) counts[index]++
    }
    return starts.mapIndexed { i, start -> DayBucket(start, counts[i]) }
}

/** Plays per hour of the local day, 0..23. */
internal fun bucketByHour(times: List<Long>): List<Int> {
    val counts = IntArray(24)
    val calendar = Calendar.getInstance()
    for (time in times) {
        calendar.timeInMillis = time
        counts[calendar.get(Calendar.HOUR_OF_DAY)]++
    }
    return counts.toList()
}

/**
 * Returns the current streak and the longest streak in the window.
 *
 * The current streak counts back from the most recent day, and deliberately
 * tolerates today being empty — it is unreasonable for a streak to look broken
 * at nine in the morning just because you have not played anything yet.
 */
internal fun streaks(days: List<DayBucket>): Pair<Int, Int> {
    if (days.isEmpty()) return 0 to 0

    var longest = 0
    var running = 0
    for (day in days) {
        if (day.plays > 0) {
            running++
            if (running > longest) longest = running
        } else {
            running = 0
        }
    }

    var current = 0
    var index = days.lastIndex
    if (days[index].plays == 0) index--
    while (index >= 0 && days[index].plays > 0) {
        current++
        index--
    }

    return current to longest
}
