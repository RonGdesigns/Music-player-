package com.irondigital.spindle.data.personal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.irondigital.spindle.data.db.PlayStat
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.playback.PlaybackSnapshot
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.listeningDataStore by preferencesDataStore("listening_workspace")

data class ListeningSession(val id: String, val name: String, val snapshot: PlaybackSnapshot, val updatedAt: Long)
data class PinnedCollection(val kind: String, val key: String, val name: String) {
    val id get() = "$kind:$key"
}
data class TrackBookmark(val id: String = UUID.randomUUID().toString(), val mediaId: String,
    val title: String, val positionMs: Long, val label: String)
enum class MixSort { TITLE, RECENTLY_ADDED, LEAST_PLAYED }
data class MixRule(val id: String = UUID.randomUUID().toString(), val name: String,
    val favoritesOnly: Boolean = false, val unplayedDays: Int = 0, val addedDays: Int = 0,
    val maxDurationMinutes: Int = 0, val sort: MixSort = MixSort.TITLE) {
    fun matches(track: Track, favorite: Boolean, stat: PlayStat?, now: Long): Boolean {
        if (favoritesOnly && !favorite) return false
        val day = 86_400_000L
        if (unplayedDays > 0 && (stat?.lastPlayedAt ?: 0) > now - unplayedDays * day) return false
        if (addedDays > 0 && track.dateAddedSec * 1000 < now - addedDays * day) return false
        return maxDurationMinutes <= 0 || track.durationMs <= maxDurationMinutes * 60_000L
    }
    fun select(tracks: List<Track>, favorites: Set<String>, stats: Map<String, PlayStat>, now: Long): List<Track> {
        val matches = tracks.filter { matches(it, it.mediaId in favorites, stats[it.mediaId], now) }
        return when (sort) {
            MixSort.TITLE -> matches.sortedBy { it.title.lowercase() }
            MixSort.RECENTLY_ADDED -> matches.sortedByDescending { it.dateAddedSec }
            MixSort.LEAST_PLAYED -> matches.sortedWith(compareBy({ stats[it.mediaId]?.playCount ?: 0 }, { it.title }))
        }
    }
}
enum class WidgetStyle { QUEUE, ARTWORK }
enum class WidgetDensity { COMFORTABLE, COMPACT }
data class WidgetAppearance(val style: WidgetStyle = WidgetStyle.QUEUE,
    val density: WidgetDensity = WidgetDensity.COMFORTABLE)
