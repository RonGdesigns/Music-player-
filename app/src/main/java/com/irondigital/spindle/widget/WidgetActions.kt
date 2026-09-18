package com.irondigital.spindle.widget

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.irondigital.spindle.playback.PlaybackService
import com.irondigital.spindle.playback.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widget buttons act on the session through a short-lived MediaController.
 *
 * The obvious alternative — firing an Intent at the service — means the service
 * may be cold-started with a custom action and has five seconds to put up a
 * foreground notification or be killed by the platform. Connecting a controller
 * hands that problem to Media3, which starts the service, waits for the session
 * and manages the foreground transition itself. It also means the widget
 * traverses exactly the same path as every other surface.
 */
private suspend fun <T> withController(context: Context, block: (MediaController) -> T): T? =
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
        } ?: return@withContext null

        try {
            block(controller)
        } finally {
            // Leaking a controller keeps the service bound and alive forever.
            controller.release()
        }
    }

private const val CONNECT_TIMEOUT_MS = 4_000L

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                // A restored-but-unprepared session needs preparing before it
                // will do anything, which is the state after a process death.
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.play()
            }
        }
        NowPlayingWidget.refresh(context)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { it.seekToNextMediaItem() }
        NowPlayingWidget.refresh(context)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            // Matches every physical transport control ever made: the first
            // press restarts the track, a second one within a few seconds goes
            // back. Media3's own default already does this.
            controller.seekToPreviousMediaItem()
        }
        NowPlayingWidget.refresh(context)
    }
}

/**
 * The queue row tap. This is the behaviour Samsung's widget lost: seeing what
 * is coming up and going straight to it without opening anything.
 */
class JumpToIndexAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[INDEX_KEY] ?: return
        withController(context) { controller ->
            if (index in 0 until controller.mediaItemCount) {
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.seekTo(index, 0L)
                controller.play()
            }
        }
        NowPlayingWidget.refresh(context)
    }

    companion object {
        val INDEX_KEY = ActionParameters.Key<Int>("queue_index")
    }
}

class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        NowPlayingWidget.refresh(context)
    }
}

class CycleRepeatAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            controller.repeatMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
        NowPlayingWidget.refresh(context)
    }
}

class ToggleFavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }
        NowPlayingWidget.refresh(context)
    }
}
