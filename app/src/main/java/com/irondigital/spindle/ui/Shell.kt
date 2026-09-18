package com.irondigital.spindle.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irondigital.spindle.ui.components.Artwork
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * The persistent band: what is playing, and one press to change that.
 *
 * It carries exactly two controls. The moment a bar like this accumulates a
 * third and a fourth it stops being a band and becomes a second player, and
 * then there are two places to look for the same thing.
 */
@Composable
fun MiniPlayerBar(
    playerViewModel: PlayerViewModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val track by playerViewModel.currentTrack.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = playback.hasContent,
        enter = expandVertically(tween(Motion.ELEMENT_MS, easing = Motion.Primary)),
        exit = shrinkVertically(tween(Motion.STATE_MS, easing = Motion.Exit)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ground.Plate)
        ) {
            // The tick scale doubles as the progress read-out. One element, two
            // jobs: it divides the band from the list above it and it tells you
            // how far through the track you are.
            TickScale(
                progress = playback.progress,
                height = 8.dp,
                spacing = 5.dp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.m, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    uri = track?.albumArtUri?.toString(),
                    size = 44,
                    contentDescription = null,
                )

                Spacer(Modifier.width(Space.m))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = track?.title ?: "—",
                        style = SpindleType.RowTitle,
                        color = Ink.Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track?.artist ?: "",
                        style = SpindleType.Secondary,
                        color = Steel.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                LampIconButton(
                    icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                    onClick = playerViewModel::playPause,
                    lit = playback.isPlaying,
                    iconSize = 26.dp,
                )
                LampIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next track",
                    onClick = playerViewModel::next,
                    iconSize = 24.dp,
                )
            }
        }
    }
}

/**
 * The permission ask. A centred, axial composition — ceremony and focus on one
 * object — because it is the only screen in the app with exactly one thing to
 * do, and because it is deliberately not built like the dense index behind it.
 */
@Composable
fun PermissionPrompt(
    alreadyAsked: Boolean,
    onRequest: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
            .padding(horizontal = Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Your music,\nnot anyone else's",
                style = SpindleType.Display,
                color = Ink.Primary,
            )

            Spacer(Modifier.height(Space.l))

            TickScale(
                progress = 0f,
                height = 12.dp,
                spacing = 7.dp,
                modifier = Modifier.width(220.dp),
            )

            Spacer(Modifier.height(Space.l))

            Text(
                text = if (alreadyAsked) {
                    "Spindle still cannot read your audio files. Grant access to " +
                        "music and audio in Settings, then come back — everything " +
                        "stays on this device either way."
                } else {
                    "Spindle plays the files already on your phone. It needs " +
                        "permission to read them, and nothing else: no account, " +
                        "no network, no uploads."
                },
                style = SpindleType.Body,
                color = Steel.Bright,
            )

            Spacer(Modifier.height(Space.xl))

            Box(
                modifier = Modifier
                    .background(Lamp.Bright)
                    .clickable(onClick = onRequest)
                    .padding(horizontal = Space.xl, vertical = Space.m)
                    .height(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (alreadyAsked) "Ask again" else "Allow access",
                    style = SpindleType.RowTitle,
                    color = Ink.OnLamp,
                )
            }
        }
    }
}

/** Shown when the scan finishes and there was nothing to find. */
@Composable
fun EmptyLibrary(onRescan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No audio found",
                style = SpindleType.DisplaySmall,
                color = Ink.Primary,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                text = "Nothing on this device is indexed as music yet. Copy some " +
                    "files over, or lower the minimum track length in Settings if " +
                    "your tracks are short.",
                style = SpindleType.Body,
                color = Steel.Dim,
            )
            Spacer(Modifier.height(Space.l))
            Box(
                modifier = Modifier
                    .background(Ground.Raised)
                    .clickable(onClick = onRescan)
                    .padding(horizontal = Space.l, vertical = Space.s),
            ) {
                Text("Scan again", style = SpindleType.RowTitle, color = Lamp.Bright)
            }
        }
    }
}

/** A small fixed-size spacer used where the layout needs a deliberate gap. */
@Composable
fun VGap(dp: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(dp))

@Composable
fun HGap(dp: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(dp))
