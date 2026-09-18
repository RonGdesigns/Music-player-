package com.irondigital.spindle.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.repo.LibraryRepository
import com.irondigital.spindle.data.repo.SmartPlaylist
import com.irondigital.spindle.data.settings.LibrarySort
import kotlinx.coroutines.launch
import com.irondigital.spindle.ui.Destination
import com.irondigital.spindle.ui.EmptyLibrary
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.components.Artwork
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.components.IndexRail
import com.irondigital.spindle.ui.components.IndexRailEntry
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.components.TrackActionSheet
import com.irondigital.spindle.ui.components.TrackRow
import com.irondigital.spindle.ui.components.formatTotalDuration
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

private enum class LibraryTab(val label: String) {
    HOME("Home"),
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    FOLDERS("Folders"),
    LISTS("Lists"),
}

@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onOpen: (Destination) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.HOME) }
    var searching by rememberSaveable { mutableStateOf(false) }

    val scanState by libraryViewModel.scanState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
            .statusBarsPadding()
    ) {
        LibraryHeader(
            searching = searching,
            query = libraryViewModel.search.collectAsStateWithLifecycle().value,
            onQueryChange = libraryViewModel::setSearch,
            onToggleSearch = {
                searching = !searching
                if (!searching) libraryViewModel.setSearch("")
            },
            onOpenSettings = onOpenSettings,
        )

        if (searching) {
            SearchResults(libraryViewModel, playerViewModel, onOpenNowPlaying)
            return@Column
        }

        TabBar(selected = tab, onSelect = { tab = it })

        if (scanState == LibraryRepository.ScanState.EMPTY) {
            EmptyLibrary(onRescan = libraryViewModel::refresh)
            return@Column
        }

        when (tab) {
            LibraryTab.HOME -> HomeTab(libraryViewModel, playerViewModel, onOpen, onOpenNowPlaying)
            LibraryTab.SONGS -> SongsTab(libraryViewModel, playerViewModel, onOpen, onOpenNowPlaying)
            LibraryTab.ALBUMS -> AlbumsTab(libraryViewModel, onOpen)
            LibraryTab.ARTISTS -> ArtistsTab(libraryViewModel, onOpen)
            LibraryTab.FOLDERS -> FoldersTab(libraryViewModel, onOpen)
            LibraryTab.LISTS -> ListsTab(libraryViewModel, onOpen)
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun LibraryHeader(
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.s, top = Space.s, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = SpindleType.ScreenTitle.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Lamp.Bright),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search",
                            style = SpindleType.ScreenTitle,
                            color = Steel.Engrave,
                        )
                    }
                    inner()
                },
            )
        } else {
            Text(
                text = "Spindle",
                style = SpindleType.ScreenTitle,
                color = Ink.Primary,
                modifier = Modifier.weight(1f),
            )
        }

        LampIconButton(
            icon = if (searching) Icons.Filled.Close else Icons.Filled.Search,
            contentDescription = if (searching) "Close search" else "Search",
            onClick = onToggleSearch,
            lit = searching,
        )
        LampIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
    }
}

/**
 * The tab row. The selected tab lights rather than growing an underline bar —
 * the same lamp language as the transport, so selection means one thing
 * throughout the app.
 */
@Composable
private fun TabBar(selected: LibraryTab, onSelect: (LibraryTab) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            LibraryTab.entries.forEach { entry ->
                val isSelected = entry == selected
                val color by animateColorAsState(
                    targetValue = if (isSelected) Lamp.Bright else Steel.Dim,
                    animationSpec = if (isSelected) Motion.lampOn() else Motion.lampOff(),
                    label = "tab-${entry.name}",
                )
                Column(
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(vertical = Space.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = entry.label, style = SpindleType.Section, color = color)
                    Spacer(Modifier.height(Space.xs))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .background(if (isSelected) Lamp.Bright else Color.Transparent)
                    )
                }
            }
        }
        Groove()
    }
}

// -------------------------------------------------------------------- home

/**
 * Modular cells of genuinely unequal weight. Most Played is the reason this
 * app exists, so it gets a full-width cell with the artwork bled into it; the
 * rest are half-width plates. Nine equal cells with icons would be a bento
 * clone and would say nothing about which of these matters.
 */
