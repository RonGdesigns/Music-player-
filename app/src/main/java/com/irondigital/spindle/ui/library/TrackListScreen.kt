package com.irondigital.spindle.ui.library

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.components.TrackActionSheet
import com.irondigital.spindle.ui.components.TrackRow
import com.irondigital.spindle.ui.components.formatTotalDuration
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Any list of tracks with a heading: an album, an artist, a folder, a smart
 * playlist, a hand-built one.
 *
 * When there is artwork the header bleeds it edge to edge and the title sits on
 * it — a ground change, which is a different division channel from the grooves
 * used in the list below, so two sections running do not divide the same way
 * twice.
 */
@Composable
fun TrackListScreen(
    title: String,
    subtitle: String?,
    artUri: String?,
    tracks: List<Track>,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit,
    numbered: Boolean = false,
    ranked: Boolean = false,
    onRemoveTrack: ((String) -> Unit)? = null,
) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val favorites by playerViewModel.favoriteIds.collectAsStateWithLifecycle()
    val counts by playerViewModel.playCounts.collectAsStateWithLifecycle()

    var addingToPlaylist by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Track?>(null) }
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            item {
                DetailHeader(
                    title = title,
                    subtitle = subtitle,
                    artUri = artUri,
                    trackCount = tracks.size,
                    totalDurationMs = tracks.sumOf { it.durationMs },
                    onBack = onBack,
                    onPlay = { if (tracks.isNotEmpty()) playerViewModel.play(tracks) },
                    onShuffle = { if (tracks.isNotEmpty()) playerViewModel.shufflePlay(tracks) },
                    onAddToPlaylist = { addingToPlaylist = true },
                )
            }

            itemsIndexed(tracks, key = { _, track -> track.mediaId }) { index, track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(
                        track = track,
                        modifier = Modifier.weight(1f),
                        isCurrent = track.mediaId == playback.mediaId,
                        isPlaying = playback.isPlaying,
                        isFavorite = track.mediaId in favorites,
                        playCount = counts[track.mediaId] ?: 0,
                        showArtwork = !numbered && !ranked,
                        // In a ranked list the number IS the content, which is
                        // the one case where numbering a row earns its place.
                        trackNumber = when {
                            ranked -> index + 1
                            numbered -> track.trackNumber.takeIf { it > 0 } ?: (index + 1)
                            else -> null
                        },
                        onClick = { playerViewModel.play(tracks, index) },
                        onLongClick = { actionsFor = track },
                    )
                    if (onRemoveTrack != null) {
                        LampIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Remove ${track.title} from this playlist",
                            onClick = { onRemoveTrack(track.mediaId) },
                            size = 44.dp,
                            iconSize = 18.dp,
                            unlitColor = Steel.Engrave,
                        )
                    }
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Text(
                        text = "Nothing here yet.",
                        style = SpindleType.Body,
                        color = Steel.Dim,
                        modifier = Modifier.padding(Space.gutter),
                    )
                }
            }
        }
    }

    actionsFor?.let { track ->
        TrackActionSheet(
            track = track,
            isFavorite = track.mediaId in favorites,
            playlists = playlists,
            onPlayNext = { playerViewModel.playNext(listOf(track)) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleFavorite = {
                playerViewModel.setFavorite(track.mediaId, track.mediaId !in favorites)
            },
            onAddToPlaylist = { libraryViewModel.addToPlaylist(it, listOf(track.mediaId)) },
            onDismiss = { actionsFor = null },
        )
    }

    if (addingToPlaylist) {
        AddToPlaylistSheet(
            libraryViewModel = libraryViewModel,
            mediaIds = tracks.map { it.mediaId },
            onDismiss = { addingToPlaylist = false },
        )
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String?,
    artUri: String?,
    trackCount: Int,
    totalDurationMs: Long,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (artUri != null) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Ground.Deep.copy(alpha = 0.70f),
                            0.5f to Ground.Deep.copy(alpha = 0.86f),
                            1f to Ground.Deep,
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            LampIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier.padding(start = Space.s),
            )

            Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(if (artUri != null) Space.huge else Space.s))

                Text(
                    text = title,
                    style = SpindleType.Display,
                    color = Ink.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (subtitle != null) {
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        text = subtitle,
                        style = SpindleType.Body,
                        color = Steel.Bright,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(Space.s))
                Text(
                    text = "$trackCount tracks · ${formatTotalDuration(totalDurationMs)}",
                    style = SpindleType.Data,
                    color = Steel.Dim,
                )

                Spacer(Modifier.height(Space.m))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LampIconButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "Play all",
                        onClick = onPlay,
                        lit = true,
                        enabled = trackCount > 0,
                        iconSize = 26.dp,
                    )
                    LampIconButton(
                        icon = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle all",
                        onClick = onShuffle,
                        enabled = trackCount > 0,
                    )
                    LampIconButton(
                        icon = Icons.Filled.PlaylistAdd,
                        contentDescription = "Add all to a playlist",
                        onClick = onAddToPlaylist,
                        enabled = trackCount > 0,
                    )
                }

                Spacer(Modifier.height(Space.m))
                TickScale(height = 10.dp, spacing = 6.dp)
                Spacer(Modifier.height(Space.s))
            }
        }
    }
}

@Composable
private fun AddToPlaylistSheet(
    libraryViewModel: LibraryViewModel,
    mediaIds: List<String>,
    onDismiss: () -> Unit,
) {
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = {
            Text("Add ${mediaIds.size} tracks to", style = SpindleType.Section, color = Ink.Primary)
        },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        "You have not made a playlist yet.",
                        style = SpindleType.Body,
                        color = Steel.Dim,
                    )
                }
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                libraryViewModel.addToPlaylist(playlist.id, mediaIds)
                                onDismiss()
                            }
                            .padding(vertical = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(Lamp.Bright)
                        )
                        Spacer(Modifier.width(Space.m))
                        Text(playlist.name, style = SpindleType.RowTitle, color = Ink.Primary)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Done", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
    )
}
