package com.example

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface to bridge python's hook dictionary back into Kotlin.
 * Chaquopy automatically implements this at runtime when an instance is passed to Python.
 */
interface DownloadProgressListener {
    fun onProgress(status: String, downloadedBytes: Long, totalBytes: Long, speed: Double, eta: Long)
}

interface PyLogger {
    fun log(level: String, message: String)
}

data class DownloadProgress(
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speed: Double,
    val eta: Long
)

data class VideoInfo(
    val title: String,
    val duration: Int,
    val thumbnailUrl: String,
    val formats: List<FormatInfo>,
    val subtitles: List<SubtitleInfo>,
    val isPlaylist: Boolean = false,
    val playlistEntries: List<PlaylistEntry> = emptyList()
)

data class PlaylistEntry(
    val title: String,
    val url: String,
    val duration: Int,
    val thumbnailUrl: String
)

data class FormatInfo(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val filesizeApprox: Long,
    val acodec: String,
    val vcodec: String
)

data class SubtitleInfo(
    val code: String,
    val name: String,
    val type: String // "manual" or "auto"
)

/**
 * YtdlpEngine provides modern, type-safe API to interact with Python-based yt-dlp.
 * Highly robust error catching, clean Hilt Injection, and complete Dispatchers isolation.
 */
@Singleton
class YtdlpEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Extracts full video formatting, download and subtitle information.
     * Running strictly on withContext(Dispatchers.IO) since Python GIL locks the calling thread.
     */
    suspend fun extractInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) {
                return@withContext Result.failure(IllegalStateException("لم يتم تهيئة محرك البايثون"))
            }

            val py = Python.getInstance()
            val helper = py.getModule("ytdlp_helper")
            val pyLogger = object : PyLogger {
                override fun log(level: String, message: String) {
                    TerminalLogManager.log(level, message)
                }
            }
            val cookiesFile = File(context.filesDir, "cookies.txt")
            val cookiesPath = if (cookiesFile.exists()) cookiesFile.absolutePath else null

            val pyResult = helper.callAttr("extract_info", url, pyLogger, cookiesPath)

            val error = pyResult.item("error")?.toString()
            if (!error.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("خطأ من yt-dlp: $error"))
            }

            val typeStr = pyResult.item("_type")?.toString()
            if (typeStr == "playlist") {
                val title = pyResult.item("title")?.toString() ?: "قائمة تشغيل غير معروفة"
                val entriesPyList = pyResult.item("entries")?.asList() ?: emptyList()
                val entries = entriesPyList.map { entryObj ->
                    PlaylistEntry(
                        title = entryObj.item("title")?.toString() ?: "فيديو غير معروف",
                        url = entryObj.item("url")?.toString() ?: "",
                        duration = entryObj.item("duration")?.toInt() ?: 0,
                        thumbnailUrl = entryObj.item("thumbnail")?.toString() ?: ""
                    )
                }
                return@withContext Result.success(
                    VideoInfo(
                        title = title,
                        duration = 0,
                        thumbnailUrl = entries.firstOrNull()?.thumbnailUrl ?: "",
                        formats = emptyList(),
                        subtitles = emptyList(),
                        isPlaylist = true,
                        playlistEntries = entries
                    )
                )
            }

            val title = pyResult.item("title")?.toString() ?: "عنوان غير معروف"
            val duration = pyResult.item("duration")?.toInt() ?: 0
            val thumbnailUrl = pyResult.item("thumbnail")?.toString() ?: ""
            val formatsPyList = pyResult.item("formats")?.asList() ?: emptyList()
            val subtitlesPyList = pyResult.item("subtitles")?.asList() ?: emptyList()

            val formats = formatsPyList.map { formatObj ->
                FormatInfo(
                    formatId = formatObj.item("format_id")?.toString() ?: "",
                    ext = formatObj.item("ext")?.toString() ?: "",
                    resolution = formatObj.item("resolution")?.toString() ?: "",
                    filesizeApprox = formatObj.item("filesize_approx")?.toLong() ?: 0L,
                    acodec = formatObj.item("acodec")?.toString() ?: "",
                    vcodec = formatObj.item("vcodec")?.toString() ?: ""
                )
            }

            val subtitles = subtitlesPyList.map { subObj ->
                SubtitleInfo(
                    code = subObj.item("code")?.toString() ?: "",
                    name = subObj.item("name")?.toString() ?: "",
                    type = subObj.item("type")?.toString() ?: ""
                )
            }

            Result.success(VideoInfo(title, duration, thumbnailUrl, formats, subtitles))
        } catch (e: Exception) {
            Result.failure(Exception("حدث خطأ أثناء استخراج معلومات الفيديو: ${e.message}", e))
        }
    }

    /**
     * Triggers actual media stream download using the specified format, custom subtitles & SponsorBlock segment rules.
     * Runs strictly on withContext(Dispatchers.IO) to prevent UI blockages.
     */
    suspend fun startDownload(
        url: String,
        formatId: String,
        outputPath: String,
        subtitleLang: String? = null,
        sponsorblockAction: String = "none",
        sponsorblockCategories: Set<String> = emptySet(),
        onProgress: (DownloadProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) {
                return@withContext Result.failure(IllegalStateException("لم يتم تهيئة محرك البايثون"))
            }

            val py = Python.getInstance()
            val helper = py.getModule("ytdlp_helper")

            // Correct tactic for W^X constraints: we check if ffmpeg (named libffmpeg.so)
            // is extracted to nativeLibraryDir by Android Package Manager during installation
            val ffmpegFile = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            val ffmpegLocation = if (ffmpegFile.exists()) {
                ffmpegFile.absolutePath
            } else {
                null // Passes None to Python, yt-dlp will proceed without merging if not needed
            }

            val progressListener = object : DownloadProgressListener {
                override fun onProgress(
                    status: String,
                    downloadedBytes: Long,
                    totalBytes: Long,
                    speed: Double,
                    eta: Long
                ) {
                    onProgress(
                        DownloadProgress(
                            status = status,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            speed = speed,
                            eta = eta
                        )
                    )
                }
            }

            val pyLogger = object : PyLogger {
                override fun log(level: String, message: String) {
                    TerminalLogManager.log(level, message)
                }
            }

            val cookiesFile = File(context.filesDir, "cookies.txt")
            val cookiesPath = if (cookiesFile.exists()) cookiesFile.absolutePath else null

            val pyResult = helper.callAttr(
                "download_video",
                url,
                formatId,
                outputPath,
                ffmpegLocation,
                subtitleLang,
                sponsorblockAction,
                sponsorblockCategories.toList(),
                progressListener,
                pyLogger,
                cookiesPath
            )

            val error = pyResult.item("error")?.toString()
            if (!error.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("توقف التحميل بسبب خطأ: $error"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("خطأ غير متوقع أثناء عملية التحميل: ${e.message}", e))
        }
    }

    /**
     * Reads the current bundled version of yt-dlp from the python environment
     */
    fun getYtdlpVersion(): String {
        return try {
            if (!Python.isStarted()) return "غير مفعل"
            val py = Python.getInstance()
            val versionModule = py.getModule("yt_dlp.version")
            versionModule.get("__version__")?.toString() ?: "غير معروف"
        } catch (e: Exception) {
            "مجهول"
        }
    }
}

private fun PyObject.item(key: String): PyObject? = this.callAttr("get", key)
