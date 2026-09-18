package com.irondigital.spindle.data.lyrics

/** One timestamped line. [timeMs] is -1 for unsynced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: LyricsSource,
    val raw: String,
    /**
     * The user's own timing correction, in milliseconds, applied on top of
     * whatever the file says.
     *
     * Positive means the lines appear *later*. Downloaded LRC is routinely off
     * by a fraction of a second against a particular encode, and half a second
     * is the difference between following along and being distracted by it.
     */
    val offsetMs: Long = 0,
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }

    /**
     * Index of the line that should be highlighted at [positionMs], or -1 before
     * the first line. Binary search — this runs on every frame of the lyrics pane.
     */
    fun activeIndexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        // Shifting the playhead back is the same as shifting every timestamp
        // forward, and costs one subtraction instead of rebuilding the list.
        val effective = positionMs - offsetMs
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= effective) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /**
     * Where to seek when a line is tapped. The offset has to come back out
     * here, or correcting the timing would quietly break tap-to-seek.
     */
    fun seekTargetFor(line: LyricLine): Long =
        (line.timeMs + offsetMs).coerceAtLeast(0L)

    companion object {
        val NONE = Lyrics(emptyList(), synced = false, source = LyricsSource.NONE, raw = "")

        /** How far the correction can go either way, and the step it moves in. */
        const val MAX_OFFSET_MS = 10_000L
        const val OFFSET_STEP_MS = 100L
    }
}

enum class LyricsSource {
    /** Typed or pasted by the user. Always wins. */
    USER,

    /** A .lrc file sitting next to the audio file. */
    SIDECAR_LRC,

    /** USLT / SYLT frame inside the file's own ID3 tag. */
    EMBEDDED_TAG,

    /** Fetched from the online database, with the user's consent, and kept. */
    ONLINE,

    NONE,
}
