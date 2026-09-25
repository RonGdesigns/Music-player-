package com.irondigital.spindle.ui.settings

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondigital.spindle.data.settings.LibrarySort
import com.irondigital.spindle.data.settings.NormalizationMode
import com.irondigital.spindle.data.settings.Settings
import com.irondigital.spindle.data.settings.VisualizerMode
import com.irondigital.spindle.spindle
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.SignalRed
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel
import com.irondigital.spindle.ui.tools.BackupControls
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.spindle
    private val store = app.settingsStore

    val settings: StateFlow<Settings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    fun setVisualizer(mode: VisualizerMode) = edit { store.setVisualizerMode(mode) }
    fun setCountPlays(enabled: Boolean) = edit { store.setCountPlaysEnabled(enabled) }
    fun setThreshold(percent: Int) = edit { store.setPlayThresholdPercent(percent) }
    fun setMinDuration(seconds: Int) = edit { store.setMinTrackDuration(seconds) }
    fun setIncludeNonMusicAudio(enabled: Boolean) = edit { store.setIncludeNonMusicAudio(enabled) }
    fun setMostPlayedSize(size: Int) = edit { store.setMostPlayedSize(size) }
    fun setSkipSilence(enabled: Boolean) = edit { store.setSkipSilence(enabled) }
    fun setSmartShuffle(enabled: Boolean) = edit { store.setSmartShuffle(enabled) }
    fun setShuffleFavorsUnheard(enabled: Boolean) = edit { store.setShuffleFavorsUnheard(enabled) }
    fun setNormalization(mode: NormalizationMode) = edit { store.setNormalizationMode(mode) }
    fun setPreamp(db: Int) = edit { store.setNormalizationPreamp(db) }
    fun rescanGain() = edit { app.gains.clearCache() }
    fun setKeepScreenOn(enabled: Boolean) = edit { store.setKeepScreenOnWithLyrics(enabled) }
    fun setLyricsLookup(enabled: Boolean) = edit { store.setLyricsLookupEnabled(enabled) }
    fun setSort(sort: LibrarySort) = edit { store.setLibrarySort(sort) }

    fun resetStatistics() = edit { app.stats.resetEverything() }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenAutoTag: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenBatchEdit: () -> Unit,
    onOpenSaveToFiles: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmingReset by remember { mutableStateOf(false) }
    val interruptedSaves by context.spindle.tagSaver.pendingCount.collectAsStateWithLifecycle()

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Only commit to the reactive mode if the permission actually arrived.
        if (granted) viewModel.setVisualizer(VisualizerMode.AUDIO_REACTIVE)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.s, end = Space.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Text("Settings", style = SpindleType.ScreenTitle, color = Ink.Primary)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = Space.xxl)) {

            item {
                SettingsSection("Visualizer") {
                    Text(
                        text = "What moves behind the cover art while a track plays.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.m))

                    ChoiceRow(
                        title = "Artwork colors",
                        description = "Colors drawn from the cover, drifting slowly. " +
                            "No permissions, negligible battery.",
                        selected = settings.visualizerMode == VisualizerMode.ARTWORK,
                        onClick = { viewModel.setVisualizer(VisualizerMode.ARTWORK) },
                    )
                    ChoiceRow(
                        title = "Audio reactive",
                        description = "Moves with the music itself. Android gates the " +
                            "audio-analysis API behind the microphone permission, so " +
                            "this mode has to ask for it. Spindle never records " +
                            "anything — the permission is only what unlocks the API.",
                        selected = settings.visualizerMode == VisualizerMode.AUDIO_REACTIVE,
                        onClick = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                    ChoiceRow(
                        title = "Off",
                        description = "A still ground. Lowest power draw.",
                        selected = settings.visualizerMode == VisualizerMode.OFF,
                        onClick = { viewModel.setVisualizer(VisualizerMode.OFF) },
                    )
                }
            }

            item {
                SettingsSection("Play counts") {
                    SwitchRow(
                        title = "Count plays",
                        description = "Off means Most Played and On Repeat stop updating.",
                        checked = settings.countPlaysEnabled,
                        onCheckedChange = viewModel::setCountPlays,
                    )

                    Spacer(Modifier.height(Space.m))
                    SliderRow(
                        title = "Counts as a play after",
                        value = settings.playThresholdPercent.toFloat(),
                        range = 10f..95f,
                        steps = 16,
                        format = { "${it.toInt()}% of the track" },
                        onChange = { viewModel.setThreshold(it.toInt()) },
                    )
                    Text(
                        text = "Or four minutes, whichever comes first — so a long " +
                            "track does not need finishing to count.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )

                    Spacer(Modifier.height(Space.m))
                    SliderRow(
                        title = "Most Played holds",
                        value = settings.mostPlayedSize.toFloat(),
                        range = 10f..500f,
                        steps = 48,
                        format = { "${it.toInt()} tracks" },
                        onChange = { viewModel.setMostPlayedSize(it.toInt()) },
                    )

                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = "Reset all play counts",
                        style = SpindleType.RowTitle,
                        color = SignalRed,
                        modifier = Modifier
                            .clickable { confirmingReset = true }
                            .padding(vertical = Space.s),
                    )
                }
            }

            item {
                SettingsSection("Library") {
                    SliderRow(
                        title = "Ignore tracks shorter than",
                        value = settings.minTrackDurationSec.toFloat(),
                        range = 0f..120f,
                        steps = 23,
                        format = { if (it < 1f) "No minimum" else "${it.toInt()} seconds" },
                        onChange = { viewModel.setMinDuration(it.toInt()) },
                    )
                    Text(
                        text = "Keeps interludes, voice memos and ringtone fragments " +
                            "out of the library.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )

                    Spacer(Modifier.height(Space.m))
                    SwitchRow(
                        title = "Include audio outside the Music folder",
                        description = "Android only marks a file as music when its " +
                            "scanner decides to, and audio that lands in Download " +
                            "usually misses out — so it never shows up here. Turn " +
                            "this on to include anything that is not a ringtone, " +
                            "alarm or notification. Rescan afterwards.",
                        checked = settings.includeNonMusicAudio,
                        onCheckedChange = {
                            viewModel.setIncludeNonMusicAudio(it)
                            onRescan()
                        },
                    )

                    Spacer(Modifier.height(Space.m))
                    Text("Sort songs by", style = SpindleType.RowTitle, color = Ink.Primary)
                    Spacer(Modifier.height(Space.s))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LibrarySort.entries.take(3).forEach { sort ->
                            SortChip(
                                sort = sort,
                                selected = settings.librarySort == sort,
                                onClick = { viewModel.setSort(sort) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LibrarySort.entries.drop(3).forEach { sort ->
                            SortChip(
                                sort = sort,
                                selected = settings.librarySort == sort,
                                onClick = { viewModel.setSort(sort) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = "Rescan the library now",
                        style = SpindleType.RowTitle,
                        color = Lamp.Bright,
                        modifier = Modifier
                            .clickable {
                                scope.launch { onRescan() }
                            }
                            .padding(vertical = Space.s),
                    )
                }
            }

            item {
                SettingsSection("Sound") {
                    Text(
                        text = "Shapes the sound on its way out, using the effects " +
                            "this device provides. Every phone offers a different " +
                            "set of bands, so the screen is built from what yours " +
                            "actually has.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.s))
                    LinkRow("Open the equalizer", onOpenEqualizer)
                }
            }

            item {
                SettingsSection("Volume") {
                    Text(
                        text = "Evens out the difference between a quiet album and a " +
                            "loud one, using the ReplayGain values already in your " +
                            "files. Nothing is analyzed or re-encoded, and files " +
                            "without those tags simply play as they are.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.m))

                    ChoiceRow(
                        title = "Off",
                        description = "Every file plays at the level it was mastered at.",
                        selected = settings.normalizationMode == NormalizationMode.OFF,
                        onClick = { viewModel.setNormalization(NormalizationMode.OFF) },
                    )
                    ChoiceRow(
                        title = "Match tracks",
                        description = "Every track against every other. Best on shuffle.",
                        selected = settings.normalizationMode == NormalizationMode.TRACK,
                        onClick = { viewModel.setNormalization(NormalizationMode.TRACK) },
                    )
                    ChoiceRow(
                        title = "Match albums",
                        description = "Levels albums against each other but leaves the " +
                            "loud and quiet passages within an album alone. Best for " +
                            "anything mastered as one continuous piece.",
                        selected = settings.normalizationMode == NormalizationMode.ALBUM,
                        onClick = { viewModel.setNormalization(NormalizationMode.ALBUM) },
                    )

                    if (settings.normalizationMode != NormalizationMode.OFF) {
                        Spacer(Modifier.height(Space.m))
                        SliderRow(
                            title = "Pre-amp",
                            value = settings.normalizationPreampDb.toFloat(),
                            range = -15f..15f,
                            steps = 29,
                            format = {
                                val db = it.toInt()
                                if (db > 0) "+$db dB" else "$db dB"
                            },
                            onChange = { viewModel.setPreamp(it.toInt()) },
                        )
                        Text(
                            text = "Applied on top of each file's own value. A boost is " +
                                "cut back automatically where the track would otherwise " +
                                "clip.",
                            style = SpindleType.Data,
                            color = Steel.Dim,
                        )

                        Spacer(Modifier.height(Space.m))
                        Text(
                            text = "Re-read gain tags",
                            style = SpindleType.RowTitle,
                            color = Lamp.Bright,
                            modifier = Modifier
                                .clickable { viewModel.rescanGain() }
                                .padding(vertical = Space.s),
                        )
                        Text(
                            text = "Values are cached after the first play. Use this if " +
                                "you have just retagged your library.",
                            style = SpindleType.Data,
                            color = Steel.Dim,
                        )
                    }
                }
            }

            item {
                SettingsSection("Playback") {
                    SwitchRow(
                        title = "Skip silence",
                        description = "Trims dead air at the start and end of a track. " +
                            "Useful on ripped vinyl, distracting on a gapless album.",
                        checked = settings.skipSilence,
                        onCheckedChange = viewModel::setSkipSilence,
                    )
                    SwitchRow(
                        title = "Shuffle that sounds shuffled",
                        description = "A uniform shuffle is random, which is not the " +
                            "same as feeling random — it regularly puts two tracks by " +
                            "one artist together. This deals the queue out so that " +
                            "does not happen, and keeps tracks from one record apart. " +
                            "Turn it off for a plain random order.",
                        checked = settings.smartShuffleEnabled,
                        onCheckedChange = viewModel::setSmartShuffle,
                    )
                    if (settings.smartShuffleEnabled) {
                        SwitchRow(
                            title = "Favor what you have not heard lately",
                            description = "Brings tracks you have never played, or " +
                                "have not played in a while, toward the front of a " +
                                "shuffle. Everything still gets played.",
                            checked = settings.shuffleFavorsUnheard,
                            onCheckedChange = viewModel::setShuffleFavorsUnheard,
                        )
                    }
                    SwitchRow(
                        title = "Keep the screen on for lyrics",
                        description = "Only while the lyrics pane is open.",
                        checked = settings.keepScreenOnWithLyrics,
                        onCheckedChange = viewModel::setKeepScreenOn,
                    )
                }
            }

            item {
                SettingsSection("Lyrics") {
                    Text(
                        text = "Spindle reads a .lrc file sitting beside a track and " +
                            "lyrics stored in the track's own tags. Both are local and " +
                            "always on.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.m))
                    SwitchRow(
                        title = "Look up lyrics online",
                        description = "When a track has none of its own, ask an open " +
                            "lyrics database. The title, artist, album, and length " +
                            "of that track are sent to the provider, and what " +
                            "comes back is saved on the phone so a track is only " +
                            "looked up once. Off unless you turn it on.",
                        checked = settings.lyricsLookupEnabled,
                        onCheckedChange = viewModel::setLyricsLookup,
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(
                        text = "Lyrics that arrive a fraction of a second early or " +
                            "late can be nudged into place from the lyrics pane, and " +
                            "the correction is remembered per track.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                }
            }

            item {
                SettingsSection("Listening") {
                    Text(
                        text = "See your play counts, streaks and listening hours " +
                            "drawn out.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(
                        text = "Open listening stats",
                        style = SpindleType.RowTitle,
                        color = Lamp.Bright,
                        modifier = Modifier
                            .clickable(onClick = onOpenStats)
                            .padding(vertical = Space.s),
                    )
                }
            }

            item {
                SettingsSection("Library tools") {
                    Text(
                        text = "The jobs a library assembled from downloads always " +
                            "needs and nobody ever does by hand.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.s))

                    ToolRow(
                        title = "Fix names",
                        description = "Reads the artist and title out of the filename " +
                            "for tracks that arrived with no tags. Shows every " +
                            "proposal before changing anything.",
                        onClick = onOpenAutoTag,
                    )
                    ToolRow(
                        title = "Edit several at once",
                        description = "Sets one artist, album or year across a whole " +
                            "selection — which is the only practical way to fix a " +
                            "compilation.",
                        onClick = onOpenBatchEdit,
                    )
                    ToolRow(
                        title = "Find duplicates",
                        description = "The same recording sitting in the library " +
                            "twice. A remix or a live cut is never grouped with the " +
                            "original.",
                        onClick = onOpenDuplicates,
                    )
                    ToolRow(
                        title = "Save into files",
                        description = "Writes your corrections into the files themselves, " +
                            "so other players and your car see them too. Checked " +
                            "before and after, and undoable.",
                        onClick = onOpenSaveToFiles,
                    )
                    if (interruptedSaves > 0) {
                        Text(
                            text = "A save into a file was interrupted. Open Save into " +
                                "files to put it right.",
                            style = SpindleType.Secondary,
                            color = SignalRed,
                        )
                    }
                }
            }

            item {
                SettingsSection("Backup") {
                    BackupControls()
                }
            }

            item {
                SettingsSection("About") {
                    Text(
                        text = "Spindle plays what is already on your phone. There is " +
                            "no account or sync. Your library, play counts, and playlists " +
                            "stay on this device. Link downloads use the internet when " +
                            "you start them; online lyrics are optional.",
                        style = SpindleType.Body,
                        color = Steel.Bright,
                    )
                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = "Typefaces: Barlow and IBM Plex Mono, both SIL Open " +
                            "Font License.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                }
            }
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = Ground.Plate,
            title = { Text("Reset play counts?", style = SpindleType.Section, color = Ink.Primary) },
            text = {
                Text(
                    text = "Every play count and the whole listening history go. " +
                        "Most Played, On Repeat and Forgotten Favorites start from " +
                        "nothing. Your playlists and favorites are untouched. This " +
                        "cannot be undone.",
                    style = SpindleType.Body,
                    color = Steel.Bright,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetStatistics()
                    confirmingReset = false
                }) {
                    Text("Reset", color = SignalRed, style = SpindleType.RowTitle)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text("Keep them", color = Lamp.Bright, style = SpindleType.RowTitle)
                }
            },
        )
    }
}

/** A plain way through to another screen. Amber, because it is the live thing. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = SpindleType.RowTitle,
        color = Lamp.Bright,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
    )
}

/**
 * A tool, with what it does said before it is opened. Each of these changes the
 * library, and one of them deletes files, so none of them is a bare label.
 */
@Composable
private fun ToolRow(title: String, description: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
    ) {
        Text(title, style = SpindleType.RowTitle, color = Lamp.Bright)
        Text(description, style = SpindleType.Secondary, color = Steel.Dim)
    }
}

@Composable
private fun SettingsSection(heading: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
        Spacer(Modifier.height(Space.l))
        TickScale(height = 10.dp, spacing = 6.dp)
        Spacer(Modifier.height(Space.m))
        Text(text = heading, style = SpindleType.Section, color = Ink.Primary)
        Spacer(Modifier.height(Space.s))
        content()
        Spacer(Modifier.height(Space.m))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = SpindleType.RowTitle, color = Ink.Primary)
            Text(description, style = SpindleType.Secondary, color = Steel.Dim)
        }
        Spacer(Modifier.width(Space.m))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink.OnLamp,
                checkedTrackColor = Lamp.Bright,
                uncheckedThumbColor = Steel.Dim,
                uncheckedTrackColor = Ground.Raised,
                uncheckedBorderColor = Steel.Engrave,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
    ) {
        // A lit groove rather than a radio dot: the same selection language as
        // the queue, the tabs and the lyrics.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (selected) 40.dp else 18.dp)
                .background(if (selected) Lamp.Bright else Steel.Engrave)
        )
        Spacer(Modifier.width(Space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SpindleType.RowTitle,
                color = if (selected) Lamp.Bright else Ink.Primary,
            )
            Text(description, style = SpindleType.Secondary, color = Steel.Dim)
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = SpindleType.RowTitle, color = Ink.Primary)
            Spacer(Modifier.weight(1f))
            Text(format(value), style = SpindleType.DataEmphasis, color = Lamp.Bright)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Lamp.Bright,
                activeTrackColor = Lamp.Bright,
                inactiveTrackColor = Steel.Engrave,
                activeTickColor = Ground.Deep,
                inactiveTickColor = Steel.Engrave,
            ),
        )
    }
}

@Composable
private fun SortChip(
    sort: LibrarySort,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(if (selected) Lamp.Bright else Ground.Raised)
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (sort) {
                LibrarySort.TITLE -> "Title"
                LibrarySort.ARTIST -> "Artist"
                LibrarySort.ALBUM -> "Album"
                LibrarySort.DATE_ADDED -> "Added"
                LibrarySort.PLAY_COUNT -> "Plays"
                LibrarySort.DURATION -> "Length"
            },
            style = SpindleType.Secondary,
            color = if (selected) Ink.OnLamp else Steel.Bright,
        )
    }
}
