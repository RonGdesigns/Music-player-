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
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import com.irondigital.spindle.ui.personal.NameDialog
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
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/** Full queue with long-press drag handles and accessible move/remove menu actions. */
@Composable
fun QueuePane(playerViewModel: PlayerViewModel) {
    val queue by playerViewModel.queue.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    if(saving) NameDialog("Save listening session", onDismiss = { saving = false }, onSave = playerViewModel::saveSession)

    LaunchedEffect(playback.queueIndex) {
        if (playback.queueIndex >= 0 && playback.queueIndex < queue.size) {
            listState.animateScrollToItem(playback.queueIndex, scrollOffset = -120)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Ground.Scrim)) {
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
            TextButton(enabled = queue.isNotEmpty(), onClick = { saving = true }) { Text("Save session") }

        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            itemsIndexed(queue, key = { index, track -> "$index-${track.mediaId}" }) { index, track ->
                val isCurrent = index == playback.queueIndex
                var menu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if(index == dragTarget) Ground.Raised else androidx.compose.ui.graphics.Color.Transparent)
                        .heightIn(min = 72.dp)
                        .clickable { playerViewModel.seekToQueueEntry(track) }
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
                                color = if (isCurrent) Lamp.Bright else Steel.Dim,
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

                    Box(Modifier.width(48.dp).height(56.dp)
                        .semantics { contentDescription = "Drag ${track.title} to reorder" }
                        .pointerInput(track.revision, track.index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragTarget = index
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                    dragY = ((info?.offset ?: 0) + (info?.size ?: 0) / 2).toFloat()
                                },
                                onDragCancel = { dragTarget = null },
                                onDragEnd = {
                                    dragTarget?.let { if(it != index) playerViewModel.moveInQueue(track, it) }
                                    dragTarget = null
                                },
                                onDrag = { change, amount ->
                                    change.consume(); dragY += amount.y
                                    val info = listState.layoutInfo
                                    val target = info.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - dragY) }
                                    dragTarget = target?.index
                                    if(dragY > info.viewportEndOffset - 80 || dragY < info.viewportStartOffset + 80) {
                                        scope.launch { listState.scrollBy(amount.y) }
                                    }
                                },
                            )
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.DragHandle, null, tint = Steel.Bright)
                    }
                    Box {
                        LampIconButton(Icons.Filled.MoreVert, "Queue actions for ${track.title}", { menu = true }, size = 48.dp)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0,
                                onClick = { playerViewModel.moveInQueue(track, index - 1); menu = false })
                            DropdownMenuItem(text = { Text("Move down") }, enabled = index < queue.lastIndex,
                                onClick = { playerViewModel.moveInQueue(track, index + 1); menu = false })
                            DropdownMenuItem(text = { Text("Remove from queue") },
                                onClick = { playerViewModel.removeFromQueue(track); menu = false })
                        }
                    }
                }
            }
        }
    }
}
