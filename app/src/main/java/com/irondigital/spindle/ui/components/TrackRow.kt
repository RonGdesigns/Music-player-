package com.irondigital.spindle.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.irondigital.spindle.data.model.Track
import com.irondigital.spindle.ui.theme.Corner
import com.irondigital.spindle.ui.theme.Ground
import com.irondigital.spindle.ui.theme.Ink
import com.irondigital.spindle.ui.theme.Lamp
import com.irondigital.spindle.ui.theme.Motion
import com.irondigital.spindle.ui.theme.Space
import com.irondigital.spindle.ui.theme.SpindleType
import com.irondigital.spindle.ui.theme.Steel

/**
 * One track in a list.
 *
 * The composition here is an index — dense, tabular, authority through
 * information rather than through cards. Play counts and durations are set in
 * the mono at tabular widths so they form real columns down the page, which is
 * the entire reason a list like this can carry a thousand rows and still be
 * scannable.
 *
 * The lit bar on the left is the same "this is live" language the widget and
 * the transport use. One signature, applied everywhere it applies.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isFavorite: Boolean = false,
    playCount: Int = 0,
    showArtwork: Boolean = true,
    /** Shown instead of artwork in album context, where art would repeat. */
    trackNumber: Int? = null,
) {
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) Lamp.Bright else Ink.Primary,
        animationSpec = if (isCurrent) Motion.lampOn() else Motion.lampOff(),
        label = "row-title",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Space.gutter, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lit groove: the current track's marker. 2dp, same as every corner in
        // the app, so it reads as part of the same machined language.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (isCurrent) 34.dp else 20.dp)
                .background(if (isCurrent) Lamp.Bright else Steel.Engrave)
        )

        Spacer(Modifier.width(Space.m))

        when {
            trackNumber != null -> {
                Box(
                    modifier = Modifier.width(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent && isPlaying) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "Now playing",
                            tint = Lamp.Bright,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(
                            text = trackNumber.toString().padStart(2, '0'),
                            style = SpindleType.Data,
                            color = if (isCurrent) Lamp.Bright else Steel.Dim,
                        )
                    }
                }
                Spacer(Modifier.width(Space.m))
            }

            showArtwork -> {
                Artwork(
                    uri = track.albumArtUri?.toString(),
                    size = 48,
                    contentDescription = null,
                )
                Spacer(Modifier.width(Space.m))
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = track.title.ifBlank { track.displayName },
                style = SpindleType.RowTitle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = SpindleType.Secondary,
                color = Steel.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(Space.s))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = formatDuration(track.durationMs),
                style = SpindleType.Data,
                color = Steel.Dim,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Favourite",
                        tint = Lamp.Bright,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (playCount > 0) {
                    // The number that makes Most Played auditable: you can see
                    // why a track is on the list from the list itself.
                    Text(
                        text = "×$playCount",
                        style = SpindleType.DataEmphasis,
                        color = if (isCurrent) Lamp.Bright else Steel.Dim,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = formatPlayCount(playCount)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun Artwork(
    uri: String?,
    size: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(Corner.edge))
            .background(Ground.Raised),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = contentDescription,
                tint = Steel.Engrave,
                modifier = Modifier.size((size * 0.36f).dp),
            )
        }
    }
}
