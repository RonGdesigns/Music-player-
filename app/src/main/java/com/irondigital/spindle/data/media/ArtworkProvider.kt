package com.irondigital.spindle.data.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.irondigital.spindle.BuildConfig
import com.irondigital.spindle.spindle
import java.io.File
import java.io.FileNotFoundException
import java.util.Collections

/**
 * Serves cover art that is inside the audio file.
 *
 * MediaStore only hands out art it decided to extract into its own album-art
 * provider, and for files that arrived as downloads rather than as a ripped
 * album it very often decides not to. The art is right there in the file — an
 * ID3 `APIC` frame, a FLAC picture block, an MP4 `covr` atom — and every other
 * player shows it, so a blank plate reads as the app being broken.
 *
 * This is a content provider rather than a loader for one reason: a `content://`
 * URI is understood by everything already. Coil draws it, the palette extractor
 * opens it, the widget decodes it, and Media3 hands it to the notification and
 * to the car without any of them knowing this exists. One component instead of
 * four separate integrations.
 *
 * Extraction is lazy and cached on disk. Opening a file to find out it has no
 * art is not free, so a miss is remembered too — otherwise every scroll past an
 * art-less track pays for the discovery again.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val mediaId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: throw FileNotFoundException("No track in $uri")
        val context = context ?: throw FileNotFoundException("No context")

        if (mediaId in missing) throw FileNotFoundException("No embedded art for $mediaId")

        val cached = cacheFile(context, mediaId)
        if (!cached.exists()) {
            val track = context.spindle.library.trackFor(mediaId)
                ?: throw FileNotFoundException("Unknown track $mediaId")

            val bytes = extract(context, track.uri)
            if (bytes == null) {
                missing += mediaId
                throw FileNotFoundException("No embedded art for $mediaId")
            }

            // Written via a temporary file and moved into place, so a reader
            // arriving mid-write never sees a half-decoded image.
            cached.parentFile?.mkdirs()
            val temp = File(cached.parentFile, "${cached.name}.part")
            runCatching {
                temp.writeBytes(bytes)
                if (!temp.renameTo(cached)) temp.delete()
            }.onFailure { temp.delete() }

            if (!cached.exists()) throw FileNotFoundException("Could not cache art for $mediaId")
        }

        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun extract(context: Context, source: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, source)
            retriever.embeddedPicture
        } catch (e: Exception) {
            // A file that cannot be opened or parsed simply has no art as far
            // as this is concerned. It must never take the caller down with it.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // This provider exists to serve one file. Nothing else is meaningful on it.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /**
         * Carries the build's own application id, so the debug and release
         * builds do not fight over one authority when both are installed.
         */
        private val AUTHORITY = BuildConfig.APPLICATION_ID + ".artwork"

        /** Tracks known to have no embedded art, so the file is opened once. */
        private val missing: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf<String>())

        fun uriFor(mediaId: String): Uri =
            Uri.parse("content://$AUTHORITY/$mediaId")

        private fun cacheFile(context: Context, mediaId: String): File =
            File(File(context.cacheDir, "embedded-art"), mediaId)

        /**
         * Forgets everything, for after a rescan or a retagging session. The
         * negative cache in particular would otherwise outlive the reason it
         * was correct.
         */
        fun clearCache(context: Context) {
            missing.clear()
            runCatching { File(context.cacheDir, "embedded-art").deleteRecursively() }
        }
    }
}
