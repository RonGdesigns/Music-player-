package com.irondigital.spindle.data.personal

import android.net.Uri
import android.graphics.Bitmap
import com.irondigital.spindle.SpindleApp
import com.irondigital.spindle.data.db.PlayStat
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ListeningFeaturesTest {
    @org.junit.Before fun requireRealImageData() { org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(false) }
    private val day = 86_400_000L
    private val now = 100 * day
    private fun track(id: Long = 1, title: String = "Night Drive", duration: Long = 180000, added: Long = 99 * day) = Track(
        id, Uri.parse("content://media/external/audio/media/$id"), title, "Artist", "Album", "", 7, null,
        duration, 1, 1, 2000, "/Music/$id.mp3", "$id.mp3", 1000, "audio/mpeg", 128000, added / 1000, 0)
    private fun snapshot() = PlaybackSnapshot(currentMediaId = "2", title = "River Walk", currentIndex = 1,
        positionMs = 36000, shuffleEnabled = true, repeatMode = 2, playbackOrder = listOf(2,1,0), queue = listOf(
            QueueEntry("1", "Night Drive", "Artist", 60000), QueueEntry("2", "River Walk", "Artist", 60000), QueueEntry("1", "Night Drive", "Artist", 60000)))

    @Test fun `listening data round trip preserves every feature and duplicate queue entries`() {
        val data = ListeningData(sessions = listOf(ListeningSession("session", "Driving", snapshot(), 12)),
            pins = listOf(PinnedCollection("folder", "/Music/Drive", "Drive")),
            bookmarks = listOf(TrackBookmark("b", "2", "River Walk", 12000, "Solo")),
            mixes = listOf(MixRule("mix", "Rediscover", true, 30, 0, 5, MixSort.LEAST_PLAYED)),
            widget = WidgetAppearance(WidgetStyle.ARTWORK, WidgetDensity.COMPACT), activeSessionId = "session")
        assertEquals(data, ListeningData.fromJson(data.toJson()))
    }
    @Test fun `unknown store version is rejected instead of erased`() {
        assertThrows(IllegalArgumentException::class.java) { ListeningData.fromJson("{\"version\":9}") }
    }
    @Test fun `malformed store is rejected instead of erased`() {
        assertThrows(org.json.JSONException::class.java) { ListeningData.fromJson("broken") }
    }
    @Test fun `favorites rule excludes unmarked tracks`() {
        val rule = MixRule(name = "Favorites", favoritesOnly = true)
        assertEquals(listOf("1"), rule.select(listOf(track(), track(2)), setOf("1"), emptyMap(), now).map { it.mediaId })
    }
    @Test fun `unplayed rules include never played and exact day boundary`() {
        val rule = MixRule(name = "Rediscover", unplayedDays = 30)
        assertTrue(rule.matches(track(), false, null, now))
        assertTrue(rule.matches(track(), false, PlayStat("1", lastPlayedAt = now - 30*day), now))
        assertFalse(rule.matches(track(), false, PlayStat("1", lastPlayedAt = now - 29*day), now))
    }
    @Test fun `recent addition and length rules intersect`() {
        val rule = MixRule(name = "New short tracks", addedDays = 7, maxDurationMinutes = 5)
        assertTrue(rule.matches(track(duration = 300000, added = now-7*day), false, null, now))
        assertFalse(rule.matches(track(duration = 300001), false, null, now))
        assertFalse(rule.matches(track(added = now-8*day), false, null, now))
    }
    @Test fun `zero rules leave the full library eligible`() {
        assertTrue(MixRule(name = "All").matches(track(added = 0), false, PlayStat("1",lastPlayedAt = now),now))
    }
    @Test fun `least played order responds to current stats`() {
        val rule = MixRule(name = "Rare", sort = MixSort.LEAST_PLAYED)
        assertEquals(listOf("2","1"), rule.select(listOf(track(),track(2)),emptySet(),mapOf("1" to PlayStat("1",playCount=9)),now).map { it.mediaId })
    }
    @Test fun `session filtering preserves duplicate occurrences and current position`() {
        val result = snapshot().availableSession(setOf("1"))
        assertEquals(listOf("1","1"),result.queue.map { it.mediaId })
        assertEquals(listOf(1,0),result.playbackOrder)
        assertEquals(0,result.currentIndex)
        assertEquals(0L,result.positionMs)
        val current = snapshot().availableSession(setOf("2"))
        assertEquals(36000L,current.positionMs)
        assertEquals(0,current.currentIndex)
    }
    @Test fun `entirely missing session has no playable content`() {
        val result=snapshot().availableSession(emptySet())
        assertFalse(result.hasContent); assertEquals(-1,result.currentIndex)
    }
    @Test fun `loop validation rejects reversed short out of range and other track bounds`() {
        assertTrue(LoopRegion("1",1000,2000).validFor("1",60000))
        assertFalse(LoopRegion("1",1000,1200).validFor("1",60000))
        assertFalse(LoopRegion("1",2000,1000).validFor("1",60000))
        assertFalse(LoopRegion("1",1000,60000).validFor("1",60000))
        assertFalse(LoopRegion("1",1000,2000).validFor("2",60000))
    }
    @Test fun `loop only seeks the playing owner after B`() {
        val loop=LoopRegion("1",1000,2000)
        assertTrue(loop.shouldSeek("1",2000,true))
        assertFalse(loop.shouldSeek("1",1999,true))
        assertFalse(loop.shouldSeek("1",3000,false))
        assertFalse(loop.shouldSeek("2",3000,true))
    }
    @Test fun `atomic store edits preserve concurrent pins and bookmarks`() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as SpindleApp
        val store=app.listening
        store.update { ListeningData() }
        coroutineScope {
            launch { store.update { it.copy(pins=listOf(PinnedCollection("album","7","Album"))) } }
            launch { store.update { it.copy(bookmarks=listOf(TrackBookmark(mediaId="1",title="Song",positionMs=1000,label="Start"))) } }
        }
        val saved=store.data.first()
        assertEquals(1,saved.pins.size);assertEquals(1,saved.bookmarks.size)
    }
    @Test fun `session save is paused and validation leaves the saved data intact`() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as SpindleApp
        app.listening.saveSession("one","Driving",snapshot().copy(isPlaying=true))
        val saved=app.listening.data.first()
        assertFalse(saved.sessions.first().snapshot.isPlaying)
        try { app.listening.saveSession("bad","",snapshot()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(saved,app.listening.data.first())
    }
    @Test fun `custom cover survives repository recreation and track reset falls back to album`() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as SpindleApp
        val source=File(app.cacheDir,"cover.png")
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        app.customArtwork.set("album:7",Uri.fromFile(source))
        val album=app.customArtwork.uriFor("1",7)
        app.customArtwork.set("track:1",Uri.fromFile(source))
        assertNotEquals(album,app.customArtwork.uriFor("1",7))
        assertEquals(app.customArtwork.uriFor("1",7),CustomArtwork(app).uriFor("1",7))
        app.customArtwork.clear("track:1")
        assertEquals(album,app.customArtwork.uriFor("1",7))
    }
    @Test fun `bad artwork does not replace a working override`() = runBlocking {
        val app=RuntimeEnvironment.getApplication() as SpindleApp
        val source=File(app.cacheDir,"bad.png").apply { writeText("not an image") }
        val before=app.customArtwork.uriFor("1",7)
        try { app.customArtwork.set("track:1",Uri.fromFile(source));fail() } catch (_: IllegalArgumentException) {}
        assertEquals(before,app.customArtwork.uriFor("1",7))
    }
}
