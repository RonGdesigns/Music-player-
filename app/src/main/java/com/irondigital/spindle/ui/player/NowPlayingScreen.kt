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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Share
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
import com.irondigital.spindle.ui.personal.*
import com.irondigital.spindle.ui.MiniPlayerBar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.irondigital.spindle.ui.components.ShareTracks
import com.irondigital.spindle.ui.components.consumeTouches
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
    embedded: Boolean = false,
) {
    // The playhead ticks four times a second, and this screen needs none of
    // it — only two fields that change rarely. Unwrapping the whole state here
    // invalidated the entire player, the pane inside it and everything they
    // contain, four times a second while a scroll animation was running.
    val playbackState = playerViewModel.playback.collectAsStateWithLifecycle()
    val audioSessionId by remember { derivedStateOf { playbackState.value.audioSessionId } }
    val sleepTimerMinutes by remember { derivedStateOf { playbackState.value.sleepTimerMinutes } }

    val track by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val settings by playerViewModel.settings.collectAsStateWithLifecycle()
    val colors = LocalArtworkColors.current
    val context = LocalContext.current

    var pane by remember { mutableStateOf(PlayerPane.PLAYING) }
    var showTools by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<com.irondigital.spindle.data.model.Track?>(null) }

    // Honors the setting rather than merely storing it: the screen is held
    // awake only while lyrics are actually on screen, and the flag is released
    // the moment the pane changes or the player closes.
    val view = LocalView.current
    DisposableEffect(pane, settings.keepScreenOnWithLyrics) {
        val hold = pane == PlayerPane.LYRICS && settings.keepScreenOnWithLyrics
        view.keepScreenOn = hold
        onDispose { view.keepScreenOn = false }
    }

    val levels = rememberAudioLevels(
        enabled = settings.visualizerMode == VisualizerMode.AUDIO_REACTIVE,
        audioSessionId = audioSessionId,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The player is drawn over the library rather than replacing it, so
            // it has to claim its own touches and paint its own ground —
            // otherwise a tap on empty space reaches the list underneath and
            // changes the song.
            .background(Ground.Deep)
            .consumeTouches()
    ) {
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
                if(!embedded) LampIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Collapse player",
                    onClick = onCollapse,
                )
                Text("Now playing", style = SpindleType.Secondary, color = Steel.Bright, modifier = Modifier.weight(1f))
                LampIconButton(Icons.Filled.Tune, "Listening tools", { showTools = true }, enabled = track != null)
                track?.let { current ->
                    LampIconButton(
                        icon = Icons.Filled.Share,
                        contentDescription = "Share this track",
                        onClick = { ShareTracks.share(context, current) },
                    )
                }
                LampIconButton(
                    icon = Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer",
                    onClick = { showSleepTimer = true },
                    lit = sleepTimerMinutes > 0,
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
                    PlayerPane.PLAYING -> PlayingPane(track?.artUri?.toString(), track?.title ?: "Your music")
                    PlayerPane.LYRICS -> LyricsPane(
                        playerViewModel = playerViewModel,
                        onSeek = playerViewModel::seekTo,
                    )
                    PlayerPane.QUEUE -> QueuePane(playerViewModel)
                }
            }

            TransportBlock(
                compact = pane != PlayerPane.PLAYING,
                playerViewModel = playerViewModel,
                title = track?.title ?: "—",
                artist = track?.artist ?: "",
                album = track?.album.orEmpty(),
                onShowQueue = { pane = PlayerPane.QUEUE },
                // The reactive mode is the one worth watching, so the plate
                // behind the transport gets out of its way.
                seeThrough = settings.visualizerMode == VisualizerMode.AUDIO_REACTIVE,
            )
        }
    }

    if (showTools) PlayerTools(playerViewModel, { showTools = false })

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
            activeMinutes = sleepTimerMinutes,
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
                modifier = Modifier.clickable { onSelect(entry) }.heightIn(min = 48.dp).padding(vertical = 10.dp),
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
private fun PlayingPane(artUri: String?, title: String) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, 320.dp)
        TypographicCover(artUri, title, Modifier.size(side))
    }
}

@Composable
private fun TransportBlock(
    playerViewModel: PlayerViewModel,
    title: String,
    artist: String,
    album: String,
    onShowQueue: () -> Unit,
    compact: Boolean = false,
    seeThrough: Boolean = false,
) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()

    // While a drag is in progress the slider follows the finger, not the
    // playhead — otherwise the thumb fights the 250ms position poll.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayedProgress = if (scrubbing) scrubPosition else playback.progress

    // Ground.Scrim is ninety percent opaque, which across the whole block puts
    // a near-solid plate over the part of the screen there is most of. In the
    // reactive mode it is faded instead: full strength behind the title, thin
    // by the time it reaches the controls, gone underneath them.
    //
    // Not dropped altogether, and the reason is measurable rather than a
    // preference. The artwork palette's accent is only ever brightened, never
    // dimmed, so a pale cover can push the visualizer to roughly a quarter of
    // full luminance. Over that, the title reads at about 2.5:1 with no plate
    // at all against better than 10:1 with this one — the difference between
    // readable and not. The transport icons are large shapes rather than text,
    // so they need none of it, which is exactly where it reaches zero.
    val plate: Brush = if (seeThrough) {
        Brush.verticalGradient(
            0f to Ground.Scrim,
            0.62f to Ground.Deep.copy(alpha = 0.42f),
            1f to Color.Transparent,
        )
    } else {
        SolidColor(Ground.Scrim)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(plate)
            .padding(top = Space.s, bottom = Space.s),
    ) {
        // The gutter is applied per block rather than to the whole column, so
        // the transport row can run wider than the text above it. At the full
        // gutter the five controls left about 11dp between them, and play and
        // next — the two pressed most — sat closest together of all.
        Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
        Text(
            text = title,
            style = if (compact) SpindleType.RowTitle else SpindleType.ScreenTitle,
            color = Ink.Primary,
            maxLines = if (compact) 1 else 2,
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
        if (!compact) TickScale(progress = displayedProgress, height = 6.dp, spacing = 6.dp)

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
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
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

        }

        Spacer(Modifier.height(Space.m))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.s),
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
                size = if(compact) 48.dp else 60.dp,
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

        if (!compact) {
        Spacer(Modifier.height(Space.s))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter),
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
}
