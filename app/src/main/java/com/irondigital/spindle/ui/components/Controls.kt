package com.irondigital.spindle.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.Steel

/**
 * The interaction signature, in one place.
 *
 * Anything that becomes live warms to amber over 180ms and cools over 260ms —
 * slower off than on, the way a filament actually behaves. Press gives a small
 * physical scale-down, because on a touch screen press is the only state that
 * exists: there is no hover to lean on, and a design whose whole feedback story
 * is hover has no feedback at all on a phone.
 */
@Composable
fun LampIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Space.tap,
    iconSize: Dp = 22.dp,
    lit: Boolean = false,
    enabled: Boolean = true,
    litColor: Color = Lamp.Bright,
    unlitColor: Color = Steel.Bright,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> Steel.Engrave
            lit -> litColor
            else -> unlitColor
        },
        animationSpec = if (lit) Motion.lampOn() else Motion.lampOff(),
        label = "lamp-tint",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = Motion.state(),
        label = "press-give",
    )

    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = size / 2, color = litColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .scale(pressScale),
        )
    }
}

/**
 * The one filled control in the app. Amber ground, dark glyph — so the thing
 * you press most is the thing you can hit without looking at the screen.
 */
@Composable
fun LampTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    playing: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val background by animateColorAsState(
        targetValue = if (playing) Lamp.Bright else Lamp.Warm,
        animationSpec = if (playing) Motion.lampOn() else Motion.lampOff(),
        label = "lamp-ground",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Motion.state(),
        label = "transport-press",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(pressScale)
            .background(background, RoundedCornerShape(Corner.plate))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Ink.OnLamp),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Ink.OnLamp) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Ink.OnLamp,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}
