package com.irondigital.spindle.data.media

import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
    data object Cancelled : YoutubeDownloadResult
}

/**
 * Local YouTube audio downloader/converter used by the paste-link flow.
 *
 * yt-dlp and FFmpeg run entirely on-device. Downloads land in app-private
 * storage first; AudioImporter then copies the finished MP3s into MediaStore
 * (Music/Spindle), which keeps this compatible with scoped storage.
 */
class YoutubeAudioDownloader(private val context: Context) {

    @Volatile
    private var initialized = false

    @Volatile
    private var currentProcessId: String? = null

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
        ).apply { mkdirs() }

        if (!tempDirectory.exists()) {
            return@withContext YoutubeDownloadResult.Failed(
                "Could not create temporary download storage."
            )
        }

        val processId = "spindle-youtube-${UUID.randomUUID()}"
        currentProcessId = processId

        try {
            val request = YoutubeDLRequest(url)
                .addOption("--yes-playlist")
                .addOption("--ignore-errors")
                .addOption("--no-mtime")
                .addOption("--no-overwrites")
                .addOption("--newline")
                .addOption("--trim-filenames", 160)
                .addOption("--format", "bestaudio/best")
                .addOption("--extract-audio")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0")
                .addOption("--embed-metadata")
                .addOption(
                    "--output",
                    File(tempDirectory, "%(title).160B [%(id)s].%(ext)s").absolutePath,
                )

            YoutubeDL.execute(request, processId) { progress, eta, _ ->
                onProgress(
                    YoutubeDownloadProgress(
                        percent = progress.coerceIn(0f, 100f),
                        etaSeconds = eta.coerceAtLeast(0L),
                    )
                )
            }

            val files = tempDirectory
                .walkTopDown()
                .filter { file ->
                    file.isFile && file.extension.equals("mp3", ignoreCase = true)
                }
                .sortedBy { it.name.lowercase() }
                .toList()

            if (files.isEmpty()) {
                tempDirectory.deleteRecursively()
                YoutubeDownloadResult.Failed(
                    "No audio was downloaded. The link may be private, unavailable, or unsupported."
                )
            } else {
                YoutubeDownloadResult.Success(
                    DownloadedAudio(files = files, tempDirectory = tempDirectory)
                )
            }
        } catch (_: YoutubeDL.CanceledException) {
            tempDirectory.deleteRecursively()
            YoutubeDownloadResult.Cancelled
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            tempDirectory.deleteRecursively()
            YoutubeDownloadResult.Cancelled
        } catch (error: Throwable) {
            tempDirectory.deleteRecursively()
            YoutubeDownloadResult.Failed(cleanError(error))
        } finally {
            if (currentProcessId == processId) currentProcessId = null
        }
    }

    fun cancelCurrent(): Boolean {
        val processId = currentProcessId ?: return false
        return runCatching { YoutubeDL.destroyProcessById(processId) }.getOrDefault(false)
    }

    fun cleanUp(download: DownloadedAudio) {
        runCatching { download.tempDirectory.deleteRecursively() }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        YoutubeDL.init(context.applicationContext)
        FFmpeg.init(context.applicationContext)

        // The bundled extractor can age quickly as YouTube changes. Try to use
        // the newest stable yt-dlp, but do not make GitHub availability a hard
        // requirement: the bundled copy remains the fallback.
        runCatching {
            YoutubeDL.updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE,
            )
        }

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
    }
}
