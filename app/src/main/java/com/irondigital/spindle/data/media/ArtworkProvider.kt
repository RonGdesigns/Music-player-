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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
 * Extraction is lazy and cached on disk, and both halves of that matter more
 * than they look. Opening a file to find out it has *no* art costs the same as
 * opening one that has some, and in a library assembled from downloads most
 * files have none — so a miss is written down as a marker file. Without it,
 * every scroll past an art-less track paid the full cost again, and a library
 * of a few thousand of them made the whole app feel slow.
 *
 * Extraction is also rate-limited. An image loader fires a request per visible
 * row, so a fling asks for a dozen at once; letting all of them open and parse
 * an audio file simultaneously is what turns a background cost into an
 * unresponsive interface. Three at a time, and a request that cannot get a turn
 * quickly gives up rather than holding a thread — the row keeps its placeholder
 * and asks again the next time it is drawn, which is exactly what an image
 * loader is built to do.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.pathSegments.firstOrNull() == "custom") {
            val name = uri.lastPathSegment.orEmpty()
            if (!name.matches(Regex("[a-f0-9-]+\\.jpg"))) throw FileNotFoundException("Invalid artwork")
            val file = File(context!!.filesDir, "custom-art/$name")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val mediaId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: throw FileNotFoundException("No track in $uri")
        val context = context ?: throw FileNotFoundException("No context")

        // Cheapest first: a set lookup, then a file stat. Both answer for a
        // track with no art without opening anything.
        if (mediaId in missing) throw FileNotFoundException("No embedded art for $mediaId")

        val cached = cacheFile(context, mediaId)
        if (!cached.exists()) {
            if (missFile(context, mediaId).exists()) {
                missing += mediaId
                throw FileNotFoundException("No embedded art for $mediaId")
            }
            extractInto(context, mediaId, cached)
        }

        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun extractInto(context: Context, mediaId: String, cached: File) {
        if (!extractions.tryAcquire(EXTRACT_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // Busy. Giving up beats holding a loader thread: the row keeps its
            // placeholder and the next draw asks again.
            throw FileNotFoundException("Artwork extraction is busy")
        }

        try {
            // Re-checked inside the gate, because several rows can queue for
            // the same track and only the first should do the work.
            if (cached.exists()) return

            val track = context.spindle.library.trackFor(mediaId)
                // Transient — the library may not have scanned yet — so this is
                // deliberately not written down as a miss.
                ?: throw FileNotFoundException("Unknown track $mediaId")

            val bytes = extract(context, track.uri)
            if (bytes == null) {
                rememberMiss(context, mediaId)
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
        } finally {
            extractions.release()
        }
    }

    /**
     * Records that this file has no art, on disk as well as in memory. The
     * in-memory set alone would forget on every process death, and re-parsing a
     * whole library of art-less downloads is the cost this exists to avoid.
     */
    private fun rememberMiss(context: Context, mediaId: String) {
        missing += mediaId
        runCatching {
            val marker = missFile(context, mediaId)
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        }
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

        fun customUri(file: String): Uri = Uri.parse("content://$AUTHORITY/custom/$file")

        fun uriFor(mediaId: String): Uri =
            Uri.parse("content://$AUTHORITY/$mediaId")

        /** At most this many files being opened and parsed at once. */
        private val extractions = Semaphore(3)

        private const val EXTRACT_WAIT_MS = 400L

        private fun cacheDir(context: Context): File = File(context.cacheDir, "embedded-art")

        private fun cacheFile(context: Context, mediaId: String): File =
            File(cacheDir(context), mediaId)

        private fun missFile(context: Context, mediaId: String): File =
            File(cacheDir(context), "$mediaId.none")

        /**
         * Forgets everything, for after a rescan or a retagging session. The
         * negative cache in particular would otherwise outlive the reason it
         * was correct.
         */
        fun clearCache(context: Context) {
            missing.clear()
            runCatching { cacheDir(context).deleteRecursively() }
        }
    }
}
