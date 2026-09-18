package com.irondigital.spindle

import android.app.Application
import android.content.Context
import android.net.Uri
import com.irondigital.spindle.data.backup.BackupRepository
import com.irondigital.spindle.data.db.SpindleDatabase
import com.irondigital.spindle.data.lyrics.LyricsRepository
import com.irondigital.spindle.data.media.AudioImporter
import com.irondigital.spindle.data.media.YoutubeAudioDownloader
import com.irondigital.spindle.data.repo.CollectionsRepository
import com.irondigital.spindle.data.repo.GainRepository
import com.irondigital.spindle.data.repo.LibraryRepository
import com.irondigital.spindle.data.repo.SmartPlaylistProvider
import com.irondigital.spindle.data.repo.StatsRepository
import com.irondigital.spindle.data.settings.SettingsStore
import com.irondigital.spindle.playback.EqualizerCapabilities
import com.irondigital.spindle.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Manual dependency graph.
 *
 * A player has one database, one library and one settings store, all of them
 * application-scoped and all of them needed by the service, the widget and the
 * UI alike. That is a service locator's actual job, and it keeps the build free
 * of an annotation processor whose only work would be constructing eight
 * singletons.
 */
class SpindleApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob())

    /**
     * Files shared into the app, waiting to be imported.
     *
     * Held here rather than passed through the activity because the share can
     * arrive while the activity is being recreated, and a URI grant from a share
     * only lasts as long as the receiving activity — so it has to be picked up
     * and copied promptly rather than parked in a saved-state bundle.
     */
    val pendingImports = MutableStateFlow<List<Uri>>(emptyList())

    fun consumePendingImports(): List<Uri> {
        val pending = pendingImports.value
        if (pending.isNotEmpty()) pendingImports.value = emptyList()
        return pending
    }

    /**
     * What the device's own equalizer turned out to offer, filled in by the
     * playback service once it has an audio session.
     *
     * Null means nobody has asked yet — which is not the same as "this device
     * has no equalizer", and the screen says so rather than showing an empty
     * set of bands as though the answer were known.
     */
    val equalizerCapabilities = MutableStateFlow<EqualizerCapabilities?>(null)

    val database: SpindleDatabase by lazy { SpindleDatabase.build(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val snapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(this) }

    val library: LibraryRepository by lazy {
        LibraryRepository(this, settingsStore, database.trackEditDao(), applicationScope)
    }
    val stats: StatsRepository by lazy { StatsRepository(database.statsDao()) }
    val collections: CollectionsRepository by lazy {
        CollectionsRepository(database.favoritesDao(), database.playlistDao())
    }
    val lyrics: LyricsRepository by lazy { LyricsRepository(this, database.lyricsDao()) }
    val gains: GainRepository by lazy { GainRepository(this, database.gainDao()) }
    val importer: AudioImporter by lazy { AudioImporter(this) }
    val youtubeDownloader: YoutubeAudioDownloader by lazy { YoutubeAudioDownloader(this) }
    val smartPlaylists: SmartPlaylistProvider by lazy {
        SmartPlaylistProvider(library, stats, collections)
    }
    val backup: BackupRepository by lazy {
        BackupRepository(
            library = library,
            statsDao = database.statsDao(),
            favoritesDao = database.favoritesDao(),
            playlistDao = database.playlistDao(),
            trackEditDao = database.trackEditDao(),
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}

val Context.spindle: SpindleApp
    get() = applicationContext as SpindleApp
