package com.irondigital.spindle.ui.library

import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.settings.LibrarySort
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One stop on the index rail: a label and the row it jumps to. */
data class IndexEntry(val label: String, val itemIndex: Int)

/**
 * Builds the fast-scroll index for a list.
 *
 * The index key is derived from **the same field the list is sorted by**, which is
 * the only way the rail can be honest. An A–Z rail over a list sorted by play
 * count would point at nothing — so when the sort is not alphabetical the rail
 * shows the scale that sort actually runs on: years for date added, count bands
 * for plays, minute bands for length.
 *
 * Leading articles are deliberately *not* stripped. The sort does not strip them
 * either, and an index that disagrees with the order it indexes is worse than no
 * index at all.
 */
object ListIndex {

    /** How many stops fit on a phone-height rail before it has to thin them out. */
    const val MAX_VISIBLE_LABELS = 28

    fun forTracks(
        tracks: List<Track>,
        sort: LibrarySort,
        playCounts: Map<String, Int>,
    ): List<IndexEntry> = forItems(tracks) { track ->
        trackKey(
            sort = sort,
            title = track.title.ifBlank { track.displayName },
            artist = track.artist,
            album = track.album,
            dateAddedSec = track.dateAddedSec,
            playCount = playCounts[track.mediaId] ?: 0,
            durationMs = track.durationMs,
        )
    }

    fun forLabels(labels: List<String>): List<IndexEntry> = forItems(labels) { alphaKey(it) }

    /**
     * The stop a single row belongs to, expressed over plain values rather than a
     * Track. Keeping it separable is what makes the bucketing rules testable
     * without a MediaStore row behind them.
     */
    fun trackKey(
        sort: LibrarySort,
        title: String,
        artist: String,
        album: String,
        dateAddedSec: Long,
        playCount: Int,
        durationMs: Long,
    ): String = when (sort) {
        LibrarySort.TITLE -> alphaKey(title)
        LibrarySort.ARTIST -> alphaKey(artist)
        LibrarySort.ALBUM -> alphaKey(album)
        LibrarySort.DATE_ADDED -> yearKey(dateAddedSec)
        LibrarySort.PLAY_COUNT -> playCountKey(playCount)
        LibrarySort.DURATION -> durationKey(durationMs)
    }

    /** Collapses a sorted list into one stop per run of equal keys. */
    fun <T> forItems(items: List<T>, key: (T) -> String): List<IndexEntry> {
        if (items.isEmpty()) return emptyList()
        val entries = ArrayList<IndexEntry>(32)
        var previous: String? = null
        items.forEachIndexed { index, item ->
            val label = key(item)
            if (label != previous) {
                entries += IndexEntry(label, index)
                previous = label
            }
        }
        return entries
    }

    /**
     * First character, folded to upper case. Anything that is not a letter — a
     * numeral, a bracket, a non-Latin script we cannot alphabetise meaningfully —
     * collects under a single bucket rather than littering the rail with one-off
     * glyphs.
     */
    private fun alphaKey(value: String): String {
        val first = value.trim().firstOrNull() ?: return NON_ALPHA
        return if (first.isLetter()) first.uppercaseChar().toString() else NON_ALPHA
    }

    private fun yearKey(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        val calendar = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        return calendar.get(Calendar.YEAR).toString()
    }

    /**
     * Descending bands, matching the descending sort. The boundaries are powers of
     * ten because that is how play counts actually distribute — a handful of
     * hundreds, a long tail of ones.
     */
    private fun playCountKey(count: Int): String = when {
        count >= 500 -> "500"
        count >= 200 -> "200"
        count >= 100 -> "100"
        count >= 50 -> "50"
        count >= 20 -> "20"
        count >= 10 -> "10"
        count >= 5 -> "5"
        count >= 1 -> "1"
        else -> "0"
    }

    private fun durationKey(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        return when {
            minutes >= 20 -> "20+"
            minutes >= 10 -> "10"
            minutes >= 7 -> "7"
            minutes >= 5 -> "5"
            minutes >= 4 -> "4"
            minutes >= 3 -> "3"
            minutes >= 2 -> "2"
            else -> "<2"
        }
    }

    /**
     * Thins a long index down to something that fits a phone's height, keeping the
     * first and last so the rail's ends always mean what they look like they mean.
     *
     * The rail then drags across exactly these stops. Dragging across the full set
     * while showing a thinned one would put the labels out of step with the
     * positions they sit at, which is worse than landing a few rows off.
     */
    fun sampleForDisplay(entries: List<IndexEntry>, maxLabels: Int = MAX_VISIBLE_LABELS): List<IndexEntry> {
        if (entries.size <= maxLabels) return entries
        val step = (entries.size - 1).toFloat() / (maxLabels - 1)
        return (0 until maxLabels).map { entries[(it * step).toInt().coerceIn(entries.indices)] }
    }

    /** Suffix appended to a band label so "50" reads as a threshold, not a count. */
    fun displayLabel(entry: IndexEntry, sort: LibrarySort): String = when (sort) {
        LibrarySort.PLAY_COUNT -> entry.label
        LibrarySort.DURATION -> entry.label
        LibrarySort.DATE_ADDED -> entry.label.takeLast(2)
        else -> entry.label
    }

    /** The full, spoken-out form used for the drag puck and for accessibility. */
    fun spokenLabel(entry: IndexEntry, sort: LibrarySort): String = when (sort) {
        LibrarySort.PLAY_COUNT ->
            if (entry.label == "0") "Never played" else "${entry.label}+ plays"
        LibrarySort.DURATION ->
            if (entry.label.startsWith("<")) "Under 2 minutes" else "${entry.label} minutes"
        LibrarySort.DATE_ADDED -> entry.label
        else -> entry.label.uppercase(Locale.getDefault())
    }

    const val NON_ALPHA = "#"
}