@Composable
private fun HomeTab(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onOpen: (Destination) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
    // Each smartPlaylist() call builds a new combined flow, so they are
    // remembered rather than rebuilt and re-collected on every recomposition.
    val mostPlayedFlow = remember(settings.mostPlayedSize) {
        libraryViewModel.smartPlaylist(SmartPlaylist.MOST_PLAYED, settings.mostPlayedSize)
    }
    val recentlyAddedFlow = remember {
        libraryViewModel.smartPlaylist(SmartPlaylist.RECENTLY_ADDED, 20)
    }
    val mostPlayed by mostPlayedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentlyAdded by recentlyAddedFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val secondaryShelves = listOf(
        SmartPlaylist.MOST_PLAYED_MONTH,
        SmartPlaylist.FAVORITES,
        SmartPlaylist.RECENTLY_PLAYED,
        SmartPlaylist.FORGOTTEN,
        SmartPlaylist.NEVER_PLAYED,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.xxl),
    ) {
        item {
            MostPlayedCell(
                tracks = mostPlayed,
                size = settings.mostPlayedSize,
                onOpen = { onOpen(Destination.Smart(SmartPlaylist.MOST_PLAYED)) },
                onPlay = {
                    playerViewModel.play(mostPlayed)
                    onOpenNowPlaying()
                },
                onShuffle = {
                    playerViewModel.shufflePlay(mostPlayed)
                    onOpenNowPlaying()
                },
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = Space.gutter)) {
                Spacer(Modifier.height(Space.l))
                TickScale(height = 10.dp, spacing = 6.dp)
                Spacer(Modifier.height(Space.l))
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                ShelfPlate(
                    playlist = secondaryShelves[0],
                    modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.Smart(secondaryShelves[0])) },
                )
                ShelfPlate(
                    playlist = secondaryShelves[1],
                    modifier = Modifier.weight(1f),
                    onClick = { onOpen(Destination.Smart(secondaryShelves[1])) },
                )
            }
        }

        item { Spacer(Modifier.height(Space.m)) }

        items(secondaryShelves.drop(2)) { playlist ->
            ShelfRow(
                playlist = playlist,
                onClick = { onOpen(Destination.Smart(playlist)) },
            )
        }

        item {
            ShelfRow(
                title = "Listening",
                subtitle = "Your play counts, streaks and hours, drawn out",
                onClick = { onOpen(Destination.Stats) },
            )
        }

        if (recentlyAdded.isNotEmpty()) {
            item {
                Column {
                    Spacer(Modifier.height(Space.l))
                    Text(
                        text = "Recently added",
                        style = SpindleType.Section,
                        color = Ink.Primary,
                        modifier = Modifier.padding(horizontal = Space.gutter),
                    )
                    Spacer(Modifier.height(Space.m))
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Space.gutter
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        items(recentlyAdded) { track ->
                            Column(
                                modifier = Modifier
                                    .width(116.dp)
                                    .clickable {
                                        playerViewModel.play(
                                            recentlyAdded,
                                            recentlyAdded.indexOf(track),
                                        )
                                        onOpenNowPlaying()
                                    }
                            ) {
                                Artwork(
                                    uri = track.albumArtUri?.toString(),
                                    size = 116,
                                    contentDescription = track.album,
                                )
                                Spacer(Modifier.height(Space.s))
                                Text(
                                    text = track.title,
                                    style = SpindleType.Secondary,
                                    color = Ink.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artist,
                                    style = SpindleType.Data,
                                    color = Steel.Dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MostPlayedCell(
    tracks: List<Track>,
    size: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val leadArt = tracks.firstOrNull()?.albumArtUri?.toString()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clickable(onClick = onOpen)
    ) {
        // Full bleed: the artwork runs to both edges, which is the one place in
        // the library where an element ignores the gutter. That break is the
        // division — no rule needed.
        if (leadArt != null) {
            AsyncImage(
                model = leadArt,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Ground.Plate))
        }

        // The scrim is a gradient rather than a flat wash, so the art survives
        // at the top while the text stays at full contrast at the bottom.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ground.Deep.copy(alpha = 0.55f),
                        0.45f to Ground.Deep.copy(alpha = 0.82f),
                        1f to Ground.Deep.copy(alpha = 0.97f),
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Space.gutter),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = "Most Played",
                style = SpindleType.Display,
                color = Ink.Primary,
                maxLines = 1,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = if (tracks.isEmpty()) {
                    "Nothing counted yet — play something and this fills itself in"
                } else {
                    "Top ${minOf(tracks.size, size)}, kept current automatically"
                },
                style = SpindleType.Secondary,
                color = Steel.Bright,
                maxLines = 2,
            )
            Spacer(Modifier.height(Space.m))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LampIconButton(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "Play Most Played",
                    onClick = onPlay,
                    lit = true,
                    enabled = tracks.isNotEmpty(),
                    size = 44.dp,
                    iconSize = 26.dp,
                )
                LampIconButton(
                    icon = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle Most Played",
                    onClick = onShuffle,
                    enabled = tracks.isNotEmpty(),
                    size = 44.dp,
                )
                if (tracks.isNotEmpty()) {
                    Text(
                        text = formatTotalDuration(tracks.sumOf { it.durationMs }),
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfPlate(
    playlist: SmartPlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Corner.plate))
            .background(Ground.Plate)
            .clickable(onClick = onClick)
            .padding(Space.m)
            .height(84.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.width(20.dp).height(2.dp).background(Lamp.Bright))
        Column {
            Text(
                text = playlist.title,
                style = SpindleType.RowTitle,
                color = Ink.Primary,
                maxLines = 1,
            )
            Text(
                text = playlist.subtitle,
                style = SpindleType.Data,
                color = Steel.Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShelfRow(playlist: SmartPlaylist, onClick: () -> Unit) =
    ShelfRow(playlist.title, playlist.subtitle, onClick)

@Composable
private fun ShelfRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(2.dp).height(26.dp).background(Steel.Engrave))
        Spacer(Modifier.width(Space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = SpindleType.RowTitle, color = Ink.Primary)
            Text(subtitle, style = SpindleType.Secondary, color = Steel.Dim, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------- tabs

@Composable
private fun SongsTab(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onOpen: (Destination) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val tracks by libraryViewModel.tracks.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val favorites by playerViewModel.favoriteIds.collectAsStateWithLifecycle()
    val counts by playerViewModel.playCounts.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()

    var actionsFor by remember { mutableStateOf<Track?>(null) }

    val settings by libraryViewModel.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val railEntries = rememberRailEntries(tracks, settings.librarySort, counts)

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            items(tracks, key = { it.mediaId }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = track.mediaId == playback.mediaId,
                    isPlaying = playback.isPlaying,
                    isFavorite = track.mediaId in favorites,
                    playCount = counts[track.mediaId] ?: 0,
                    onClick = {
                        playerViewModel.play(tracks, tracks.indexOf(track))
                        onOpenNowPlaying()
                    },
                    onLongClick = { actionsFor = track },
                )
            }
        }

        AttachedIndexRail(entries = railEntries, listState = listState)
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
            onGoToAlbum = { onOpen(Destination.Album(track.albumId)) },
            onGoToArtist = { onOpen(Destination.Artist(track.artist)) },
            onDismiss = { actionsFor = null },
        )
    }
}

@Composable
private fun AlbumsTab(libraryViewModel: LibraryViewModel, onOpen: (Destination) -> Unit) {
    val albums by libraryViewModel.albums.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        items(albums, key = { it.albumId }) { album ->
            Column(
                modifier = Modifier.clickable { onOpen(Destination.Album(album.albumId)) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Corner.edge))
                        .background(Ground.Raised),
                ) {
                    if (album.artUri != null) {
                        AsyncImage(
                            model = album.artUri,
                            contentDescription = album.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    text = album.name,
                    style = SpindleType.RowTitle,
                    color = Ink.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = SpindleType.Secondary,
                    color = Steel.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${album.trackCount} tracks",
                    style = SpindleType.Data,
                    color = Steel.Engrave,
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(libraryViewModel: LibraryViewModel, onOpen: (Destination) -> Unit) {
    val artists by libraryViewModel.artists.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val railEntries = rememberLabelRailEntries(artists.map { it.name })

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(artists, key = { it.name }) { artist ->
                GroupRow(
                    title = artist.name,
                    subtitle = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                    trailing = formatTotalDuration(artist.totalDurationMs),
                    onClick = { onOpen(Destination.Artist(artist.name)) },
                )
            }
        }
        AttachedIndexRail(entries = railEntries, listState = listState)
    }
}

@Composable
private fun FoldersTab(libraryViewModel: LibraryViewModel, onOpen: (Destination) -> Unit) {
    val folders by libraryViewModel.folders.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val railEntries = rememberLabelRailEntries(folders.map { it.name })

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(folders, key = { it.path }) { folder ->
                GroupRow(
                    title = folder.name,
                    subtitle = folder.path,
                    trailing = "${folder.trackCount}",
                    onClick = { onOpen(Destination.Folder(folder.path)) },
                )
            }
        }
        AttachedIndexRail(entries = railEntries, listState = listState)
    }
}

@Composable
private fun ListsTab(libraryViewModel: LibraryViewModel, onOpen: (Destination) -> Unit) {
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val counts by libraryViewModel.playlistCounts.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { creating = true }
                    .padding(horizontal = Space.gutter, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Lamp.Bright,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Space.m))
                Text("New playlist", style = SpindleType.RowTitle, color = Lamp.Bright)
            }
            Groove()
        }

        items(playlists, key = { it.id }) { playlist ->
            GroupRow(
                title = playlist.name,
                subtitle = "${counts[playlist.id] ?: 0} tracks",
                trailing = null,
                onClick = { onOpen(Destination.UserPlaylist(playlist.id, playlist.name)) },
            )
        }

        if (playlists.isEmpty()) {
            item {
                Text(
                    text = "Playlists you build by hand live here. The automatic " +
                        "ones — Most Played, On Repeat, Favourites — are on Home " +
                        "and look after themselves.",
                    style = SpindleType.Body,
                    color = Steel.Dim,
                    modifier = Modifier.padding(Space.gutter),
                )
            }
        }
    }

    if (creating) {
        NamePlaylistDialog(
            onDismiss = { creating = false },
            onConfirm = { name ->
                libraryViewModel.createPlaylist(name)
                creating = false
            },
        )
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    trailing: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SpindleType.RowTitle,
                color = Ink.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = SpindleType.Secondary,
                color = Steel.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Text(text = trailing, style = SpindleType.Data, color = Steel.Dim)
        }
    }
}

