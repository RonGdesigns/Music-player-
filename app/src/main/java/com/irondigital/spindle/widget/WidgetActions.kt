package com.irondigital.spindle.widget

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.annotation.OptIn
import com.irondigital.spindle.playback.PlaybackService
import com.irondigital.spindle.playback.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "SpindleWidget"

/**
 * Widget buttons act on the session through a short-lived MediaController.
 *
 * The obvious alternative — firing an Intent at the service — means the service
 * may be cold-started with a custom action and has five seconds to put up a
 * foreground notification or be killed by the platform. Connecting a controller
 * hands that problem to Media3, which starts the service, waits for the session
 * and manages the foreground transition itself. It also means the widget
 * traverses exactly the same path as every other surface.
 *
 * Three things have to be right, and each of them fails silently if it is not.
 *
 * **The context must be the application context.** A Glance action callback runs
 * inside a manifest-declared BroadcastReceiver, and the platform hands those a
 * ReceiverRestrictedContext, which refuses `bindService` outright — it throws
 * `ReceiverCallNotAllowedException`. Building a MediaController binds to the
 * session service, so a controller built on the receiver's own context can never
 * connect and every button on the widget does nothing at all. This was that bug.
 *
 * **A command is dispatched asynchronously**, so releasing the controller
 * straight afterwards can unbind the service before it has processed it.
 *
 * **An unbind can destroy the service.** While the service is only bound and not
 * yet a started foreground service — the normal state after the process has been
 * away — the platform destroys it the moment the last client lets go. Media3
 * promotes it when it posts its notification, which happens shortly *after*
 * `isPlaying` turns true, so a release timed on that alone can still kill
 * playback a fraction of a second after starting it.
 *
 * Hence: app context, wait for the player to reflect the command, and give
 * Media3 a beat to take the service foreground before letting go.
 */
@OptIn(UnstableApi::class)
private suspend fun withController(
    context: Context,
    block: suspend (MediaController) -> Unit,
) {
    // Not the receiver's context. See above — this single line is the
    // difference between every button working and none of them working.
    val appContext = context.applicationContext

    withContext(Dispatchers.Main) {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))

        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            try {
                MediaController.Builder(appContext, token).buildAsync().await()
            } catch (e: Exception) {
                // Logged rather than swallowed. A silent catch here is what let
                // a hard platform refusal look like an unresponsive button.
                Log.w(TAG, "Could not connect to the playback session", e)
                null
            }
        }

        if (controller == null) {
            Log.w(TAG, "No controller: the widget press could not reach the player")
            return@withContext
        }

        try {
            block(controller)
        } catch (e: Exception) {
            Log.w(TAG, "Widget action failed", e)
        } finally {
            // Leaking a controller keeps the service bound and alive forever.
            controller.release()
        }
    }
}

/**
 * Suspends until [condition] holds, or the timeout expires. Returns whether it
 * held — callers use that to decide whether it is safe to let go.
 */
@OptIn(UnstableApi::class)
private suspend fun MediaController.awaitState(
    timeoutMs: Long = COMMAND_TIMEOUT_MS,
    condition: (MediaController) -> Boolean,
): Boolean {
    if (condition(this)) return true
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (condition(this@awaitState)) {
                        removeListener(this)
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
            }
            addListener(listener)
            continuation.invokeOnCancellation { removeListener(listener) }
        }
    } ?: false
}

/**
 * The service restores its saved queue asynchronously on creation, so a cold
 * start can hand back a controller whose timeline is still empty. Issuing play
 * against an empty timeline does nothing at all, which is the other half of why
 * a widget button can look dead.
 */
@OptIn(UnstableApi::class)
private suspend fun MediaController.awaitQueue(): Boolean =
    awaitState(QUEUE_TIMEOUT_MS) { it.mediaItemCount > 0 }

/**
 * Waits for playback to actually be running, then holds on a moment longer so
 * Media3 can post its notification and take the service foreground. Releasing
 * inside that window destroys a merely-bound service, which stops the music a
 * heartbeat after the button appeared to work.
 */
@OptIn(UnstableApi::class)
private suspend fun MediaController.awaitPlaybackStarted() {
    if (awaitState { it.isPlaying }) delay(FOREGROUND_GRACE_MS)
}

/**
 * Every timeout here is spent inside a BroadcastReceiver, which the platform
 * gives roughly ten seconds before it may kill the process. The worst case has
 * to stay comfortably under that, so these are deliberately tighter than they
 * would be anywhere else. In practice each resolves in a few milliseconds.
 */
private const val CONNECT_TIMEOUT_MS = 4_000L
private const val COMMAND_TIMEOUT_MS = 1_500L
private const val QUEUE_TIMEOUT_MS = 1_500L
private const val FOREGROUND_GRACE_MS = 500L

/** Long enough for the service's debounced snapshot write to have landed. */
private const val SNAPSHOT_SETTLE_MS = 200L

private suspend fun refreshWidgets(context: Context) {
    delay(SNAPSHOT_SETTLE_MS)
    NowPlayingWidget.refresh(context.applicationContext)
}

@OptIn(UnstableApi::class)
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (controller.isPlaying) {
                controller.pause()
                controller.awaitState { !it.isPlaying }
            } else {
                if (!controller.awaitQueue()) return@withController
                // A restored-but-unprepared session needs preparing before it
                // will do anything, which is the state after a process death.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.play()
                controller.awaitPlaybackStarted()
            }
        }
        refreshWidgets(context)
    }
}

@OptIn(UnstableApi::class)
class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (!controller.awaitQueue()) return@withController
            val startIndex = controller.currentMediaItemIndex
            controller.seekToNextMediaItem()
            controller.awaitState { it.currentMediaItemIndex != startIndex }
        }
        refreshWidgets(context)
    }
}

@OptIn(UnstableApi::class)
class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (!controller.awaitQueue()) return@withController
            val startIndex = controller.currentMediaItemIndex
            val startPosition = controller.currentPosition
            // Matches every physical transport control ever made: the first
            // press restarts the track, a second one within a few seconds goes
            // back. Media3's own default already does this — which is why the
            // wait has to accept a position change as well as an index change.
            controller.seekToPrevious()
            controller.awaitState {
                it.currentMediaItemIndex != startIndex || it.currentPosition < startPosition
            }
        }
        refreshWidgets(context)
    }
}

/**
 * The queue row tap. This is the behavior Samsung's widget lost: seeing what
 * is coming up and going straight to it without opening anything.
 */
@OptIn(UnstableApi::class)
class JumpToIndexAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[INDEX_KEY] ?: return
        withController(context) { controller ->
            if (!controller.awaitQueue()) return@withController
            if (index !in 0 until controller.mediaItemCount) return@withController
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.seekTo(index, 0L)
            controller.play()
            controller.awaitPlaybackStarted()
        }
        refreshWidgets(context)
    }

    companion object {
        val INDEX_KEY = ActionParameters.Key<Int>("queue_index")
    }
}

@OptIn(UnstableApi::class)
class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            val target = !controller.shuffleModeEnabled
            controller.shuffleModeEnabled = target
            controller.awaitState { it.shuffleModeEnabled == target }
        }
        refreshWidgets(context)
    }
}

@OptIn(UnstableApi::class)
class CycleRepeatAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            val target = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = target
            controller.awaitState { it.repeatMode == target }
        }
        refreshWidgets(context)
    }
}

@OptIn(UnstableApi::class)
class ToggleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            // A custom command returns a future, so unlike the player commands
            // this one can simply be awaited.
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY),
                Bundle.EMPTY,
            ).await()
        }
        refreshWidgets(context)
    }
}
