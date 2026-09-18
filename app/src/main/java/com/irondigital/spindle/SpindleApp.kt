package com.irondigital.spindle

import android.app.Application
import android.content.Context
import com.irondigital.spindle.data.db.SpindleDatabase
import com.irondigital.spindle.data.lyrics.LyricsRepository
import com.irondigital.spindle.data.repo.CollectionsRepository
import com.irondigital.spindle.data.repo.LibraryRepository
import com.irondigital.spindle.data.repo.SmartPlaylistProvider
import com.irondigital.spindle.data.repo.StatsRepository
import com.irondigital.spindle.data.settings.SettingsStore
import com.irondigital.spindle.playback.PlaybackSnapshotStore
import kotlinx.coroutines.CoroutineScope
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

    val database: SpindleDatabase by lazy { SpindleDatabase.build(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val snapshotStore: PlaybackSnapshotStore by lazy { PlaybackSnapshotStore(this) }

    val library: LibraryRepository by lazy {
        LibraryRepository(this, settingsStore, applicationScope)
    }
    val stats: StatsRepository by lazy { StatsRepository(database.statsDao()) }
    val collections: CollectionsRepository by lazy {
        CollectionsRepository(database.favoritesDao(), database.playlistDao())
    }
    val lyrics: LyricsRepository by lazy { LyricsRepository(this, database.lyricsDao()) }
    val smartPlaylists: SmartPlaylistProvider by lazy {
        SmartPlaylistProvider(library, stats, collections)
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }
}

val Context.spindle: SpindleApp
    get() = applicationContext as SpindleApp
