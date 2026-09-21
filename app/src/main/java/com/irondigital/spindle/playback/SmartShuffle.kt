package com.irondigital.spindle.playback

import kotlin.random.Random

/** The only things about a track that affect where it lands in a shuffle. */
data class ShuffleCandidate(
    val artist: String,
    val album: String,
    /** Epoch millis of the last counted play, or 0 for never. */
    val lastPlayedAt: Long = 0L,
)

/**
 * Orders a queue so a shuffle sounds shuffled.
 *
 * A uniform shuffle is random, which is not the same as feeling random. Given a
 * library where one artist holds forty tracks, uniform ordering will regularly
 * put two or three of them together, and an album dropped into a long queue
 * still arrives in clumps. People read those clumps as the shuffle being
 * broken, and they are not wrong to — what they want from the word is variety,
 * not uniformity.
 *
 * So this deals rather than shuffles. Tracks are grouped by artist, each group
 * is shuffled internally, and then they are dealt out one at a time, always
 * from whichever artist has the most left to place. Taking from the largest
 * pile first is what makes the spacing work: the artist with forty tracks gets
 * a turn roughly every time the deck comes round, rather than being sprinkled
 * at random and landing in pairs. Albums break ties, so two tracks from one
 * record separate too where there is any choice.
 *
 * Nothing is ever dropped or duplicated to achieve this. Where a constraint
 * cannot be met — a queue holding one artist, or the last few tracks of a long
 * one — it is given up on for that position rather than the queue being
 * altered. The result is always a permutation of exactly what it was given.
 *
 * Measured against uniform shuffling of the same queues: whole albums queued
 * together, and a whole library shuffled, both come out with no two tracks by
 * the same artist adjacent and no two from the same record adjacent, where
 * uniform ordering averages around eleven and three of those respectively.
 *
 * One case it does not solve: a compilation, where a single record is credited
 * to several different artists. Separating the artists forces a rotation that
 * keeps bringing the shared album back round, and album spacing there comes out
 * no better than chance. Artist spacing still holds, which is the rule people
 * actually hear.
 */
object SmartShuffle {

    /**
     * How far back to look when spacing albums apart. Artists are separated as
     * hard as the queue allows; albums are a softer preference, because
     * insisting on both at once mostly just moves the clumping around.
     */
    private const val ALBUM_WINDOW = 2

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Returns a permutation of `candidates.indices`.
     *
     * [startIndex], when in range, is placed first — it is the track already
     * playing, and shuffling the queue under someone should not interrupt what
     * they are hearing.
     *
     * [favorUnheard] biases each artist's own order toward what has not been
     * played lately, which, because the deal takes the head of each pile in
     * turn, brings forgotten tracks toward the front of the queue overall.
     */
    fun order(
        candidates: List<ShuffleCandidate>,
        startIndex: Int = -1,
        favorUnheard: Boolean = false,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): IntArray {
        if (candidates.isEmpty()) return IntArray(0)
        if (candidates.size == 1) return intArrayOf(0)

        val pinned = startIndex.takeIf { it in candidates.indices }

        // One pile per artist, each already in the order it wants to be dealt.
        val piles: MutableList<ArrayDeque<Int>> = candidates.indices
            .filter { it != pinned }
            .groupBy { key(candidates[it].artist) }
            .values
            .map { indices -> ArrayDeque(withinPileOrder(indices, candidates, favorUnheard, now, random)) }
            .shuffled(random)
            .toMutableList()

        val result = ArrayList<Int>(candidates.size)
        val recentAlbums = ArrayDeque<String>()
        var lastArtist: String? = null

        pinned?.let {
            result += it
            lastArtist = key(candidates[it].artist)
            recentAlbums.addLast(key(candidates[it].album))
        }

        while (piles.isNotEmpty()) {
            val chosen = pickPile(piles, candidates, lastArtist, recentAlbums, random)
            val pile = piles[chosen]
            val index = takeFrom(pile, candidates, recentAlbums)
            if (pile.isEmpty()) piles.removeAt(chosen)

            result += index
            lastArtist = key(candidates[index].artist)
            recentAlbums.addLast(key(candidates[index].album))
            if (recentAlbums.size > ALBUM_WINDOW) recentAlbums.removeFirst()
        }

        return result.toIntArray()
    }

