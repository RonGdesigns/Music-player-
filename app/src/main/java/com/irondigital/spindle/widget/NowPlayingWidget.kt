package com.irondigital.spindle.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.irondigital.spindle.data.personal.*
import kotlinx.coroutines.flow.catch
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

    // The launcher permits 180 x 70 dp. Responsive's previous 250 x 96 dp
    // minimum rendered an oversized layout when no candidate fit that space.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = context.spindle.snapshotStore
        // Seeded so the very first frame is drawn with real content rather
        // than the empty plate.
        val initial = store.snapshot.first()
        val appearanceFlow = context.spindle.listening.data.catch { emit(ListeningData()) }
        val initialAppearance = appearanceFlow.first().widget

        provideContent {
            // Collected inside provideContent, and that placement is the whole
            // point. Glance runs provideGlance once per session; every later
            // updateAll only recomposes the content that is already here. A
            // snapshot read outside this lambda is captured as a plain value and
            // frozen at the moment the widget was first laid out — which is why
            // the widget kept showing whatever was playing when you added it, no
            // matter what the player did afterwards.
            //
            // Collecting the flow here makes the composition observe it, so the
            // widget follows the player on its own and updateAll is only a nudge.
            val snapshot by store.snapshot.collectAsState(initial)
            val listening by appearanceFlow.collectAsState(ListeningData(widget = initialAppearance))
            val appearance = listening.widget
            val fontScale by context.spindle.widgetFontScale.collectAsState()

            // Decoding is kept off the composition and re-run only when the
            // artwork actually changes, not on every position update.
            var art by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(snapshot.artUri) {
                art = WidgetArt.load(context, snapshot.artUri)
            }

            val size = LocalSize.current
            val layout = WidgetLayout.calculate(size.height.value, fontScale, appearance)
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Ground.Plate))
                    .padding(PLATE_PADDING)
            ) {
                if (!snapshot.hasContent) {
                    EmptyPlate()
                } else {
                    val showQueue = layout.showQueue
                    val showProgress = layout.showProgress
                    val compact = !showProgress
                    val showExtraControls = size.width >= EXTRA_CONTROLS_MIN_WIDTH

                    if (layout.tiny) {
                        TinyNowPlaying(snapshot)
                        return@Box
                    }

                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        if (layout.artworkHeight > 0f) {
                            Box(GlanceModifier.fillMaxWidth().height(layout.artworkHeight.dp).background(ColorProvider(Ground.Raised)).clickable(actionStartActivity<MainActivity>()),
                                contentAlignment = Alignment.Center) {
                                if(art != null) Image(ImageProvider(art!!), "Cover art", modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Text(snapshot.title.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString(""),
                                    style = TextStyle(color = ColorProvider(Ink.Primary), fontSize = 40.sp, fontWeight = FontWeight.Bold))
                            }
                            Spacer(GlanceModifier.height(8.dp))
                            Text(snapshot.title, modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()), maxLines = 1, style = TextStyle(color = ColorProvider(Ink.Primary), fontSize = 16.sp, fontWeight = FontWeight.Medium))
                            Text(snapshot.artist, maxLines = 1, style = TextStyle(color = ColorProvider(Steel.Bright), fontSize = 12.sp))
                        } else NowPlayingHead(
                            snapshot = snapshot,
                            art = art,
                            compact = compact,
                            showFavorite = !compact && size.width >= CARD_SIZE.width,
                        )

                        if (showProgress) {
                            Spacer(GlanceModifier.height(6.dp))
                            ProgressRule(snapshot)
                        }

                        Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
                        // Previous / play-pause / next are never optional. The
                        // old compact branch put controls in the title row and
                        // some launchers measured that weighted row so narrowly
                        // the trailing buttons vanished completely.
                        TransportRow(
                            snapshot = snapshot,
                            showExtras = showExtraControls,
                            compact = compact,
                        )

                        if (showQueue) {
                            Spacer(GlanceModifier.height(8.dp))
                            EngravedRule()
                            Spacer(GlanceModifier.height(6.dp))
                            QueueList(snapshot, layout.queueRowHeight)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- pieces

    @Composable
    private fun TinyNowPlaying(snapshot: PlaybackSnapshot) {
        val controlSize = if (LocalSize.current.width >= 250.dp) 48.dp else 32.dp
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = snapshot.artist.ifBlank { "Unknown artist" },
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Steel.Dim),
                        fontSize = 10.sp,
                    ),
                )
            }
            Spacer(GlanceModifier.width(2.dp))
            TransportButton(
                R.drawable.ic_previous,
                "Previous",
                controlSize,
                Steel.Bright,
                PreviousAction::class.java,
            )
            PlayPauseButton(snapshot.isPlaying, controlSize)
            TransportButton(
                R.drawable.ic_next,
                "Next",
                controlSize,
                Steel.Bright,
                NextAction::class.java,
            )
        }
    }

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
    private fun NowPlayingHead(
        snapshot: PlaybackSnapshot,
        art: Bitmap?,
        compact: Boolean,
        showFavorite: Boolean,
    ) {
        val artSize = if (compact) COMPACT_ART else 52.dp

        // RemoteViews has no way to say "drop this if it does not fit" — a row
        // whose fixed children outgrow it just squeezes them into each other,
        // which is what put the artwork on top of the transport buttons at the
        // smallest size. So the budget is worked out here instead.
        //
        // At the 180dp minimum, 24dp of plate padding leaves 156dp: artwork and
        // its gutter take 52, the play button 40, and the title needs the rest.
        // Skip and next only appear once there is genuine room for them.
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

            if (showFavorite) {
                Spacer(GlanceModifier.width(4.dp))
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
    private fun TransportRow(
        snapshot: PlaybackSnapshot,
        showExtras: Boolean,
        compact: Boolean,
    ) {
        val sideSize = 48.dp
        val playSize = 48.dp
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            if (showExtras) {
                TransportButton(
                    iconRes = R.drawable.ic_shuffle,
                    description = if (snapshot.shuffleEnabled) "Shuffle on" else "Shuffle off",
                    size = 48.dp,
                    tint = if (snapshot.shuffleEnabled) Lamp.Bright else Steel.Dim,
                    action = ToggleShuffleAction::class.java,
                )
                Spacer(GlanceModifier.defaultWeight())
            }

            TransportButton(
                R.drawable.ic_previous,
                "Previous",
                sideSize,
                Steel.Bright,
                PreviousAction::class.java,
            )
            Spacer(GlanceModifier.width(4.dp))
            PlayPauseButton(snapshot.isPlaying, playSize)
            Spacer(GlanceModifier.width(4.dp))
            TransportButton(
                R.drawable.ic_next,
                "Next",
                sideSize,
                Steel.Bright,
                NextAction::class.java,
            )

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
                    size = 48.dp,
                    tint = if (snapshot.repeatMode == Player.REPEAT_MODE_OFF) Steel.Dim else Lamp.Bright,
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
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                modifier = GlanceModifier.size(19.dp),
                colorFilter = ColorFilter.tint(
                    ColorProvider(if (isFavorite) Lamp.Bright else Steel.Dim)
                ),
            )
        }
    }

    /** The faceplate engraving: a hairline the same color as a milled groove. */
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
    private fun QueueList(snapshot: PlaybackSnapshot, rowHeight: Float) {
        val upcoming = snapshot.upcoming(QUEUE_WINDOW)
        if (upcoming.isEmpty()) return

        val remaining = snapshot.remainingAfter(QUEUE_WINDOW)

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(upcoming, itemId = { it.index.toLong() }) { indexed ->
                QueueRow(
                    entry = indexed.value,
                    queueIndex = indexed.index,
                    isCurrent = indexed.index == snapshot.currentIndex,
                    rowHeight = rowHeight,
                )
            }

            // Said out loud rather than left to look like the end of the queue.
            // A list that simply stops is indistinguishable from a bug — which
            // is exactly how the old limit read.
            if (remaining > 0) {
                item {
                    Text(
                        text = "$remaining more — open Spindle",
                        style = TextStyle(color = ColorProvider(Steel.Dim), fontSize = 12.sp),
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clickable(actionStartActivity<MainActivity>())
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun QueueRow(entry: QueueEntry, queueIndex: Int, isCurrent: Boolean, rowHeight: Float) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(rowHeight.dp)
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
        private val PLATE_PADDING = 8.dp

        /** Artwork on the bar layout, sized so the transport still fits at 180dp. */
        private val COMPACT_ART = 40.dp

        /** Shuffle/repeat only appear when the host gives them real room. */
        private val EXTRA_CONTROLS_MIN_WIDTH = 300.dp

        /**
         * How far ahead the widget lists.
         *
         * Not a taste decision. Everything a widget draws crosses a Binder
         * transaction with a hard limit around 1MB, and from Android 12 a
         * Glance list puts every one of its rows in that payload rather than
         * fetching them lazily — so a queue of five thousand tracks cannot be
         * handed over whole, and a widget whose payload is too large does not
         * render at all. Two hundred is far enough to scroll through an album,
         * a playlist or an evening's listening, and leaves generous room under
         * the limit. Past it the list says so and offers the app.
         */
        private const val QUEUE_WINDOW = 200

        private val CARD_SIZE = DpSize(250.dp, 150.dp)

        suspend fun refresh(context: Context) {
            runCatching { NowPlayingWidget().updateAll(context) }
            LockScreenNowPlayingWidget.refresh(context)
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
