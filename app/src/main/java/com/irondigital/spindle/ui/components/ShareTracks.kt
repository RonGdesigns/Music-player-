package com.irondigital.spindle.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.irondigital.spindle.data.model.Track

/**
 * Hands the audio file itself to whatever the user wants to send it with.
 *
 * The URIs are MediaStore's own, which every other app can already read, so
 * this needs no file provider and copies nothing — the share sheet passes a
 * reference and the receiving app reads the original. The read grant is
 * attached explicitly all the same, because a receiver that holds no media
 * permission of its own still has to be able to open what it was handed.
 */
object ShareTracks {

    fun share(context: Context, track: Track) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType.ifBlank { "audio/*" }
            putExtra(Intent.EXTRA_STREAM, track.uri)
            // Some receivers show a subject line; a filename is more use there
            // than the app's name.
            putExtra(Intent.EXTRA_SUBJECT, track.title.ifBlank { track.displayName })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, intent, if (track.title.isBlank()) "Share track" else "Share ${track.title}")
    }

    fun share(context: Context, tracks: List<Track>) {
        when {
            tracks.isEmpty() -> return
            tracks.size == 1 -> share(context, tracks.first())
            else -> {
                val uris = ArrayList<Uri>(tracks.size).apply { tracks.forEach { add(it.uri) } }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    // One concrete type when they agree, so a receiver that only
                    // takes MP3s still offers itself; audio/* when they do not.
                    type = tracks.map { it.mimeType }.distinct().singleOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "audio/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                start(context, intent, "Share ${tracks.size} tracks")
            }
        }
    }

    private fun start(context: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The sheet may be started from a context that is not an activity,
            // and without this that is a crash rather than a share sheet.
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
