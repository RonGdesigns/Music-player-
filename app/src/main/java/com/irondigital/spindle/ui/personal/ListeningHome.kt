package com.irondigital.spindle.ui.personal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.irondigital.spindle.ui.*
import com.irondigital.spindle.ui.library.LibraryViewModel
import com.irondigital.spindle.ui.components.*
import com.irondigital.spindle.ui.theme.*
import com.irondigital.spindle.data.repo.SmartPlaylist

@Composable
fun TypographicCover(uri: String?, title: String, modifier: Modifier = Modifier) {
    var loaded by remember(uri) { mutableStateOf(false) }
    val initials = remember(title) { title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "S" } }
    BoxWithConstraints(modifier.background(Ground.Raised), contentAlignment = Alignment.BottomStart) {
        val showBrand = maxWidth >= 140.dp
        val initialsSize = (maxWidth.value * .27f).coerceIn(24f, 88f).sp
        if (!loaded) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if(showBrand) Text("SPINDLE", style = SpindleType.Data, color = Steel.Bright, maxLines = 1)
                Text(initials, fontSize = initialsSize,
                    fontWeight = FontWeight.Bold, color = Ink.Primary, maxLines = 1)
            }
        }
        if(uri != null) AsyncImage(uri, "Cover for $title", contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { loaded = false }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun ListeningHome(library: LibraryViewModel, player: PlayerViewModel, onOpen: (Destination) -> Unit,
    onPlayer: () -> Unit, vm: PersonalViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val track by player.currentTrack.collectAsStateWithLifecycle()
    val playback by player.playback.collectAsStateWithLifecycle()
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val mostPlayed = remember { library.smartPlaylist(SmartPlaylist.MOST_PLAYED, 5) }
    val most by mostPlayed.collectAsStateWithLifecycle(emptyList())
    val recent = remember(tracks) { tracks.sortedByDescending { it.dateAddedSec }.distinctBy { it.albumId }.take(10) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Text("Continue listening", style = SpindleType.Section, color = Ink.Primary)
            Spacer(Modifier.height(12.dp))
            if(playback.hasContent) Row(Modifier.fillMaxWidth().background(Ground.Plate).clickable(onClick = onPlayer).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TypographicCover(track?.artUri?.toString(), track?.title ?: "Your music", Modifier.size(84.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: "Saved queue", style = SpindleType.RowTitle, color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${playback.queueSize} tracks · ${formatDuration(playback.positionMs)}", style = SpindleType.Secondary, color = Steel.Bright)
                    TextButton(onClick = { if(!playback.isPlaying) player.playPause(); onPlayer() }) { Text(if(playback.isPlaying) "Open player" else "Resume") }
                }
            } else EmptyNote("Your next listen starts here", "Choose an album or song from Library, then save a session to pick up later.")
        }
        item {
            HomeHeading("Pinned", "Manage") { onOpen(Destination.Workspace("Pins")) }
            if(data.pins.isEmpty()) Text("Keep favorite albums, playlists, and folders within reach.", color = Steel.Bright)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(data.pins, key = { it.id }) { pin ->
                    val cover = when(pin.kind) { "album" -> tracks.firstOrNull { it.albumId.toString() == pin.key }; "folder" -> tracks.firstOrNull { it.folderPath == pin.key }; else -> null }
                    Column(Modifier.width(142.dp).clickable { pin.destination()?.let(onOpen) }) {
                        TypographicCover(cover?.artUri?.toString(), pin.name, Modifier.size(142.dp))
                        Text(pin.name, style = SpindleType.RowTitle, color = Ink.Primary, maxLines = 2, modifier = Modifier.padding(top = 8.dp), overflow = TextOverflow.Ellipsis)
                        Text(pin.kind.replaceFirstChar { it.uppercase() }, style = SpindleType.Secondary, color = Steel.Bright)
                    }
                }
            }
        }
        item {
            HomeHeading("Listening sessions", "View") { onOpen(Destination.Workspace("Sessions")) }
            if(data.sessions.isEmpty()) Text("Save separate queues for a drive, a workday, or a long mix.", color = Steel.Bright)
            data.sessions.sortedByDescending { it.updatedAt }.take(3).forEach { session ->
                Row(Modifier.fillMaxWidth().clickable { player.resumeSession(session.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(session.name, color = Ink.Primary, style = SpindleType.RowTitle); Text("${session.snapshot.queue.size} tracks · ${formatDuration(session.snapshot.positionMs)}", color = Steel.Bright, style = SpindleType.Secondary) }
                    Icon(Icons.Filled.PlayArrow, "Resume ${session.name}", tint = Lamp.Bright)
                }
            }
        }
        if(recent.isNotEmpty()) item {
            HomeHeading("Recently added", "Library") { onOpen(Destination.Library) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(recent, key = { it.albumId }) { album ->
                Column(Modifier.width(142.dp).clickable { onOpen(Destination.Album(album.albumId)) }) {
                    TypographicCover(album.artUri.toString(), album.album, Modifier.size(142.dp))
                    Text(album.album, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(album.effectiveAlbumArtist, color = Steel.Bright, style = SpindleType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } }
        }
        item {
            HomeHeading("Made by you", "Create") { onOpen(Destination.Workspace("Smart playlists")) }
            if(data.mixes.isEmpty()) Text("Smart playlists follow your rules and update as you listen.", color = Steel.Bright)
            data.mixes.forEach { rule -> TextButton(onClick = { onOpen(Destination.CustomMix(rule.id)) }) { Text(rule.name) } }
        }
        item {
            HomeHeading("Most played", "See all") { onOpen(Destination.Smart(SmartPlaylist.MOST_PLAYED)) }
            if(most.isEmpty()) Text("Your listening favorites will appear here.", color = Steel.Bright)
            most.take(3).forEachIndexed { index, song -> TextButton(onClick = { player.play(most, index); onPlayer() }) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
        item { Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { onOpen(Destination.Workspace("Bookmarks")) }) { Text("Bookmarks") }
            TextButton(onClick = { onOpen(Destination.Workspace("Widgets")) }) { Text("Widgets") }
            TextButton(onClick = { onOpen(Destination.Stats) }) { Text("Listening stats") }
        } }
    }
}
@Composable
private fun HomeHeading(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SpindleType.Section, color = Ink.Primary, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}
