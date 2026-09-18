package com.irondigital.spindle.ui.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.components.Groove
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * The head of a tool screen: back, title, and one plain sentence saying what
 * the tool will do before anyone taps anything.
 *
 * The explanation is not decoration. Every one of these tools changes or
 * removes something, and a screen that opens straight onto a list of checkboxes
 * with no statement of consequence is how people delete things they wanted.
 */
@Composable
fun ToolHeader(
    title: String,
    explanation: String,
    onBack: () -> Unit,
) {
    // Worth reading once, worth four lines of list back afterwards. It starts
    // open, because these tools change things and nobody should meet one blind.
    var showExplanation by rememberSaveable { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.s, end = Space.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Text(
                text = title,
                style = SpindleType.ScreenTitle,
                color = Ink.Primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (showExplanation) "Hide" else "What is this?",
                style = SpindleType.Secondary,
                color = Lamp.Bright,
                modifier = Modifier
                    .clickable { showExplanation = !showExplanation }
                    .padding(Space.s),
            )
        }
        if (showExplanation) {
            Text(
                text = explanation,
                style = SpindleType.Body,
                color = Steel.Bright,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
            Spacer(Modifier.height(Space.m))
        } else {
            Spacer(Modifier.height(Space.xs))
        }
        TickScale(height = 10.dp, spacing = 6.dp, modifier = Modifier.padding(horizontal = Space.gutter))
        Spacer(Modifier.height(Space.s))
    }
}

/**
 * The indicator lamp used wherever something is selected.
 *
 * A square that lights rather than a check mark, because the whole interface is
 * a faceplate and this is the one gesture repeated across four screens — so it
 * may as well be the faceplate's own.
 */
@Composable
fun SelectionLamp(selected: Boolean, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(
        targetValue = if (selected) Lamp.Bright else Ground.Deep,
        animationSpec = if (selected) Motion.lampOn() else Motion.lampOff(),
        label = "selection-lamp",
    )
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(Corner.edge))
            .background(fill)
            .border(1.dp, if (selected) Lamp.Warm else Steel.EngraveLight, RoundedCornerShape(Corner.edge))
    )
}

/**
 * A row in a tool list: a lamp, a title, a secondary line, and a right-hand
 * column of specs in the mono.
 */
@Composable
fun SelectableRow(
    selected: Boolean,
    onToggle: () -> Unit,
    title: String,
    subtitle: String,
    detail: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = Ink.Primary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = Space.gutter, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionLamp(selected)
        Spacer(Modifier.width(Space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SpindleType.RowTitle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = SpindleType.Secondary,
                color = Steel.Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail != null) {
            Spacer(Modifier.width(Space.m))
            Text(detail, style = SpindleType.Data, color = Steel.Bright)
        }
    }
}

/**
 * The action band. One action, continuously available, pinned to the bottom —
 * so the count of what is about to change is visible at the moment of pressing
 * rather than somewhere up the list.
 */
@Composable
fun ActionBand(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    accent: androidx.compose.ui.graphics.Color = Lamp.Bright,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Ground.Plate)) {
        Groove(color = Steel.EngraveLight)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.gutter, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (secondaryLabel != null) {
                Text(
                    text = secondaryLabel,
                    style = SpindleType.RowTitle,
                    color = Steel.Bright,
                    modifier = Modifier
                        .then(if (onSecondary != null) Modifier.clickable(onClick = onSecondary) else Modifier)
                        .padding(vertical = Space.s),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .background(if (enabled) accent else Ground.Raised)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = Space.xl, vertical = Space.m),
            ) {
                Text(
                    text = label,
                    style = SpindleType.RowTitle,
                    color = if (enabled) Ink.OnLamp else Steel.Engrave,
                )
            }
        }
    }
}

/** Nothing to do, said in a way that reads as good news rather than a failure. */
@Composable
fun ToolEmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(Space.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = SpindleType.Body, color = Steel.Dim)
    }
}
