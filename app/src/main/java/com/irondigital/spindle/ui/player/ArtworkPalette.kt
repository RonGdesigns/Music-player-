package com.irondigital.spindle.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.irondigital.spindle.ui.theme.ArtworkColors
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Steel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls three colors out of the cover art.
 *
 * This is the only color in the app the app did not choose, and it is what
 * makes the player look different for every record without any of it being
 * arbitrary — the colors are the record's own.
 *
 * Every extracted color is then forced dark enough to sit behind white text.
 * A palette that comes back pale would otherwise turn the now-playing screen
 * into unreadable light-on-light the moment somebody plays an album with a
 * white sleeve, and "mostly fine" is not a contrast standard.
 */
@Composable
fun rememberArtworkColors(artUri: String?): State<ArtworkColors> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(ArtworkColors()) }

    LaunchedEffect(artUri) {
        state.value = if (artUri == null) ArtworkColors() else extract(context, artUri)
    }
    return state
}

private suspend fun extract(context: Context, artUri: String): ArtworkColors =
    withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, artUri) ?: return@withContext ArtworkColors()
        val palette = runCatching {
            Palette.from(bitmap).clearFilters().maximumColorCount(16).generate()
        }.getOrNull() ?: return@withContext ArtworkColors()

        val dominant = palette.dominantSwatch?.rgb?.let(::Color)
        val vibrant = (palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch)
            ?.rgb?.let(::Color)
        val muted = (palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.lightMutedSwatch)
            ?.rgb?.let(::Color)

        ArtworkColors(
            // The ground goes furthest down: it sits under everything and only
            // has to read as "this record's color", not as a color.
            dominant = dominant?.toGround() ?: Ground.Plate,
            vibrant = vibrant?.toAccent() ?: Lamp.Bright,
            muted = muted?.toGround(target = 0.14f) ?: Steel.Engrave,
            isFallback = dominant == null && vibrant == null,
        )
    }

private fun decodeScaled(context: Context, artUri: String): Bitmap? = runCatching {
    val uri = Uri.parse(artUri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0) return@runCatching null

    // Palette quantizes anyway; 128px is plenty and keeps this off the
    // main thread's critical path when skipping quickly through an album.
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 128) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}.getOrNull()

/**
 * Darkens a color to a target luminance while keeping its hue. Anything at or
 * below [target] is left alone — the point is a ceiling, not a uniform wash.
 */
private fun Color.toGround(target: Float = 0.09f): Color {
    val currentLuminance = luminance()
    if (currentLuminance <= target) return this
    val factor = (target / currentLuminance).coerceIn(0.05f, 1f)
    return Color(
        red = red * factor,
        green = green * factor,
        blue = blue * factor,
        alpha = 1f,
    )
}

/**
 * Accents have the opposite problem: a swatch from a dark sleeve can come back
 * almost black and vanish. This lifts such a color until it clears roughly
 * 4.5:1 against the app ground.
 */
private fun Color.toAccent(): Color {
    var candidate = this
    var guard = 0
    while (candidate.luminance() < MIN_ACCENT_LUMINANCE && guard++ < 12) {
        candidate = Color(
            red = (candidate.red * 1.22f + 0.02f).coerceAtMost(1f),
            green = (candidate.green * 1.22f + 0.02f).coerceAtMost(1f),
            blue = (candidate.blue * 1.22f + 0.02f).coerceAtMost(1f),
            alpha = 1f,
        )
    }
    return candidate
}

/**
 * Contrast against Ground.Deep (relative luminance 0.0043) reaches 4.5:1 at
 * about 0.19, so this is the floor for anything an accent has to carry.
 */
private const val MIN_ACCENT_LUMINANCE = 0.19f
