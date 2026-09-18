package com.irondigital.spindle.ui.components

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Playhead and duration. Hours only appear when a track actually has them. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Shelf subtitles: "1 hr 24 min" rather than a wall of seconds. */
fun formatTotalDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0 -> "$hours hr $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "under a minute"
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} ${units[unit]}"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatBitrate(bps: Int): String =
    if (bps <= 0) "—" else "${bps / 1000} kbps"

fun formatSampleRate(hz: Int): String =
    if (hz <= 0) "—" else String.format(Locale.US, "%.1f kHz", hz / 1000f)

fun formatEpochSeconds(seconds: Long): String =
    if (seconds <= 0) "—"
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(seconds * 1000))

fun formatEpochMillis(ms: Long): String =
    if (ms <= 0) "Never"
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** Play counts read as a unit, not a bare integer. */
fun formatPlayCount(count: Int): String = when (count) {
    0 -> "Never played"
    1 -> "1 play"
    else -> "$count plays"
}
