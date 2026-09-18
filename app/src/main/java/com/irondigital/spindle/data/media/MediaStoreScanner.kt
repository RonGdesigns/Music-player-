package com.irondigital.spindle.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.irondigital.spindle.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the on-device audio library out of MediaStore in one pass.
 *
 * MediaStore is the only way to see files the user put there with any other app
 * (a file manager, a USB copy, another player) without demanding
 * MANAGE_EXTERNAL_STORAGE, which is an unjustifiable ask for a music player.
 */
class MediaStoreScanner(private val context: Context) {

    suspend fun scan(
        minDurationMs: Long,
        excludedFolders: Set<String>,
        includeNonMusicAudio: Boolean,
    ): List<Track> =
        withContext(Dispatchers.IO) {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.ALBUM_ID)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.TRACK)
                add(MediaStore.Audio.Media.YEAR)
                add(MediaStore.Audio.Media.DATA)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.MIME_TYPE)
                add(MediaStore.Audio.Media.DATE_ADDED)
                add(MediaStore.Audio.Media.DATE_MODIFIED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Audio.Media.BITRATE)
                }
            }.toTypedArray()

            // IS_MUSIC is what makes MediaStore usable as a music library — it
            // keeps out ringtones, notifications and alarms.
            //
            // But it is set by the media scanner's own guess, and audio that
            // arrives in Download/ rather than Music/ frequently does not get the
            // flag. To a user who downloads tracks and expects to play them, the
            // library simply looks empty for no visible reason. So the relaxed
            // mode drops IS_MUSIC and excludes the system-sound categories
            // directly, which is the same intent stated the other way round.
            val selection = if (includeNonMusicAudio) {
                "${MediaStore.Audio.Media.IS_RINGTONE} = 0" +
                    " AND ${MediaStore.Audio.Media.IS_ALARM} = 0" +
                    " AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0"
            } else {
                "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            }
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            val out = ArrayList<Track>(512)
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
                ?.use { cursor -> readAll(cursor, collection, minDurationMs, excludedFolders, out) }
            out
        }

    private fun readAll(
        cursor: Cursor,
        collection: Uri,
        minDurationMs: Long,
        excludedFolders: Set<String>,
        out: MutableList<Track>,
    ) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        // Not every device populates this, so it is looked up leniently and
        // falls back to the track artist rather than failing the whole scan.
        val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val bitrateCol = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)

        while (cursor.moveToNext()) {
            val duration = cursor.getLong(durationCol)
            // Drops the two-second clips and voice-memo fragments that otherwise
            // pad out the library. Threshold is a user setting, not a constant.
            if (duration < minDurationMs) continue

            val path = cursor.getStringOrNull(dataCol)
            val folder = path?.substringBeforeLast('/', "")
            if (folder != null && excludedFolders.any { folder.startsWith(it) }) continue

            val id = cursor.getLong(idCol)
            val albumId = cursor.getLong(albumIdCol)

            // TRACK is encoded as disc*1000 + track by the media scanner.
            val rawTrack = cursor.getInt(trackCol)
            val disc = if (rawTrack > 1000) rawTrack / 1000 else 1
            val trackNo = if (rawTrack > 1000) rawTrack % 1000 else rawTrack

            out += Track(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                title = cursor.getStringOrNull(titleCol)?.takeIf { it.isNotBlank() }
                    ?: cursor.getStringOrNull(nameCol).orEmpty(),
                artist = cursor.getStringOrNull(artistCol)
                    ?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Unknown artist",
                album = cursor.getStringOrNull(albumCol)
                    ?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Unknown album",
                albumArtist = (
                    if (albumArtistCol >= 0) cursor.getStringOrNull(albumArtistCol) else null
                    )?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                albumId = albumId,
                albumArtUri = albumArtUri(albumId),
                durationMs = duration,
                trackNumber = trackNo,
                discNumber = disc,
                year = cursor.getInt(yearCol),
                filePath = path,
                displayName = cursor.getStringOrNull(nameCol).orEmpty(),
                sizeBytes = cursor.getLong(sizeCol),
                mimeType = cursor.getStringOrNull(mimeCol).orEmpty(),
                bitrateBps = if (bitrateCol >= 0 && !cursor.isNull(bitrateCol)) cursor.getInt(bitrateCol) else 0,
                dateAddedSec = cursor.getLong(addedCol),
                dateModifiedSec = cursor.getLong(modifiedCol),
            )
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    companion object {
        private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

        /**
         * The legacy albumart provider still resolves on every Android version
         * we support and, unlike loadThumbnail, works from a plain Uri — which
         * the widget and the media notification both need.
         */
        fun albumArtUri(albumId: Long): Uri? =
            if (albumId <= 0) null else ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
    }
}
