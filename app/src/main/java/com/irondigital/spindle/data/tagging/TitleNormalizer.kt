package com.irondigital.spindle.data.tagging

/**
 * Tells apart the two kinds of parenthetical a track title can carry.
 *
 * `(Official Video)` describes the upload. `(Live)`, `(Remix)`, `(Acoustic)`,
 * `(Remastered 2011)`, `(Radio Edit)` and `(feat. Someone)` describe the
 * recording, and two files that differ only by one of those are two different
 * recordings — not duplicates.
 *
 * Getting this line wrong in the permissive direction means offering to delete
 * a remix as a copy of the original, so the noise list stays short and explicit
 * rather than clever.
 */
object TitleNormalizer {

    private val NOISE = listOf(
        "official music video",
        "official lyric video",
        "official lyrics video",
        "official video",
        "official audio",
        "official visualizer",
        // Both spellings, because this list matches other people's upload
        // titles rather than anything Spindle writes.
        "official visualiser",
        "official version",
        "lyric video",
        "lyrics video",
        "with lyrics",
        "lyrics",
        "music video",
        "audio only",
        "full hd",
        "hd",
        "hq",
        "4k",
        "1080p",
        "720p",
    )

    private val BRACKETED = Regex("""[\[(]([^\[\]()]*)[\])]""")

    /** Collapses underscores and runs of whitespace; downloaded names are full of both. */
    fun normalizeSpacing(value: String): String =
        value.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

    /** Removes only the parentheticals that describe an upload. */
    fun stripUploadNoise(value: String): String {
        var result = BRACKETED.replace(value) { match ->
            if (NOISE.any { match.groupValues[1].trim().equals(it, ignoreCase = true) }) "" else match.value
        }
        for (noise in NOISE) {
            result = Regex("""\s*[-–—]?\s*\b${Regex.escape(noise)}\b\s*$""", RegexOption.IGNORE_CASE)
                .replace(result, "")
        }
        return normalizeSpacing(result).trim(' ', '-', '–', '—')
    }

    /**
     * A comparison key: upload noise gone, case folded, punctuation dropped.
     *
     * Punctuation goes because the same track is written "Don't Stop", "Dont
     * Stop" and "Don’t Stop" across three sources; the words are what identify it.
     */
    fun matchKey(value: String): String =
        stripUploadNoise(value)
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
