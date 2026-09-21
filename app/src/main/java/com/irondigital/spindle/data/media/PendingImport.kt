package com.irondigital.spindle.data.media

import java.io.IOException

/** Own the pending destination until publication succeeds. */
internal inline fun <T> completePendingImport(
    create: () -> T,
    copy: (T) -> Long,
    publish: (T) -> Boolean,
    remove: (T) -> Unit,
): T {
    val target = create()
    var committed = false
    try {
        if (copy(target) <= 0) throw IOException("The file was empty or unreadable")
        if (!publish(target)) throw IOException("Could not add the file to the music library")
        committed = true
        return target
    } finally {
        if (!committed) remove(target)
    }
}
