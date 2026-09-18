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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.irondigital.spindle.ui.library.LibraryScreen
import com.irondigital.spindle.ui.library.TrackListScreen
import com.irondigital.spindle.ui.player.NowPlayingScreen
import com.irondigital.spindle.ui.player.rememberArtworkColors
import com.irondigital.spindle.ui.settings.SettingsScreen
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.SpindleTheme

/**
 * Where the user is. A plain stack rather than a routing library: this app has
 * seven destinations and no deep links worth encoding as strings, so a typed
 * stack is both smaller and harder to get wrong.
 */
sealed interface Destination {
    data object Library : Destination
    data object Settings : Destination
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
    val artworkColors by rememberArtworkColors(currentTrack?.albumArtUri?.toString())

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

    val current = backStack.last()

    fun push(destination: Destination) {
        backStack.add(destination)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }
    BackHandler(enabled = !nowPlayingOpen && backStack.size > 1) { pop() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (val destination = current) {
                Destination.Library -> LibraryScreen(
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    onOpen = ::push,
                    onOpenSettings = { push(Destination.Settings) },
                    onOpenNowPlaying = { nowPlayingOpen = true },
                )

                Destination.Settings -> SettingsScreen(
                    onBack = ::pop,
                    onRescan = libraryViewModel::refresh,
                )

                is Destination.Album -> {
                    val album = libraryViewModel.albumFor(destination.albumId)
                    TrackListScreen(
                        title = album?.name ?: "Album",
                        subtitle = album?.artist,
                        artUri = album?.artUri?.toString(),
                        tracks = libraryViewModel.tracksInAlbum(destination.albumId),
                        playerViewModel = playerViewModel,
                        libraryViewModel = libraryViewModel,
                        onBack = ::pop,
                        numbered = true,
                    )
                }

                is Destination.Artist -> TrackListScreen(
                    title = destination.name,
                    subtitle = null,
                    artUri = null,
                    tracks = libraryViewModel.tracksByArtist(destination.name),
                    playerViewModel = playerViewModel,
                    libraryViewModel = libraryViewModel,
                    onBack = ::pop,
                )

                is Destination.Folder -> TrackListScreen(
                    title = destination.path.substringAfterLast('/'),
                    subtitle = destination.path,
                    artUri = null,
                    tracks = libraryViewModel.tracksInFolder(destination.path),
                    playerViewModel = playerViewModel,
                    libraryViewModel = libraryViewModel,
                    onBack = ::pop,
                )

                is Destination.Smart -> {
                    val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
                    val limit = if (destination.playlist == SmartPlaylist.MOST_PLAYED) {
                        settings.mostPlayedSize
                    } else {
                        200
                    }
                    val tracks by libraryViewModel
                        .smartPlaylist(destination.playlist, limit)
                        .collectAsStateWithLifecycle(initialValue = emptyList())

                    TrackListScreen(
                        title = destination.playlist.title,
                        subtitle = destination.playlist.subtitle,
                        artUri = tracks.firstOrNull()?.albumArtUri?.toString(),
                        tracks = tracks,
                        playerViewModel = playerViewModel,
                        libraryViewModel = libraryViewModel,
                        onBack = ::pop,
                        ranked = destination.playlist == SmartPlaylist.MOST_PLAYED ||
                            destination.playlist == SmartPlaylist.MOST_PLAYED_MONTH,
                    )
                }

                is Destination.UserPlaylist -> {
                    val tracks by libraryViewModel
                        .playlistTracks(destination.playlistId)
                        .collectAsStateWithLifecycle()

                    TrackListScreen(
                        title = destination.name,
                        subtitle = null,
                        artUri = tracks.firstOrNull()?.albumArtUri?.toString(),
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

        // The persistent band. One action continuously available, and it never
        // accumulates a second and a third — tapping it opens the player, which
        // is where every other action lives.
        MiniPlayerBar(
            playerViewModel = playerViewModel,
            onOpen = { nowPlayingOpen = true },
        )
    }

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
                asked = true
                launcher.launch(permission)
            },
        )
    }
}
