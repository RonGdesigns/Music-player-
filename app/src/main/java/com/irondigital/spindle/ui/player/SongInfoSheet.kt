package com.irondigital.spindle.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.components.formatBitrate
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.components.formatEpochSeconds
import com.irondigital.spindle.ui.components.formatFileSize
import com.irondigital.spindle.ui.components.formatSampleRate
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Details that need the file itself opened, so they are loaded on demand. */
private data class DeepDetails(
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val codec: String = "",
)

/**
 * Everything about this file: where it is, what it is, and what your history
 * with it looks like.
 *
 * The file path is here because on a local library it is the answer to the
 * question people actually have — "which copy is this, and where did it come
 * from" — and it is tappable to copy, because the next thing you do with a path
 * is paste it somewhere.
 */
@Composable
fun SongInfoSheet(
    track: Track,
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val counts by playerViewModel.playCounts.collectAsStateWithLifecycle()
    var deep by remember(track.mediaId) { mutableStateOf(DeepDetails()) }

    LaunchedEffect(track.mediaId) {
        deep = loadDeepDetails(context, track)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = {
            Column {
                Text(
                    text = track.title,
                    style = SpindleType.DisplaySmall,
                    color = Ink.Primary,
                    maxLines = 2,
                )
                Text(
                    text = track.artist,
                    style = SpindleType.Body,
                    color = Steel.Bright,
                    maxLines = 1,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                InfoGroup("Recording") {
                    InfoLine("Album", track.album)
                    InfoLine("Track", track.trackNumber.takeIf { it > 0 }?.toString() ?: "—")
                    InfoLine("Disc", track.discNumber.toString())
                    InfoLine("Year", track.year.takeIf { it > 0 }?.toString() ?: "—")
                    InfoLine("Length", formatDuration(track.durationMs))
                }

                InfoGroup("File") {
                    InfoLine("Name", track.displayName)
                    InfoLine("Format", track.mimeType.substringAfter('/').uppercase())
                    InfoLine("Codec", deep.codec.ifBlank { "—" })
                    InfoLine("Bitrate", formatBitrate(track.bitrateBps))
                    InfoLine("Sample rate", formatSampleRate(deep.sampleRateHz))
                    InfoLine(
                        "Channels",
                        when (deep.channelCount) {
                            0 -> "—"
                            1 -> "Mono"
                            2 -> "Stereo"
                            else -> "${deep.channelCount} channels"
                        },
                    )
                    InfoLine("Size", formatFileSize(track.sizeBytes))
                    InfoLine("Added", formatEpochSeconds(track.dateAddedSec))
                    InfoLine("Modified", formatEpochSeconds(track.dateModifiedSec))
                }

                InfoGroup("Location") {
                    val path = track.filePath ?: track.uri.toString()
                    Text(
                        text = path,
                        style = SpindleType.Data,
                        color = Steel.Bright,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { copyToClipboard(context, "File path", path) }
                            .background(Ground.Raised)
                            .padding(Space.s),
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = "Tap to copy",
                        style = SpindleType.Data,
                        color = Steel.Engrave,
                    )
                }

                InfoGroup("Your history") {
                    val playCount = counts[track.mediaId] ?: 0
                    InfoLine("Plays", playCount.toString())
                    Spacer(Modifier.height(Space.s))
                    // A play count against the most-played track in the library
                    // is the only reading of it that means anything on its own.
                    val ceiling = counts.values.maxOrNull() ?: 1
                    TickScale(
                        progress = if (ceiling > 0) playCount.toFloat() / ceiling else 0f,
                        height = 12.dp,
                        spacing = 6.dp,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = if (playCount == 0) {
                            "Not counted yet. A play registers once you have heard " +
                                "half the track, or four minutes of it."
                        } else {
                            "Against $ceiling for your most played track"
                        },
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
    )
}

@Composable
private fun InfoGroup(heading: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = Space.l)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(modifier = Modifier.width(14.dp).height(2.dp).background(Lamp.Bright))
            Spacer(Modifier.width(Space.s))
            Text(text = heading, style = SpindleType.Section, color = Ink.Primary)
        }
        Spacer(Modifier.height(Space.s))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = SpindleType.Secondary, color = Steel.Dim)
        Text(
            text = value,
            style = SpindleType.DataEmphasis,
            color = Ink.Primary,
            maxLines = 1,
        )
    }
}

/**
 * Sample rate and channel count are not in MediaStore, so they come from the
 * container. MediaExtractor reads the header only, which is why this is
 * affordable to run when the sheet opens rather than during the library scan.
 */
private suspend fun loadDeepDetails(context: Context, track: Track): DeepDetails =
    withContext(Dispatchers.IO) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, track.uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("audio/")) continue
                    return@runCatching DeepDetails(
                        sampleRateHz = format.optInt(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.optInt(MediaFormat.KEY_CHANNEL_COUNT),
                        codec = mime.substringAfter('/'),
                    )
                }
                DeepDetails()
            } finally {
                extractor.release()
            }
        }.getOrDefault(DeepDetails())
    }

private fun MediaFormat.optInt(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}
