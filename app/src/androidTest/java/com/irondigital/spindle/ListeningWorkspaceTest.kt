package com.irondigital.spindle

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.*
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.irondigital.spindle.data.personal.*
import com.irondigital.spindle.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs against an isolated emulator with synthetic audio fixtures already in MediaStore. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ListeningWorkspaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as SpindleApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun command(controller: MediaController, action: String, args: Bundle): SessionResult {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<SessionResult>
        main { future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        return future.get(20,TimeUnit.SECONDS)
    }
    @Before fun setup() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.READ_MEDIA_AUDIO")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        runBlocking { app.library.refresh() }
    }
    @Test fun serviceSessionsAndLoopUseTheRealPlayer() = runBlocking {
        val tracks=app.library.tracks.value.take(3)
        assertTrue("Install synthetic audio fixtures before this test",tracks.size >= 3)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
        val controller=future.get(20,TimeUnit.SECONDS)
        try {
            main {
                controller.setMediaItems(tracks.map { it.toMediaItem() },1,12000)
                controller.prepare();controller.shuffleModeEnabled=true;controller.repeatMode=Player.REPEAT_MODE_ALL
            }
            assertEquals(0,command(controller,PlaybackService.COMMAND_SAVE_SESSION,Bundle().apply { putString("id","instrumented-session");putString("name","Test drive") }).resultCode)
            val saved=app.listening.data.first().sessions.first { it.id=="instrumented-session" }
            assertEquals(3,saved.snapshot.queue.size);assertEquals(12000L,saved.snapshot.positionMs)
            main { controller.setMediaItems(listOf(tracks[0].toMediaItem()));controller.prepare() }
            assertEquals(0,command(controller,PlaybackService.COMMAND_RESUME_SESSION,Bundle().apply { putString("id","instrumented-session") }).resultCode)
            main { assertEquals(3,controller.mediaItemCount);assertEquals(tracks[1].mediaId,controller.currentMediaItem?.mediaId)
                assertTrue(controller.shuffleModeEnabled);assertEquals(Player.REPEAT_MODE_ALL,controller.repeatMode);controller.pause() }
            withTimeout(10000) { while(true) { var ready=false;main { ready=controller.duration>0 };if(ready)break;delay(100) } }
            assertEquals(0,command(controller,PlaybackService.COMMAND_SET_LOOP,Bundle().apply {
                putString("mediaId",tracks[1].mediaId);putLong("start",1000);putLong("end",3000)
            }).resultCode)
            main { controller.seekTo(3200);controller.play() }
            withTimeout(10000) { while(true) { var looped=false;main { looped=controller.currentPosition in 1000..2999 };if(looped)break;delay(100) } }
            assertEquals(0,command(controller,PlaybackService.COMMAND_SET_LOOP,Bundle().apply { putLong("start",-1) }).resultCode)
            main { controller.pause() }
        } finally { main { controller.release() } }
    }
    @Test fun navigationPinsRulesWidgetsAndAccessibleControls() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Continue listening")),20000))
        device.findObject(By.text("Manage")).click()
        assertNotNull(device.wait(Until.findObject(By.text("Find a collection")),10000))
        val checkbox = device.findObject(By.clazz("android.widget.CheckBox"))
        checkbox?.click()
        capture("pins")
        device.findObject(By.text("Smart playlists")).click()
        device.wait(Until.findObject(By.text("Create smart playlist")),10000).click()
        assertNotNull(device.wait(Until.findObject(By.text("Smart playlist rules")),10000))
        capture("rules")
        device.pressBack()
        device.wait(Until.findObject(By.text("Widgets")),10000).click()
        assertNotNull(device.wait(Until.findObject(By.text("Widget appearance")),10000))
        device.findObject(By.text("Artwork")).click()
        capture("widgets")
        // Named action and a useful touch target, checked through the native accessibility hierarchy.
        var button=device.findObject(By.text("Apply to widgets"))
        if(button == null) button=device.findObject(By.scrollable(true)).scrollUntil(Direction.DOWN,Until.findObject(By.text("Apply to widgets")))
        assertNotNull(button)
        val actionable=if(button.isClickable) button else button.parent
        assertTrue("Apply button must have a useful touch height", actionable.visibleBounds.height() >= (48 * context.resources.displayMetrics.density).toInt()-2)
        button.click()
        runBlocking { withTimeout(10000) { app.listening.data.first { it.widget.style == WidgetStyle.ARTWORK } } }
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("Continue listening")),10000))
    }
    @Test fun customArtworkProviderServesOwnedCopyAndReset() = runBlocking {
        val track=app.library.tracks.value.first()
        val source=File(context.cacheDir,"instrumented-cover.png")
        val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(143,163,184))
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        app.customArtwork.set("track:${track.mediaId}",android.net.Uri.fromFile(source))
        val uri=app.customArtwork.uriFor(track.mediaId,track.albumId)!!
        source.delete()
        val decoded=context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
        assertNotNull(decoded);assertEquals(64,decoded.width);decoded.recycle()
        app.customArtwork.clear("track:${track.mediaId}")
        assertNotEquals(uri,app.customArtwork.uriFor(track.mediaId,track.albumId))
    }
    private fun capture(name: String) {
        val dir=File(context.getExternalFilesDir(null),"review").apply { mkdirs() }
        device.takeScreenshot(File(dir,"$name.png"))
        device.dumpWindowHierarchy(File(dir,"$name.xml"))
    }
}
