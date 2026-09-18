package com.irondigital.spindle.data.dedupe

import com.irondigital.spindle.data.tagging.TitleNormalizer
import kotlin.math.abs

/**
 * The minimum a track has to expose to be compared against another.
 *
 * Deliberately not Track: this keeps the matching rules testable without a
 * MediaStore row behind them, and makes it obvious that nothing here depends on
 * anything but these six values.
 */
data class DedupeCandidate(
    val mediaId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val bitrateBps: Int,
    val sizeBytes: Long,
)

/**
 * One set of files that appear to be the same recording.
 *
 * [keep] is the copy worth keeping; [others] is everything else in the group.
 * Nothing is deleted here — the finder only ever proposes.
 */
data class DuplicateGroup(
    val keep: DedupeCandidate,
    val others: List<DedupeCandidate>,
) {
    val all: List<DedupeCandidate> get() = listOf(keep) + others
    val reclaimableBytes: Long get() = others.sumOf { it.sizeBytes }
}

/**
 * Finds files that are the same recording arriving twice.
 *
 * A library assembled from downloads accumulates these invisibly: the same track
 * under two filenames, in two folders, at two bitrates. They are impossible to
 * spot by scrolling and trivial to spot by comparison.
 *
 * The matching is intentionally strict, because the cost of the two kinds of
 * mistake is wildly different. A missed duplicate wastes a few megabytes; a
 * false match offers to delete a song the user still wanted. So a group needs
 * the same artist, the same title once upload noise is stripped, *and* a
 * duration within [TOLERANCE_MS] — a remix or a live cut will differ on title or
 * on length, and either is enough to keep them apart.
 */
object DuplicateFinder {

    /**
     * Two encodes of one recording differ by a few hundred milliseconds of
     * encoder padding. Two different recordings essentially never land this close.
     */
    const val TOLERANCE_MS = 2_000L

    fun find(candidates: List<DedupeCandidate>): List<DuplicateGroup> {
        if (candidates.size < 2) return emptyList()

        return candidates
            .filter { it.title.isNotBlank() }
            .groupBy { key(it) }
            .values
            .filter { it.size > 1 }
            .flatMap { clusterByDuration(it) }
            .filter { it.size > 1 }
            .map { cluster ->
                val ordered = cluster.sortedWith(BEST_FIRST)
                DuplicateGroup(keep = ordered.first(), others = ordered.drop(1))
            }
            .sortedByDescending { it.reclaimableBytes }
    }

    private fun key(candidate: DedupeCandidate): String =
        TitleNormalizer.matchKey(candidate.artist) + "\u0000" + TitleNormalizer.matchKey(candidate.title)

    /**
     * Splits a same-title group into runs of similar length.
     *
     * Chained against the cluster's first member rather than its neighbour, so a
     * long tail of tracks each two seconds longer than the last cannot drag a
     * three-minute edit and a ten-minute version into one group.
     */
    private fun clusterByDuration(group: List<DedupeCandidate>): List<List<DedupeCandidate>> {
        val sorted = group.sortedBy { it.durationMs }
        val clusters = mutableListOf<MutableList<DedupeCandidate>>()

        for (candidate in sorted) {
            val existing = clusters.lastOrNull()
            if (existing != null && abs(candidate.durationMs - existing.first().durationMs) <= TOLERANCE_MS) {
                existing += candidate
            } else {
                clusters += mutableListOf(candidate)
            }
        }
        return clusters
    }

    /**
     * Which copy to keep: the better encode, then the bigger file, then whichever
     * has a stable id — the last rule only exists so the answer never depends on
     * the order the library happened to be scanned in.
     */
    private val BEST_FIRST = compareByDescending<DedupeCandidate> { it.bitrateBps }
        .thenByDescending { it.sizeBytes }
        .thenBy { it.mediaId }
}
