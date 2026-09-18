package com.irondigital.spindle.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class VisualizerMode {
    /** No moving background. The artwork sits on the plate and stays there. */
    OFF,

    /**
     * Colours drawn from the album art, drifting slowly. Needs no permission,
     * costs almost nothing, and is the default.
     */
    ARTWORK,

    /**
     * Genuinely driven by the audio signal via the platform Visualizer effect.
     * Requires RECORD_AUDIO, so it is opt-in and explained at the point of asking.
     */
    AUDIO_REACTIVE,
}

enum class LibrarySort { TITLE, ARTIST, ALBUM, DATE_ADDED, PLAY_COUNT, DURATION }

enum class NormalizationMode {
    /** Play every file at the level it was mastered at. */
    OFF,

    /** Even out every track against every other, regardless of album. */
    TRACK,

    /**
     * Even out albums against each other while leaving the relative loudness
     * *within* an album alone — which is what you want for anything mastered as
     * one continuous piece.
     */
    ALBUM,
}

data class Settings(
    val minTrackDurationSec: Int = 20,
    val excludedFolders: Set<String> = emptySet(),
    val visualizerMode: VisualizerMode = VisualizerMode.ARTWORK,
    val countPlaysEnabled: Boolean = true,
    /** Share of a track that must be heard before it counts as a play. */
    val playThresholdPercent: Int = 50,
    val crossfadeMs: Int = 0,
    val skipSilence: Boolean = false,
    val normalizationMode: NormalizationMode = NormalizationMode.OFF,
    /** Applied on top of the file's own ReplayGain value. */
    val normalizationPreampDb: Int = 0,
    val resumeOnHeadsetConnect: Boolean = false,
    val keepScreenOnWithLyrics: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.TITLE,
    val mostPlayedSize: Int = 100,
    val showSongInfoInPlayer: Boolean = true,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data.map { p ->
        Settings(
            minTrackDurationSec = p[Keys.MIN_DURATION] ?: 20,
            excludedFolders = p[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            visualizerMode = p[Keys.VISUALIZER]?.let { runCatching { VisualizerMode.valueOf(it) }.getOrNull() }
                ?: VisualizerMode.ARTWORK,
            countPlaysEnabled = p[Keys.COUNT_PLAYS] ?: true,
            playThresholdPercent = p[Keys.PLAY_THRESHOLD] ?: 50,
            crossfadeMs = p[Keys.CROSSFADE] ?: 0,
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            normalizationMode = p[Keys.NORMALIZATION]
                ?.let { runCatching { NormalizationMode.valueOf(it) }.getOrNull() }
                ?: NormalizationMode.OFF,
            normalizationPreampDb = p[Keys.NORMALIZATION_PREAMP] ?: 0,
            resumeOnHeadsetConnect = p[Keys.RESUME_ON_HEADSET] ?: false,
            keepScreenOnWithLyrics = p[Keys.KEEP_SCREEN_ON] ?: true,
            librarySort = p[Keys.LIBRARY_SORT]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() }
                ?: LibrarySort.TITLE,
            mostPlayedSize = p[Keys.MOST_PLAYED_SIZE] ?: 100,
            showSongInfoInPlayer = p[Keys.SHOW_SONG_INFO] ?: true,
        )
    }

    suspend fun setMinTrackDuration(seconds: Int) = put(Keys.MIN_DURATION, seconds)
    suspend fun setExcludedFolders(folders: Set<String>) = put(Keys.EXCLUDED_FOLDERS, folders)
    suspend fun setVisualizerMode(mode: VisualizerMode) = put(Keys.VISUALIZER, mode.name)
    suspend fun setCountPlaysEnabled(enabled: Boolean) = put(Keys.COUNT_PLAYS, enabled)
    suspend fun setPlayThresholdPercent(percent: Int) = put(Keys.PLAY_THRESHOLD, percent.coerceIn(10, 95))
    suspend fun setCrossfadeMs(ms: Int) = put(Keys.CROSSFADE, ms.coerceIn(0, 12_000))
    suspend fun setSkipSilence(enabled: Boolean) = put(Keys.SKIP_SILENCE, enabled)
    suspend fun setNormalizationMode(mode: NormalizationMode) = put(Keys.NORMALIZATION, mode.name)
    suspend fun setNormalizationPreamp(db: Int) = put(Keys.NORMALIZATION_PREAMP, db.coerceIn(-15, 15))
    suspend fun setResumeOnHeadsetConnect(enabled: Boolean) = put(Keys.RESUME_ON_HEADSET, enabled)
    suspend fun setKeepScreenOnWithLyrics(enabled: Boolean) = put(Keys.KEEP_SCREEN_ON, enabled)
    suspend fun setLibrarySort(sort: LibrarySort) = put(Keys.LIBRARY_SORT, sort.name)
    suspend fun setMostPlayedSize(size: Int) = put(Keys.MOST_PLAYED_SIZE, size.coerceIn(10, 500))
    suspend fun setShowSongInfoInPlayer(enabled: Boolean) = put(Keys.SHOW_SONG_INFO, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private object Keys {
        val MIN_DURATION = intPreferencesKey("min_track_duration_sec")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val VISUALIZER = stringPreferencesKey("visualizer_mode")
        val COUNT_PLAYS = booleanPreferencesKey("count_plays")
        val PLAY_THRESHOLD = intPreferencesKey("play_threshold_percent")
        val CROSSFADE = intPreferencesKey("crossfade_ms")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val NORMALIZATION = stringPreferencesKey("normalization_mode")
        val NORMALIZATION_PREAMP = intPreferencesKey("normalization_preamp_db")
        val RESUME_ON_HEADSET = booleanPreferencesKey("resume_on_headset")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_lyrics")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val MOST_PLAYED_SIZE = intPreferencesKey("most_played_size")
        val SHOW_SONG_INFO = booleanPreferencesKey("show_song_info")
    }
}
