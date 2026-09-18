package com.irondigital.spindle.ui.tools

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.data.dedupe.DuplicateGroup
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.components.formatBitrate
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.components.formatFileSize
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.SignalRed
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Finds the same recording sitting in the library twice and offers to delete
 * the worse copy.
 *
 * This is the one tool here that destroys something, so it is the most cautious
 * of them. The matching refuses anything short of the same artist, the same
 * title and a duration within two seconds — a remix or a live cut never appears
 * — the better encode is pre-selected as the keeper, both copies' specs are on
 * screen, and the deletion itself goes through Android's own consent dialog.
 */
@Composable
fun DuplicatesScreen(onBack: () -> Unit) {
    val viewModel: ToolsViewModel = viewModel()
    val groups by viewModel.duplicates.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every non-keeper starts selected, which is the whole point of the screen;
    // the keeper is never selectable at all.
    val spared = remember { mutableStateMapOf<String, Boolean>() }
    var confirming by remember { mutableStateOf(false) }

    val doomed = groups.flatMap { group -> group.others.filter { spared[it.mediaId] != true } }
    val reclaimable = doomed.sumOf { it.sizeBytes }

    DisposableEffect(Unit) {
        viewModel.clearState()
        onDispose { viewModel.clearState() }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleteConfirmed(doomed.size)
            spared.clear()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        ToolHeader(
            title = "Duplicates",
            explanation = "Files that are the same recording twice: same artist, " +
                "same title, same length. A remix, a live version or a different " +
                "edit is never grouped here. Spindle keeps the better encode.",
            onBack = onBack,
        )

        when {
            groups.isEmpty() -> ToolEmptyState(
                (state as? ToolState.Done)?.message?.plus(". Nothing appears twice now.")
                    ?: "No duplicates. Every recording here appears once."
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Space.m),
                ) {
                    groups.forEach { group ->
                        item(key = "head-${group.keep.mediaId}") {
                            GroupHeading(group)
                        }
                        item(key = "keep-${group.keep.mediaId}") {
                            CopyRow(
                                label = "Keep",
                                detail = "${formatBitrate(group.keep.bitrateBps)}  " +
                                    formatFileSize(group.keep.sizeBytes),
                                accent = Lamp.Bright,
                            )
                        }
                        for (other in group.others) {
                            item(key = "drop-${other.mediaId}") {
                                SelectableRow(
                                    selected = spared[other.mediaId] != true,
                                    onToggle = {
                                        spared[other.mediaId] = spared[other.mediaId] != true
                                    },
                                    title = "Delete this copy",
                                    subtitle = formatBitrate(other.bitrateBps) + "  " +
                                        formatFileSize(other.sizeBytes),
                                    titleColor = if (spared[other.mediaId] != true) SignalRed else Steel.Dim,
                                )
                            }
                        }
                        item(key = "rule-${group.keep.mediaId}") {
                            Groove(
                                color = Steel.EngraveLight,
                                modifier = Modifier.padding(horizontal = Space.gutter),
                            )
                            Spacer(Modifier.height(Space.s))
                        }
                    }
                }

                (state as? ToolState.Failed)?.let {
                    Text(
                        text = it.message,
                        style = SpindleType.Secondary,
                        color = SignalRed,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                    )
                }
                (state as? ToolState.Done)?.let {
                    Text(
                        text = it.message,
                        style = SpindleType.Secondary,
                        color = Lamp.Bright,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
                    )
                }

                ActionBand(
                    label = "Delete ${doomed.size}",
                    enabled = doomed.isNotEmpty() && state !is ToolState.Working,
                    onClick = { confirming = true },
                    secondaryLabel = if (reclaimable > 0) "Frees ${formatFileSize(reclaimable)}" else null,
                    accent = SignalRed,
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Ground.Plate,
            title = {
                Text("Delete ${doomed.size} files?", style = SpindleType.Section, color = Ink.Primary)
            },
            text = {
                Text(
                    text = "These files are removed from the phone, not just from " +
                        "Spindle, and that cannot be undone. The copy marked Keep in " +
                        "each group stays exactly where it is.",
                    style = SpindleType.Body,
                    color = Steel.Bright,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ids = doomed.map { it.mediaId }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val sender = viewModel.deleteRequest(ids)
                        if (sender != null) {
                            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    } else {
                        // Before Android 11 there is no system consent dialog, so
                        // the delete is attempted directly and may simply be refused.
                        viewModel.deleteDirectly(ids)
                        spared.clear()
                    }
                }) {
                    Text("Delete", color = SignalRed, style = SpindleType.RowTitle)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Keep them all", color = Lamp.Bright, style = SpindleType.RowTitle)
                }
            },
        )
    }
}

@Composable
private fun GroupHeading(group: DuplicateGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s)
    ) {
        Text(
            text = group.keep.title,
            style = SpindleType.Section,
            color = Ink.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                text = group.keep.artist,
                style = SpindleType.Secondary,
                color = Steel.Bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Space.s))
            Text(formatDuration(group.keep.durationMs), style = SpindleType.Data, color = Steel.Dim)
        }
    }
}

@Composable
private fun CopyRow(
    label: String,
    detail: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.m),
    ) {
        // A lit groove rather than a lamp: the keeper is not a choice the user
        // is being asked to make, so it must not look like one.
        Spacer(
            Modifier
                .width(2.dp)
                .height(18.dp)
                .background(accent)
        )
        Spacer(Modifier.width(Space.m))
        Text(label, style = SpindleType.RowTitle, color = accent, modifier = Modifier.weight(1f))
        Text(detail, style = SpindleType.Data, color = Steel.Bright)
    }
}
