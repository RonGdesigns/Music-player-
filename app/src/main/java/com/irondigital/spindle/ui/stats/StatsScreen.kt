package com.irondigital.spindle.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.TickScale
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What your listening actually looks like, built entirely from the play-event
 * log the app already keeps to make Most Played work.
 *
 * The composition is a KPI row over two charts over two ranked tables — one
 * hero number, then shape, then detail — so the screen answers "how much",
 * "when" and "what" in that order.
 */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val viewModel: StatsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ground.Deep)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = Space.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LampIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Text("Listening", style = SpindleType.ScreenTitle, color = Ink.Primary)
        }

        if (!state.hasData) {
            EmptyStats()
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.gutter,
                end = Space.gutter,
                bottom = Space.xxl,
            )
        ) {
            item {
                Spacer(Modifier.height(Space.m))
                HeroFigure(
                    value = formatListeningTime(state.totalListenedMs),
                    caption = "of music played on this phone",
                )
                Spacer(Modifier.height(Space.l))
                TickScale(height = 10.dp, spacing = 6.dp)
                Spacer(Modifier.height(Space.l))
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    StatTile(
                        value = state.totalPlays.toString(),
                        label = "plays counted",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = "${state.distinctTracksPlayed}",
                        label = "of ${state.librarySize} tracks heard",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = "${state.currentStreakDays}d",
                        label = "streak, best ${state.longestStreakDays}d",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Space.xl))
            }

            item {
                SectionHeading("Last 30 days")
                ColumnChart(
                    values = state.dailyPlays.map { it.plays },
                    axisLabels = dayAxisLabels(state.dailyPlays),
                    valueLabel = { index, value ->
                        val day = state.dailyPlays.getOrNull(index)
                        val date = day?.let { dayFormat.format(Date(it.dayStartMs)) } ?: ""
                        "$date — ${plays(value)}"
                    },
                    summary = "Daily plays over the last 30 days. " +
                        "Highest ${state.dailyPlays.maxOfOrNull { it.plays } ?: 0} in a day, " +
                        "${state.dailyPlays.sumOf { it.plays }} in total.",
                )
                Spacer(Modifier.height(Space.xl))
            }

            item {
                SectionHeading("When you listen")
                ColumnChart(
                    values = state.playsByHour,
                    axisLabels = HOUR_TICKS,
                    valueLabel = { index, value -> "${hourLabel(index)} — ${plays(value)}" },
                    summary = "Plays by hour of day over the last 30 days. " +
                        "Busiest hour is ${hourLabel(state.playsByHour.indexOf(state.playsByHour.max()))}.",
                    height = 108.dp,
                )
                Spacer(Modifier.height(Space.xl))
            }

            item {
                SectionHeading("Most played artists")
                RankedBars(entries = state.topArtists, unit = { plays(it) })
                Spacer(Modifier.height(Space.xl))
            }

            item {
                SectionHeading("Most played tracks")
                RankedBars(entries = state.topTracks, unit = { plays(it) })
                Spacer(Modifier.height(Space.l))
                Text(
                    text = "Counts come from the same log as Most Played: a track " +
                        "registers once you have heard half of it, or four minutes.",
                    style = SpindleType.Data,
                    color = Steel.Dim,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Column {
        Text(text = text, style = SpindleType.Section, color = Ink.Primary)
        Spacer(Modifier.height(Space.m))
    }
}

@Composable
private fun EmptyStats() {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = Space.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            Text("Nothing counted yet", style = SpindleType.DisplaySmall, color = Ink.Primary)
            Spacer(Modifier.height(Space.s))
            Text(
                text = "Play something. A track counts once you have heard half of " +
                    "it, or four minutes — and everything on this screen builds " +
                    "itself from there.",
                style = SpindleType.Body,
                color = Steel.Dim,
            )
        }
    }
}

// ------------------------------------------------------------------ labels

private val dayFormat = SimpleDateFormat("MMM d", Locale.US)
private val shortDayFormat = SimpleDateFormat("M/d", Locale.US)

/** Four ticks across thirty days — enough to orient, few enough to read. */
private fun dayAxisLabels(days: List<DayBucket>): List<Pair<Int, String>> {
    if (days.isEmpty()) return emptyList()
    val positions = listOf(0, days.size / 3, days.size * 2 / 3, days.lastIndex).distinct()
    return positions.map { it to shortDayFormat.format(Date(days[it].dayStartMs)) }
}

private val HOUR_TICKS = listOf(
    0 to "00",
    6 to "06",
    12 to "12",
    18 to "18",
    23 to "23",
)

private fun hourLabel(hour: Int): String =
    if (hour !in 0..23) "—" else String.format(Locale.US, "%02d:00", hour)

private fun plays(count: Int): String = if (count == 1) "1 play" else "$count plays"

/**
 * Listening time reads as hours once there are enough of them. Days would be
 * technically correct and completely unhelpful.
 */
private fun formatListeningTime(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours >= 1 -> "$hours hr $minutes min"
        minutes >= 1 -> "$minutes min"
        else -> "under a minute"
    }
}
