package com.irondigital.spindle.data.tagging

/** What a filename appears to say about a track. Any field may be absent. */
data class FilenameTags(
    val artist: String? = null,
    val title: String? = null,
    val trackNumber: Int? = null,
) {
    val isEmpty: Boolean get() = artist == null && title == null && trackNumber == null
}

/**
 * Reads artist, title and track number out of a filename.
 *
 * Files that arrive from a download rather than a rip usually carry no usable
 * tags at all, so the library fills up with "Unknown artist" even though the
 * information is sitting right there in the filename. This recovers it.
 *
 * The parsing is deliberately conservative about what it throws away. Stripping
 * "(Official Video)" is safe because it describes the upload, not the recording.
 * Stripping "(Live)", "(Remix)", "(Acoustic)", "(Remastered 2011)" or
 * "(feat. Someone)" would destroy real metadata — those stay.
 */
object FilenameTagger {

    /**
     * Separators between artist and title. Hyphen, en dash and em dash all show
     * up in the wild, and the spaces matter: "Jay-Z - Song" must not split on
     * the hyphen inside the artist's name.
     */
    private val SEPARATOR = Regex("""\s+[-–—]\s+""")

    /** A leading track number: "01 - ", "01. ", "01 " or "1) ". */
    private val LEADING_TRACK = Regex("""^\s*(\d{1,3})\s*[-.)]?\s+""")

    private val TRAILING_ID = Regex("""\s*[\[(][A-Za-z0-9_-]{11}[\])]\s*$""")

    fun parse(fileName: String): FilenameTags {
        val base = stripExtension(fileName)
        if (base.isBlank()) return FilenameTags()

        var working = TitleNormalizer.normalizeSpacing(base)
        working = TRAILING_ID.replace(working, "")
        working = TitleNormalizer.stripUploadNoise(working)

        var trackNumber: Int? = null
        LEADING_TRACK.find(working)?.let { match ->
            val candidate = match.groupValues[1].toIntOrNull()
            // A leading number is only a track number if something follows it.
            val remainder = working.removeRange(match.range).trim()
            if (candidate != null && candidate in 1..999 && remainder.isNotBlank()) {
                trackNumber = candidate
                working = remainder
            }
        }

        val parts = SEPARATOR.split(working).map { it.trim() }.filter { it.isNotBlank() }

        return when {
            parts.isEmpty() -> FilenameTags(trackNumber = trackNumber)

            parts.size == 1 -> FilenameTags(
                title = parts[0].takeIf { it.isNotBlank() },
                trackNumber = trackNumber,
            )

            // "Artist - Title"
            parts.size == 2 -> FilenameTags(
                artist = parts[0],
                title = parts[1],
                trackNumber = trackNumber,
            )

            // Three or more: the first is the artist and the last is the title.
            // Whatever sits between is usually an album or a label, and guessing
            // which would be worse than leaving it out.
            else -> FilenameTags(
                artist = parts.first(),
                title = parts.last(),
                trackNumber = trackNumber,
            )
        }
    }

    /**
     * Whether a track's existing metadata is poor enough to be worth replacing.
     *
     * MediaStore falls back to the filename for a title and to a placeholder for
     * an artist, so "the title equals the filename" is itself a sign that nothing
     * real was ever read.
     */
    fun looksUntagged(title: String, artist: String, fileName: String): Boolean {
        val base = stripExtension(fileName)
        val unknownArtist = artist.isBlank() ||
            artist.equals("Unknown artist", ignoreCase = true) ||
            artist.equals("<unknown>", ignoreCase = true)
        val titleIsFilename = title.isBlank() ||
            title.equals(base, ignoreCase = true) ||
            title.equals(TitleNormalizer.normalizeSpacing(base), ignoreCase = true)
        return unknownArtist || titleIsFilename
    }

    /**
     * A real audio extension starts with a letter and holds no spaces, which is
     * what keeps "Track Vol. 2" and "Track 1.5" intact — measuring the trailing
     * run by length alone eats both of them.
     */
    private val EXTENSION = Regex("""\.[A-Za-z][A-Za-z0-9]{0,4}$""")

    private fun stripExtension(fileName: String): String =
        EXTENSION.replace(fileName.substringAfterLast('/'), "")
}
