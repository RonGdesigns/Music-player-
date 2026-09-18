package com.irondigital.spindle.data.lyrics

import android.content.Context
import com.irondigital.spindle.data.db.LyricsDao
import com.irondigital.spindle.data.db.LyricsOverride
import com.irondigital.spindle.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves lyrics for a track from local sources only, in priority order:
 * what the user typed, a sidecar .lrc, then the file's own tags.
 *
 * Nothing here touches the network. Local-library listeners tend to have their
 * lyrics already, either as .lrc files or baked into the tags, and a player
 * that quietly ships every filename you own to a lyrics API in order to guess
 * is not a trade worth making by default.
 */
class LyricsRepository(
    private val context: Context,
    private val lyricsDao: LyricsDao,
) {

    suspend fun load(track: Track): Lyrics = withContext(Dispatchers.IO) {
        userOverride(track)
            ?: sidecar(track)
            ?: embedded(track)
            ?: Lyrics.NONE
    }

    suspend fun saveOverride(track: Track, content: String) {
        val parsed = LrcParser.parse(content)
        lyricsDao.upsert(
            LyricsOverride(
                mediaId = track.mediaId,
                content = content,
                synced = parsed?.synced == true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearOverride(track: Track) = lyricsDao.delete(track.mediaId)

    private suspend fun userOverride(track: Track): Lyrics? {
        val saved = lyricsDao.get(track.mediaId) ?: return null
        LrcParser.parse(saved.content)?.let { return it.copy(source = LyricsSource.USER) }
        return Lyrics(
            lines = saved.content.lines().map { LyricLine(-1L, it.trim()) },
            synced = false,
            source = LyricsSource.USER,
            raw = saved.content,
        )
    }

    /**
     * A .lrc beside the audio file, matched on base name. Also accepts a plain
     * .txt of the same name, which is how a lot of older libraries store them.
     */
    private fun sidecar(track: Track): Lyrics? {
        val path = track.filePath ?: return null
        val base = path.substringBeforeLast('.', path)
        for (extension in SIDECAR_EXTENSIONS) {
            val file = File(base + extension)
            if (!file.isFile || file.length() > MAX_SIDECAR_BYTES) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            LrcParser.parse(text)?.let { return it }
        }
        return null
    }

    private fun embedded(track: Track): Lyrics? = runCatching {
        context.contentResolver.openInputStream(track.uri)?.use { stream ->
            EmbeddedLyricsReader.read(stream, track.mimeType, track.displayName)
        }
    }.getOrNull()

    private companion object {
        val SIDECAR_EXTENSIONS = listOf(".lrc", ".LRC", ".txt")
        const val MAX_SIDECAR_BYTES = 1L * 1024 * 1024
    }
}