@Composable
private fun SearchResults(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onOpenNowPlaying: () -> Unit,
) {
    val results by libraryViewModel.searchResults.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val counts by playerViewModel.playCounts.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.mediaId }) { track ->
            TrackRow(
                track = track,
                isCurrent = track.mediaId == playback.mediaId,
                isPlaying = playback.isPlaying,
                playCount = counts[track.mediaId] ?: 0,
                onClick = {
                    playerViewModel.play(results, results.indexOf(track))
                    onOpenNowPlaying()
                },
            )
        }
    }
}

@Composable
private fun NamePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = { Text("Name this playlist", style = SpindleType.Section, color = Ink.Primary) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = SpindleType.Body.copy(color = Ink.Primary),
                cursorBrush = SolidColor(Lamp.Bright),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ground.Raised)
                    .padding(Space.m),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
            }
        },
    )
}


// ------------------------------------------------------------------- rail

/**
 * Builds the rail stops for a track list, keyed so the work only repeats when the
 * list, the sort or the play counts actually change.
 */
@Composable
private fun rememberRailEntries(
    tracks: List<Track>,
    sort: LibrarySort,
    counts: Map<String, Int>,
): List<IndexRailEntry> = remember(tracks, sort, counts) {
    ListIndex.sampleForDisplay(ListIndex.forTracks(tracks, sort, counts)).map { entry ->
        IndexRailEntry(
            shortLabel = ListIndex.displayLabel(entry, sort),
            spokenLabel = ListIndex.spokenLabel(entry, sort),
            itemIndex = entry.itemIndex,
        )
    }
}

@Composable
private fun rememberLabelRailEntries(labels: List<String>): List<IndexRailEntry> =
    remember(labels) {
        ListIndex.sampleForDisplay(ListIndex.forLabels(labels)).map { entry ->
            IndexRailEntry(entry.label, entry.label, entry.itemIndex)
        }
    }

/**
 * Wires a rail to a list: the rail scrolls the list, and the list drives which
 * stop is lit when the user is scrolling by hand instead.
 */
@Composable
private fun AttachedIndexRail(
    entries: List<IndexRailEntry>,
    listState: LazyListState,
) {
    val scope = rememberCoroutineScope()

    // derivedStateOf so a scroll only recomposes the rail when the lit stop
    // actually changes, not on every pixel of every fling.
    val activeEntryIndex by remember(entries) {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            entries.indexOfLast { it.itemIndex <= first }.coerceAtLeast(0)
        }
    }

    IndexRail(
        entries = entries,
        activeEntryIndex = activeEntryIndex,
        onJump = { itemIndex ->
            scope.launch { listState.scrollToItem(itemIndex) }
        },
    )
}
