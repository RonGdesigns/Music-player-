package com.irondigital.spindle.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.SignalRed
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Writes the listening history, favorites, playlists and corrections to a file
 * the user holds, and reads one back.
 *
 * Cloud backup already covers the ordinary case. It does not cover a factory
 * reset with backup switched off, a move to a phone from a different maker, or
 * a reinstall after clearing data — and the play counts are the entire reason
 * Most Played means anything, so they are worth a file you can see.
 *
 * Restore matches on the recording rather than on MediaStore ids, because those
 * ids do not survive a single one of the events this exists for.
 */
@Composable
fun BackupControls() {
    val viewModel: ToolsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report by viewModel.restoreReport.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }

    // Some file pickers hand JSON back as text/plain or as an unknown type, and
    // a filter that only accepts application/json greys out the user's own
    // backup — so the filter is wide and the decoder is what says no.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreFrom(uri) }

    Column {
        Text(
            text = "Your play counts, favorites, playlists and any details you have " +
                "corrected, written to one readable file. Nothing leaves the device " +
                "unless you put the file somewhere else yourself.",
            style = SpindleType.Secondary,
            color = Steel.Dim,
        )

        Spacer(Modifier.height(Space.m))
        Text(
            text = "Save a backup file",
            style = SpindleType.RowTitle,
            color = Lamp.Bright,
            modifier = Modifier
                .clickable { exportLauncher.launch(viewModel.suggestedBackupName()) }
                .padding(vertical = Space.s),
        )
        Text(
            text = "Restore from a backup file",
            style = SpindleType.RowTitle,
            color = Lamp.Bright,
            modifier = Modifier
                .clickable {
                    restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                .padding(vertical = Space.s),
        )
        Text(
            text = "A restore adds to what is already here rather than replacing it: " +
                "where a track has been played on both phones, the higher count wins, " +
                "and a playlist whose name is already taken arrives beside it rather " +
                "than merging into it.",
            style = SpindleType.Data,
            color = Steel.Dim,
        )

        when (val current = state) {
            is ToolState.Working -> Status("Working…", Steel.Bright)
            is ToolState.Done -> Status(current.message, Lamp.Bright)
            is ToolState.Failed -> Status(current.message, SignalRed)
            else -> Unit
        }
    }

    report?.let { restored ->
        AlertDialog(
            onDismissRequest = viewModel::clearState,
            containerColor = Ground.Plate,
            title = { Text("Restored", style = SpindleType.Section, color = Ink.Primary) },
            text = {
                Column {
                    ReportLine("Tracks in the file", restored.tracksInFile.toString())
                    ReportLine("Found in this library", restored.tracksMatched.toString())
                    ReportLine("Histories merged", restored.historyRestored.toString())
                    ReportLine("Favorites", restored.favoritesRestored.toString())
                    ReportLine("Detail corrections", restored.editsRestored.toString())
                    ReportLine("Playlists", restored.playlistsRestored.toString())
                    ReportLine(
                        "Playlist tracks",
                        "${restored.playlistItemsMatched} of ${restored.playlistItemsInFile}",
                    )
                    if (restored.unmatched > 0) {
                        Spacer(Modifier.height(Space.m))
                        Text(
                            text = "${restored.unmatched} tracks in the file are not on " +
                                "this phone. Their history is untouched in the file — " +
                                "restore again once those files are back and it will " +
                                "find them.",
                            style = SpindleType.Secondary,
                            color = Steel.Dim,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearState) {
                    Text("Done", color = Lamp.Bright, style = SpindleType.RowTitle)
                }
            },
        )
    }
}

@Composable
private fun ReportLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.padding(vertical = Space.xxs)) {
        Text(label, style = SpindleType.Secondary, color = Steel.Bright, modifier = Modifier.weight(1f))
        Text(value, style = SpindleType.DataEmphasis, color = Ink.Primary)
    }
}

@Composable
private fun Status(message: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.height(Space.s))
    Text(message, style = SpindleType.Secondary, color = color)
}
