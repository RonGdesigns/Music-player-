package com.irondigital.spindle.data.backup

import com.irondigital.spindle.data.tagging.TitleNormalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * One track's history, keyed so it can find its way home again.
 *
 * [key] is the important field. MediaStore hands out row ids, and those ids are
 * not stable — reinstall the app, factory reset, move to a new phone, or let the
 * media scanner rebuild, and the same file comes back under a different id. A
 * backup keyed on ids restores nothing at all, silently. So the key is derived
 * from what the *recording* is: artist, title and rounded length.
 */
data class BackupTrack(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long = 0,
    val firstPlayedAt: Long = 0,
    val msListened: Long = 0,
    val favorite: Boolean = false,
    val editedTitle: String? = null,
    val editedArtist: String? = null,
    val editedAlbum: String? = null,
    val editedYear: Int? = null,
    val editedTrackNumber: Int? = null,
    val sourceTitle: String? = null,
    val sourceArtist: String? = null,
) {
    val hasHistory: Boolean get() = playCount > 0 || skipCount > 0 || msListened > 0
    val hasEdit: Boolean
        get() = editedTitle != null || editedArtist != null || editedAlbum != null ||
            editedYear != null || editedTrackNumber != null
}

data class BackupPlaylist(
    val name: String,
    val createdAt: Long,
    /** Track keys, in order. */
    val itemKeys: List<String>,
)

data class Backup(
    val version: Int = BackupCodec.VERSION,
    val exportedAt: Long,
    val tracks: List<BackupTrack>,
    val playlists: List<BackupPlaylist>,
)

/**
 * Reads and writes the backup file.
 *
 * Plain JSON on purpose: the file is the user's, and they should be able to open
 * it, read it, and see that their listening history is just text — not a binary
 * blob that only this app can interpret.
 */
object BackupCodec {

    const val VERSION = 2

    /**
     * Length is rounded to the nearest second before it goes in the key. Two
     * scans of the same file can disagree by a few milliseconds, and a key that
     * disagrees is a key that never matches.
     */
    fun keyFor(title: String, artist: String, durationMs: Long): String {
        val seconds = (durationMs + 500) / 1000
        return buildString {
            append(TitleNormalizer.matchKey(artist))
            append('\u0000')
            append(TitleNormalizer.matchKey(title))
            append('\u0000')
            append(seconds)
        }
    }

    fun encode(backup: Backup): String = JSONObject().apply {
        put("version", backup.version)
        put("exportedAt", backup.exportedAt)
        put("tracks", JSONArray().also { array ->
            backup.tracks.forEach { track ->
                array.put(JSONObject().apply {
                    put("key", track.key)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("durationMs", track.durationMs)
                    track.sourceTitle?.let { put("sourceTitle", it) }
                    track.sourceArtist?.let { put("sourceArtist", it) }
                    if (track.playCount != 0) put("playCount", track.playCount)
                    if (track.skipCount != 0) put("skipCount", track.skipCount)
                    if (track.lastPlayedAt != 0L) put("lastPlayedAt", track.lastPlayedAt)
                    if (track.firstPlayedAt != 0L) put("firstPlayedAt", track.firstPlayedAt)
                    if (track.msListened != 0L) put("msListened", track.msListened)
                    if (track.favorite) put("favorite", true)
                    track.editedTitle?.let { put("editedTitle", it) }
                    track.editedArtist?.let { put("editedArtist", it) }
                    track.editedAlbum?.let { put("editedAlbum", it) }
                    track.editedYear?.let { put("editedYear", it) }
                    track.editedTrackNumber?.let { put("editedTrackNumber", it) }
                })
            }
        })
        put("playlists", JSONArray().also { array ->
            backup.playlists.forEach { playlist ->
                array.put(JSONObject().apply {
                    put("name", playlist.name)
                    put("createdAt", playlist.createdAt)
                    put("items", JSONArray().also { items ->
                        playlist.itemKeys.forEach(items::put)
                    })
                })
            }
        })
    }.toString(2)

    /**
     * Returns null when the text is not a Spindle backup at all. A file that
     * parses but carries a newer version is refused rather than half-read —
     * restoring only the fields this build happens to recognize would quietly
     * lose the rest.
     */
    fun decode(text: String): Backup? = runCatching {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        if (version <= 0 || version > VERSION) return null

        val trackArray = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until trackArray.length()).mapNotNull { i ->
            val item = trackArray.optJSONObject(i) ?: return@mapNotNull null
            val key = item.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackupTrack(
                key = key,
                title = item.optString("title"),
                artist = item.optString("artist"),
                durationMs = item.optLong("durationMs"),
                playCount = item.optInt("playCount"),
                skipCount = item.optInt("skipCount"),
                lastPlayedAt = item.optLong("lastPlayedAt"),
                firstPlayedAt = item.optLong("firstPlayedAt"),
                msListened = item.optLong("msListened"),
                favorite = item.optBoolean("favorite"),
                editedTitle = item.optStringOrNull("editedTitle"),
                editedArtist = item.optStringOrNull("editedArtist"),
                editedAlbum = item.optStringOrNull("editedAlbum"),
                editedYear = item.optIntOrNull("editedYear"),
                editedTrackNumber = item.optIntOrNull("editedTrackNumber"),
                sourceTitle = item.optStringOrNull("sourceTitle"),
                sourceArtist = item.optStringOrNull("sourceArtist"),
            )
        }

        val playlistArray = root.optJSONArray("playlists") ?: JSONArray()
        val playlists = (0 until playlistArray.length()).mapNotNull { i ->
            val item = playlistArray.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val itemArray = item.optJSONArray("items") ?: JSONArray()
            BackupPlaylist(
                name = name,
                createdAt = item.optLong("createdAt"),
                itemKeys = (0 until itemArray.length()).map { itemArray.optString(it) }
                    .filter { it.isNotBlank() },
            )
        }

        Backup(version, root.optLong("exportedAt"), tracks, playlists)
    }.getOrNull()

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name).takeIf { it != 0 } else null
}
