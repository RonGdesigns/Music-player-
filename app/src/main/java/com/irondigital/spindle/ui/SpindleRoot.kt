package com.irondigital.spindle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.personal.*
import com.irondigital.spindle.ui.theme.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.data.repo.SmartPlaylist
import com.irondigital.spindle.ui.library.LibraryViewModel
import com.irondigital.spindle.ui.library.ImportDialogs
import com.irondigital.spindle.ui.library.LibraryScreen
import com.irondigital.spindle.ui.library.TrackListScreen
import com.irondigital.spindle.ui.player.NowPlayingScreen
import com.irondigital.spindle.ui.player.rememberArtworkColors
import com.irondigital.spindle.ui.audio.EqualizerScreen
import com.irondigital.spindle.ui.settings.SettingsScreen
import com.irondigital.spindle.ui.tools.AutoTagScreen
import com.irondigital.spindle.ui.tools.BatchEditScreen
import com.irondigital.spindle.ui.tools.DuplicatesScreen
import com.irondigital.spindle.ui.stats.StatsScreen
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.SpindleTheme

/**
 * Where the user is. A plain stack rather than a routing library: this app has
 * a handful of destinations and no deep links worth encoding as strings, so a
 * typed stack is both smaller and harder to get wrong.
 */
sealed interface Destination {
    data class Workspace(val section: String) : Destination
    data class CustomMix(val id: String) : Destination
    data object Library : Destination
    data object Settings : Destination
    data object Stats : Destination
    data object Equalizer : Destination
    data object AutoTag : Destination
    data object Duplicates : Destination
    data object BatchEdit : Destination
    data class Album(val albumId: Long) : Destination
    data class Artist(val name: String) : Destination
    data class Folder(val path: String) : Destination
    data class Smart(val playlist: SmartPlaylist) : Destination
    data class UserPlaylist(val playlistId: Long, val name: String) : Destination
}

@Composable
fun SpindleRoot() {
    val playerViewModel: PlayerViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()

    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val artworkColors by rememberArtworkColors(currentTrack?.artUri?.toString())

    SpindleTheme(artworkColors = artworkColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Ground.Deep)
        ) {
            LibraryPermissionGate(
                onGranted = { libraryViewModel.refresh() },
            ) {
                MainStack(playerViewModel, libraryViewModel)
            }
        }
    }
}

