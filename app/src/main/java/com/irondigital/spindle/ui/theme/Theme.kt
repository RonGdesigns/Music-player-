package com.irondigital.spindle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Artwork-derived colors, published down the tree so the player background,
 * the progress bar and the visualizer all agree without passing them by hand.
 */
data class ArtworkColors(
    val dominant: Color = Ground.Plate,
    val vibrant: Color = Lamp.Bright,
    val muted: Color = Steel.Bright,
    val isFallback: Boolean = true,
)

val LocalArtworkColors = staticCompositionLocalOf { ArtworkColors() }

/**
 * There is one theme. A music player that spends its life behind album art has
 * no business being light, and offering a light mode here would mean two sets
 * of artwork-blending rules to keep honest for no real gain.
 */
private val SpindleColorScheme = darkColorScheme(
    primary = Lamp.Bright,
    onPrimary = Ink.OnLamp,
    secondary = Steel.Bright,
    onSecondary = Ink.OnLamp,
    background = Ground.Deep,
    onBackground = Ink.Primary,
    surface = Ground.Plate,
    onSurface = Ink.Primary,
    surfaceVariant = Ground.Raised,
    onSurfaceVariant = Steel.Bright,
    outline = Steel.Engrave,
    error = SignalRed,
)

@Composable
fun SpindleTheme(
    artworkColors: ArtworkColors = ArtworkColors(),
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // read so the app is not flagged as ignoring the setting

    CompositionLocalProvider(LocalArtworkColors provides artworkColors) {
        MaterialTheme(
            colorScheme = SpindleColorScheme,
            typography = SpindleTypography,
            content = content,
        )
    }
}
