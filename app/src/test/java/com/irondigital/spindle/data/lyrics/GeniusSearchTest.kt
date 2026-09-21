package com.irondigital.spindle.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class GeniusSearchTest {
    @Test
    fun `search includes artist and cleaned title`() {
        assertEquals(
            "https://genius.com/search?q=Juice+WRLD+Rental",
            GeniusSearch.url("Rental (Official Audio)", "Juice WRLD"),
        )
    }

    @Test
    fun `unofficial version markers stay in the search`() {
        assertEquals(
            "https://genius.com/search?q=Artist+Song+%28Demo+V2%29",
            GeniusSearch.url("Song (Demo V2)", "Artist"),
        )
    }

    @Test
    fun `filename is used when metadata title is unknown`() {
        assertEquals(
            "https://genius.com/search?q=Artist+Lost+Song",
            GeniusSearch.url("<unknown>", "Artist", "Lost_Song.mp3"),
        )
    }
}
