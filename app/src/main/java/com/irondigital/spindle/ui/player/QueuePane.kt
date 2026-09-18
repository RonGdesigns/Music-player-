package com.irondigital.spindle.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.irondigital.spindle.ui.PlayerViewModel
import com.irondigital.spindle.ui.components.LampIconButton
import com.irondigital.spindle.ui.components.formatDuration
import com.irondigital.spindle.ui.components.formatTotalDuration
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * The queue, in full and editable.
 *
 * The widget shows what is coming; this is where you rearrange it. Tap to jump,
 * the arrows move a track, the cross drops it. Drag-and-drop would be nicer
 * still, but explicit buttons work with a screen reader and with one thumb on a
 * bus, which drag does not.
 */
@Composable
fun QueuePane(playerViewModel: PlayerViewModel) {
    val queue by playerViewModel.queue.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(playback.queueIndex) {
        if (playback.queueIndex >= 0 && playback.queueIndex < queue.size) {
            listState.animateScrollToItem(playback.queueIndex, scrollOffset = -120)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (queue.isEmpty()) "Queue is empty" else "${queue.size} tracks",
                style = SpindleType.Secondary,
                color = Steel.Bright,
            )
            Spacer(Modifier.weight(1f))
            if (queue.isNotEmpty()) {
                Text(
                    text = formatTotalDuration(
                        queue.drop(playback.queueIndex.coerceAtLeast(0)).sumOf { it.durationMs }
                    ) + " left",
                    style = SpindleType.Data,
                    color = Steel.Dim,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            itemsIndexed(queue, key = { index, track -> "$index-${track.mediaId}" }) { index, track ->
                val isCurrent = index == playback.queueIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable { playerViewModel.seekToQueueIndex(index) }
                        .padding(start = Space.gutter, end = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (isCurrent) 32.dp else 18.dp)
                            .background(if (isCurrent) Lamp.Bright else Steel.Engrave)
                    )

                    Spacer(Modifier.width(Space.m))

                    Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                        if (isCurrent && playback.isPlaying) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = "Now playing",
                                tint = Lamp.Bright,
                                modifier = Modifier.width(16.dp),
                            )
                        } else {
                            Text(
                                text = (index + 1).toString(),
                                style = SpindleType.Data,
                                color = if (isCurrent) Lamp.Bright else Steel.Engrave,
                            )
                        }
                    }

                    Spacer(Modifier.width(Space.m))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = track.title,
                            style = SpindleType.RowTitle,
                            color = if (isCurrent) Lamp.Bright else Ink.Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${track.artist} · ${formatDuration(track.durationMs)}",
                            style = SpindleType.Secondary,
                            color = Steel.Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    LampIconButton(
                        icon = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Move ${track.title} up",
                        onClick = { playerViewModel.moveInQueue(index, index - 1) },
                        enabled = index > 0,
                        size = 36.dp,
                        iconSize = 18.dp,
                        unlitColor = Steel.Dim,
                    )
                    LampIconButton(
                        icon = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Move ${track.title} down",
                        onClick = { playerViewModel.moveInQueue(index, index + 1) },
                        enabled = index < queue.lastIndex,
                        size = 36.dp,
                        iconSize = 18.dp,
                        unlitColor = Steel.Dim,
                    )
                    LampIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Remove ${track.title} from queue",
                        onClick = { playerViewModel.removeFromQueue(index) },
                        size = 36.dp,
                        iconSize = 16.dp,
                        unlitColor = Steel.Engrave,
                    )
                }
            }
        }
    }
}
