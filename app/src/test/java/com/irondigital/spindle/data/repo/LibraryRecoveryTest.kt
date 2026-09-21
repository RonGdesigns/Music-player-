package com.irondigital.spindle.data.repo

import android.net.Uri
import androidx.room.Room
import com.irondigital.spindle.SpindleApp
import com.irondigital.spindle.data.backup.BackupCodec
import com.irondigital.spindle.data.backup.BackupRepository
import com.irondigital.spindle.data.db.*
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.data.settings.SettingsStore
import com.irondigital.spindle.playback.AutoMediaLibrary
import com.irondigital.spindle.playback.toMediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryRecoveryTest {
    private lateinit var app: SpindleApp
    private lateinit var db: SpindleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryRepository
    private var files = emptyList<Track>()
    private var scanFailure: Exception? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as SpindleApp
        db = Room.inMemoryDatabaseBuilder(app, SpindleDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        library = LibraryRepository(app, SettingsStore(app), db.trackEditDao(), scope) {
            scanFailure?.let { throw it }
            files
        }
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun track(id: Long, title: String = "untagged", albumId: Long = 7) = Track(
        id, Uri.parse("content://media/external/audio/media/$id"), title, "Unknown artist", "Album", "",
        albumId, null, 180000, 1, 1, 2000, "/Music/song.mp3", "song.mp3", 1000,
        "audio/mpeg", 128000, 0, 0,
    )

    private fun backup() = BackupRepository(library, db.statsDao(), db.favoritesDao(), db.playlistDao(), db.trackEditDao())

    @Test fun `corrected metadata survives export and restore with new MediaStore ids`() = runBlocking {
        files = listOf(track(1))
        assertTrue(library.refresh())
        library.saveEdit(TrackEdit("1", title = "Dreams", artist = "Fleetwood Mac", updatedAt = 1))
        withTimeout(5000) { library.tracks.first { it.firstOrNull()?.title == "Dreams" } }
        db.statsDao().upsert(PlayStat(mediaId = "1", playCount = 9, msListened = 90000))
        db.favoritesDao().add(Favorite("1", 1))
        val playlist = db.playlistDao().insertPlaylist(Playlist(name = "Road", createdAt = 1, updatedAt = 1))
        db.playlistDao().append(playlist, listOf("1"), 1)
        val exported = BackupCodec.decode(BackupCodec.encode(backup().export()))!!
        assertEquals(2, exported.version)
        assertEquals("untagged", exported.tracks.single().sourceTitle)
        files = listOf(track(99))
        assertTrue(library.refresh())
        assertEquals("untagged", library.trackFor("99")!!.title)
        val report = backup().restore(exported)
        assertEquals(1, report.tracksMatched)
        assertEquals(1, report.editsRestored)
        assertEquals(1, report.playlistItemsMatched)
        assertEquals(9, db.statsDao().get("99")!!.playCount)
        assertEquals("Dreams", db.trackEditDao().get("99")!!.title)
        assertTrue(db.favoritesDao().getAll().any { it.mediaId == "99" })
        val restored = db.playlistDao().getAllPlaylists().first { it.name == "Road (restored)" }
        assertEquals(listOf("99"), db.playlistDao().getItems(restored.id))
    }

    @Test fun `failed scans preserve the last library and a retry recovers`() = runBlocking {
        files = listOf(track(1))
        assertTrue(library.refresh())
        scanFailure = IOException("Provider unavailable")
        assertFalse(library.refresh())
        assertEquals(listOf("1"), library.tracks.value.map { it.mediaId })
        assertEquals(LibraryRepository.ScanState.ERROR, library.scanState.value)
        assertNotNull(library.scanError.value)
        scanFailure = null
        files = listOf(track(2))
        assertTrue(library.refresh())
        assertNull(library.scanError.value)
        assertEquals("2", library.tracks.value.single().mediaId)
    }

    @Test fun `scan cancellation propagates and restores the previous state`() = runBlocking {
        files = listOf(track(1))
        library.refresh()
        scanFailure = CancellationException("Canceled scan")
        try { library.refresh(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertEquals(LibraryRepository.ScanState.READY, library.scanState.value)
        assertEquals("1", library.tracks.value.single().mediaId)
    }

    @Test fun `explicit song stays single while browse selection keeps its album`() = runBlocking {
        files = listOf(track(1, "One"), track(2, "Two"), track(3, "Other", 8))
        library.refresh()
        val auto = AutoMediaLibrary(app, library)
        assertNull(auto.queueForSelection("1"))
        assertEquals(listOf("1"), auto.resolvePlayable(listOf(files[0].toMediaItem())).map { it.mediaId })
        val album = auto.children(AutoMediaLibrary.ALBUMS_ID).first()
        val selected = auto.children(album.mediaId)[1]
        val queue = auto.queueForSelection(selected.mediaId)!!
        assertEquals(listOf("1", "2"), queue.first.map { it.mediaId })
        assertEquals(1, queue.second)
        assertEquals(selected.mediaId, auto.item(selected.mediaId)!!.mediaId)
        assertEquals(files[1].artUri, queue.first[1].mediaMetadata.artworkUri)
    }
}
