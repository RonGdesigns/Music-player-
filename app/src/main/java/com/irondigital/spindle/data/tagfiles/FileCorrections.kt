package com.irondigital.spindle.data.tagfiles

import com.irondigital.spindle.data.db.TrackEdit

/** What the file itself says, as the media scanner read it. */
data class FileTags(
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val trackNumber: Int,
)

/** One field that would change: what the file says now, and what it would say. */
data class FieldDiff(val label: String, val inFile: String, val corrected: String)

/**
 * Turns a Spindle correction into the tag changes that would carry it into the
 * file.
 *
 * Only fields where the correction and the file actually disagree are written.
 * A correction that repeats what the file already says writes nothing, so a
 * file whose corrections are all already in it drops out of the list rather
 * than being rewritten for no reason.
 */
object FileCorrections {

    fun changes(file: FileTags, edit: TrackEdit): TagChanges = TagChanges(
        title = edit.title?.trim()?.takeIf { it.isNotEmpty() && it != file.title },
        artist = edit.artist?.trim()?.takeIf { it.isNotEmpty() && it != file.artist },
        album = edit.album?.trim()?.takeIf { it.isNotEmpty() && it != file.album },
        year = edit.year?.takeIf { it in 1..9999 && it != file.year },
        trackNumber = edit.trackNumber?.takeIf { it in 1..999 && it != file.trackNumber },
    )

    fun diffs(file: FileTags, changes: TagChanges): List<FieldDiff> = buildList {
        changes.title?.let { add(FieldDiff("Title", shown(file.title), it)) }
        changes.artist?.let { add(FieldDiff("Artist", shown(file.artist), it)) }
        changes.album?.let { add(FieldDiff("Album", shown(file.album), it)) }
        changes.year?.let { add(FieldDiff("Year", shown(file.year), it.toString())) }
        changes.trackNumber?.let { add(FieldDiff("Track", shown(file.trackNumber), it.toString())) }
    }

    private fun shown(value: String) = value.ifBlank { "nothing" }

    private fun shown(value: Int) = if (value > 0) value.toString() else "nothing"
}
