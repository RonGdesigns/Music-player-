package com.irondigital.spindle.data.lyrics

/**
 * Parses LRC, the de-facto format for local synced lyrics.
 *
 * Handles the two things real .lrc files in the wild actually do that a naive
 * parser gets wrong: several timestamps on one line (a repeated chorus written
 * once), and both two- and three-digit fractional seconds.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val METADATA = Regex("""^\[(ti|ar|al|by|offset|length|re|ve):(.*)]$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Lyrics? {
        if (raw.isBlank()) return null

        var offsetMs = 0L
        val collected = ArrayList<LyricLine>()
        var sawTimestamp = false

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            METADATA.matchEntire(line)?.let { meta ->
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@let
            }

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                // A file with no timestamps at all is still perfectly good
                // unsynced lyrics; keep the text.
                if (!line.startsWith("[")) collected += LyricLine(-1L, line)
                continue
            }

            sawTimestamp = true
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                val fractionText = stamp.groupValues[3]
                val fraction = when (fractionText.length) {
                    0 -> 0L
                    1 -> (fractionText.toLongOrNull() ?: 0L) * 100
                    2 -> (fractionText.toLongOrNull() ?: 0L) * 10
                    else -> fractionText.take(3).toLongOrNull() ?: 0L
                }
                collected += LyricLine(minutes * 60_000 + seconds * 1_000 + fraction, text)
            }
        }

        if (collected.isEmpty()) return null

        return if (sawTimestamp) {
            // The offset tag is defined as "shift lyrics later by N ms", so it
            // subtracts from the timestamps.
            val lines = collected
                .filter { it.timeMs >= 0 }
                .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
                .sortedBy { it.timeMs }
            Lyrics(lines, synced = true, source = LyricsSource.SIDECAR_LRC, raw = raw)
        } else {
            Lyrics(collected, synced = false, source = LyricsSource.SIDECAR_LRC, raw = raw)
        }
    }
}
