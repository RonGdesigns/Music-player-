package com.irondigital.spindle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.irondigital.spindle.R

/**
 * Two families, contrasting on class and width rather than on era — a grotesque
 * against a mono, which is a real pairing, and a condensed cut of the same
 * grotesque for display, which is a real third voice without a third family.
 *
 * Both are SIL Open Font License: Barlow (Jeremy Tribby) and IBM Plex Mono
 * (Bold Monday for IBM). Bundled rather than fetched, so the app renders
 * identically with no network and no Play Services dependency.
 *
 * The mono has one job and it is functional: numbers. Track times, play counts,
 * bitrates, file sizes — anything that wants to line up in a column or be
 * compared down a list. It is never used as a label, and never tracked out in
 * small caps, which is the single most over-used typographic gesture in
 * software right now.
 */
val Grotesque = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

val Condensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * Six sizes in play, on roughly a 1.25 ratio. The display cut is 46sp against a
 * 15sp body — a little over 3x, which is the floor for a screen this narrow;
 * anything less and the now-playing screen stops being a different kind of
 * space from the list it came from.
 */
object SpindleType {

    val Display = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 46.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.4).sp,
    )

    val DisplaySmall = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    )

    val ScreenTitle = TextStyle(
        fontFamily = Condensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    )

    val Section = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

    val RowTitle = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    val Body = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp, // 1.6, the floor for running text
    )

    val Secondary = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Times, counts, specs. Tabular figures so columns align down a list. */
    val Data = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    val DataEmphasis = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    val DataLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /**
     * Lyrics. Wider leading than anything else in the app, because a lyric line
     * is read as a line rather than scanned as a row, and the active line needs
     * air above and below it to be findable while the phone is moving.
     */
    val Lyric = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 34.sp,
        textAlign = TextAlign.Start,
    )
}

/**
 * Material3 still wants a Typography for the components we borrow (the sheet,
 * the snackbar). Mapped onto the real scale so nothing arrives in Roboto.
 */
val SpindleTypography = Typography(
    displayLarge = SpindleType.Display,
    displayMedium = SpindleType.DisplaySmall,
    headlineMedium = SpindleType.ScreenTitle,
    titleLarge = SpindleType.Section,
    titleMedium = SpindleType.RowTitle,
    bodyLarge = SpindleType.Body,
    bodyMedium = SpindleType.Secondary,
    labelMedium = SpindleType.Data,
)
