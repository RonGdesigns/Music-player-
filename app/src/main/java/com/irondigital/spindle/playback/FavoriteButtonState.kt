package com.irondigital.spindle.playback

import com.irondigital.spindle.R

/** Shared selected state for media-session controls and widget snapshots. */
internal data class FavoriteButtonState(val enabled: Boolean, val selected: Boolean) {
    val iconRes: Int get() = if (selected) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_outline
    val label: String get() = if (selected) "Remove from favorites" else "Add to favorites"

    companion object {
        fun forTrack(mediaId: String?, favorites: Set<String>) =
            FavoriteButtonState(mediaId != null, mediaId != null && mediaId in favorites)
    }
}
