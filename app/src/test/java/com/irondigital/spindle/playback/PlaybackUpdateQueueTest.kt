package com.irondigital.spindle.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackUpdateQueueTest {
    @Test fun `skip publishes without waiting for a debounce timer`() = runTest {
        var count = 0
        val queue = PlaybackUpdateQueue(backgroundScope, { throw it }) { count++ }
        queue.request()
        runCurrent()
        assertEquals(1, count)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `rapid skips finish the active refresh then render the latest song`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var title = "First"
        val rendered = mutableListOf<String>()
        val queue = PlaybackUpdateQueue(backgroundScope, { throw it }) {
            val current = title
            if (current == "First") releaseFirst.await()
            rendered += current
        }
        queue.request()
        runCurrent()
        title = "Second"
        queue.request()
        runCurrent()
        title = "Third"
        queue.request()
        runCurrent()
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("First", "Third"), rendered)
    }

    @Test fun `a failed refresh does not stop later updates`() = runTest {
        var attempts = 0
        val errors = mutableListOf<Exception>()
        val queue = PlaybackUpdateQueue(backgroundScope, errors::add) {
            attempts++
            if (attempts == 1) error("Launcher temporarily unavailable")
        }
        queue.request()
        runCurrent()
        queue.request()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, errors.size)
    }
}
