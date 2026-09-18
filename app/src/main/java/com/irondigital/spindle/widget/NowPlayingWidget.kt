package com.irondigital.spindle.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import com.irondigital.spindle.MainActivity
import com.irondigital.spindle.R
import com.irondigital.spindle.playback.PlaybackSnapshot
import com.irondigital.spindle.playback.QueueEntry
import com.irondigital.spindle.spindle
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Steel
import kotlinx.coroutines.flow.first

/**
 * The widget Samsung Music used to have and no longer does: cover art,
 * transport, and the live queue you can reach into without opening anything.
 *
 * It resizes through four honest layouts rather than one layout that squashes.
 * At a bar's height there is no room for a queue, so there is no queue; at full
 * height the queue is the point and gets most of the space.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            BAR_SIZE,
            CARD_SIZE,
            QUEUE_SIZE,
            TALL_SIZE,
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read outside provideContent: this is disk and bitmap work, and doing
        // it in composition would block the launcher's render.
        val snapshot = context.spindle.snapshotStore.snapshot.first()
        val art = WidgetArt.load(context, snapshot.artUri)

        provideContent {
            val size = LocalSize.current
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Ground.Plate))
                    .padding(PLATE_PADDING)
            ) {
                if (!snapshot.hasContent) {
                    EmptyPlate()
                } else {
                    val showQueue = size.height >= QUEUE_SIZE.height
                    val showProgress = size.height >= CARD_SIZE.height
                    val showExtraControls = size.width >= CARD_SIZE.width

                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        NowPlayingHead(
                            snapshot = snapshot,
                            art = art,
                            compact = !showProgress,
                        )

                        if (showProgress) {
                            Spacer(GlanceModifier.height(10.dp))
                            ProgressRule(snapshot)
                            Spacer(GlanceModifier.height(6.dp))
                            TransportRow(snapshot, showExtraControls)
                        } else {
                            Spacer(GlanceModifier.height(2.dp))
                        }

                        if (showQueue) {
                            Spacer(GlanceModifier.height(10.dp))
                            EngravedRule()
                            Spacer(GlanceModifier.height(6.dp))
                            QueueList(snapshot)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- pieces

    @Composable
    private fun EmptyPlate() {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = "Nothing queued",
                style = TextStyle(
                    color = ColorProvider(Ink.Primary),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = "Tap to open your library",
                style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 12.sp),
            )
        }
    }

    @Composable
    private fun NowPlayingHead(snapshot: PlaybackSnapshot, art: Bitmap?, compact: Boolean) {
        val artSize = if (compact) 48.dp else 60.dp
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(artSize)
                    .background(ColorProvider(Ground.Raised))
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                if (art != null) {
                    Image(
                        provider = ImageProvider(art),
                        contentDescription = "Open ${snapshot.title}",
                        modifier = GlanceModifier.size(artSize),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = GlanceModifier.size(artSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_note),
                            contentDescription = null,
                            modifier = GlanceModifier.size(20.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(Steel.Engrave)),
                        )
                    }
                }
            }

            Spacer(GlanceModifier.width(12.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = snapshot.title.ifBlank { "Unknown title" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Ink.Primary),
                        fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = snapshot.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 12.sp),
                )
            }

            if (compact) {
                Spacer(GlanceModifier.width(4.dp))
                TransportButton(R.drawable.ic_previous, "Previous", 34.dp, Steel.Bright, PreviousAction::class.java)
                PlayPauseButton(snapshot.isPlaying, 40.dp)
                TransportButton(R.drawable.ic_next, "Next", 34.dp, Steel.Bright, NextAction::class.java)
            } else {
                FavoriteButton(snapshot.isFavorite)
            }
        }
    }

    /**
     * Progress doubles as the widget's main dividing rule, which is why it is
     * here rather than tucked under the artwork: one element doing two jobs.
     *
     * It shows the position as of the last playback event. A widget that ticked
     * every second would cost a wakeup a second for a bar most people never
     * look at, which is not a trade worth making.
     */
    @Composable
    private fun ProgressRule(snapshot: PlaybackSnapshot) {
        val fraction = if (snapshot.durationMs > 0) {
            (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                color = ColorProvider(Lamp.Bright),
                backgroundColor = ColorProvider(Steel.Engrave),
            )
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = formatTime(snapshot.positionMs),
                    style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 11.sp),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = formatTime(snapshot.durationMs),
                    style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 11.sp),
                )
            }
        }
    }

    @Composable
    private fun TransportRow(snapshot: PlaybackSnapshot, showExtras: Boolean) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            if (showExtras) {
                TransportButton(
                    iconRes = R.drawable.ic_shuffle,
                    description = if (snapshot.shuffleEnabled) "Shuffle on" else "Shuffle off",
                    size = 38.dp,
                    tint = if (snapshot.shuffleEnabled) Lamp.Bright else Steel.Engrave,
                    action = ToggleShuffleAction::class.java,
                )
                Spacer(GlanceModifier.defaultWeight())
            }

            TransportButton(R.drawable.ic_previous, "Previous", 42.dp, Steel.Bright, PreviousAction::class.java)
            Spacer(GlanceModifier.width(4.dp))
            PlayPauseButton(snapshot.isPlaying, 46.dp)
            Spacer(GlanceModifier.width(4.dp))
            TransportButton(R.drawable.ic_next, "Next", 42.dp, Steel.Bright, NextAction::class.java)

            if (showExtras) {
                Spacer(GlanceModifier.defaultWeight())
                val repeatIcon = if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) {
                    R.drawable.ic_repeat_one
                } else {
                    R.drawable.ic_repeat
                }
                TransportButton(
                    iconRes = repeatIcon,
                    description = "Repeat",
                    size = 38.dp,
                    tint = if (snapshot.repeatMode == Player.REPEAT_MODE_OFF) Steel.Engrave else Lamp.Bright,
                    action = CycleRepeatAction::class.java,
                )
            }
        }
    }

    /**
     * The lamp. Amber ground, dark glyph — the one filled control on the plate,
     * so the thing you press most is the thing you can hit without looking.
     */
    @Composable
    private fun PlayPauseButton(isPlaying: Boolean, size: androidx.compose.ui.unit.Dp) {
        Box(
            modifier = GlanceModifier
                .size(size)
                .background(ColorProvider(if (isPlaying) Lamp.Bright else Lamp.Warm))
                .clickable(actionRunCallback<PlayPauseAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = GlanceModifier.size(size * 0.5f),
                colorFilter = ColorFilter.tint(ColorProvider(Ink.OnLamp)),
            )
        }
    }

    @Composable
    private fun TransportButton(
        iconRes: Int,
        description: String,
        size: androidx.compose.ui.unit.Dp,
        tint: androidx.compose.ui.graphics.Color,
        action: Class<out ActionCallback>,
    ) {
        Box(
            modifier = GlanceModifier
                .size(size)
                .clickable(actionRunCallback(action)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = description,
                modifier = GlanceModifier.size(size * 0.52f),
                colorFilter = ColorFilter.tint(ColorProvider(tint)),
            )
        }
    }

    @Composable
    private fun FavoriteButton(isFavorite: Boolean) {
        Box(
            modifier = GlanceModifier
                .size(40.dp)
                .clickable(actionRunCallback<ToggleFavoriteAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(
                    if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
                ),
                contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                modifier = GlanceModifier.size(19.dp),
                colorFilter = ColorFilter.tint(
                    ColorProvider(if (isFavorite) Lamp.Bright else Steel.Dim)
                ),
            )
        }
    }

    /** The faceplate engraving: a hairline the same colour as a milled groove. */
    @Composable
    private fun EngravedRule() {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorProvider(Steel.Engrave))
        ) {}
    }

    @Composable
    private fun QueueList(snapshot: PlaybackSnapshot) {
        val upcoming = snapshot.upcoming(QUEUE_WINDOW)
        if (upcoming.isEmpty()) return

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(upcoming, itemId = { it.index.toLong() }) { indexed ->
                QueueRow(
                    entry = indexed.value,
                    queueIndex = indexed.index,
                    isCurrent = indexed.index == snapshot.currentIndex,
                )
            }
        }
    }

    @Composable
    private fun QueueRow(entry: QueueEntry, queueIndex: Int, isCurrent: Boolean) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable(
                    actionRunCallback<JumpToIndexAction>(
                        androidx.glance.action.actionParametersOf(
                            JumpToIndexAction.INDEX_KEY to queueIndex
                        )
                    )
                )
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            // A lit bar on the playing row, unlit groove on the rest. The same
            // lamp language the rest of the app uses for "this is live".
            Box(
                modifier = GlanceModifier
                    .width(2.dp)
                    .height(22.dp)
                    .background(ColorProvider(if (isCurrent) Lamp.Bright else Steel.Engrave))
            ) {}

            Spacer(GlanceModifier.width(10.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = entry.title.ifBlank { "Unknown title" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(if (isCurrent) Lamp.Bright else Ink.Primary),
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
                Text(
                    text = entry.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 11.sp),
                )
            }

            Spacer(GlanceModifier.width(8.dp))

            Text(
                text = formatTime(entry.durationMs),
                style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 11.sp),
            )
        }
    }

    companion object {
        private val PLATE_PADDING = 12.dp

        /** How far ahead the widget lists. Beyond this, open the app. */
        private const val QUEUE_WINDOW = 40

        private val BAR_SIZE = DpSize(180.dp, 72.dp)
        private val CARD_SIZE = DpSize(250.dp, 132.dp)
        private val QUEUE_SIZE = DpSize(250.dp, 210.dp)
        private val TALL_SIZE = DpSize(300.dp, 340.dp)

        suspend fun refresh(context: Context) {
            runCatching { NowPlayingWidget().updateAll(context) }
        }

        internal fun formatTime(ms: Long): String {
            if (ms <= 0) return "0:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
