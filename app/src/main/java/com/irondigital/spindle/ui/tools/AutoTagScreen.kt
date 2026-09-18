package com.irondigital.spindle.ui.tools

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.data.tagging.TagProposal
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Reads artist and title out of the filename for tracks that arrived with no
 * usable tags.
 *
 * Nothing is applied without being shown first. The proposal for every track is
 * on screen, individually switchable, and what it writes is the same undoable
 * override the edit sheet writes — so a wrong guess costs a tap to fix, never a
 * damaged file.
 */
@Composable
fun AutoTagScreen(onBack: () -> Unit) {
    val viewModel: ToolsViewModel = viewModel()
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Everything starts selected: the list only holds tracks the tagger is
    // already confident about, and deselecting the odd wrong one is less work
    // than ticking forty right ones.
    val excluded: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    var applied by remember { mutableStateOf(false) }

    // Read straight off the snapshot map rather than remembered against its
    // size: flipping an existing key leaves the size alone, and a cache keyed
    // on it would quietly stop updating.
    val selected = proposals.filter { excluded[it.mediaId] != true }

    // The status line is shared with the other tools, so it is wiped on the
    // way in and on the way out rather than following the user to the next one.
    DisposableEffect(Unit) {
        viewModel.clearState()
        onDispose { viewModel.clearState() }
    }

    LaunchedEffect(state) {
        if (state is ToolState.Done) applied = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        ToolHeader(
            title = "Fix names",
            explanation = "Tracks whose tags are missing, with what their filename " +
                "says they are. Spindle shows how it read every one before anything " +
                "is changed, and the change is an override you can undo.",
            onBack = onBack,
        )

        when {
            applied -> ToolEmptyState(
                (state as? ToolState.Done)?.message?.plus(". Nothing else here needs fixing.")
                    ?: "Done."
            )

            proposals.isEmpty() -> ToolEmptyState(
                "Nothing to fix. Every track here already carries a real artist and " +
                    "title, or its filename has nothing better to offer."
            )

            else -> {
                Column(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = Space.m),
                    ) {
                        items(proposals, key = { it.mediaId }) { proposal ->
                            ProposalRow(
                                proposal = proposal,
                                selected = excluded[proposal.mediaId] != true,
                                onToggle = {
                                    excluded[proposal.mediaId] = excluded[proposal.mediaId] != true
                                },
                            )
                            Groove(
                                color = Steel.Engrave,
                                modifier = Modifier.padding(horizontal = Space.gutter),
                            )
                        }
                    }
                }

                ActionBand(
                    label = "Fix ${selected.size}",
                    enabled = selected.isNotEmpty() && state !is ToolState.Working,
                    onClick = { viewModel.applyProposals(selected) },
                    secondaryLabel = if (excluded.values.any { it }) "Select all" else "Select none",
                    onSecondary = {
                        val selectingNone = excluded.values.none { it }
                        excluded.clear()
                        if (selectingNone) proposals.forEach { excluded[it.mediaId] = true }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: TagProposal,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        SelectableRow(
            selected = selected,
            onToggle = onToggle,
            title = proposal.proposedTitle,
            subtitle = proposal.proposedArtist,
            titleColor = if (selected) Ink.Primary else Steel.Dim,
        )
        // The filename is the evidence for the proposal above it, so it sits
        // directly under it in the mono rather than being hidden behind a tap.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.gutter, end = Space.gutter, bottom = Space.m),
        ) {
            Spacer(Modifier.width(30.dp))
            Text(
                text = proposal.fileName,
                style = SpindleType.Data,
                color = Steel.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(0.dp))
    }
}
