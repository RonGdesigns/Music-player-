package com.irondigital.spindle.data.lyrics

import android.net.Uri
import com.irondigital.spindle.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks lyrics up in LRCLIB, an open database with no account and no key.
 *
 * Written against HttpURLConnection rather than pulling in an HTTP client: this
 * is two GET requests that return JSON, and a whole networking stack to make
 * them would be the largest dependency in the app for the smallest feature.
 *
 * What leaves the device is the track title, artist, album, and length —
 * nothing else, and only for tracks the user has asked about. That is a real
 * trade and the setting that enables it says so in those words.
 */
class OnlineLyricsClient(
    private val baseUrl: String = "https://lrclib.net",
) {

    suspend fun fetch(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ): LyricsLookup = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext LyricsLookup.NotFound

        // The exact endpoint matches on length as well as name, which is what
        // keeps a live cut from being answered with the studio lyrics.
        val exact = get(
            Uri.parse("$baseUrl/api/get").buildUpon()
                .appendQueryParameter("track_name", title)
                .appendQueryParameter("artist_name", artist)
                .apply { if (album.isNotBlank()) appendQueryParameter("album_name", album) }
                .appendQueryParameter("duration", ((durationMs + 500) / 1000).toString())
                .build()
                .toString()
        )

        when (exact) {
            is Response.Body ->
                return@withContext OnlineLyricsParser.toLookup(
                    OnlineLyricsParser.parseOne(exact.text)
                )
            // Only a definite "not here" falls through to the wider search.
            // A server error must not be turned into an answer.
            is Response.Missing -> Unit
            is Response.Error -> return@withContext LyricsLookup.Failed(exact.reason)
        }

        val search = get(
            Uri.parse("$baseUrl/api/search").buildUpon()
                .appendQueryParameter("track_name", title)
                .appendQueryParameter("artist_name", artist)
                .build()
                .toString()
        )

        when (search) {
            is Response.Body -> OnlineLyricsParser.toLookup(
                OnlineLyricsParser.chooseBest(
                    OnlineLyricsParser.parseMany(search.text),
                    durationMs,
                )
            )
            is Response.Missing -> LyricsLookup.NotFound
            is Response.Error -> LyricsLookup.Failed(search.reason)
        }
    }

    private sealed interface Response {
        data class Body(val text: String) : Response
        data object Missing : Response
        data class Error(val reason: String) : Response
    }

    private fun get(url: String): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // The database asks callers to identify themselves, which is a
                // reasonable thing to ask of anyone using a free service.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }

            when (val code = connection.responseCode) {
                in 200..299 -> Response.Body(
                    connection.inputStream.bufferedReader().use { it.readText() }
                )
                404 -> Response.Missing
                else -> Response.Error("Lyrics service returned $code")
            }
        } catch (e: Exception) {
            Response.Error(e.message ?: "Could not reach the lyrics service")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        val USER_AGENT = "Spindle/${BuildConfig.VERSION_NAME} (local music player)"
    }
}
