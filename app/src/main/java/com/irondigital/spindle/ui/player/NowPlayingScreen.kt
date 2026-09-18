package com.irondigital.spindle.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.irondigital.spindle.data.settings.VisualizerMode
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.LampTransportButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.library.EditTrackHost
import com.irondigital.spindle.ui.library.LibraryViewModel
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.LocalArtworkColors
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

private enum class PlayerPane(val label: String) {
    PLAYING("Playing"),
    LYRICS("Lyrics"),
    QUEUE("Queue"),
}

/**
 * The player.
 *
 * Full-bleed immersive: the artwork's own colors run edge to edge behind
 * everything, which is a completely different kind of space from the dense
 * index the user just came from. That contrast is the division — no rule is
 * needed between a list and this.
 */
@Composable
fun NowPlayingScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onCollapse: () -> Unit,
) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val track by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val settings by playerViewModel.settings.collectAsStateWithLifecycle()
    val colors = LocalArtworkColors.current

    var pane by remember { mutableStateOf(PlayerPane.PLAYING) }
    var showInfo by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<com.irondigital.spindle.data.model.Track?>(null) }

    // Honours the setting rather than merely storing it: the screen is held
    // awake only while lyrics are actually on screen, and the flag is released
    // the moment the pane changes or the player closes.
    val view = LocalView.current
    DisposableEffect(pane, settings.keepScreenOnWithLyrics) {
        val hold = pane == PlayerPane.LYRICS && settings.keepScreenOnWithLyrics
        view.keepScreenOn = hold
        onDispose { view.keepScreenOn = false }
    }

    val levels by rememberAudioLevels(
        enabled = settings.visualizerMode == VisualizerMode.AUDIO_REACTIVE,
        audioSessionId = playback.audioSessionId,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ArtworkVisualizer(
            colors = colors,
            mode = settings.visualizerMode,
            levels = levels,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LampIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Collapse player",
                    onClick = onCollapse,
                )
                Spacer(Modifier.weight(1f))
                LampIconButton(
                    icon = Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer",
                    onClick = { showSleepTimer = true },
                    lit = playback.sleepTimerMinutes > 0,
                )
                LampIconButton(
                    icon = Icons.Filled.Info,
                    contentDescription = "Song information",
                    onClick = { showInfo = true },
                    enabled = track != null,
                )
            }

            PaneStrip(selected = pane, onSelect = { pane = it })

            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                when (pane) {
                    PlayerPane.PLAYING -> PlayingPane(track?.albumArtUri?.toString())
                    PlayerPane.LYRICS -> LyricsPane(
                        playerViewModel = playerViewModel,
                        onSeek = playerViewModel::seekTo,
                    )
                    PlayerPane.QUEUE -> QueuePane(playerViewModel)
                }
            }

            TransportBlock(
                playerViewModel = playerViewModel,
                title = track?.title ?: "—",
                artist = track?.artist ?: "",
                album = track?.album.orEmpty(),
                onShowQueue = { pane = PlayerPane.QUEUE },
            )
        }
    }

    if (showInfo) {
        track?.let { current ->
            SongInfoSheet(
                track = current,
                playerViewModel = playerViewModel,
                onEditDetails = { editingTrack = current },
                onDismiss = { showInfo = false },
            )
        }
    }

    editingTrack?.let { current ->
        EditTrackHost(
            track = current,
            libraryViewModel = libraryViewModel,
            onDismiss = { editingTrack = null },
        )
    }

    if (showSleepTimer) {
        SleepTimerSheet(
            activeMinutes = playback.sleepTimerMinutes,
            onSet = { minutes, endOfTrack ->
                playerViewModel.setSleepTimer(minutes, endOfTrack)
                showSleepTimer = false
            },
            onCancel = {
                playerViewModel.cancelSleepTimer()
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false },
        )
    }
}

@Composable
private fun PaneStrip(selected: PlayerPane, onSelect: (PlayerPane) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        PlayerPane.entries.forEach { entry ->
            val isSelected = entry == selected
            val color by animateColorAsState(
                targetValue = if (isSelected) Lamp.Bright else Steel.Dim,
                animationSpec = if (isSelected) Motion.lampOn() else Motion.lampOff(),
                label = "pane-${entry.name}",
            )
            Column(
                modifier = Modifier.clickable { onSelect(entry) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(entry.label, style = SpindleType.RowTitle, color = color)
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) Lamp.Bright else androidx.compose.ui.graphics.Color.Transparent
                        )
                )
            }
        }
    }
}

