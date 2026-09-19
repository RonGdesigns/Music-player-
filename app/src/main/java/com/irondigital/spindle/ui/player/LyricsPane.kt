package com.irondigital.spindle.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.irondigital.spindle.data.lyrics.Lyrics
import com.irondigital.spindle.data.lyrics.LyricsSource
import com.irondigital.spindle.ui.LyricsLookupState
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/** How far below the top edge the line being sung sits while it follows. */
private val FOLLOW_INSET = 96.dp

/**
 * Lyrics, synced where the file provides timings.
 *
 * The active line is lit and everything else dims — the same lamp language as
 * the transport and the queue, so "this is the live one" reads identically
 * everywhere in the app. Tapping a timed line seeks to it, which turns the
 * lyrics into a navigation surface rather than a read-only panel.
 */
@Composable
fun LyricsPane(
    playerViewModel: PlayerViewModel,
    onSeek: (Long) -> Unit,
) {
    val lyrics by playerViewModel.lyrics.collectAsStateWithLifecycle()
    val playbackState = playerViewModel.playback.collectAsStateWithLifecycle()
    val track by playerViewModel.currentTrack.collectAsStateWithLifecycle()

    val lookup by playerViewModel.lyricsLookup.collectAsStateWithLifecycle()
    val settings by playerViewModel.settings.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Derived rather than computed from an unwrapped position.
    //
    // The playhead moves four times a second; which line is lit changes every
    // few seconds. Reading the position directly rebuilt this whole pane —
    // every visible line, every color animation, the list itself — on every
    // tick, including while it was mid-scroll. Behind a derived state the pane
    // only recomposes when the answer actually changes, which is the one thing
    // that should move it.
    val activeIndex by remember(lyrics) {
        derivedStateOf { lyrics.activeIndexAt(playbackState.value.positionMs) }
    }

    // Keeps the current line below the top edge rather than at it, so the next
    // few lines are always visible — which is the whole point of following
    // along. In dp: this was raw pixels, so how far down the line actually sat
    // depended on the density of the screen it was running on.
    val followOffsetPx = with(LocalDensity.current) { -FOLLOW_INSET.roundToPx() }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.synced) {
            listState.animateScrollToItem(
                index = activeIndex.coerceAtLeast(0),
                scrollOffset = followOffsetPx,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (lyrics.isEmpty) {
            NoLyrics(
                hasTrack = track != null,
                lookupEnabled = settings.lyricsLookupEnabled,
                lookup = lookup,
                onAdd = { editing = true },
                onLookUp = { track?.let(playerViewModel::lookUpLyrics) },
                onEnableLookup = { track?.let(playerViewModel::enableLookupAndSearch) },
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = Space.xl,
                    bottom = Space.huge,
                ),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = lyrics.synced && index < activeIndex

                    val color by animateColorAsState(
                        targetValue = when {
                            !lyrics.synced -> Ink.Primary
                            isActive -> Lamp.Bright
                            isPast -> Steel.Engrave
                            else -> Steel.Bright
                        },
                        animationSpec = if (isActive) Motion.lampOn() else Motion.lampOff(),
                        label = "lyric-$index",
                    )

                    if (line.text.isBlank()) {
                        Spacer(Modifier.height(Space.l))
                    } else {
                        Text(
                            text = line.text,
                            style = SpindleType.Lyric.copy(
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (lyrics.synced && line.timeMs >= 0) {
                                        Modifier.clickable { onSeek(lyrics.seekTargetFor(line)) }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(vertical = Space.xs),
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(Space.l))
                    LyricsSourceNote(
                        source = lyrics.source,
                        synced = lyrics.synced,
                        lookup = lookup,
                        onEdit = { editing = true },
                        // Offered even when lyrics are already showing: the
                        // ones in a file are not always the right ones, and
                        // before this there was no way past them.
                        onLookUp = if (settings.lyricsLookupEnabled) {
                            { track?.let(playerViewModel::lookUpLyrics) }
                        } else {
                            null
                        },
                    )
                }
            }

            // Pinned rather than placed in the list. Judging a timing
            // correction means watching the lines move while you nudge it, so
            // the control cannot be something that scrolls out from under you.
            if (lyrics.synced) {
                track?.let { current ->
                    TimingNudge(
                        offsetMs = lyrics.offsetMs,
                        onNudge = { playerViewModel.nudgeLyricsOffset(current, it) },
                        onReset = { playerViewModel.setLyricsOffset(current, 0L) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
    }

    if (editing) {
        track?.let { current ->
            EditLyricsDialog(
                initial = lyrics.raw,
                onDismiss = { editing = false },
                onSave = { text ->
                    playerViewModel.saveLyrics(current, text)
                    editing = false
                },
            )
        }
    }
}

/**
 * Shifts the lyrics against the music.
 *
 * Downloaded LRC is routinely a fraction of a second out against a particular
 * encode, and being consistently early is far more distracting than having no
 * lyrics at all. Tenths, because that is the resolution at which the drift
 * stops being visible; the readout is the current correction and tapping it
 * puts everything back.
 */
@Composable
private fun TimingNudge(
    offsetMs: Long,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(Space.m)
            .background(Ground.Plate.copy(alpha = 0.92f))
            .padding(horizontal = Space.xs, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NudgeButton("-", "Show lyrics earlier") { onNudge(-Lyrics.OFFSET_STEP_MS) }
        Text(
            text = formatOffset(offsetMs),
            style = SpindleType.DataEmphasis,
            color = if (offsetMs == 0L) Steel.Dim else Lamp.Bright,
            modifier = Modifier
                .clickable(onClick = onReset)
                .padding(horizontal = Space.s, vertical = Space.xs),
        )
        NudgeButton("+", "Show lyrics later") { onNudge(Lyrics.OFFSET_STEP_MS) }
    }
}

@Composable
private fun NudgeButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Space.tap)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = SpindleType.DataLarge, color = Steel.Bright)
    }
}

private fun formatOffset(offsetMs: Long): String {
    val seconds = offsetMs / 1000.0
    return when {
        offsetMs == 0L -> "0.0s"
        offsetMs > 0 -> String.format(java.util.Locale.US, "+%.1fs", seconds)
        else -> String.format(java.util.Locale.US, "%.1fs", seconds)
    }
}

@Composable
private fun NoLyrics(
    hasTrack: Boolean,
    lookupEnabled: Boolean,
    lookup: LyricsLookupState,
    onAdd: () -> Unit,
    onLookUp: () -> Unit,
    onEnableLookup: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No lyrics in this file",
                style = SpindleType.DisplaySmall,
                color = Ink.Primary,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                text = when {
                    lookupEnabled ->
                        "Spindle reads a .lrc file beside the track and lyrics in " +
                            "the track's own tags, then asks the online database."
                    else ->
                        "Spindle reads a .lrc file sitting next to the track, or " +
                            "lyrics stored in the track's own tags. Looking online " +
                            "is off, so nothing about what you play leaves the device."
                },
                style = SpindleType.Body,
                color = Steel.Dim,
            )

            when (lookup) {
                LyricsLookupState.Searching -> {
                    Spacer(Modifier.height(Space.m))
                    Text("Looking…", style = SpindleType.Secondary, color = Lamp.Bright)
                }
                LyricsLookupState.NotFound -> {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = "The database has nothing for this one.",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                }
                is LyricsLookupState.Failed -> {
                    Spacer(Modifier.height(Space.m))
                    // Named rather than generic: "could not reach the service"
                    // and "this song has no lyrics" call for different responses.
                    Text(
                        text = lookup.reason,
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                    )
                    Spacer(Modifier.height(Space.s))
                    Text(
                        text = "Try again",
                        style = SpindleType.RowTitle,
                        color = Lamp.Bright,
                        modifier = Modifier
                            .clickable(onClick = onLookUp)
                            .padding(vertical = Space.xs),
                    )
                }
                LyricsLookupState.Idle -> Unit
            }

            if (hasTrack) {
                Spacer(Modifier.height(Space.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(
                        modifier = Modifier
                            .background(Ground.Raised)
                            .clickable(onClick = onAdd)
                            .padding(horizontal = Space.l, vertical = Space.s),
                    ) {
                        Text("Paste lyrics", style = SpindleType.RowTitle, color = Lamp.Bright)
                    }

                    if (lookup != LyricsLookupState.Searching) {
                        Box(
                            modifier = Modifier
                                .background(Ground.Raised)
                                // Turning the setting on and using it are the
                                // same gesture, and this is the moment where
                                // saying what it sends actually means something.
                                .clickable(onClick = if (lookupEnabled) onLookUp else onEnableLookup)
                                .padding(horizontal = Space.l, vertical = Space.s),
                        ) {
                            Text(
                                text = if (lookupEnabled) "Look online" else "Look online once",
                                style = SpindleType.RowTitle,
                                color = Lamp.Bright,
                            )
                        }
                    }
                }
                if (!lookupEnabled) {
                    Spacer(Modifier.height(Space.s))
                    Text(
                        text = "Sends the title, artist, album, and length of this track to " +
                            "an open lyrics database, and turns lookup on for future " +
                            "tracks. Settings has the switch.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsSourceNote(
    source: LyricsSource,
    synced: Boolean,
    lookup: LyricsLookupState,
    onEdit: () -> Unit,
    onLookUp: (() -> Unit)?,
) {
    val description = when (source) {
        LyricsSource.USER -> "Added by you"
        LyricsSource.SIDECAR_LRC -> if (synced) "From the .lrc beside this file" else "From a text file beside this track"
        LyricsSource.EMBEDDED_TAG -> if (synced) "Synced, from this file's tags" else "From this file's tags"
        LyricsSource.ONLINE -> if (synced) "Synced, found online and kept" else "Found online and kept"
        LyricsSource.NONE -> ""
    }
    Column {
        Box(modifier = Modifier.width(24.dp).height(1.dp).background(Steel.Engrave))
        Spacer(Modifier.height(Space.s))
        Text(text = description, style = SpindleType.Data, color = Steel.Dim)
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Text(
                text = "Edit",
                style = SpindleType.Secondary,
                color = Lamp.Bright,
                modifier = Modifier
                    .clickable(onClick = onEdit)
                    .padding(vertical = Space.xs),
            )
            if (onLookUp != null) {
                Text(
                    text = when (lookup) {
                        LyricsLookupState.Searching -> "Looking…"
                        LyricsLookupState.NotFound -> "Nothing found online"
                        else -> "Replace from online"
                    },
                    style = SpindleType.Secondary,
                    color = if (lookup == LyricsLookupState.Idle) Lamp.Bright else Steel.Dim,
                    modifier = Modifier
                        .clickable(enabled = lookup != LyricsLookupState.Searching, onClick = onLookUp)
                        .padding(vertical = Space.xs),
                )
            }
        }
    }
}

@Composable
private fun EditLyricsDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = { Text("Lyrics", style = SpindleType.Section, color = Ink.Primary) },
        text = {
            Column {
                Text(
                    text = "Plain text works. LRC timestamps like [01:23.45] are " +
                        "understood too, and turn this into a synced, tappable lyric.",
                    style = SpindleType.Secondary,
                    color = Steel.Dim,
                )
                Spacer(Modifier.height(Space.m))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = SpindleType.Body.copy(color = Ink.Primary),
                    cursorBrush = SolidColor(Lamp.Bright),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Ground.Raised)
                        .padding(Space.m),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("Save", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
            }
        },
    )
}
