package com.irondigital.spindle.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.IOException
import android.util.Log
import kotlinx.coroutines.withContext
import java.io.File

/** What happened to one file the user picked. */
sealed interface ImportResult {
    data class Added(val mediaId: String, val displayName: String) : ImportResult
    data class Failed(val displayName: String, val reason: String) : ImportResult
}

/**
 * Copies audio files the user picks into the on-device music library.
 *
 * The copy is deliberate rather than just remembering the picked URI. A document
 * URI is a loan: its permission can lapse, the provider can go away, and the
 * file never appears to any other app. Copying into `Music/Spindle` through
 * MediaStore makes it a real library file — indexed, visible to every other
 * player, and still there after a reinstall.
 *
 * Requires Android 10 or newer, which is where MediaStore gained `RELATIVE_PATH`
 * and `IS_PENDING`. Doing this on older versions would mean writing to public
 * storage directly, and that means holding WRITE_EXTERNAL_STORAGE — a
 * whole-device permission that every user would then be asked for, to serve a
 * handful of devices. Not a trade worth making.
 */
class AudioImporter(private val context: Context) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun import(sources: List<Uri>): List<ImportResult> = withContext(Dispatchers.IO) {
        if (!isSupported) {
            return@withContext sources.map {
                ImportResult.Failed(displayNameOf(it), "Importing needs Android 10 or newer")
            }
        }
        sources.map { currentCoroutineContext().ensureActive(); importOne(it) }
    }

    suspend fun importLocalFiles(sources: List<File>): List<ImportResult> = withContext(Dispatchers.IO) {
        if (!isSupported) {
            return@withContext sources.map {
                ImportResult.Failed(it.name, "Importing needs Android 10 or newer")
            }
        }
        sources.map { currentCoroutineContext().ensureActive(); importOne(it) }
    }

    private suspend fun importOne(source: Uri): ImportResult {
        val name = displayNameOf(source)
        return importAudio(name,
            mimeType = { context.contentResolver.getType(source)?.takeIf { it.startsWith("audio/") }
                ?: guessMimeFromName(name) },
            openSource = { context.contentResolver.openInputStream(source)
                ?: throw IOException("Could not open the audio file") },
        )
    }

    private suspend fun importOne(source: File): ImportResult = importAudio(
        source.name, { guessMimeFromName(source.name) }, { source.inputStream() },
    )

    private suspend fun importAudio(
        name: String,
        mimeType: () -> String?,
        openSource: () -> InputStream,
    ): ImportResult {
        val coroutine = currentCoroutineContext()
        return try {
            coroutine.ensureActive()
            val mime = mimeType() ?: return ImportResult.Failed(name, "That does not look like an audio file")
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target = completePendingImport(
                create = {
                    resolver.insert(collection, ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, mime)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$IMPORT_FOLDER")
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }) ?: throw IOException("Could not create the file")
                },
                copy = { destination ->
                    openSource().use { input ->
                        val output = resolver.openOutputStream(destination)
                            ?: throw IOException("Could not write the audio file")
                        output.use {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                coroutine.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                it.write(buffer, 0, count)
                                total += count
                            }
                            total
                        }
                    }
                },
                publish = { destination ->
                    coroutine.ensureActive()
                    resolver.update(destination,
                        ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                        null, null,
                    ) == 1
                },
                remove = { destination ->
                    runCatching { resolver.delete(destination, null, null) }
                        .onFailure { Log.w("SpindleImport", "Could not clean up pending audio", it) }
                },
            )
            ImportResult.Added(target.lastPathSegment.orEmpty(), name)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (error: Exception) {
            ImportResult.Failed(name, error.message ?: "Could not import that file")
        }
    }

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "Imported track").substringAfterLast('/')
    }

    private fun guessMimeFromName(name: String): String? = when {
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".aac", true) -> "audio/aac"
        name.endsWith(".flac", true) -> "audio/flac"
        name.endsWith(".ogg", true) || name.endsWith(".oga", true) -> "audio/ogg"
        name.endsWith(".opus", true) -> "audio/opus"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> null
    }

    private companion object {
        const val IMPORT_FOLDER = "Spindle"
    }
}
