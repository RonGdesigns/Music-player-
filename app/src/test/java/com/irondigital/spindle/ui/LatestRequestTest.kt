package com.irondigital.spindle.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LatestRequestTest {
    @Test fun `late lyrics for A cannot replace completed lyrics for B`() = runTest {
        val request = LatestRequest(this)
        val resultA = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        request.submit({ withContext(NonCancellable) { resultA.await() } }, published::add)
        runCurrent()
        request.submit({ "B lyrics" }, published::add)
        runCurrent()
        resultA.complete("A lyrics")
        runCurrent()
        assertEquals(listOf("B lyrics"), published)
    }

    @Test fun `leaving a track clears ownership even without another request`() = runTest {
        val request = LatestRequest(this)
        val result = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        request.submit({ withContext(NonCancellable) { result.await() } }, published::add)
        runCurrent()
        request.cancel()
        result.complete("stale lyrics")
        runCurrent()
        assertTrue(published.isEmpty())
    }
}
