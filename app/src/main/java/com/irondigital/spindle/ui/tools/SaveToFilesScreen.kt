package com.irondigital.spindle.ui.tools

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irondigital.spindle.data.tagfiles.FileCorrection
import com.irondigital.spindle.data.tagfiles.SaveItem
import com.irondigital.spindle.data.tagfiles.SaveReport
import com.irondigital.spindle.spindle
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.components.SelectionLamp
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.SignalRed
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel
import kotlinx.coroutines.launch

/** What the system consent dialog is being asked for. */
private sealed interface WriteAction {
    data class Save(val items: List<SaveItem>) : WriteAction
    data object Undo : WriteAction
    data object Recover : WriteAction
}

/**
 * Carries Spindle's corrections into the audio files themselves, so any other
 * player, a car stereo, or a computer sees them too.
 *
 * Every row shows what the file says now against what it will say, and nothing
 * is written until the user has confirmed here and then again in Android's own
 * consent dialog. The careful part is in TagSaveEngine; this screen's job is to
 * make sure nobody reaches it without knowing exactly what will change.
 */
@Composable
fun SaveToFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.spindle
    val saver = app.tagSaver
    val scope = rememberCoroutineScope()

    val tracks by app.library.tracks.collectAsStateWithLifecycle()
    val playing by app.nowPlayingId.collectAsStateWithLifecycle()
    val progress by saver.progress.collectAsStateWithLifecycle()
    val pendingCount by saver.pendingCount.collectAsStateWithLifecycle()
    val undoableCount by saver.undoableCount.collectAsStateWithLifecycle()

    val corrections = remember(tracks, playing) { saver.corrections() }
    // Held as what was *left out*, so every correction starts selected and a
    // newly appearing one is included without anyone having to notice it.
    val excluded: SnapshotStateList<String> = remember { emptyList<String>().toMutableStateList() }
    val chosen = corrections.filter { it.blockedBy == null && it.track.mediaId !in excluded }

    var confirming by remember { mutableStateOf(false) }
    var confirmingUndo by remember { mutableStateOf(false) }
    var awaiting by remember { mutableStateOf<WriteAction?>(null) }
    var report by remember { mutableStateOf<SaveReport?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = awaiting
        awaiting = null
        if (result.resultCode != Activity.RESULT_OK || action == null) {
            notice = "Nothing was changed."
            return@rememberLauncherForActivityResult
        }
        when (action) {
            is WriteAction.Save -> saver.save(action.items) { report = it }
            WriteAction.Undo -> saver.undoLast { undone, changedSince, failed ->
                notice = buildString {
                    append(if (undone == 1) "1 file put back as it was." else "$undone files put back as they were.")
                    if (changedSince > 0) append(" $changedSince had been changed by something else since, and were left alone.")
                    if (failed > 0) append(" $failed could not be confirmed. Their originals are kept safe.")
                }
            }
            WriteAction.Recover -> saver.recover { unsettled ->
                notice = if (unsettled == 0) "Settled. Every file is whole." else
                    "$unsettled still could not be settled. Their originals are kept safe; try again later."
            }
        }
    }

    fun ask(action: WriteAction, ids: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sender = saver.writeRequest(ids)
        if (sender == null) {
            notice = "Android would not ask for permission to change these files."
            return
        }
        awaiting = action
        consent.launch(IntentSenderRequest.Builder(sender).build())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        ToolHeader(
            title = "Save into files",
            explanation = "Your corrections normally live only in Spindle. This writes " +
                "them into the files, so other players and your car see them too. " +
                "Spindle edits a copy first and checks that the sound is byte-for-byte " +
                "the same. It also checks the new tags read back and nothing else in " +
                "the file moved. Only then is the file replaced, and it keeps each " +
                "original until your next save so you can undo.",
            onBack = onBack,
        )

        if (!saver.supported) {
            ToolEmptyState(
                "Saving into files needs Android 11 or newer. Your corrections still " +
                    "show everywhere in Spindle."
            )
            return@Column
        }

        if (pendingCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ground.Raised)
                    .padding(horizontal = Space.gutter, vertical = Space.m)
            ) {
                Text(
                    text = if (pendingCount == 1) "A save was interrupted" else "$pendingCount saves were interrupted",
                    style = SpindleType.RowTitle,
                    color = SignalRed,
                )
                Text(
                    text = "The phone stopped before Spindle could confirm the file. " +
                        "The original is kept safe. Put it right before saving anything else.",
                    style = SpindleType.Secondary,
                    color = Steel.Bright,
                )
                Text(
                    text = "Put it right",
                    style = SpindleType.RowTitle,
                    color = Lamp.Bright,
                    modifier = Modifier
                        .clickable {
                            scope.launch { ask(WriteAction.Recover, saver.pendingIds()) }
                        }
                        .padding(vertical = Space.s),
                )
            }
        }

        if (corrections.isEmpty()) {
            Column(Modifier.weight(1f)) {
                ToolEmptyState("Every correction is already in its file.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = Space.m),
            ) {
                items(corrections, key = { it.track.mediaId }) { correction ->
                    val id = correction.track.mediaId
                    CorrectionRow(
                        correction = correction,
                        selected = correction.blockedBy == null && id !in excluded,
                        onToggle = { if (id in excluded) excluded.remove(id) else excluded.add(id) },
                    )
                }
            }
        }

        val status = progress?.let { "Saving ${it.done} of ${it.total}…" } ?: notice
        status?.let {
            Text(
                text = it,
                style = SpindleType.Secondary,
                color = if (progress != null) Lamp.Bright else Steel.Bright,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s),
            )
        }

        ActionBand(
            label = if (chosen.size == 1) "Save 1 file" else "Save ${chosen.size} files",
            enabled = chosen.isNotEmpty() && progress == null && pendingCount == 0,
            onClick = { confirming = true },
            secondaryLabel = if (undoableCount > 0 && progress == null) "Undo last save" else null,
            onSecondary = { confirmingUndo = true },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Ground.Plate,
            title = {
                Text(
                    text = if (chosen.size == 1) "Save into 1 file?" else "Save into ${chosen.size} files?",
                    style = SpindleType.Section,
                    color = Ink.Primary,
                )
            },
            text = {
                Text(
                    text = "Android will ask you next to let Spindle modify these files. " +
                        "Any file that fails a check is left exactly as it was, and the " +
                        "report says why.",
                    style = SpindleType.Body,
                    color = Steel.Bright,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    notice = null
                    val items = chosen.map {
                        SaveItem(it.track.mediaId, it.track.displayName, it.changes)
                    }
                    ask(WriteAction.Save(items), items.map { it.id })
                }) { Text("Continue", color = Lamp.Bright, style = SpindleType.RowTitle) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
                }
            },
        )
    }

    if (confirmingUndo) {
        AlertDialog(
            onDismissRequest = { confirmingUndo = false },
            containerColor = Ground.Plate,
            title = { Text("Undo the last save?", style = SpindleType.Section, color = Ink.Primary) },
            text = {
                Text(
                    text = "Puts back the original copy of " +
                        (if (undoableCount == 1) "the file" else "all $undoableCount files") +
                        " from your last save, byte for byte. Your corrections stay in " +
                        "Spindle. A file something else has changed since is left alone.",
                    style = SpindleType.Body,
                    color = Steel.Bright,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUndo = false
                    notice = null
                    scope.launch { ask(WriteAction.Undo, saver.undoableIds()) }
                }) { Text("Undo", color = Lamp.Bright, style = SpindleType.RowTitle) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUndo = false }) {
                    Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
                }
            },
        )
    }

    report?.let { done ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = Ground.Plate,
            title = {
                Text(
                    text = if (done.saved == 1) "1 file saved" else "${done.saved} files saved",
                    style = SpindleType.Section,
                    color = Ink.Primary,
                )
            },
            text = {
                Column {
                    if (done.rolledBack > 0) {
                        Text(
                            text = "${done.rolledBack} did not read back correctly after " +
                                "writing, so the original was put back and checked.",
                            style = SpindleType.Body,
                            color = Steel.Bright,
                        )
                        Spacer(Modifier.height(Space.s))
                    }
                    if (done.datesNotKept > 0) {
                        Text(
                            text = if (done.datesNotKept == done.saved) {
                                "Android would not let Spindle keep the modified date, so " +
                                    "file managers will show today's date. The date added, " +
                                    "which Spindle goes by, is unchanged."
                            } else {
                                "${done.datesNotKept} now show today's modified date in file " +
                                    "managers; Android would not let it be kept. The date " +
                                    "added, which Spindle goes by, is unchanged."
                            },
                            style = SpindleType.Body,
                            color = Steel.Bright,
                        )
                        Spacer(Modifier.height(Space.s))
                    }
                    if (done.needsRecovery > 0) {
                        Text(
                            text = "${done.needsRecovery} could not be confirmed either way. " +
                                "The originals are kept safe; use Put it right.",
                            style = SpindleType.Body,
                            color = SignalRed,
                        )
                        Spacer(Modifier.height(Space.s))
                    }
                    if (done.skipped.isNotEmpty()) {
                        Text("Left exactly as they were:", style = SpindleType.RowTitle, color = Ink.Primary)
                        done.skipped.take(REPORT_LIMIT).forEach { (name, reason) ->
                            Spacer(Modifier.height(Space.xs))
                            Text(name, style = SpindleType.Secondary, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(reason, style = SpindleType.Data, color = Steel.Dim)
                        }
                        if (done.skipped.size > REPORT_LIMIT) {
                            Text("and ${done.skipped.size - REPORT_LIMIT} more", style = SpindleType.Data, color = Steel.Dim)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { report = null }) {
                    Text("Done", color = Lamp.Bright, style = SpindleType.RowTitle)
                }
            },
        )
    }
}

/**
 * One file: its name, then each field as the file has it now and, beneath it,
 * what it will become. The new value is the brighter, heavier line, so the eye
 * lands on it.
 */
@Composable
private fun CorrectionRow(
    correction: FileCorrection,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val blocked = correction.blockedBy != null
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !blocked, role = Role.Checkbox, onClick = onToggle)
                .padding(horizontal = Space.gutter, vertical = Space.s),
            verticalAlignment = Alignment.Top,
        ) {
            SelectionLamp(selected)
            Spacer(Modifier.width(Space.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = correction.track.displayName,
                    style = SpindleType.Secondary,
                    color = Steel.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                correction.diffs.forEach { diff ->
                    Spacer(Modifier.height(Space.xs))
                    Text(diff.label, style = SpindleType.Data, color = Steel.Dim)
                    Text(
                        text = diff.inFile,
                        style = SpindleType.Secondary,
                        color = Steel.Bright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "→ " + diff.corrected,
                        style = SpindleType.RowTitle,
                        color = if (blocked) Steel.Bright else Ink.Primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                correction.blockedBy?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(it, style = SpindleType.Data, color = Lamp.Bright)
                }
            }
        }
        Groove(color = Steel.EngraveLight, modifier = Modifier.padding(horizontal = Space.gutter))
    }
}

private const val REPORT_LIMIT = 8
