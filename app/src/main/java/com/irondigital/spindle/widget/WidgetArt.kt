package com.irondigital.spindle.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads cover art small enough for a widget.
 *
 * Everything a widget draws crosses a Binder transaction with a hard limit
 * around 1 MB, and a full-size embedded cover is comfortably past it — a widget
 * that silently fails to appear is almost always a bitmap that was too big. So
 * art is decoded down to [TARGET_PX] before it ever reaches a RemoteViews.
 */
object WidgetArt {

    private const val TARGET_PX = 192

    // Two entries: what is playing and what was playing a moment ago. The
    // launcher re-renders the same widget repeatedly, so even this helps.
    private val cache = object : LruCache<String, Bitmap>(2) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    suspend fun load(context: Context, uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        cache.get(uriString)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.RGB_565 // no alpha in cover art
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@runCatching null

                decoded.also { cache.put(uriString, it) }
            }.getOrNull()
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= TARGET_PX && h / 2 >= TARGET_PX) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
