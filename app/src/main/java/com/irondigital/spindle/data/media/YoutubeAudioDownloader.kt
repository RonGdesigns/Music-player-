package com.irondigital.spindle.data.media

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
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
 * The maintained Android wrapper embeds a full yt-dlp Python runtime, but its
 * Java request parser intentionally supports only a small CLI subset. Modern
 * YouTube 403 handling depends on extractor args such as player_client, so this
 * class talks to the embedded yt_dlp Python API directly for YouTube downloads.
 *
 * Downloads land in app-private cache. FFmpegKit then converts each result to
 * MP3, and AudioImporter copies it into MediaStore (Music/Spindle).
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
            onProgress(YoutubeDownloadProgress(percent = 1f, etaSeconds = 0L))

            val attempt = downloadWithFallbacks(
                url = url,
                sourceDirectory = sourceDirectory,
            )

            if (attempt.files.isEmpty()) {
                tempDirectory.deleteRecursively()
                return@withContext YoutubeDownloadResult.Failed(
                    userFacingDownloadError(attempt.lastError)
                )
            }

            onProgress(YoutubeDownloadProgress(percent = 92f, etaSeconds = 0L))

            val converted = attempt.files.mapNotNull { source ->
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
                onProgress(YoutubeDownloadProgress(percent = 100f, etaSeconds = 0L))
                YoutubeDownloadResult.Success(
                    DownloadedAudio(
                        files = converted,
                        tempDirectory = tempDirectory,
                    )
                )
            }
        } catch (error: Throwable) {
            tempDirectory.deleteRecursively()
            YoutubeDownloadResult.Failed(userFacingDownloadError(error))
        }
    }

    fun cleanUp(download: DownloadedAudio) {
        runCatching { download.tempDirectory.deleteRecursively() }
    }

    /**
     * Try routes which avoid the current YouTube GVS 403/PO-token trap.
     *
     * web_safari is first because yt-dlp currently exposes HLS formats for that
     * client which do not require a GVS PO token. android_vr and web_embedded
     * are useful fallbacks for videos where their no-token playback path is
     * available. The final default attempt covers ordinary cases and uses IPv4,
     * which also avoids a known class of transient YouTube 403s.
     */
    private fun downloadWithFallbacks(
        url: String,
        sourceDirectory: File,
    ): DownloadAttempt {
        val strategies = listOf(
            DownloadStrategy(
                name = "web_safari_hls",
                playerClient = "web_safari",
                format = "bestaudio[protocol^=m3u8]/best[protocol^=m3u8]/bestaudio/best",
                preferNativeHls = true,
            ),
            DownloadStrategy(
                name = "android_vr",
                playerClient = "android_vr",
                format = "bestaudio/best",
            ),
            DownloadStrategy(
                name = "web_embedded",
                playerClient = "web_embedded",
                format = "bestaudio/best",
            ),
            DownloadStrategy(
                name = "default_ipv4",
                playerClient = null,
                format = "bestaudio/best",
            ),
        )

        var lastError: Throwable? = null

        for (strategy in strategies) {
            sourceDirectory.listFiles()?.forEach { it.deleteRecursively() }

            try {
                executePythonDownload(
                    url = url,
                    outputTemplate = File(
                        sourceDirectory,
                        "%(title).160B [%(id)s].%(ext)s",
                    ).absolutePath,
                    strategy = strategy,
                )
            } catch (error: Throwable) {
                lastError = error
            }

            val files = downloadedAudioFiles(sourceDirectory)
            if (files.isNotEmpty()) {
                return DownloadAttempt(files = files, lastError = null)
            }
        }

        return DownloadAttempt(files = emptyList(), lastError = lastError)
    }

    /**
     * Direct Chaquopy call equivalent to:
     *
     *   with yt_dlp.YoutubeDL(opts) as ydl:
     *       ydl.download([url])
     *
     * This is necessary because the wrapper's CLI parser currently drops
     * --extractor-args and --force-ipv4.
     */
    private fun executePythonDownload(
        url: String,
        outputTemplate: String,
        strategy: DownloadStrategy,
    ) {
        val py = Python.getInstance()
        val builtins = py.builtins
        val opts = newPythonDict(py)

        put(opts, "outtmpl", outputTemplate)
        put(opts, "format", strategy.format)
        put(opts, "ignoreerrors", false)
        put(opts, "noplaylist", false)
        put(opts, "force_ipv4", true)
        put(opts, "retries", 3)
        put(opts, "fragment_retries", 3)
        put(opts, "trim_file_name", 160)
        put(opts, "quiet", true)
        put(opts, "no_warnings", true)

        if (strategy.preferNativeHls) {
            put(opts, "hls_prefer_native", true)
        }

        strategy.playerClient?.let { client ->
            val youtubeArgs = newPythonDict(py)
            val clients = builtins.callAttr("list", arrayOf(client))
            put(youtubeArgs, "player_client", clients)

            val extractorArgs = newPythonDict(py)
            put(extractorArgs, "youtube", youtubeArgs)
            put(opts, "extractor_args", extractorArgs)
        }

        val ytDlp = py.getModule("yt_dlp")
        val ydl = ytDlp.callAttr("YoutubeDL", opts)
        val urls = builtins.callAttr("list", arrayOf(url))

        try {
            ydl.callAttr("__enter__")
            ydl.callAttr("download", urls)
        } finally {
            runCatching {
                ydl.callAttr("__exit__", null, null, null)
            }
        }
    }

    private fun downloadedAudioFiles(directory: File): List<File> =
        directory
            .walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.lowercase() in AUDIO_EXTENSIONS &&
                    file.length() > 0L &&
                    !file.name.endsWith(".part", ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
            .toList()

    private fun newPythonDict(py: Python): PyObject =
        py.builtins.callAttr("dict")

    private fun put(dict: PyObject, key: String, value: Any) {
        dict.asMap()[PyObject.fromJava(key)] =
            if (value is PyObject) value else PyObject.fromJava(value)
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

    private fun userFacingDownloadError(error: Throwable?): String {
        val cleaned = cleanError(error)
        val lower = cleaned.lowercase()

        return when {
            "403" in lower || "forbidden" in lower ->
                "YouTube rejected the media request (403). Spindle tried multiple YouTube clients. " +
                    "Turn off any VPN/proxy and try Wi-Fi or mobile data once. If this video requires " +
                    "your account, cookie sign-in support will be needed."

            "sign in" in lower || "login" in lower || "private" in lower ->
                "This video needs a signed-in YouTube session. Spindle does not have your YouTube " +
                    "cookies yet, so private or account-restricted videos cannot be downloaded."

            else -> cleaned
        }
    }

    private fun cleanError(error: Throwable?): String {
        val message = error?.message
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.lastOrNull()
            ?.removePrefix("ERROR:")
            ?.trim()
            ?.take(300)

        return message?.takeIf { it.isNotBlank() } ?: "The download failed."
    }

    private data class DownloadStrategy(
        val name: String,
        val playerClient: String?,
        val format: String,
        val preferNativeHls: Boolean = false,
    )

    private data class DownloadAttempt(
        val files: List<File>,
        val lastError: Throwable?,
    )

    private companion object {
        const val TEMP_ROOT = "youtube-imports"

        val AUDIO_EXTENSIONS = setOf(
            "aac",
            "flac",
            "m4a",
            "mp3",
            "mp4",
            "oga",
            "ogg",
            "opus",
            "ts",
            "wav",
            "webm",
        )
    }
}
