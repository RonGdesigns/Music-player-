package com.irondigital.spindle.data.lyrics

/** One timestamped line. [timeMs] is -1 for unsynced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: LyricsSource,
    val raw: String,
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }

    /**
     * Index of the line that should be highlighted at [positionMs], or -1 before
     * the first line. Binary search — this runs on every frame of the lyrics pane.
     */
    fun activeIndexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    companion object {
        val NONE = Lyrics(emptyList(), synced = false, source = LyricsSource.NONE, raw = "")
    }
}

enum class LyricsSource {
    /** Typed or pasted by the user. Always wins. */
    USER,

    /** A .lrc file sitting next to the audio file. */
    SIDECAR_LRC,

    /** USLT / SYLT frame inside the file's own ID3 tag. */
    EMBEDDED_TAG,

    NONE,
}
