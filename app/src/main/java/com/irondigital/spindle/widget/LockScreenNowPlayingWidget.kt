package com.irondigital.spindle.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.irondigital.spindle.MainActivity
import com.irondigital.spindle.R
import com.irondigital.spindle.playback.PlaybackSnapshot
import com.irondigital.spindle.spindle
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Steel
import kotlinx.coroutines.flow.first

/**
 * Compact widget intended for Samsung LockStar / lock-screen widget hosts.
 *
 * Standard Android launchers can also place it on the home screen. The layout
 * deliberately keeps only title, artist and the three essential transport
 * controls so a lock-screen host never has to clip the buttons.
 */
class LockScreenNowPlayingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(220.dp, 72.dp),
            DpSize(300.dp, 72.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = context.spindle.snapshotStore.snapshot.first()

        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Ground.Plate))
                    .padding(8.dp)
            ) {
                if (snapshot.hasContent) {
                    PlayerRow(snapshot)
                } else {
                    EmptyRow()
                }
            }
        }
    }

    @Composable
    private fun PlayerRow(snapshot: PlaybackSnapshot) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Text(
                    text = snapshot.title.ifBlank { "Unknown title" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Ink.Primary),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = snapshot.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Steel.Dim),
                        fontSize = 11.sp,
                    ),
                )
            }

            Spacer(GlanceModifier.width(4.dp))
            SmallButton(
                iconRes = R.drawable.ic_previous,
                description = "Previous",
                action = PreviousAction::class.java,
            )
            Spacer(GlanceModifier.width(2.dp))
            PlayButton(snapshot.isPlaying)
            Spacer(GlanceModifier.width(2.dp))
            SmallButton(
                iconRes = R.drawable.ic_next,
                description = "Next",
                action = NextAction::class.java,
            )
        }
    }

    @Composable
    private fun EmptyRow() {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_note),
                contentDescription = null,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(ColorProvider(Lamp.Bright)),
            )
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    text = "Spindle",
                    style = TextStyle(
                        color = ColorProvider(Ink.Primary),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = "Tap to open your library",
                    style = TextStyle(
                        color = ColorProvider(Steel.Dim),
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    }

    @Composable
    private fun PlayButton(isPlaying: Boolean) {
        Box(
            modifier = GlanceModifier
                .size(38.dp)
                .background(ColorProvider(Lamp.Bright))
                .clickable(actionRunCallback<PlayPauseAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(ColorProvider(Ink.OnLamp)),
            )
        }
    }

    @Composable
    private fun SmallButton(
        iconRes: Int,
        description: String,
        action: Class<out androidx.glance.appwidget.action.ActionCallback>,
    ) {
        Box(
            modifier = GlanceModifier
                .size(34.dp)
                .clickable(actionRunCallback(action)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = description,
                modifier = GlanceModifier.size(19.dp),
                colorFilter = ColorFilter.tint(ColorProvider(Steel.Bright)),
            )
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { LockScreenNowPlayingWidget().updateAll(context) }
        }
    }
}
