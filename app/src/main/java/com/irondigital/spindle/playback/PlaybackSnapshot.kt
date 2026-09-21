package com.irondigital.spindle.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One row in the widget's queue list. */
data class QueueEntry(
    val mediaId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val album: String = "",
    val artUri: String? = null,
    val uri: String? = null,
)

/**
 * Everything the home-screen widget needs to draw itself, and everything the
 * service needs to put the queue back after being killed.
 *
 * Widgets outlive processes: the launcher may ask for a render hours after the
 * app was last in memory. So this is written to disk on every meaningful
 * playback change rather than held in the service, and it doubles as the
 * restore point — which is what lets the widget's play button work when nothing
 * is running.
 */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val currentMediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The full queue, for restore. The widget only renders a window of it. */
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val isFavorite: Boolean = false,
    /**
     * The player's audio session, needed by the opt-in audio-reactive
     * visualizer. It lives on the ExoPlayer instance inside the service, so
     * this snapshot is how the UI process learns about it.
     */
    val audioSessionId: Int = 0,
    val updatedAt: Long = 0,
    /** Full traversal order, retaining timeline indices for widget actions. */
    val playbackOrder: List<Int> = emptyList(),
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
) {
    val hasContent: Boolean get() = currentMediaId != null

    /**
     * The slice the widget shows: what is playing now and what follows. Looking
     * backwards is what the app is for; a widget's job is "what is next".
     */
    fun upcoming(count: Int): List<IndexedValue<QueueEntry>> {
        if (currentIndex !in queue.indices || count <= 0) return emptyList()
        return upcomingIndices().take(count).map { IndexedValue(it, queue[it]) }
    }

    /** How many tracks are queued beyond what [upcoming] returned. */
    fun remainingAfter(count: Int): Int {
        return (upcomingIndices().size - count.coerceAtLeast(0)).coerceAtLeast(0)
    }

    private fun upcomingIndices(): List<Int> {
        if (currentIndex !in queue.indices) return emptyList()
        if (repeatMode == 1) return listOf(currentIndex)
        val order = validPlaybackOrder()
        val offset = order.indexOf(currentIndex)
        return if (repeatMode == 2) order.drop(offset) + order.take(offset) else order.drop(offset)
    }

    fun validPlaybackOrder(): List<Int> =
        playbackOrder.takeIf { it.size == queue.size && it.toSet() == queue.indices.toSet() }
            ?: queue.indices.toList()

    fun toJson(): String = JSONObject().apply {
        put("loopStartMs", loopStartMs)
        put("loopEndMs", loopEndMs)
        put("isPlaying", isPlaying)
        put("currentMediaId", currentMediaId ?: JSONObject.NULL)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("artUri", artUri ?: JSONObject.NULL)
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("currentIndex", currentIndex)
        put("shuffleEnabled", shuffleEnabled)
        put("repeatMode", repeatMode)
        put("isFavorite", isFavorite)
        put("audioSessionId", audioSessionId)
        put("updatedAt", updatedAt)
        put("playbackOrder", JSONArray(playbackOrder))
        put(
            "queue",
            JSONArray().also { array ->
                queue.forEach { entry ->
                    array.put(
                        JSONObject().apply {
                            put("mediaId", entry.mediaId)
                            put("title", entry.title)
                            put("artist", entry.artist)
                            put("durationMs", entry.durationMs)
                            put("album", entry.album)
                            put("artUri", entry.artUri ?: JSONObject.NULL)
                            put("uri", entry.uri ?: JSONObject.NULL)
                        }
                    )
                }
            }
        )
    }.toString()

    companion object {
        val EMPTY = PlaybackSnapshot()

        fun fromJson(raw: String?): PlaybackSnapshot {
            if (raw.isNullOrBlank()) return EMPTY
            return runCatching {
                val json = JSONObject(raw)
                val queueArray = json.optJSONArray("queue") ?: JSONArray()
                val queue = (0 until queueArray.length()).map { i ->
                    val item = queueArray.getJSONObject(i)
                    QueueEntry(
                        mediaId = item.optString("mediaId"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        durationMs = item.optLong("durationMs"),
                        album = item.optString("album"),
                        artUri = item.optString("artUri").takeUnless { it.isBlank() || it == "null" },
                        uri = item.optString("uri").takeUnless { it.isBlank() || it == "null" },
                    )
                }
                PlaybackSnapshot(
                    loopStartMs = json.optLong("loopStartMs", -1).takeIf { it >= 0 },
                    loopEndMs = json.optLong("loopEndMs", -1).takeIf { it >= 0 },
                    isPlaying = json.optBoolean("isPlaying"),
                    currentMediaId = json.optString("currentMediaId").takeIf { it.isNotBlank() && it != "null" },
                    title = json.optString("title"),
                    artist = json.optString("artist"),
                    album = json.optString("album"),
                    artUri = json.optString("artUri").takeIf { it.isNotBlank() && it != "null" },
                    positionMs = json.optLong("positionMs"),
                    durationMs = json.optLong("durationMs"),
                    queue = queue,
                    currentIndex = json.optInt("currentIndex", -1),
                    shuffleEnabled = json.optBoolean("shuffleEnabled"),
                    repeatMode = json.optInt("repeatMode"),
                    isFavorite = json.optBoolean("isFavorite"),
                    audioSessionId = json.optInt("audioSessionId"),
                    updatedAt = json.optLong("updatedAt"),
                    playbackOrder = json.optJSONArray("playbackOrder")?.let { order ->
                        (0 until order.length()).map { order.optInt(it, -1) }
                    }.orEmpty(),
                )
            }.getOrDefault(EMPTY)
        }
    }
}

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore("playback_state")

class PlaybackSnapshotStore(private val context: Context) {

    val snapshot: Flow<PlaybackSnapshot> =
        context.playbackDataStore.data.map { PlaybackSnapshot.fromJson(it[KEY]) }

    suspend fun write(snapshot: PlaybackSnapshot) {
        context.playbackDataStore.edit { it[KEY] = snapshot.toJson() }
    }

    private companion object {
        val KEY = stringPreferencesKey("snapshot")
    }
}
