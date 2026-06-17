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
    val subtitles: List<SubtitleInfo>
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
            val pyResult = helper.callAttr("extract_info", url)

            val error = pyResult.get("error")?.toString()
            if (!error.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("خطأ من yt-dlp: $error"))
            }

            val title = pyResult.get("title")?.toString() ?: "عنوان غير معروف"
            val duration = pyResult.get("duration")?.toInt() ?: 0
            val thumbnailUrl = pyResult.get("thumbnail")?.toString() ?: ""
            val formatsPyList = pyResult.get("formats")?.asList() ?: emptyList()
            val subtitlesPyList = pyResult.get("subtitles")?.asList() ?: emptyList()

            val formats = formatsPyList.map { formatObj ->
                FormatInfo(
                    formatId = formatObj.get("format_id")?.toString() ?: "",
                    ext = formatObj.get("ext")?.toString() ?: "",
                    resolution = formatObj.get("resolution")?.toString() ?: "",
                    filesizeApprox = formatObj.get("filesize_approx")?.toLong() ?: 0L,
                    acodec = formatObj.get("acodec")?.toString() ?: "",
                    vcodec = formatObj.get("vcodec")?.toString() ?: ""
                )
            }

            val subtitles = subtitlesPyList.map { subObj ->
                SubtitleInfo(
                    code = subObj.get("code")?.toString() ?: "",
                    name = subObj.get("name")?.toString() ?: "",
                    type = subObj.get("type")?.toString() ?: ""
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

            val pyResult = helper.callAttr(
                "download_video",
                url,
                formatId,
                outputPath,
                ffmpegLocation,
                subtitleLang,
                sponsorblockAction,
                sponsorblockCategories.toList(),
                progressListener
            )

            val error = pyResult.get("error")?.toString()
            if (!error.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("توقف التحميل بسبب خطأ: $error"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("خطأ غير متوقع أثناء عملية التحميل: ${e.message}", e))
        }
    }
}
