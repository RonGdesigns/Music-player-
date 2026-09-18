package com.irondigital.spindle.widget

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
 * The subtlety that makes this work at all: **a controller command is dispatched
 * asynchronously, so releasing the controller straight afterwards can unbind the
 * service before it has processed the command.** When the service was only bound
 * and never started — which is the normal state after the process has been away
 * for a while — that unbind destroys it, and the button silently does nothing.
 * So every action here waits for the player to actually reflect the command
 * before letting go. Waiting until playback has begun also gives Media3 time to
 * post its notification, at which point the service is a started foreground
 * service and survives the unbind on its own.
 */
@OptIn(UnstableApi::class)
private suspend fun withController(
    context: Context,
    block: suspend (MediaController) -> Unit,
) {
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
        } ?: return@withContext

        try {
            block(controller)
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

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val COMMAND_TIMEOUT_MS = 2_500L
private const val QUEUE_TIMEOUT_MS = 2_000L

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
                controller.awaitState { it.isPlaying }
            }
        }
        NowPlayingWidget.refresh(context)
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
        NowPlayingWidget.refresh(context)
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
            controller.seekToPreviousMediaItem()
            controller.awaitState {
                it.currentMediaItemIndex != startIndex || it.currentPosition < startPosition
            }
        }
        NowPlayingWidget.refresh(context)
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
            controller.awaitState { it.isPlaying && it.currentMediaItemIndex == index }
        }
        NowPlayingWidget.refresh(context)
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
        NowPlayingWidget.refresh(context)
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
        NowPlayingWidget.refresh(context)
    }
}

@OptIn(UnstableApi::class)
class ToggleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            // A custom command returns a future, so unlike the player commands
            // this one can simply be awaited.
            runCatching {
                controller.sendCustomCommand(
                    SessionCommand(PlaybackService.COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY),
                    Bundle.EMPTY,
                ).await()
            }
        }
        NowPlayingWidget.refresh(context)
    }
}
