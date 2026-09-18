package com.irondigital.spindle.data.lyrics

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One candidate the lyrics database offered for a track. */
data class LyricsCandidate(
    val trackName: String,
    val artistName: String,
    val durationMs: Long,
    val instrumental: Boolean,
    val syncedLyrics: String?,
    val plainLyrics: String?,
) {
    val hasAnything: Boolean
        get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
}

/**
 * What a lookup produced.
 *
 * [NotFound] and [Failed] are kept apart on purpose, and it is the most
 * important distinction here. "This track has no lyrics" is worth remembering
 * so the same track is not looked up on every play. "The server was busy" is
 * not — caching that would permanently deny a track lyrics because of one bad
 * moment on someone else's server.
 */
sealed interface LyricsLookup {
    data class Found(val lrc: String, val synced: Boolean) : LyricsLookup
    data object NotFound : LyricsLookup
    data class Failed(val reason: String) : LyricsLookup
}

/**
 * Reads the lyrics database's responses.
 *
 * Split from the network call so the part with the judgment in it — which
 * candidate actually matches the recording in front of you — can be tested
 * without a server.
 */
object OnlineLyricsParser {

    /**
     * How far a candidate's length may differ and still be the same recording.
     *
     * Tight on purpose. A live version, a radio edit and an extended mix are
     * all the same title by the same artist, and the length is the only thing
     * that tells them apart — synced lyrics from the wrong cut are worse than
     * no lyrics, because they look right and drift.
     */
    const val DURATION_TOLERANCE_MS = 3_000L

    fun parseOne(json: String): LyricsCandidate? =
        runCatching { candidateFrom(JSONObject(json)) }.getOrNull()

    fun parseMany(json: String): List<LyricsCandidate> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::candidateFrom)
        }
    }.getOrDefault(emptyList())

    private fun candidateFrom(item: JSONObject): LyricsCandidate = LyricsCandidate(
        trackName = item.optString("trackName"),
        artistName = item.optString("artistName"),
        // Sent as seconds, and as a float.
        durationMs = (item.optDouble("duration", 0.0) * 1000).toLong(),
        instrumental = item.optBoolean("instrumental", false),
        syncedLyrics = item.optStringOrNull("syncedLyrics"),
        plainLyrics = item.optStringOrNull("plainLyrics"),
    )

    /**
     * Picks the best candidate for a recording of [durationMs], or null.
     *
     * Anything outside the tolerance is discarded rather than ranked, then a
     * synced result beats an unsynced one, and the closest length wins. An
     * instrumental is a real answer — it means there is nothing to show — but
     * it is never preferred over a candidate that actually has words.
     */
    fun chooseBest(candidates: List<LyricsCandidate>, durationMs: Long): LyricsCandidate? {
        val plausible = candidates.filter {
            it.durationMs <= 0 || abs(it.durationMs - durationMs) <= DURATION_TOLERANCE_MS
        }
        val withWords = plausible.filter { it.hasAnything }
        if (withWords.isEmpty()) return plausible.firstOrNull { it.instrumental }

        return withWords.minWithOrNull(
            compareByDescending<LyricsCandidate> { !it.syncedLyrics.isNullOrBlank() }
                .thenBy { abs(it.durationMs - durationMs) }
        )
    }

    /** Turns a chosen candidate into the result the repository stores. */
    fun toLookup(candidate: LyricsCandidate?): LyricsLookup = when {
        candidate == null -> LyricsLookup.NotFound
        !candidate.syncedLyrics.isNullOrBlank() ->
            LyricsLookup.Found(candidate.syncedLyrics, synced = true)
        !candidate.plainLyrics.isNullOrBlank() ->
            LyricsLookup.Found(candidate.plainLyrics, synced = false)
        // Instrumental, or an entry with nothing in it. Both mean there is
        // genuinely nothing to show, which is an answer worth remembering.
        else -> LyricsLookup.NotFound
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
}
