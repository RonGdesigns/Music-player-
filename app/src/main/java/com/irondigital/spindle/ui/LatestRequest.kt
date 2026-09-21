package com.irondigital.spindle.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread request ownership. A replaced request may finish but cannot publish. */
internal class LatestRequest(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun <T> submit(load: suspend () -> T, publish: (T) -> Unit) {
        cancel()
        val request = generation
        job = scope.launch {
            val result = load()
            if (request == generation) publish(result)
        }
    }
}
