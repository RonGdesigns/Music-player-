package com.irondigital.spindle.data.repo

import android.content.Context
import android.net.Uri
import com.irondigital.spindle.data.db.GainDao
import com.irondigital.spindle.data.db.TrackGain
import com.irondigital.spindle.data.media.ReplayGainValues
import com.irondigital.spindle.data.media.ReplayGainReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ReplayGain values for a track, read from the file once and then cached.
 *
 * The cache stores misses as well as hits. Without that, every untagged file in
 * the library would reopen itself on every single play to rediscover that it
 * still has no tags.
 */
class GainRepository(
    private val context: Context,
    private val gainDao: GainDao,
) {

    suspend fun valuesFor(mediaId: String, uri: Uri): ReplayGainValues {
        gainDao.get(mediaId)?.let { cached ->
            return ReplayGainValues(
                trackGainDb = cached.trackGainDb,
                albumGainDb = cached.albumGainDb,
                trackPeak = cached.trackPeak,
                albumPeak = cached.albumPeak,
            )
        }

        val read = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ReplayGainReader.read(stream)
                }
            }.getOrNull() ?: ReplayGainValues.NONE
        }

        gainDao.upsert(
            TrackGain(
                mediaId = mediaId,
                trackGainDb = read.trackGainDb,
                albumGainDb = read.albumGainDb,
                trackPeak = read.trackPeak,
                albumPeak = read.albumPeak,
                scannedAt = System.currentTimeMillis(),
            )
        )
        return read
    }

    /** Forces every file to be re-read, for when a user has just retagged a library. */
    suspend fun clearCache() = gainDao.clear()
}
