package com.irondigital.spindle.ui.personal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.data.personal.*
import com.irondigital.spindle.ui.*
import com.irondigital.spindle.ui.library.LibraryViewModel
import com.irondigital.spindle.ui.components.*
import com.irondigital.spindle.ui.theme.*

fun PinnedCollection.destination(): Destination? = when(kind) {
    "album" -> key.toLongOrNull()?.let { Destination.Album(it) }
    "playlist" -> key.toLongOrNull()?.let { Destination.UserPlaylist(it, name) }
    "folder" -> Destination.Folder(key)
    else -> null
}

@Composable
fun PersonalFeedback(vm: PersonalViewModel = viewModel(), player: PlayerViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    val playerMessage by player.featureMessage.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { host.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(playerMessage) { playerMessage?.let { host.showSnackbar(it); player.clearFeatureMessage() } }
    SnackbarHost(hostState = host)
}

@Composable
fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(section: String, player: PlayerViewModel, library: LibraryViewModel,
    onBack: () -> Unit, onOpen: (Destination) -> Unit, vm: PersonalViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val albums by library.albums.collectAsStateWithLifecycle()
    val folders by library.folders.collectAsStateWithLifecycle()
    val playlists by library.playlists.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(section) }
    var saveSession by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ListeningSession?>(null) }
    var mixEditor by remember { mutableStateOf<MixRule?>(null) }
    var deleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinSearch by rememberSaveable { mutableStateOf("") }
    val playback by player.playback.collectAsStateWithLifecycle()
    val currentTrack by player.currentTrack.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Ground.Deep).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LampIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Text("Your listening", style = SpindleType.ScreenTitle, color = Ink.Primary)
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("Sessions", "Pins", "Smart playlists", "Bookmarks", "Widgets").forEach { item ->
                TextButton(onClick = { selected = item }) { Text(item, color = if (item == selected) Lamp.Bright else Steel.Bright) }
            }
        }
        if (selected == "Widgets") {
            WidgetEditor(data.widget, currentTrack?.title.orEmpty(), currentTrack?.artist.orEmpty(), currentTrack?.artUri?.toString(), vm::widget)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when(selected) {
                    "Sessions" -> {
                        item { Text("Each session remembers its queue, position, shuffle, and repeat settings.", color = Steel.Bright) }
                        item { Button(enabled = playback.hasContent, onClick = { saveSession = true }) { Text("Save current session") } }
                        if (data.sessions.isEmpty()) item { EmptyNote("No sessions yet", "Play music, then save this queue for later.") }
                        items(data.sessions.sortedByDescending { it.updatedAt }, key = { it.id }) { session ->
                            Column(Modifier.fillMaxWidth().background(Ground.Plate).padding(16.dp)) {
                                Text(session.name, style = SpindleType.Section, color = Ink.Primary)
                                Text("${session.snapshot.queue.size} tracks · ${session.snapshot.title} · ${formatDuration(session.snapshot.positionMs)}", color = Steel.Bright)
                                Row {
                                    TextButton(onClick = { player.resumeSession(session.id) }) { Text("Resume") }
                                    TextButton(onClick = { rename = session }) { Text("Rename") }
                                    TextButton(onClick = { deleteAction = { vm.removeSession(session.id) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    "Pins" -> {
                        item { Text("Pin albums, playlists, and folders to Home. Tap a checked item to unpin it.", color = Steel.Bright) }
                        item { OutlinedTextField(pinSearch, { pinSearch = it }, label = { Text("Find a collection") }, modifier = Modifier.fillMaxWidth()) }
                        val candidates = (data.pins + albums.map { PinnedCollection("album", it.albumId.toString(), it.name) } +
                            playlists.map { PinnedCollection("playlist", it.id.toString(), it.name) } +
                            folders.map { PinnedCollection("folder", it.path, it.name) }).distinctBy { it.id }
                            .filter { it.name.contains(pinSearch, true) }
                        if (candidates.isEmpty()) item { EmptyNote("No collections found", "Add some music or create a playlist first.") }
                        items(candidates, key = { it.id }) { pin ->
                            Row(Modifier.fillMaxWidth().toggleablePin { vm.pin(pin) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = data.pins.any { it.id == pin.id }, onCheckedChange = { vm.pin(pin) })
                                Column(Modifier.weight(1f)) { Text(pin.name, color = Ink.Primary); Text(pin.kind.replaceFirstChar { it.uppercase() }, color = Steel.Bright) }
                            }
                        }
                    }
                    "Smart playlists" -> {
                        item { Text("Build a playlist that updates as your library and listening change.", color = Steel.Bright) }
                        item { Button(onClick = { mixEditor = MixRule(name = "") }) { Text("Create smart playlist") } }
                        if (data.mixes.isEmpty()) item { EmptyNote("Your own rules", "Combine favorites, recent additions, time since last play, and track length.") }
                        items(data.mixes, key = { it.id }) { rule ->
                            Column(Modifier.fillMaxWidth().background(Ground.Plate).padding(16.dp)) {
                                Text(rule.name, style = SpindleType.Section, color = Ink.Primary)
                                Row {
                                    TextButton(onClick = { onOpen(Destination.CustomMix(rule.id)) }) { Text("Open") }
                                    TextButton(onClick = { mixEditor = rule }) { Text("Edit") }
                                    TextButton(onClick = { deleteAction = { vm.removeMix(rule.id) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    "Bookmarks" -> {
                        item { Text("Save a moment from Now Playing and return to it here.", color = Steel.Bright) }
                        if (data.bookmarks.isEmpty()) item { EmptyNote("No bookmarks yet", "Open the player’s listening tools to mark a moment.") }
                        items(data.bookmarks, key = { it.id }) { bookmark ->
                            val track = tracks.firstOrNull { it.mediaId == bookmark.mediaId }
                            Column(Modifier.fillMaxWidth().background(Ground.Plate).padding(16.dp)) {
                                Text(bookmark.label, style = SpindleType.RowTitle, color = Ink.Primary)
                                Text("${bookmark.title} · ${formatDuration(bookmark.positionMs)}", color = Steel.Bright)
                                if (track == null) Text("Track unavailable", color = Steel.Bright)
                                Row {
                                    TextButton(enabled = track != null, onClick = { track?.let { player.playBookmark(it, bookmark.positionMs) } }) { Text("Play from here") }
                                    TextButton(onClick = { deleteAction = { vm.removeBookmark(bookmark.id) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (saveSession) NameDialog("Save listening session", onDismiss = { saveSession = false }, onSave = player::saveSession)
    rename?.let { item -> NameDialog("Rename session", item.name, { rename = null }) { vm.renameSession(item.id, it) } }
    mixEditor?.let { rule -> MixEditor(rule, tracks, vm, onDismiss = { mixEditor = null }) }
    if (deleteAction != null) AlertDialog(onDismissRequest = { deleteAction = null }, title = { Text("Remove this saved item?") },
        text = { Text("Your audio files will stay in the library.") },
        confirmButton = { TextButton(onClick = { deleteAction?.invoke(); deleteAction = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleteAction = null }) { Text("Cancel") } })
}
private fun Modifier.toggleablePin(action: () -> Unit) = clickable(onClick = action).heightIn(min = 64.dp)

@Composable
fun EmptyNote(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(title, style = SpindleType.Section, color = Ink.Primary)
        Spacer(Modifier.height(8.dp)); Text(message, color = Steel.Bright)
    }
}

@Composable
private fun MixEditor(initial: MixRule, tracks: List<com.irondigital.spindle.data.model.Track>, vm: PersonalViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var favoritesOnly by rememberSaveable { mutableStateOf(initial.favoritesOnly) }
    var unplayed by rememberSaveable { mutableStateOf(initial.unplayedDays.toString()) }
    var added by rememberSaveable { mutableStateOf(initial.addedDays.toString()) }
    var duration by rememberSaveable { mutableStateOf(initial.maxDurationMinutes.toString()) }
    var sort by rememberSaveable { mutableStateOf(initial.sort) }
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val rule = initial.copy(name = name, favoritesOnly = favoritesOnly, unplayedDays = unplayed.toIntOrNull() ?: 0,
        addedDays = added.toIntOrNull() ?: 0, maxDurationMinutes = duration.toIntOrNull() ?: 0, sort = sort)
    val valid = name.isNotBlank() && rule.unplayedDays in 0..3650 && rule.addedDays in 0..3650 && rule.maxDurationMinutes in 0..1440
    val count = remember(rule, tracks, favorites, stats) { rule.select(tracks, favorites, stats, System.currentTimeMillis()).size }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Smart playlist rules") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favoritesOnly, { favoritesOnly = it }); Text("Favorites only") }
            RuleNumber("Not played in days", unplayed) { unplayed = it }
            RuleNumber("Added within days", added) { added = it }
            RuleNumber("Maximum length in minutes", duration) { duration = it }
            Text("0 means no limit. Day limits: 0–3650. Length: 0–1440 minutes.", color = Steel.Bright, style = SpindleType.Secondary)
            Text("Sort by", color = Ink.Primary)
            MixSort.entries.forEach { option -> Row(Modifier.clickable { sort = option }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sort == option, { sort = option }); Text(when(option) { MixSort.TITLE -> "Title"; MixSort.RECENTLY_ADDED -> "Recently added"; MixSort.LEAST_PLAYED -> "Least played" })
            } }
            Text("$count matching tracks", color = Lamp.Bright)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { vm.saveMix(rule); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
private fun RuleNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) }, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun WidgetEditor(saved: WidgetAppearance, title: String, artist: String, artUri: String?, onApply: (WidgetAppearance) -> Unit) {
    var style by remember(saved) { mutableStateOf(saved.style) }
    var density by remember(saved) { mutableStateOf(saved.density) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Widget appearance", style = SpindleType.Section, color = Ink.Primary)
        Text("Applies to all Spindle widgets. Small sizes keep the transport controls; larger sizes reveal your chosen layout.", color = Steel.Bright)
        Row { WidgetStyle.entries.forEach { item -> FilterChip(selected = style == item, onClick = { style = item }, label = { Text(if(item == WidgetStyle.QUEUE) "Queue" else "Artwork") }, modifier = Modifier.padding(end = 8.dp)) } }
        Row { WidgetDensity.entries.forEach { item -> FilterChip(selected = density == item, onClick = { density = item }, label = { Text(if(item == WidgetDensity.COMPACT) "Compact" else "Comfortable") }, modifier = Modifier.padding(end = 8.dp)) } }
        Text("Layout preview · large widget", style = SpindleType.Secondary, color = Steel.Bright)
        Column(Modifier.fillMaxWidth().background(Ground.Plate).padding(16.dp)) {
            if(style == WidgetStyle.ARTWORK) TypographicCover(artUri, title.ifBlank { "Your music" }, Modifier.fillMaxWidth().height(150.dp))
            Text(title.ifBlank { "Your music" }, style = SpindleType.Section, color = Ink.Primary)
            Text(artist.ifBlank { "Artist" }, color = Steel.Bright)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Icon(Icons.Filled.SkipPrevious, "Previous track", tint = Steel.Bright, modifier = Modifier.size(32.dp))
                Icon(Icons.Filled.PlayArrow, "Play", tint = Lamp.Bright, modifier = Modifier.size(32.dp))
                Icon(Icons.Filled.SkipNext, "Next track", tint = Steel.Bright, modifier = Modifier.size(32.dp))
            }
            if(style == WidgetStyle.QUEUE) repeat(if(density == WidgetDensity.COMPACT) 4 else 3) { i -> Text(if(i == 0) "Current track" else "Upcoming track", color = if(i == 0) Lamp.Bright else Ink.Primary, modifier = Modifier.padding(vertical = if(density == WidgetDensity.COMPACT) 8.dp else 12.dp)) }
        }
        Button(onClick = { onApply(WidgetAppearance(style, density)) }) { Text("Apply to widgets") }
    }
}
