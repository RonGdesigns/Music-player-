package com.irondigital.spindle.playback

import com.irondigital.spindle.R
import org.junit.Assert.*
import org.junit.Test

class FavoriteButtonStateTest {
    @Test fun `switching from favorite to ordinary track restores outline and add action`() {
        val favorites = setOf("liked")
        val liked = FavoriteButtonState.forTrack("liked", favorites)
        val next = FavoriteButtonState.forTrack("ordinary", favorites)
        assertEquals(R.drawable.ic_favorite_filled, liked.iconRes)
        assertEquals("Remove from favorites", liked.label)
        assertTrue(liked.selected)
        assertEquals(R.drawable.ic_favorite_outline, next.iconRes)
        assertEquals("Add to favorites", next.label)
        assertFalse(next.selected)
        assertTrue(next.enabled)
    }

    @Test fun `editing favorites without changing tracks updates selected state`() {
        assertFalse(FavoriteButtonState.forTrack("song", emptySet()).selected)
        assertTrue(FavoriteButtonState.forTrack("song", setOf("song")).selected)
        assertFalse(FavoriteButtonState.forTrack("song", emptySet()).selected)
    }

    @Test fun `empty player cannot favorite a missing track`() {
        val state = FavoriteButtonState.forTrack(null, setOf("liked"))
        assertFalse(state.enabled)
        assertFalse(state.selected)
    }
}
