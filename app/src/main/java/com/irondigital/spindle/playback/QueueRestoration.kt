package com.irondigital.spindle.playback

data class QueueRestoration(
    val entries: List<QueueEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val order: List<Int>,
)

/** Accepts old snapshots and remaps indices if a damaged entry must be omitted. */
fun PlaybackSnapshot.restoration(): QueueRestoration? {
    if (currentIndex !in queue.indices) return null
    val valid = queue.withIndex().filter { it.value.mediaId.toLongOrNull() != null }
    if (valid.isEmpty()) return null
    val indices = valid.mapIndexed { newIndex, item -> item.index to newIndex }.toMap()
    val restoredIndex = indices[currentIndex] ?: 0
    val entries = valid.map { (index, entry) ->
        if (index == currentIndex) entry.copy(
            album = entry.album.ifBlank { album },
            artUri = entry.artUri ?: artUri,
        ) else entry
    }
    return QueueRestoration(
        entries, restoredIndex, if (currentIndex in indices) positionMs.coerceAtLeast(0) else 0,
        shuffleEnabled, repeatMode.takeIf { it in 0..2 } ?: 0,
        validPlaybackOrder().mapNotNull { indices[it] },
    )
}
