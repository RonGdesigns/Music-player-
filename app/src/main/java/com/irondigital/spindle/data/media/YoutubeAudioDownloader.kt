package com.irondigital.spindle.data.media

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class YoutubeDownloadProgress(
    val percent: Float,
    val etaSeconds: Long,
)

data class DownloadedAudio(
    val files: List<File>,
    val tempDirectory: File,
)

sealed interface YoutubeDownloadResult {
    data class Success(val download: DownloadedAudio) : YoutubeDownloadResult
    data class Failed(val reason: String) : YoutubeDownloadResult
}

/**
 * Local YouTube audio downloader/converter used by the paste-link flow.
 *
 * yt-dlp downloads the best available audio stream into app-private cache.
 * FFmpegKit then converts each downloaded item to MP3. AudioImporter copies
 * the finished MP3s into MediaStore (Music/Spindle), keeping the app compatible
 * with Android scoped storage.
 */
class YoutubeAudioDownloader(private val context: Context) {

    @Volatile
    private var initialized = false

    suspend fun download(
        rawUrl: String,
        onProgress: (YoutubeDownloadProgress) -> Unit = {},
    ): YoutubeDownloadResult = withContext(Dispatchers.IO) {
        val url = validateYoutubeUrl(rawUrl)
            ?: return@withContext YoutubeDownloadResult.Failed(
                "Paste a valid youtube.com or youtu.be video/playlist link."
            )

        try {
            ensureInitialized()
        } catch (error: Throwable) {
            return@withContext YoutubeDownloadResult.Failed(
                "The converter could not start: ${error.message ?: "initialization failed"}"
            )
        }

        val tempDirectory = File(
            File(context.cacheDir, TEMP_ROOT),
            UUID.randomUUID().toString(),
        )
        val sourceDirectory = File(tempDirectory, "source")
        val convertedDirectory = File(tempDirectory, "mp3")

        if (!sourceDirectory.mkdirs() || !convertedDirectory.mkdirs()) {
            tempDirectory.deleteRecursively()
            return@withContext YoutubeDownloadResult.Failed(
                "Could not create temporary download storage."
            )
        }

        try {
            val request = YtDlpRequest(url)
                .setOutputTemplate(
                    File(sourceDirectory, "%(title).160B [%(id)s].%(ext)s").absolutePath
                )
                .addOption("--yes-playlist")
                .addOption("--ignore-errors")
                .addOption("--no-mtime")
                .addOption("--no-overwrites")
                .addOption("--trim-filenames", "160")
                .addOption("--format", "bestaudio/best")

            YtDlp.execute(request) { progress, eta, _ ->
                onProgress(
                    YoutubeDownloadProgress(
                        percent = progress.coerceIn(0f, 100f),
                        etaSeconds = eta.coerceAtLeast(0L),
                    )
                )
            }

            val sources = sourceDirectory
                .listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile && file.extension.lowercase() in AUDIO_EXTENSIONS
                }
                .sortedBy { it.name.lowercase() }

            if (sources.isEmpty()) {
                tempDirectory.deleteRecursively()
                return@withContext YoutubeDownloadResult.Failed(
                    "No audio was downloaded. The link may be private, unavailable, or unsupported."
                )
            }

            val converted = sources.mapNotNull { source ->
                val target = File(
                    convertedDirectory,
                    "${source.nameWithoutExtension}.mp3",
                )

                val session = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-y",
                        "-i", source.absolutePath,
                        "-vn",
                        "-map_metadata", "0",
                        "-codec:a", "libmp3lame",
                        "-q:a", "0",
                        target.absolutePath,
                    )
                )

                target.takeIf {
                    ReturnCode.isSuccess(session.returnCode) &&
                        it.isFile &&
                        it.length() > 0L
                }
            }

            if (converted.isEmpty()) {
                tempDirectory.deleteRecursively()
                YoutubeDownloadResult.Failed(
                    "The audio downloaded, but FFmpeg could not convert it to MP3."
                )
            } else {
                YoutubeDownloadResult.Success(
                    DownloadedAudio(
                        files = converted,
                        tempDirectory = tempDirectory,
                    )
                )
            }
        } catch (error: Throwable) {
            tempDirectory.deleteRecursively()
            YoutubeDownloadResult.Failed(cleanError(error))
        }
    }

    fun cleanUp(download: DownloadedAudio) {
        runCatching { download.tempDirectory.deleteRecursively() }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        YtDlp.init(context.applicationContext)
        initialized = true
    }

    private fun validateYoutubeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        val supportedHost = host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com")

        return value.takeIf { supportedHost }
    }

    private fun cleanError(error: Throwable): String {
        val message = error.message
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.lastOrNull()
            ?.removePrefix("ERROR:")
            ?.trim()
            ?.take(240)

        return message?.takeIf { it.isNotBlank() } ?: "The download failed."
    }

    private companion object {
        const val TEMP_ROOT = "youtube-imports"

        val AUDIO_EXTENSIONS = setOf(
            "aac",
            "flac",
            "m4a",
            "mp3",
            "oga",
            "ogg",
            "opus",
            "wav",
            "webm",
        )
    }
}
