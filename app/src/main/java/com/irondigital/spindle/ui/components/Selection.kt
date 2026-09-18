package com.irondigital.spindle.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Steel

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