data class ListeningData(val sessions: List<ListeningSession> = emptyList(), val pins: List<PinnedCollection> = emptyList(),
    val bookmarks: List<TrackBookmark> = emptyList(), val mixes: List<MixRule> = emptyList(),
    val widget: WidgetAppearance = WidgetAppearance(), val activeSessionId: String? = null) {
    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("activeSessionId", activeSessionId)
        put("sessions", JSONArray().apply { sessions.forEach { put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("updatedAt", it.updatedAt); put("snapshot", JSONObject(it.snapshot.toJson()))
        }) } })
        put("pins", JSONArray().apply { pins.forEach { put(JSONObject().apply {
            put("kind", it.kind); put("key", it.key); put("name", it.name)
        }) } })
        put("bookmarks", JSONArray().apply { bookmarks.forEach { put(JSONObject().apply {
            put("id", it.id); put("mediaId", it.mediaId); put("title", it.title); put("positionMs", it.positionMs); put("label", it.label)
        }) } })
        put("mixes", JSONArray().apply { mixes.forEach { put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("favoritesOnly", it.favoritesOnly); put("unplayedDays", it.unplayedDays)
            put("addedDays", it.addedDays); put("maxDurationMinutes", it.maxDurationMinutes); put("sort", it.sort.name)
        }) } })
        put("widgetStyle", widget.style.name); put("widgetDensity", widget.density.name)
    }.toString()
    companion object {
        fun fromJson(raw: String?): ListeningData {
            if (raw.isNullOrBlank()) return ListeningData()
            val j = JSONObject(raw)
            require(j.optInt("version", 1) == 1) { "This listening data needs a newer version of Spindle" }
            fun rows(key: String): List<JSONObject> = (j.optJSONArray(key) ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
            }
            return ListeningData(
                sessions = rows("sessions").map { ListeningSession(it.getString("id"), it.getString("name"),
                    PlaybackSnapshot.fromJson(it.getJSONObject("snapshot").toString()), it.optLong("updatedAt")) },
                pins = rows("pins").map { PinnedCollection(it.getString("kind"), it.getString("key"), it.getString("name")) },
                bookmarks = rows("bookmarks").map { TrackBookmark(it.getString("id"), it.getString("mediaId"),
                    it.getString("title"), it.optLong("positionMs").coerceAtLeast(0), it.getString("label")) },
                mixes = rows("mixes").map { MixRule(it.getString("id"), it.getString("name"), it.optBoolean("favoritesOnly"),
                    it.optInt("unplayedDays").coerceIn(0,3650), it.optInt("addedDays").coerceIn(0,3650),
                    it.optInt("maxDurationMinutes").coerceIn(0,1440),
                    runCatching { MixSort.valueOf(it.optString("sort")) }.getOrDefault(MixSort.TITLE)) },
                widget = WidgetAppearance(runCatching { WidgetStyle.valueOf(j.optString("widgetStyle")) }.getOrDefault(WidgetStyle.QUEUE),
                    runCatching { WidgetDensity.valueOf(j.optString("widgetDensity")) }.getOrDefault(WidgetDensity.COMFORTABLE)),
                activeSessionId = j.optString("activeSessionId").takeUnless { it.isBlank() || it == "null" },
            )
        }
    }
}

class ListeningStore(private val context: Context) {
    private val key = stringPreferencesKey("workspace")
    val data = context.listeningDataStore.data.map { ListeningData.fromJson(it[key]) }
    suspend fun update(change: (ListeningData) -> ListeningData) {
        context.listeningDataStore.edit { p -> p[key] = change(ListeningData.fromJson(p[key])).toJson() }
    }
    suspend fun saveSession(id: String, name: String, snapshot: PlaybackSnapshot) {
        require(name.trim().isNotEmpty()) { "Give the session a name" }
        require(snapshot.hasContent && snapshot.queue.isNotEmpty()) { "Play some music before saving a session" }
        update { data -> data.copy(sessions = listOf(ListeningSession(id, name.trim().take(80),
            snapshot.copy(isPlaying = false), System.currentTimeMillis())) + data.sessions.filterNot { it.id == id }, activeSessionId = id) }
    }

}

/** Saved sessions omit unavailable files while preserving occurrences and the surviving playhead. */
fun PlaybackSnapshot.availableSession(available: Set<String>): PlaybackSnapshot {
    val kept = queue.withIndex().filter { it.value.mediaId in available }
    val remap = kept.mapIndexed { i, entry -> entry.index to i }.toMap()
    val current = remap[currentIndex] ?: 0
    return copy(queue = kept.map { it.value }, currentIndex = if (kept.isEmpty()) -1 else current,
        currentMediaId = kept.getOrNull(current)?.value?.mediaId,
        title = kept.getOrNull(current)?.value?.title.orEmpty(), artist = kept.getOrNull(current)?.value?.artist.orEmpty(),
        album = kept.getOrNull(current)?.value?.album.orEmpty(), artUri = kept.getOrNull(current)?.value?.artUri,
        durationMs = kept.getOrNull(current)?.value?.durationMs ?: 0,
        positionMs = if (currentIndex in remap) positionMs else 0,
        playbackOrder = validPlaybackOrder().mapNotNull { remap[it] })
}

data class LoopRegion(val mediaId: String, val startMs: Long, val endMs: Long) {
    fun validFor(id: String?, durationMs: Long) = mediaId == id && startMs >= 0 && endMs - startMs >= 500 && endMs < durationMs
    fun shouldSeek(id: String?, position: Long, playing: Boolean) = playing && id == mediaId && position >= endMs
}
