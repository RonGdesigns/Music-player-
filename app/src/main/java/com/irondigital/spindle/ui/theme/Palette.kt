package com.irondigital.spindle.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The reference is a 1970s hi-fi separate: an anodised faceplate, engraved
 * scales, and a warm lamp behind the meter that tells you the thing is live.
 * Not a dark-mode-with-a-neon-accent skin, and deliberately nothing like the
 * warm-paper editorial register every second app arrives in.
 *
 * Three families, each with a job:
 *
 *   GROUND   blue-black graphite. Never pure #000 — a true black plate reads
 *            as a hole in the screen and destroys the sense of a surface.
 *   LAMP     sodium amber. Reserved for "this is live": what is playing, what
 *            is selected, what you just pressed. Nothing decorative wears it.
 *   STEEL    cool grey-blue. Structure, secondary text, engraved rules. Reads
 *            as metal against the amber rather than as a second accent.
 *
 * The album artwork supplies a fourth colour at runtime (see ArtworkPalette),
 * which is the only colour on screen the app did not choose — and the reason
 * the player looks different for every record.
 *
 * Contrast ratios against GroundDeep, computed from the sRGB values:
 *   Ink       16.1:1      SteelDim  5.5:1
 *   Lamp      10.5:1      Steel     8.1:1
 * All clear AA for body text. Verify on the device before shipping.
 */
object Ink {
    val Primary = Color(0xFFEAF0F6)   // tinted white, never #FFFFFF
    val OnLamp = Color(0xFF14100A)    // for text sitting on the amber itself
}

object Ground {
    val Deep = Color(0xFF0B0E13)      // the cabinet
    val Plate = Color(0xFF141920)     // the faceplate a control sits on
    val Raised = Color(0xFF1C232C)    // a pressed or hovered plate
    val Scrim = Color(0xE60B0E13)     // over artwork, 90% — keeps text legible
}

object Lamp {
    val Bright = Color(0xFFFFAE3D)
    val Warm = Color(0xFFE08A1E)
    val Cold = Color(0xFF6B4A14)      // the same lamp, unlit
    val Glow = Color(0x33FFAE3D)
}

object Steel {
    val Bright = Color(0xFF8FA3B8)
    val Dim = Color(0xFF7A8A9C)       // still AA on both grounds
    val Engrave = Color(0xFF222A34)   // rules and tick marks; not for text
    val EngraveLight = Color(0xFF2E3946)
}

/** Sparingly used: destructive confirmation, and only there. */
val SignalRed = Color(0xFFD9634A)
