package com.irondigital.spindle.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irondigital.spindle.data.lyrics.LyricsSource
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

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
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val track by playerViewModel.currentTrack.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val activeIndex = remember(lyrics, playback.positionMs) {
        lyrics.activeIndexAt(playback.positionMs)
    }

    // Keeps the current line a third of the way down rather than at the very
    // top, so the next few lines are always visible — which is the whole point
    // of following along.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.synced) {
            listState.animateScrollToItem(
                index = activeIndex.coerceAtLeast(0),
                scrollOffset = -160,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (lyrics.isEmpty) {
            NoLyrics(
                hasTrack = track != null,
                onAdd = { editing = true },
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
                                        Modifier.clickable { onSeek(line.timeMs) }
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
                    LyricsSourceNote(lyrics.source, lyrics.synced, onEdit = { editing = true })
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

@Composable
private fun NoLyrics(hasTrack: Boolean, onAdd: () -> Unit) {
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
                text = "Spindle reads a .lrc file sitting next to the track, or " +
                    "lyrics stored in the track's own tags. It does not go looking " +
                    "online, so nothing about what you play leaves the device.",
                style = SpindleType.Body,
                color = Steel.Dim,
            )
            if (hasTrack) {
                Spacer(Modifier.height(Space.l))
                Box(
                    modifier = Modifier
                        .background(Ground.Raised)
                        .clickable(onClick = onAdd)
                        .padding(horizontal = Space.l, vertical = Space.s),
                ) {
                    Text("Paste lyrics", style = SpindleType.RowTitle, color = Lamp.Bright)
                }
            }
        }
    }
}

@Composable
private fun LyricsSourceNote(source: LyricsSource, synced: Boolean, onEdit: () -> Unit) {
    val description = when (source) {
        LyricsSource.USER -> "Added by you"
        LyricsSource.SIDECAR_LRC -> if (synced) "From the .lrc beside this file" else "From a text file beside this track"
        LyricsSource.EMBEDDED_TAG -> if (synced) "Synced, from this file's tags" else "From this file's tags"
        LyricsSource.NONE -> ""
    }
    Column {
        Box(modifier = Modifier.width(24.dp).height(1.dp).background(Steel.Engrave))
        Spacer(Modifier.height(Space.s))
        Text(text = description, style = SpindleType.Data, color = Steel.Dim)
        Spacer(Modifier.height(Space.s))
        Text(
            text = "Edit",
            style = SpindleType.Secondary,
            color = Lamp.Bright,
            modifier = Modifier.clickable(onClick = onEdit),
        )
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
