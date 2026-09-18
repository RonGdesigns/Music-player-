package com.irondigital.spindle.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * Sets one artist, album or year across a whole selection at once.
 *
 * The reason this exists is compilations. A rip or a batch of downloads lands
 * with forty tracks that all want the same album name, and correcting them one
 * sheet at a time is the kind of work people simply do not do — so the library
 * stays wrong instead.
 *
 * A field left blank is left alone. That is what makes it safe to fix only the
 * album across a selection whose artists genuinely differ.
 */
@Composable
fun BatchEditScreen(onBack: () -> Unit) {
    val viewModel: ToolsViewModel = viewModel()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        viewModel.clearState()
        onDispose { viewModel.clearState() }
    }

    val visible = remember(tracks, filter) {
        val needle = filter.trim()
        if (needle.isEmpty()) tracks
        else tracks.filter {
            it.title.contains(needle, true) ||
                it.artist.contains(needle, true) ||
                it.album.contains(needle, true)
        }
    }

    val selectedIds = selected.filterValues { it }.keys
    val hasField = artist.isNotBlank() || album.isNotBlank() || year.toIntOrNull() != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
    ) {
        ToolHeader(
            title = "Edit several",
            explanation = "Pick the tracks, then set what they share. A field you " +
                "leave blank is not changed, and everything here is an override you " +
                "can undo — the files themselves are never rewritten.",
            onBack = onBack,
        )

        // The fields sit above the list, on the raised plate, because they are
        // the instruction and the list below is what it will be applied to.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ground.Plate)
                .padding(horizontal = Space.gutter, vertical = Space.m)
        ) {
            ToolField("Artist", artist, placeholder = "Leave blank to keep") { artist = it }
            Spacer(Modifier.height(Space.s))
            ToolField("Album", album, placeholder = "Leave blank to keep") { album = it }
            Spacer(Modifier.height(Space.s))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    ToolField(
                        label = "Year",
                        value = year,
                        placeholder = "Leave blank to keep",
                        keyboard = KeyboardType.Number,
                    ) { year = it }
                }
                Spacer(Modifier.width(Space.m))
                Column(modifier = Modifier.weight(1f)) {
                    ToolField("Filter the list", filter, placeholder = "Title, artist or album") {
                        filter = it
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = Space.m),
        ) {
            items(visible, key = { it.mediaId }) { track ->
                SelectableRow(
                    selected = selected[track.mediaId] == true,
                    onToggle = { selected[track.mediaId] = selected[track.mediaId] != true },
                    title = track.title,
                    subtitle = "${track.artist} · ${track.album}",
                    detail = formatDuration(track.durationMs),
                )
                Groove(color = Steel.Engrave, modifier = Modifier.padding(horizontal = Space.gutter))
            }
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
            label = "Apply to ${selectedIds.size}",
            enabled = selectedIds.isNotEmpty() && hasField && state !is ToolState.Working,
            onClick = {
                viewModel.applyBatch(
                    mediaIds = selectedIds.toSet(),
                    artist = artist.trim().takeIf { it.isNotBlank() },
                    album = album.trim().takeIf { it.isNotBlank() },
                    year = year.toIntOrNull()?.takeIf { it in 1..9999 },
                )
                selected.clear()
            },
            secondaryLabel = when {
                selectedIds.isNotEmpty() -> "Clear selection"
                visible.isNotEmpty() -> "Select all ${visible.size}"
                else -> null
            },
            onSecondary = {
                if (selectedIds.isNotEmpty()) selected.clear()
                else visible.forEach { selected[it.mediaId] = true }
            },
        )
    }
}

@Composable
fun ToolField(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = SpindleType.Data, color = Steel.Dim)
        Spacer(Modifier.height(Space.xs))
        Box {
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
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = SpindleType.Body,
                    color = Steel.Dim,
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }
    }
}
