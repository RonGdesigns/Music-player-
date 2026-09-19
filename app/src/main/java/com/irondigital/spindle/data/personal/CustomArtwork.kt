package com.irondigital.spindle.data.personal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.irondigital.spindle.data.media.ArtworkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class CustomArtwork(private val context: Context) {
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val preferences = context.getSharedPreferences("custom_artwork", Context.MODE_PRIVATE)
    fun uriFor(mediaId: String, albumId: Long): Uri? {
        val file = preferences.getString("track:$mediaId", null) ?: preferences.getString("album:$albumId", null) ?: return null
        return ArtworkProvider.customUri(file)
    }
    suspend fun set(key: String, source: Uri) = withContext(Dispatchers.IO) {
        require(key.startsWith("track:") || key.startsWith("album:"))
        val bytes = context.contentResolver.openInputStream(source)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > 20 * 1024 * 1024) throw IOException("Choose an image smaller than 20 MB")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw IOException("Could not open that image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a readable image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Could not decode that image")
        val dir = File(context.filesDir, "custom-art").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".jpg")
        try {
            file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            currentCoroutineContext().ensureActive()
            check(preferences.edit().putString(key, file.name).commit()) { "Could not save artwork" }
            changes.value++
        } catch (error: Exception) { file.delete(); throw error }
        finally { bitmap.recycle() }
    }
    suspend fun clear(key: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(key).commit()) { "Could not reset artwork" }
        changes.value++
    }
}
