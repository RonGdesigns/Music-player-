package com.irondigital.spindle.ui.personal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.data.personal.TrackBookmark
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.theme.*

@Composable
fun PlayerTools(player: PlayerViewModel, onDismiss: () -> Unit, vm: PersonalViewModel = viewModel()) {
    val track by player.currentTrack.collectAsStateWithLifecycle()
    val playback by player.playback.collectAsStateWithLifecycle()
    val loop by player.loopState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val playerMessage by player.featureMessage.collectAsStateWithLifecycle()
    var bookmark by remember { mutableStateOf(false) }
    var bookmarkTrack by remember { mutableStateOf<com.irondigital.spindle.data.model.Track?>(null) }
    var bookmarkPosition by remember { mutableStateOf(0L) }
    var session by remember { mutableStateOf(false) }
    var artKey by rememberSaveable { mutableStateOf("") }
    var a by rememberSaveable(playback.mediaId) { mutableStateOf("") }
    var b by rememberSaveable(playback.mediaId) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null && artKey.isNotBlank()) vm.artwork(artKey, uri)
    }
    val start = a.toDoubleOrNull()?.times(1000)?.toLong()
    val end = b.toDoubleOrNull()?.times(1000)?.toLong()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Listening tools") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (message ?: playerMessage)?.let { Text(it, color = Lamp.Bright) }
            Text(track?.title ?: "No track selected", style = SpindleType.RowTitle, color = Ink.Primary)
            TextButton(enabled = track != null, onClick = { bookmarkTrack = track; bookmarkPosition = playback.positionMs; bookmark = true }) { Text("Bookmark ${formatDuration(playback.positionMs)}") }
            TextButton(enabled = playback.hasContent, onClick = { session = true }) { Text("Save listening session") }
            HorizontalDivider(color = Steel.Engrave)
            Text("A–B repeat", style = SpindleType.Section)
            Text("Set a start and end within this track. Times are in seconds.", color = Steel.Bright)
            OutlinedTextField(a, { a = it.take(10) }, label = { Text("A · start seconds") }, singleLine = true)
            TextButton(enabled = track != null, onClick = { a = (playback.positionMs / 1000.0).toString() }) { Text("Set A here") }
            OutlinedTextField(b, { b = it.take(10) }, label = { Text("B · end seconds") }, singleLine = true)
            TextButton(enabled = track != null, onClick = { b = (playback.positionMs / 1000.0).toString() }) { Text("Set B here") }
            Text("The loop must be at least half a second and end before the track finishes.", color = Steel.Bright, style = SpindleType.Secondary)
            Row {
                TextButton(enabled = start != null && end != null && start >= 0 && end-start >= 500 && end < playback.durationMs,
                    onClick = { player.setLoop(start!!, end!!) }) { Text("Enable loop") }
                TextButton(enabled = loop.first != null, onClick = { player.setLoop(-1, -1) }) { Text("Clear loop") }
            }
            if(loop.first != null) Text("Loop active: ${formatDuration(loop.first!!)}–${formatDuration(loop.second ?: 0)}", color = Lamp.Bright)
            HorizontalDivider(color = Steel.Engrave)
            Text("Cover art", style = SpindleType.Section)
            Text("A local override. Your audio files stay unchanged.", color = Steel.Bright)
            track?.let { current ->
                TextButton(enabled = !busy, onClick = { artKey = "track:${current.mediaId}"; picker.launch("image/*") }) { Text("Choose track cover") }
                TextButton(enabled = !busy, onClick = { artKey = "album:${current.albumId}"; picker.launch("image/*") }) { Text("Choose album cover") }
                TextButton(enabled = !busy, onClick = { vm.resetArtwork("track:${current.mediaId}") }) { Text("Reset track cover") }
                TextButton(enabled = !busy, onClick = { vm.resetArtwork("album:${current.albumId}") }) { Text("Reset album cover") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if(bookmark) NameDialog("Name this bookmark", "At ${formatDuration(bookmarkPosition)}", { bookmark = false }) { label ->
        bookmarkTrack?.let { vm.addBookmark(TrackBookmark(mediaId = it.mediaId, title = it.title, positionMs = bookmarkPosition, label = label)) }
    }
    if(session) NameDialog("Save listening session", onDismiss = { session = false }, onSave = player::saveSession)
}
