package com.irondigital.spindle.data.lyrics

import com.irondigital.spindle.data.tagging.TitleNormalizer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds a public Genius search without embedding a Genius API token in the APK.
 *
 * Genius' official API returns song metadata and page URLs, not the lyric body.
 * Sending the user to the public results page keeps the candidate selection in
 * Genius, where alternate names for demos and leaked recordings are most likely
 * to be represented, and lets the user copy lyrics they are entitled to use
 * back into Spindle's editor.
 */
object GeniusSearch {
    private const val SEARCH_URL = "https://genius.com/search?q="

    fun url(title: String, artist: String, displayName: String = ""): String {
        val cleanTitle = searchableTitle(title, displayName)
        val cleanArtist = artist
            .takeUnless(::isUnknown)
            ?.let(TitleNormalizer::normalizeSpacing)
            .orEmpty()

        val query = listOf(cleanArtist, cleanTitle)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "lyrics" }

        return SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
    }

    private fun searchableTitle(title: String, displayName: String): String {
        val fallback = displayName.substringBeforeLast('.', displayName)
        val value = title.takeUnless(::isUnknown).orEmpty().ifBlank { fallback }
        return TitleNormalizer.stripUploadNoise(value)
    }

    private fun isUnknown(value: String): Boolean =
        value.isBlank() || value.trim().equals("unknown", ignoreCase = true) ||
            value.trim().equals("<unknown>", ignoreCase = true)
}