/**
 * The cover, elevated off the ground.
 *
 * Its shadow is tinted to the artwork rather than black, layered as a tight
 * contact shadow under a wide ambient one, and it is the only elevated thing on
 * the screen — which is what makes the elevation mean something instead of
 * being a texture every card wears.
 */
@Composable
private fun PlayingPane(artUri: String?) {
    val colors = LocalArtworkColors.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        // fillMaxWidth().aspectRatio(1f) makes a square as wide as the screen,
        // which on a short screen — or once the lyrics and queue tabs push the
        // transport up — is taller than the space available, and the overflow
        // lands on top of the controls. Sizing from the smaller dimension keeps
        // the cover square and inside its pane on every screen shape.
        val side = minOf(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .size(side)
                .shadow(
                    elevation = 36.dp,
                    shape = RoundedCornerShape(Corner.plate),
                    ambientColor = colors.dominant,
                    spotColor = colors.vibrant,
                )
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(Corner.plate),
                    ambientColor = colors.dominant,
                    spotColor = colors.dominant,
                )
                .clip(RoundedCornerShape(Corner.plate))
                .background(Ground.Plate),
        ) {
            if (artUri != null) {
                AsyncImage(
                    model = artUri,
                    contentDescription = "Cover art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TransportBlock(
    playerViewModel: PlayerViewModel,
    title: String,
    artist: String,
    album: String,
    onShowQueue: () -> Unit,
) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()

    // While a drag is in progress the slider follows the finger, not the
    // playhead — otherwise the thumb fights the 250ms position poll.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayedProgress = if (scrubbing) scrubPosition else playback.progress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ground.Deep.copy(alpha = 0.55f))
            .padding(horizontal = Space.gutter)
            .padding(top = Space.m, bottom = Space.l),
    ) {
        Text(
            text = title,
            style = SpindleType.Display,
            color = Ink.Primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            text = if (album.isBlank()) artist else "$artist — $album",
            style = SpindleType.Body,
            color = Steel.Bright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(Space.m))

        // The scale under the seek bar is the faceplate engraving, and it lights
        // up to the playhead — the bar and the texture are the same element.
        TickScale(progress = displayedProgress, height = 12.dp, spacing = 6.dp)

        Slider(
            value = displayedProgress,
            onValueChange = {
                scrubbing = true
                scrubPosition = it
            },
            onValueChangeFinished = {
                if (playback.durationMs > 0) {
                    playerViewModel.seekTo((scrubPosition * playback.durationMs).toLong())
                }
                scrubbing = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Lamp.Bright,
                activeTrackColor = Lamp.Bright,
                inactiveTrackColor = Steel.Engrave,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(
                    if (scrubbing) (scrubPosition * playback.durationMs).toLong()
                    else playback.positionMs
                ),
                style = SpindleType.Data,
                color = Steel.Dim,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "−" + formatDuration(
                    (playback.durationMs - playback.positionMs).coerceAtLeast(0)
                ),
                style = SpindleType.Data,
                color = Steel.Dim,
            )
        }

        Spacer(Modifier.height(Space.m))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampIconButton(
                icon = Icons.Filled.Shuffle,
                contentDescription = if (playback.shuffleEnabled) "Shuffle on" else "Shuffle off",
                onClick = playerViewModel::toggleShuffle,
                lit = playback.shuffleEnabled,
            )
            LampIconButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "Previous track",
                onClick = playerViewModel::previous,
                iconSize = 32.dp,
                size = 56.dp,
            )
            LampTransportButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                onClick = playerViewModel::playPause,
                playing = playback.isPlaying,
            )
            LampIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Next track",
                onClick = playerViewModel::next,
                iconSize = 32.dp,
                size = 56.dp,
            )
            LampIconButton(
                icon = when (playback.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                contentDescription = when (playback.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                onClick = playerViewModel::cycleRepeat,
                lit = playback.repeatMode != Player.REPEAT_MODE_OFF,
            )
        }

        Spacer(Modifier.height(Space.s))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampIconButton(
                icon = if (playback.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (playback.isFavorite) {
                    "Remove from favorites"
                } else {
                    "Add to favorites"
                },
                onClick = playerViewModel::toggleFavorite,
                lit = playback.isFavorite,
            )
            Spacer(Modifier.width(Space.l))
            Text(
                text = if (playback.queueSize > 0) {
                    "${playback.queueIndex + 1} / ${playback.queueSize}"
                } else {
                    ""
                },
                style = SpindleType.Data,
                color = Steel.Dim,
            )
            Spacer(Modifier.width(Space.l))
            LampIconButton(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Show queue",
                onClick = onShowQueue,
            )
        }
    }
}