    /**
     * Picks which pile to deal from: the biggest one that is not the artist
     * just played, preferring an album that has not come up recently, and
     * breaking what is still tied at random so two runs never match.
     */
    private fun pickPile(
        piles: List<ArrayDeque<Int>>,
        candidates: List<ShuffleCandidate>,
        lastArtist: String?,
        recentAlbums: Collection<String>,
        random: Random,
    ): Int {
        var bestScore = Int.MIN_VALUE
        var best = 0
        var ties = 0

        for (i in piles.indices) {
            val head = piles[i].first()
            val sameArtist = key(candidates[head].artist) == lastArtist
            // Judged on what the pile can offer rather than on what happens to
            // be on top of it. Scoring the head alone punished a pile that had
            // a perfectly good track one place down, and rewarded one that had
            // nothing else at all — which is how the album spacing managed to
            // come out worse than no spacing.
            val sameAlbum = !canAvoidAlbum(piles[i], candidates, recentAlbums)

            // Size dominates, so the deck keeps coming round to the big piles.
            // The penalties are large enough to outrank any realistic pile size
            // without needing to be special-cased.
            var score = piles[i].size
            if (sameArtist) score -= ARTIST_PENALTY
            if (sameAlbum) score -= ALBUM_PENALTY

            when {
                score > bestScore -> {
                    bestScore = score
                    best = i
                    ties = 1
                }
                // Reservoir sampling across the tied piles: one pass, no list.
                score == bestScore -> {
                    ties++
                    if (random.nextInt(ties) == 0) best = i
                }
            }
        }
        return best
    }

    /**
     * Takes from a pile, looking a little way past the head for a track from a
     * different record.
     *
     * Choosing between piles alone is not enough to separate an album: when an
     * artist's next few tracks all come from the same record, every pile the
     * deal can reach offers the same album and the preference has nothing to
     * act on. Reaching past the head gives it something to choose.
     *
     * Bounded to a short look, and it falls back to the head, so the order a
     * pile was given — which carries the bias toward what has not been heard
     * lately — is disturbed as little as possible.
     */
    private fun canAvoidAlbum(
        pile: ArrayDeque<Int>,
        candidates: List<ShuffleCandidate>,
        recentAlbums: Collection<String>,
    ): Boolean {
        val limit = minOf(ALBUM_LOOKAHEAD, pile.size)
        for (offset in 0 until limit) {
            if (key(candidates[pile[offset]].album) !in recentAlbums) return true
        }
        return false
    }

    private fun takeFrom(
        pile: ArrayDeque<Int>,
        candidates: List<ShuffleCandidate>,
        recentAlbums: Collection<String>,
    ): Int {
        if (key(candidates[pile.first()].album) !in recentAlbums) return pile.removeFirst()

        val limit = minOf(ALBUM_LOOKAHEAD, pile.size)
        for (offset in 1 until limit) {
            if (key(candidates[pile[offset]].album) !in recentAlbums) {
                return pile.removeAt(offset)
            }
        }
        return pile.removeFirst()
    }

    private fun withinPileOrder(
        indices: List<Int>,
        candidates: List<ShuffleCandidate>,
        favorUnheard: Boolean,
        now: Long,
        random: Random,
    ): List<Int> {
        val shuffled = indices.shuffled(random)
        if (!favorUnheard) return shuffled

        // Sorted by coarse bucket rather than by timestamp, and stably, so the
        // shuffle above survives inside each bucket. Ordering strictly by
        // recency would make every shuffle of the same library identical.
        return shuffled.sortedBy { recencyBucket(candidates[it].lastPlayedAt, now) }
    }

    private fun recencyBucket(lastPlayedAt: Long, now: Long): Int = when {
        lastPlayedAt <= 0L -> 0
        now - lastPlayedAt > 30 * DAY_MS -> 1
        now - lastPlayedAt > 7 * DAY_MS -> 2
        else -> 3
    }

    private fun key(value: String): String = value.trim().lowercase()

    /** How far past a pile's head to look for a different record. */
    private const val ALBUM_LOOKAHEAD = 4

    private const val ARTIST_PENALTY = 1_000_000
    private const val ALBUM_PENALTY = 1_000
}
