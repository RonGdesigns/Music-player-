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
     * Colors drawn from the album art, drifting slowly. Needs no permission,
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
    /**
     * Include audio the media scanner did not flag as music — which is most of
     * what lands in Download/.
     */
    val includeNonMusicAudio: Boolean = false,
    val visualizerMode: VisualizerMode = VisualizerMode.ARTWORK,
    val countPlaysEnabled: Boolean = true,
    /** Share of a track that must be heard before it counts as a play. */
    val playThresholdPercent: Int = 50,
    val skipSilence: Boolean = false,
    /**
     * Order a shuffle so it sounds shuffled — no two tracks by one artist in a
     * row, records kept apart — rather than uniformly at random.
     */
    val smartShuffleEnabled: Boolean = true,
    /** Bias a shuffle toward what has not been played lately. */
    val shuffleFavorsUnheard: Boolean = false,
    val equalizerEnabled: Boolean = false,
    /** A device preset index, or -1 for the user's own curve in [equalizerBands]. */
    val equalizerPreset: Int = -1,
    /** Per-band gain in millibels, in the device's own band order. */
    val equalizerBands: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val normalizationMode: NormalizationMode = NormalizationMode.OFF,
    /** Applied on top of the file's own ReplayGain value. */
    val normalizationPreampDb: Int = 0,
    val keepScreenOnWithLyrics: Boolean = true,
    /**
     * Look lyrics up online when a track has none locally. Off until asked
     * for. Requests send track metadata to the lyrics provider.
     */
    val lyricsLookupEnabled: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.TITLE,
    val mostPlayedSize: Int = 100,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data.map { p ->
        Settings(
            minTrackDurationSec = p[Keys.MIN_DURATION] ?: 20,
            excludedFolders = p[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            includeNonMusicAudio = p[Keys.INCLUDE_NON_MUSIC] ?: false,
            visualizerMode = p[Keys.VISUALIZER]?.let { runCatching { VisualizerMode.valueOf(it) }.getOrNull() }
                ?: VisualizerMode.ARTWORK,
            countPlaysEnabled = p[Keys.COUNT_PLAYS] ?: true,
            playThresholdPercent = p[Keys.PLAY_THRESHOLD] ?: 50,
            skipSilence = p[Keys.SKIP_SILENCE] ?: false,
            smartShuffleEnabled = p[Keys.SMART_SHUFFLE] ?: true,
            shuffleFavorsUnheard = p[Keys.SHUFFLE_UNHEARD] ?: false,
            equalizerEnabled = p[Keys.EQ_ENABLED] ?: false,
            equalizerPreset = p[Keys.EQ_PRESET] ?: -1,
            equalizerBands = p[Keys.EQ_BANDS]?.let(::decodeBands) ?: emptyList(),
            bassBoostStrength = p[Keys.BASS_BOOST] ?: 0,
            virtualizerStrength = p[Keys.VIRTUALIZER] ?: 0,
            normalizationMode = p[Keys.NORMALIZATION]
                ?.let { runCatching { NormalizationMode.valueOf(it) }.getOrNull() }
                ?: NormalizationMode.OFF,
            normalizationPreampDb = p[Keys.NORMALIZATION_PREAMP] ?: 0,
            keepScreenOnWithLyrics = p[Keys.KEEP_SCREEN_ON] ?: true,
            lyricsLookupEnabled = p[Keys.LYRICS_LOOKUP] ?: false,
            librarySort = p[Keys.LIBRARY_SORT]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() }
                ?: LibrarySort.TITLE,
            mostPlayedSize = p[Keys.MOST_PLAYED_SIZE] ?: 100,
        )
    }

    suspend fun setMinTrackDuration(seconds: Int) = put(Keys.MIN_DURATION, seconds)
    suspend fun setExcludedFolders(folders: Set<String>) = put(Keys.EXCLUDED_FOLDERS, folders)
    suspend fun setIncludeNonMusicAudio(enabled: Boolean) = put(Keys.INCLUDE_NON_MUSIC, enabled)
    suspend fun setVisualizerMode(mode: VisualizerMode) = put(Keys.VISUALIZER, mode.name)
    suspend fun setCountPlaysEnabled(enabled: Boolean) = put(Keys.COUNT_PLAYS, enabled)
    suspend fun setPlayThresholdPercent(percent: Int) = put(Keys.PLAY_THRESHOLD, percent.coerceIn(10, 95))
    suspend fun setSkipSilence(enabled: Boolean) = put(Keys.SKIP_SILENCE, enabled)
    suspend fun setSmartShuffle(enabled: Boolean) = put(Keys.SMART_SHUFFLE, enabled)
    suspend fun setShuffleFavorsUnheard(enabled: Boolean) = put(Keys.SHUFFLE_UNHEARD, enabled)
    suspend fun setEqualizerEnabled(enabled: Boolean) = put(Keys.EQ_ENABLED, enabled)
    suspend fun setEqualizerPreset(index: Int) = put(Keys.EQ_PRESET, index)
    suspend fun setEqualizerBands(levelsMb: List<Int>) = put(Keys.EQ_BANDS, encodeBands(levelsMb))
    suspend fun setBassBoost(strength: Int) = put(Keys.BASS_BOOST, strength.coerceIn(0, 1000))
    suspend fun setVirtualizer(strength: Int) = put(Keys.VIRTUALIZER, strength.coerceIn(0, 1000))
    suspend fun setNormalizationMode(mode: NormalizationMode) = put(Keys.NORMALIZATION, mode.name)
    suspend fun setNormalizationPreamp(db: Int) = put(Keys.NORMALIZATION_PREAMP, db.coerceIn(-15, 15))
    suspend fun setKeepScreenOnWithLyrics(enabled: Boolean) = put(Keys.KEEP_SCREEN_ON, enabled)
    suspend fun setLyricsLookupEnabled(enabled: Boolean) = put(Keys.LYRICS_LOOKUP, enabled)
    suspend fun setLibrarySort(sort: LibrarySort) = put(Keys.LIBRARY_SORT, sort.name)
    suspend fun setMostPlayedSize(size: Int) = put(Keys.MOST_PLAYED_SIZE, size.coerceIn(10, 500))

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private object Keys {
        val MIN_DURATION = intPreferencesKey("min_track_duration_sec")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val INCLUDE_NON_MUSIC = booleanPreferencesKey("include_non_music_audio")
        val VISUALIZER = stringPreferencesKey("visualizer_mode")
        val COUNT_PLAYS = booleanPreferencesKey("count_plays")
        val PLAY_THRESHOLD = intPreferencesKey("play_threshold_percent")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val SMART_SHUFFLE = booleanPreferencesKey("smart_shuffle")
        val SHUFFLE_UNHEARD = booleanPreferencesKey("shuffle_favors_unheard")
        val EQ_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQ_PRESET = intPreferencesKey("equalizer_preset")
        val EQ_BANDS = stringPreferencesKey("equalizer_bands")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val VIRTUALIZER = intPreferencesKey("virtualizer")
        val NORMALIZATION = stringPreferencesKey("normalization_mode")
        val NORMALIZATION_PREAMP = intPreferencesKey("normalization_preamp_db")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_lyrics")
        val LYRICS_LOOKUP = booleanPreferencesKey("lyrics_lookup_enabled")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val MOST_PLAYED_SIZE = intPreferencesKey("most_played_size")
    }
}

/**
 * Band gains as a comma-separated string. The band count varies by device, so a
 * fixed set of keys would not fit — and a malformed value should cost the user a
 * flat curve, not a crash on launch.
 */
private fun encodeBands(levelsMb: List<Int>): String = levelsMb.joinToString(",")

private fun decodeBands(raw: String): List<Int> =
    raw.split(',').mapNotNull { it.trim().toIntOrNull() }
