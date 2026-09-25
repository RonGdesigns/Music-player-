package com.irondigital.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.data.db.TrackEdit
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.SignalRed
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Corrects a track's details.
 *
 * What this always saves is an override: undoable, needing no permission, and
 * surviving a MediaStore rescan. Writing the correction into the file as well
 * is a separate, opt-in step — [fileOption] — because it changes something the
 * user cannot get back from Spindle alone, and so it goes through its own
 * checks and Android's own consent.
 *
 * A field left exactly as the *file* reports it is saved as "no correction", so
 * fixing only the artist neither freezes the title against a future retag nor
 * erases a title correction made earlier.
 */
@Composable
fun EditTrackSheet(
    track: Track,
    /** The track as its file describes it, before any correction. */
    fileTrack: Track?,
    existing: TrackEdit?,
    fileOption: FileSaveOption,
    onSave: (edit: TrackEdit, alsoIntoFile: Boolean) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val base = fileTrack ?: track
    var alsoIntoFile by remember(track.mediaId) { mutableStateOf(false) }
    var title by remember(track.mediaId) { mutableStateOf(track.title) }
    var artist by remember(track.mediaId) { mutableStateOf(track.artist) }
    var album by remember(track.mediaId) { mutableStateOf(track.album) }
    var year by remember(track.mediaId) {
        mutableStateOf(track.year.takeIf { it > 0 }?.toString().orEmpty())
    }
    var trackNumber by remember(track.mediaId) {
        mutableStateOf(track.trackNumber.takeIf { it > 0 }?.toString().orEmpty())
    }

    val hasOverride = existing != null && !existing.isEmpty

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = { Text("Edit details", style = SpindleType.Section, color = Ink.Primary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Field("Title", title) { title = it }
                Field("Artist", artist) { artist = it }
                Field("Album", album) { album = it }

                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Field("Year", year, keyboard = KeyboardType.Number) { year = it }
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(modifier = Modifier.weight(1f)) {
                        Field("Track no.", trackNumber, keyboard = KeyboardType.Number) {
                            trackNumber = it
                        }
                    }
                }

                Spacer(Modifier.height(Space.s))
                if (fileOption == FileSaveOption.Unavailable) {
                    Text(
                        text = "This changes how Spindle shows the track. The file on " +
                            "disk is left exactly as it is, and you can undo it at any time.",
                        style = SpindleType.Data,
                        color = Steel.Dim,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { alsoIntoFile = !alsoIntoFile }
                            .padding(vertical = Space.s),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SelectionLamp(alsoIntoFile)
                        Spacer(Modifier.width(Space.m))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Also save into the file", style = SpindleType.RowTitle, color = Ink.Primary)
                            Text(
                                text = if (alsoIntoFile) {
                                    "Checked before and after writing. Undo it from Library " +
                                        "tools, Save into files." +
                                        if (fileOption == FileSaveOption.AfterThisSong) {
                                            " This song is playing, so it is written when the song changes."
                                        } else ""
                                } else {
                                    "Otherwise only Spindle shows the change, and the file stays as it is."
                                },
                                style = SpindleType.Data,
                                color = Steel.Dim,
                            )
                        }
                    }
                }

                if (hasOverride) {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = "Reset to what the file says",
                        style = SpindleType.RowTitle,
                        color = SignalRed,
                        modifier = Modifier
                            .clickable { onRevert() }
                            .padding(vertical = Space.s),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        TrackEdit(
                            mediaId = track.mediaId,
                            // Only store a field that differs from the file, so an
                            // untouched one still follows the file if it is retagged.
                            title = title.trim().takeIf { it != base.title && it.isNotBlank() },
                            artist = artist.trim().takeIf { it != base.artist && it.isNotBlank() },
                            album = album.trim().takeIf { it != base.album && it.isNotBlank() },
                            year = year.toIntOrNull()?.takeIf { it != base.year && it in 1..9999 },
                            trackNumber = trackNumber.toIntOrNull()
                                ?.takeIf { it != base.trackNumber && it in 1..999 },
                            updatedAt = System.currentTimeMillis(),
                        ),
                        alsoIntoFile && fileOption != FileSaveOption.Unavailable,
                    )
                },
            ) {
                Text("Save", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
            }
        },
    )
}

/** Whether the sheet offers to write the correction into the file too. */
enum class FileSaveOption { Unavailable, Now, AfterThisSong }

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = Space.m)) {
        Text(text = label, style = SpindleType.Data, color = Steel.Dim)
        Spacer(Modifier.height(Space.xs))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = SpindleType.Body.copy(color = Ink.Primary),
            cursorBrush = SolidColor(Lamp.Bright),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier
                .fillMaxWidth()
                .background(Ground.Raised)
                .padding(horizontal = Space.m, vertical = Space.s),
        )
    }
}
