package com.irondigital.spindle.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
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
        sources.map { importOne(it) }
    }

    suspend fun importLocalFiles(sources: List<File>): List<ImportResult> = withContext(Dispatchers.IO) {
        if (!isSupported) {
            return@withContext sources.map {
                ImportResult.Failed(it.name, "Importing needs Android 10 or newer")
            }
        }
        sources.map { importOne(it) }
    }

    private fun importOne(source: Uri): ImportResult {
        val name = displayNameOf(source)
        return runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(source)?.takeIf { it.startsWith("audio/") }
                ?: guessMimeFromName(name)
                ?: return ImportResult.Failed(name, "That does not look like an audio file")

            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val pending = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$IMPORT_FOLDER")
                // Without this the scanner may file it as a notification sound and
                // the library will never show it.
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            // MediaStore resolves a name collision itself by suffixing, so two
            // imports of the same file do not overwrite each other.
            val target = resolver.insert(collection, pending)
                ?: return ImportResult.Failed(name, "Could not create the file")

            var copied = 0L
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    copied = input.copyTo(output)
                }
            }

            if (copied <= 0L) {
                // A pending row that never received bytes would linger invisibly.
                resolver.delete(target, null, null)
                return ImportResult.Failed(name, "The file was empty or unreadable")
            }

            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )

            ImportResult.Added(mediaId = target.lastPathSegment.orEmpty(), displayName = name)
        }.getOrElse { error ->
            ImportResult.Failed(name, error.message ?: "Could not import that file")
        }
    }

    private fun importOne(source: File): ImportResult {
        val name = source.name
        return runCatching {
            if (!source.isFile || source.length() <= 0L) {
                return ImportResult.Failed(name, "The converted file was empty or unreadable")
            }

            val mime = guessMimeFromName(name)
                ?: return ImportResult.Failed(name, "That does not look like an audio file")

            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val pending = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$IMPORT_FOLDER")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val target = resolver.insert(collection, pending)
                ?: return ImportResult.Failed(name, "Could not create the file")

            val copied = source.inputStream().use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output)
                } ?: 0L
            }

            if (copied <= 0L) {
                resolver.delete(target, null, null)
                return ImportResult.Failed(name, "The converted file was empty or unreadable")
            }

            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )

            ImportResult.Added(mediaId = target.lastPathSegment.orEmpty(), displayName = name)
        }.getOrElse { error ->
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
