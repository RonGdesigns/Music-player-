package com.irondigital.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.data.db.Playlist
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * What a long press on a track offers.
 *
 * Long press is the only place in a list this dense with room for secondary
 * actions, so it carries all of them rather than one hidden favorite — a
 * gesture that does exactly one undiscoverable thing is worse than no gesture.
 */
@Composable
fun TrackActionSheet(
    track: Track,
    isFavorite: Boolean,
    playlists: List<Playlist>,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onEditDetails: (() -> Unit)? = null,
    onStartSelection: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var choosingPlaylist by remember { mutableStateOf(false) }
    // Sharing needs nothing but the track and a context, so it is not plumbed
    // through a callback the way the actions that touch the library are.
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = {
            Column {
                Text(
                    text = track.title.ifBlank { track.displayName },
                    style = SpindleType.Section,
                    color = Ink.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${track.artist} · ${formatDuration(track.durationMs)}",
                    style = SpindleType.Secondary,
                    color = Steel.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (choosingPlaylist) {
                    if (playlists.isEmpty()) {
                        Text(
                            text = "You have not made a playlist yet. Create one on " +
                                "the Lists tab and it will appear here.",
                            style = SpindleType.Body,
                            color = Steel.Dim,
                        )
                    }
                    playlists.forEach { playlist ->
                        ActionRow(
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            label = playlist.name,
                            onClick = {
                                onAddToPlaylist(playlist.id)
                                onDismiss()
                            },
                        )
                    }
                } else {
                    ActionRow(
                        icon = Icons.Filled.PlayArrow,
                        label = "Play next",
                        description = "Straight after the current track",
                        onClick = { onPlayNext(); onDismiss() },
                    )
                    ActionRow(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "Add to queue",
                        description = "At the end of what is already lined up",
                        onClick = { onAddToQueue(); onDismiss() },
                    )
                    ActionRow(
                        icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        label = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        lit = isFavorite,
                        onClick = { onToggleFavorite(); onDismiss() },
                    )
                    ActionRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = "Add to a playlist",
                        onClick = { choosingPlaylist = true },
                    )
                    ActionRow(
                        icon = Icons.Filled.Share,
                        label = "Share",
                        description = "Send the file to messages, or anywhere else",
                        onClick = { ShareTracks.share(context, track); onDismiss() },
                    )
                    if (onStartSelection != null) {
                        ActionRow(
                            icon = Icons.Filled.SelectAll,
                            label = "Select several",
                            description = "Pick a run of tracks to queue, share or " +
                                "drop into a playlist together",
                            onClick = { onStartSelection(); onDismiss() },
                        )
                    }
                    if (onEditDetails != null) {
                        ActionRow(
                            icon = Icons.Filled.Edit,
                            label = "Edit details",
                            description = "Fix a wrong title, artist or album",
                            onClick = { onEditDetails(); onDismiss() },
                        )
                    }
                    if (onGoToAlbum != null) {
                        ActionRow(
                            icon = Icons.Filled.Album,
                            label = "Go to ${track.album}",
                            onClick = { onGoToAlbum(); onDismiss() },
                        )
                    }
                    if (onGoToArtist != null) {
                        ActionRow(
                            icon = Icons.Filled.Person,
                            label = "Go to ${track.artist}",
                            onClick = { onGoToArtist(); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (choosingPlaylist) choosingPlaylist = false else onDismiss() }
            ) {
                Text(
                    text = if (choosingPlaylist) "Back" else "Close",
                    color = Lamp.Bright,
                    style = SpindleType.RowTitle,
                )
            }
        },
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    lit: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Space.tap)
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(if (lit) Lamp.Bright else Steel.Engrave)
        )
        Spacer(Modifier.width(Space.m))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (lit) Lamp.Bright else Steel.Bright,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = SpindleType.RowTitle,
                color = if (lit) Lamp.Bright else Ink.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = SpindleType.Secondary,
                    color = Steel.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
