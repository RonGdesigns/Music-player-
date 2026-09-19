package com.irondigital.spindle.data.model

import android.net.Uri
import com.irondigital.spindle.data.media.ArtworkProvider

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
    /**
     * Who the *album* is credited to, which is not always who the track is.
     * A compilation, or any album with a guest feature, otherwise shatters into
     * one artist per track.
     */
    val albumArtist: String,
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
    // Copies with display corrections retain the identity read from the file.
    val sourceTitle: String = title,
    val sourceArtist: String = artist,
) {
    /** Stable key used by the session, the database and the widget alike. */
    val mediaId: String get() = id.toString()

    /**
     * The cover to draw. MediaStore's own art when it has some, and otherwise
     * the art inside the file, served by [ArtworkProvider].
     *
     * Never null, which means a track with no art anywhere resolves to a URI
     * that fails to open. That is deliberate: deciding here would mean opening
     * every file during the scan, and the failure is already handled — the
     * artwork component keeps its placeholder behind the image.
     */
    val artUri: Uri get() = albumArtUri ?: ArtworkProvider.uriFor(mediaId)

    /** What to group this under in the Artists list. */
    val effectiveAlbumArtist: String get() = albumArtist.ifBlank { artist }

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
