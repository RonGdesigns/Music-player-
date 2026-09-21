package com.irondigital.spindle.ui

import com.irondigital.spindle.playback.QueueEntry

data class QueueTrackDetails(val title: String, val artist: String, val durationMs: Long)

fun resolveQueueRows(
    entries: List<QueueEntry>,
    revision: Long,
    resolve: (String) -> QueueTrackDetails?,
): List<QueueRowState> = entries.mapIndexed { index, entry ->
    val track = resolve(entry.mediaId)
    QueueRowState(index, revision, entry.mediaId,
        track?.title ?: entry.title.ifBlank { "Unavailable track" },
        track?.artist ?: "Not in the current library",
        track?.durationMs ?: entry.durationMs, track != null,
    )
}

/** Index and revision identify an occurrence, even when the same song repeats. */
data class QueueRowState(
    val index: Int,
    val revision: Long,
    val mediaId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val available: Boolean,
) {
    fun matches(revision: Long, mediaIds: List<String>): Boolean =
        this.revision == revision && mediaIds.getOrNull(index) == mediaId
}
