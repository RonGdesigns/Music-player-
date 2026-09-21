package com.irondigital.spindle.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Finish the active update; collapse queued events into one fresh state read. */
internal class PlaybackUpdateQueue(
    scope: CoroutineScope,
    onFailure: (Exception) -> Unit,
    update: suspend () -> Unit,
) {
    private val events = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    update()
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    onFailure(error)
                }
            }
        }
    }

    fun request() { events.trySend(Unit) }
}
