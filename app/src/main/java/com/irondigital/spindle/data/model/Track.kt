package com.irondigital.spindle.data.model

import android.net.Uri

/**
 * One audio file as the library sees it.
 *
 * Everything here comes straight out of MediaStore, which is cheap to read in
 * bulk. Anything that needs the file itself opened — sample rate, channel
 * count, embedded lyrics — is deliberately absent and loaded on demand by the
 * song-info sheet, so a 5,000-track scan stays fast.
 */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtUri: Uri?,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val filePath: String?,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val bitrateBps: Int,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
) {
    /** Stable key used by the session, the database and the widget alike. */
    val mediaId: String get() = id.toString()

    /** Parent directory, used for the Folders tab and for finding sidecar .lrc files. */
    val folderPath: String? get() = filePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

    val folderName: String get() = folderPath?.substringAfterLast('/') ?: "Unknown folder"
}

data class AlbumGroup(
    val albumId: Long,
    val name: String,
    val artist: String,
    val artUri: Uri?,
    val trackCount: Int,
    val totalDurationMs: Long,
    val year: Int,
)

data class ArtistGroup(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long,
)

data class FolderGroup(
    val path: String,
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long,
)
