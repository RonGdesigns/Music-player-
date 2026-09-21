package com.irondigital.spindle.data.lyrics

import android.content.Context
import com.irondigital.spindle.data.db.LyricsDao
import com.irondigital.spindle.data.db.LyricsOverride
import com.irondigital.spindle.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves lyrics for a track, in priority order: what is stored for it, a
 * sidecar .lrc, then the file's own tags.
 *
 * The first three are local and always run. Looking online is last, optional,
 * and off until the user turns it on — a player that quietly ships every
 * filename you own to a lyrics API is not a default worth having. When it is
 * on, what goes out is the title, the artist and the length, and whatever comes
 * back is saved locally so a track is only ever looked up once.
 *
 * The user's timing correction is separate from all of that. It is stored per
 * track and applied to lyrics from any source, including lyrics that live
 * inside the file and are never copied here.
 */
class LyricsRepository(
    private val context: Context,
    private val lyricsDao: LyricsDao,
    private val onlineClient: OnlineLyricsClient = OnlineLyricsClient(),
) {

    /**
     * Local sources only. [lookUpOnline] is the separate, explicit step.
     */
    suspend fun load(track: Track): Lyrics = withContext(Dispatchers.IO) {
        val stored = lyricsDao.get(track.mediaId)
        val offsetMs = stored?.offsetMs ?: 0L

        val found = storedLyrics(stored)
            ?: sidecar(track)
            ?: embedded(track)
            ?: Lyrics.NONE

        found.copy(offsetMs = offsetMs)
    }

    /**
     * Whether a lookup for this track would tell us anything new. False once
     * one has already answered, so a track with no lyrics anywhere is asked
     * about once rather than on every play.
     */
    suspend fun alreadyLookedUp(track: Track): Boolean =
        lyricsDao.get(track.mediaId)?.source.let {
            it == LyricsOverride.SOURCE_ONLINE || it == LyricsOverride.SOURCE_ONLINE_NONE
        }

    /**
     * Asks the online database, and keeps whatever it says.
     *
     * A definite "nothing here" is recorded so it is not asked again. A network
     * failure is not — one busy moment on someone else's server must not
     * permanently deny a track its lyrics.
     */
    suspend fun lookUpOnline(track: Track): LyricsLookup = withContext(Dispatchers.IO) {
        val result = onlineClient.fetch(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
        )

        when (result) {
            is LyricsLookup.Found -> store(
                track = track,
                content = result.lrc,
                synced = result.synced,
                source = LyricsOverride.SOURCE_ONLINE,
            )
            LyricsLookup.NotFound -> store(
                track = track,
                content = "",
                synced = false,
                source = LyricsOverride.SOURCE_ONLINE_NONE,
            )
            is LyricsLookup.Failed -> Unit
        }
        result
    }

    suspend fun setOffset(track: Track, offsetMs: Long) {
        lyricsDao.setOffset(
            track.mediaId,
            offsetMs.coerceIn(-Lyrics.MAX_OFFSET_MS, Lyrics.MAX_OFFSET_MS),
        )
    }

    private suspend fun store(track: Track, content: String, synced: Boolean, source: String) {
        val existing = lyricsDao.get(track.mediaId)
        lyricsDao.upsert(
            LyricsOverride(
                mediaId = track.mediaId,
                content = content,
                synced = synced,
                updatedAt = System.currentTimeMillis(),
                // A correction the user already made survives new lyrics
                // arriving, because it was a correction to this recording.
                offsetMs = existing?.offsetMs ?: 0L,
                source = source,
            )
        )
    }

    suspend fun saveOverride(track: Track, content: String) {
        store(
            track = track,
            content = content,
            synced = LrcParser.parse(content)?.synced == true,
            source = LyricsOverride.SOURCE_USER,
        )
    }

    /**
     * Looks specifically for timing after the user has supplied the words.
     *
     * Unlike [lookUpOnline], this never records a miss and never replaces the
     * user's text with an unsynchronised result. That distinction matters for
     * leaked and unreleased songs: Genius may have the words while LRCLIB has
     * no entry at all. In that case the words the user just pasted must remain.
     */
    suspend fun addOnlineTimingIfAvailable(track: Track): LyricsLookup =
        withContext(Dispatchers.IO) {
            when (
                val result = onlineClient.fetch(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                )
            ) {
                is LyricsLookup.Found -> {
                    if (result.synced) {
                        store(
                            track = track,
                            content = result.lrc,
                            synced = true,
                            source = LyricsOverride.SOURCE_ONLINE,
                        )
                        result
                    } else {
                        LyricsLookup.NotFound
                    }
                }
                LyricsLookup.NotFound -> LyricsLookup.NotFound
                is LyricsLookup.Failed -> result
            }
        }

    suspend fun clearOverride(track: Track) = lyricsDao.delete(track.mediaId)

    /**
     * Lyrics held for this track, if any. A row may exist carrying nothing but
     * a timing correction, or the record of a lookup that found nothing — both
     * mean there is no content here, not that the row should win.
     */
    private fun storedLyrics(saved: LyricsOverride?): Lyrics? {
        if (saved == null || saved.content.isBlank()) return null
        val source =
            if (saved.source == LyricsOverride.SOURCE_ONLINE) LyricsSource.ONLINE
            else LyricsSource.USER

        LrcParser.parse(saved.content)?.let { return it.copy(source = source) }
        return Lyrics(
            lines = saved.content.lines().map { LyricLine(-1L, it.trim()) },
            synced = false,
            source = source,
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
            // Sidecar files come from the same places the tags do, and carry
            // the same advertising.
            LrcParser.parse(text)?.let(LyricsSanitizer::clean)?.let { return it }
        }
        return null
    }

    private fun embedded(track: Track): Lyrics? = runCatching {
        context.contentResolver.openInputStream(track.uri)?.use { stream ->
            EmbeddedLyricsReader.read(stream, track.mimeType, track.displayName)
        }
    }.getOrNull()?.let(LyricsSanitizer::clean)

    private companion object {
        val SIDECAR_EXTENSIONS = listOf(".lrc", ".LRC", ".txt")
        const val MAX_SIDECAR_BYTES = 1L * 1024 * 1024
    }
}