@Composable
private fun MainStack(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
) {
    val backStack: SnapshotStateList<Destination> =
        remember { listOf<Destination>(Destination.Library).toMutableStateList() }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    var mainSection by rememberSaveable { mutableStateOf("Home") }
    val personal: PersonalViewModel = viewModel()

    /**
     * Holds each screen's own state — scroll position above all — for as long
     * as it is on the stack.
     *
     * Pushing a destination disposes the one underneath, and a disposed
     * LazyColumn forgets where it was scrolled to. Coming back then dumps you
     * at the top of a list you had scrolled halfway down, which is the single
     * most irritating thing a navigation stack can do. This keeps the state
     * against the stack entry and hands it back on the way out.
     */
    val stateHolder = rememberSaveableStateHolder()

    val current = backStack.last()

    // Depth is part of the key so the same destination opened twice at
    // different points in the stack does not share one scroll position.
    fun keyFor(index: Int, destination: Destination) = "$index:$destination"

    fun push(destination: Destination) {
        if (destination == Destination.Library) { mainSection = "Library"; return }
        backStack.add(destination)
    }

    fun pop() {
        if (backStack.size <= 1) return
        // Dropped rather than kept: an entry the user has backed out of should
        // open fresh next time, and holding its state forever is a slow leak.
        stateHolder.removeState(keyFor(backStack.lastIndex, backStack.last()))
        backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }
    BackHandler(enabled = !nowPlayingOpen && backStack.size > 1) { pop() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 840.dp
    // Dispose the obscured surface so keyboard and accessibility focus stay in the player.
    if (!nowPlayingOpen) {
    Row(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.weight(1f)) {
        Box(modifier = Modifier.weight(1f)) {
            stateHolder.SaveableStateProvider(keyFor(backStack.lastIndex, current)) {
                when (val destination = current) {
                    Destination.Library -> LibraryScreen(
                        section = mainSection, onSectionChange = { mainSection = it },
                        libraryViewModel = libraryViewModel,
                        playerViewModel = playerViewModel,
                        onOpen = ::push,
                        onOpenSettings = { push(Destination.Settings) },
                        onOpenNowPlaying = { nowPlayingOpen = true },
                    )

                    is Destination.Workspace -> WorkspaceScreen(destination.section, playerViewModel, libraryViewModel, ::pop, ::push)
                    is Destination.CustomMix -> {
                        val data by personal.data.collectAsStateWithLifecycle()
                        val all by libraryViewModel.tracks.collectAsStateWithLifecycle()
                        val favorites by personal.favorites.collectAsStateWithLifecycle()
                        val stats by personal.stats.collectAsStateWithLifecycle()
                        val rule = data.mixes.firstOrNull { it.id == destination.id }
                        val selected = remember(rule, all, favorites, stats) { rule?.select(all, favorites, stats, System.currentTimeMillis()).orEmpty() }
                        TrackListScreen(rule?.name ?: "Smart playlist", "${selected.size} matching tracks", selected.firstOrNull()?.artUri?.toString(),
                            selected, playerViewModel, libraryViewModel, ::pop)
                    }
                    Destination.Settings -> SettingsScreen(
                        onBack = ::pop,
                        onRescan = libraryViewModel::refresh,
                        onOpenStats = { push(Destination.Stats) },
                        onOpenEqualizer = { push(Destination.Equalizer) },
                        onOpenAutoTag = { push(Destination.AutoTag) },
                        onOpenDuplicates = { push(Destination.Duplicates) },
                        onOpenBatchEdit = { push(Destination.BatchEdit) },
                    )

                    Destination.Stats -> StatsScreen(onBack = ::pop)

                    Destination.Equalizer -> EqualizerScreen(onBack = ::pop)
                    Destination.AutoTag -> AutoTagScreen(onBack = ::pop)
                    Destination.Duplicates -> DuplicatesScreen(onBack = ::pop)
                    Destination.BatchEdit -> BatchEditScreen(onBack = ::pop)

                    is Destination.Album -> {
                        // Filtering the whole library on every recomposition would
                        // be wasteful; these only change when the library rescans.
                        val libraryTracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
                        val album = remember(destination.albumId, libraryTracks) {
                            libraryViewModel.albumFor(destination.albumId)
                        }
                        val albumTracks = remember(destination.albumId, libraryTracks) {
                            libraryViewModel.tracksInAlbum(destination.albumId)
                        }
                        TrackListScreen(
                            title = album?.name ?: "Album",
                            subtitle = album?.artist,
                            artUri = album?.artUri?.toString(),
                            tracks = albumTracks,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = ::pop,
                            numbered = true,
                        )
                    }

                    is Destination.Artist -> {
                        val libraryTracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
                        val artistTracks = remember(destination.name, libraryTracks) {
                            libraryViewModel.tracksByArtist(destination.name)
                        }
                        TrackListScreen(
                            title = destination.name,
                            subtitle = null,
                            artUri = null,
                            tracks = artistTracks,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = ::pop,
                        )
                    }

                    is Destination.Folder -> {
                        val libraryTracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
                        val folderTracks = remember(destination.path, libraryTracks) {
                            libraryViewModel.tracksInFolder(destination.path)
                        }
                        TrackListScreen(
                            title = destination.path.substringAfterLast('/'),
                            subtitle = destination.path,
                            artUri = null,
                            tracks = folderTracks,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = ::pop,
                        )
                    }

                    is Destination.Smart -> {
                        val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
                        val limit = if (destination.playlist == SmartPlaylist.MOST_PLAYED) {
                            settings.mostPlayedSize
                        } else {
                            200
                        }
                        val flow = remember(destination.playlist, limit) {
                            libraryViewModel.smartPlaylist(destination.playlist, limit)
                        }
                        val tracks by flow.collectAsStateWithLifecycle(initialValue = emptyList())

                        TrackListScreen(
                            title = destination.playlist.title,
                            subtitle = destination.playlist.subtitle,
                            artUri = tracks.firstOrNull()?.artUri?.toString(),
                            tracks = tracks,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = ::pop,
                            ranked = destination.playlist == SmartPlaylist.MOST_PLAYED ||
                                destination.playlist == SmartPlaylist.MOST_PLAYED_MONTH,
                        )
                    }

                    is Destination.UserPlaylist -> {
                        val flow = remember(destination.playlistId) {
                            libraryViewModel.playlistTracks(destination.playlistId)
                        }
                        val tracks by flow.collectAsStateWithLifecycle(initialValue = emptyList())

                        TrackListScreen(
                            title = destination.name,
                            subtitle = null,
                            artUri = tracks.firstOrNull()?.artUri?.toString(),
                            tracks = tracks,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = ::pop,
                            onRemoveTrack = { mediaId ->
                                libraryViewModel.removeFromPlaylist(destination.playlistId, mediaId)
                            },
                        )
                    }
                }
            }
        }

        // The persistent band. One action continuously available, and it never
        // accumulates a second and a third — tapping it opens the player, which
        // is where every other action lives.
        if (!wide) MiniPlayerBar(playerViewModel = playerViewModel, onOpen = { nowPlayingOpen = true })
        if (current == Destination.Library) NavigationBar(containerColor = Ground.Deep) {
            listOf("Home", "Library", "Search").forEach { section ->
                NavigationBarItem(selected = mainSection == section, onClick = { mainSection = section },
                    icon = { Icon(when(section) { "Home" -> Icons.Filled.Home; "Library" -> Icons.Filled.LibraryMusic; else -> Icons.Filled.Search }, null) },
                    label = { Text(section) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Lamp.Bright,
                        selectedTextColor = Lamp.Bright, indicatorColor = Ground.Plate, unselectedIconColor = Steel.Bright, unselectedTextColor = Steel.Bright))
            }
        } else Spacer(Modifier.navigationBarsPadding())
    }
    if(wide) Box(Modifier.width(380.dp).fillMaxHeight()) {
        NowPlayingScreen(playerViewModel, libraryViewModel, onCollapse = { nowPlayingOpen = false }, embedded = true)
    }
    }
    }
    }

    // Above every destination: a shared file can arrive whatever screen is open.
    ImportDialogs(libraryViewModel)

    AnimatedVisibility(
        visible = nowPlayingOpen,
        enter = slideInVertically(
            animationSpec = tween(Motion.COMPOSITION_MS, easing = Motion.Primary),
        ) { it },
        exit = slideOutVertically(
            animationSpec = tween(Motion.ELEMENT_MS, easing = Motion.Exit),
        ) { it },
    ) {
        NowPlayingScreen(
            playerViewModel = playerViewModel,
            libraryViewModel = libraryViewModel,
            onCollapse = { nowPlayingOpen = false },
        )
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
        PersonalFeedback(personal, playerViewModel)
    }
}

/**
 * The library is unreadable without this permission, so the app says exactly
 * what it wants and why in plain words, once, and takes no for an answer.
 */
@Composable
private fun LibraryPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        // Only flip the flag; the LaunchedEffect below owns the follow-up work,
        // so the scan is not kicked off twice.
        granted = result
    }

    // Notifications are requested separately and only after the library works,
    // because a permission dialog before the app has shown anything is just a
    // toll gate.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(granted) {
        if (granted) {
            onGranted()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (granted) {
        content()
    } else {
        PermissionPrompt(
            alreadyAsked = asked,
            onRequest = {
                if (asked) {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                } else {
                    asked = true
                    launcher.launch(permission)
                }
            },
        )
    }
}
