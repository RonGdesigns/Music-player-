package com.irondigital.spindle.data.lyrics

/**
 * Throws away the download site's advertising that arrives in the lyrics tag.
 *
 * Plenty of files come with their source stamped into the lyrics field — a URL,
 * a site name, a "downloaded from" credit — and sometimes that is the entire
 * contents. Before this, such a file counted as having lyrics: the site's
 * address was displayed as though it were the song, and because something had
 * been found, the online lookup never ran. The one track that most needed
 * looking up was the one guaranteed not to be.
 *
 * The bias here is deliberate. Dropping a real lyric line by mistake is much
 * worse than leaving a stray credit on screen, so a line is only removed when
 * it is unmistakably not part of a song — it carries a web address, or it is a
 * short line whose whole job is to name where the file came from. Anything
 * else survives, including lines this cannot make sense of.
 */
object LyricsSanitizer {

    /** Requires a real top-level domain, so "Mr. Jones" and "U.S.A." are safe. */
    private val URL = Regex(
        """(https?://|www\.)|(\b[\w-]{2,}\.(com|net|org|info|biz|xyz|me|io|ru|ua|pl|de|fr|co\.uk|co)\b)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Only matched against short lines. A song could plausibly contain the
     * words "visit" or "free download"; a two-word line that is nothing but
     * those words is not a lyric.
     */
    private val CREDIT_PHRASES = listOf(
        """downloaded\s+from""",
        """download(?:ed)?\s+by""",
        """converted\s+by""",
        """ripped\s+by""",
        """encoded\s+by""",
        """lyrics?\s+(?:by|from|provided\s+by|source)""",
        """provided\s+by""",
        """powered\s+by""",
        """visit\s+(?:us|our)""",
        """get\s+more""",
        """more\s+songs?\s+at""",
        """free\s+download""",
        """all\s+rights\s+reserved""",
        """copyright\b""",
        """\u00A9""",
    )

    private val CREDIT = Regex(
        """^\s*[\[(]?\s*(?:""" + CREDIT_PHRASES.joinToString("|") + ")",
        RegexOption.IGNORE_CASE,
    )

    /** An LRC timestamp, so the text is judged rather than its timing. */
    private val TIMESTAMP = Regex("""^\s*(\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]\s*)+""")

    /** Tag lines like [ar:...] — metadata, never content. */
    private val LRC_METADATA = Regex("""^\s*\[[a-z]{2,8}:.*]\s*$""", RegexOption.IGNORE_CASE)

    private const val SHORT_LINE_CHARS = 60
    private const val MIN_REAL_LINES = 2
    private const val MIN_REAL_CHARS = 40

    fun isNoise(rawLine: String): Boolean {
        val line = TIMESTAMP.replace(rawLine, "").trim()
        if (line.isEmpty()) return false
        if (URL.containsMatchIn(line)) return true
        return line.length <= SHORT_LINE_CHARS && CREDIT.containsMatchIn(line)
    }

    /**
     * Strips the noise. Returns null when what is left is too thin to be a
     * song, which is what lets the lookup treat the track as having none.
     */
    fun clean(lyrics: Lyrics): Lyrics? {
        val keptLines = lyrics.lines.filterNot { isNoise(it.text) }
        val realLines = keptLines.filter { it.text.isNotBlank() }

        if (realLines.size < MIN_REAL_LINES) return null
        if (realLines.sumOf { it.text.trim().length } < MIN_REAL_CHARS) return null

        // Nothing was noise, so nothing needs rebuilding.
        if (keptLines.size == lyrics.lines.size) return lyrics

        val keptRaw = lyrics.raw
            .lineSequence()
            .filterNot { isNoise(it) && !LRC_METADATA.matches(it) }
            .joinToString("\n")

        return lyrics.copy(lines = keptLines, raw = keptRaw)
    }
}
