package com.irondigital.spindle.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

private val PRESETS = listOf(10, 15, 30, 45, 60, 90)

/**
 * The sleep timer.
 *
 * "Finish this track first" exists because the alternative is being cut off
 * mid-song, which is the one thing that will wake you up.
 */
@Composable
fun SleepTimerSheet(
    activeMinutes: Int,
    onSet: (minutes: Int, endOfTrack: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(activeMinutes.takeIf { it > 0 } ?: 30) }
    var endOfTrack by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ground.Plate,
        title = { Text("Sleep timer", style = SpindleType.Section, color = Ink.Primary) },
        text = {
            Column {
                if (activeMinutes > 0) {
                    Text(
                        text = "A timer is already running.",
                        style = SpindleType.Secondary,
                        color = Lamp.Bright,
                    )
                    Spacer(Modifier.height(Space.m))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PRESETS.take(3).forEach { minutes ->
                        PresetChip(
                            minutes = minutes,
                            selected = selected == minutes,
                            onClick = { selected = minutes },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PRESETS.drop(3).forEach { minutes ->
                        PresetChip(
                            minutes = minutes,
                            selected = selected == minutes,
                            onClick = { selected = minutes },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(Space.m))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { endOfTrack = !endOfTrack },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = endOfTrack,
                        onCheckedChange = { endOfTrack = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Lamp.Bright,
                            checkmarkColor = Ink.OnLamp,
                            uncheckedColor = Steel.Dim,
                        ),
                    )
                    Text(
                        text = "Let the current track finish",
                        style = SpindleType.Body,
                        color = Ink.Primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(selected, endOfTrack) }) {
                Text("Start", color = Lamp.Bright, style = SpindleType.RowTitle)
            }
        },
        dismissButton = {
            if (activeMinutes > 0) {
                TextButton(onClick = onCancel) {
                    Text("Stop timer", color = Steel.Bright, style = SpindleType.RowTitle)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Steel.Dim, style = SpindleType.RowTitle)
                }
            }
        },
    )
}

@Composable
private fun PresetChip(
    minutes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(if (selected) Lamp.Bright else Ground.Raised)
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = minutes.toString(),
            style = SpindleType.DataLarge,
            color = if (selected) Ink.OnLamp else Ink.Primary,
        )
        Text(
            text = "min",
            style = SpindleType.Data,
            color = if (selected) Ink.OnLamp else Steel.Dim,
        )
    }
}
