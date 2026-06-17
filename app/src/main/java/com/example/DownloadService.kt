package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var ytdlpEngine: YtdlpEngine

    @Inject
    lateinit var downloadRepository: DownloadRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val queueMutex = Mutex()

    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val CHANNEL_NAME = "قنوات التحميل"
        const val NOTIFICATION_ID = 9999
        
        const val ACTION_START_DOWNLOAD = "com.example.action.START_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.action.RESUME_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        // Start foreground immediately to meet Android requirements and avoid background start crashes
        startForeground(NOTIFICATION_ID, createInitialNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getLongExtra(EXTRA_DOWNLOAD_ID, -1L) ?: -1L
        val action = intent?.action

        if (action == ACTION_START_DOWNLOAD || action == ACTION_RESUME_DOWNLOAD) {
            if (downloadId != -1L) {
                serviceScope.launch {
                    processQueue()
                }
            }
        } else {
            // Check queue general trigger
            serviceScope.launch {
                processQueue()
            }
        }

        return START_STICKY
    }

    private suspend fun processQueue() = queueMutex.withLock {
        val activeCount = activeJobs.size
        if (activeCount >= 2) {
            Log.d("DownloadService", "Queue is full (active downloads: $activeCount)")
            return@withLock
        }

        val activeFromDb = downloadRepository.getActiveDownloads()
        val runningIds = activeJobs.keys

        // Reset stale RUNNING downloads to QUEUED
        activeFromDb.forEach { entity ->
            if (entity.status == DownloadStatus.RUNNING && !runningIds.contains(entity.id)) {
                downloadRepository.update(entity.copy(status = DownloadStatus.QUEUED))
            }
        }

        // Fetch refreshed status queued list
        val refreshedActiveFromDb = downloadRepository.getActiveDownloads()
        val queuedItems = refreshedActiveFromDb.filter { it.status == DownloadStatus.QUEUED }
        val slotsAvailable = 2 - activeJobs.size

        if (slotsAvailable > 0 && queuedItems.isNotEmpty()) {
            val itemsToStart = queuedItems.take(slotsAvailable)
            itemsToStart.forEach { download ->
                startDownloadJob(download)
            }
        }

        if (activeJobs.isEmpty()) {
            // Shut service foreground down cleanly when idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startDownloadJob(download: DownloadEntity) {
        val id = download.id
        val job = serviceScope.launch {
            try {
                downloadRepository.update(
                    download.copy(
                        status = DownloadStatus.RUNNING,
                        errorMessage = null
                    )
                )

                // Schedule watchdog fallback to trigger if killed
                DownloadWorkManagerHelper.scheduleWatchdog(applicationContext, id)

                var lastUpdatedTime = 0L

                val result = ytdlpEngine.startDownload(
                    url = download.url,
                    formatId = download.formatId,
                    outputPath = download.filePath,
                    subtitleLang = download.subtitleLang,
                    sponsorblockAction = download.sponsorblockAction,
                    sponsorblockCategories = download.sponsorblockCategories
                        .split(",")
                        .filter { it.isNotEmpty() }
                        .toSet()
                ) { progress ->
                    val currentTime = System.currentTimeMillis()
                    // Throttle: Max once per second to prevent database overhead and save battery life
                    if (currentTime - lastUpdatedTime >= 1000L || progress.status == "finished") {
                        lastUpdatedTime = currentTime
                        
                        val speedStr = if (progress.speed > 0) {
                            formatSpeed(progress.speed)
                        } else {
                            ""
                        }

                        val progressPct = if (progress.totalBytes > 0) {
                            (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()) * 100f
                        } else {
                            0f
                        }

                        // Notification update
                        updateNotification(
                            id = id.toInt(),
                            title = download.title,
                            progressPercent = progressPct,
                            speedStr = speedStr,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes
                        )

                        // Room update 
                        serviceScope.launch {
                            val currentDbEntity = downloadRepository.getById(id)
                            if (currentDbEntity != null) {
                                downloadRepository.update(
                                    currentDbEntity.copy(
                                        progressPercent = progressPct,
                                        downloadedBytes = progress.downloadedBytes,
                                        totalBytes = progress.totalBytes,
                                        status = DownloadStatus.RUNNING
                                    )
                                )
                            }
                        }
                    }
                }

                result.onSuccess {
                    val currentDbEntity = downloadRepository.getById(id)
                    if (currentDbEntity != null) {
                        downloadRepository.update(
                            currentDbEntity.copy(
                                status = DownloadStatus.COMPLETED,
                                progressPercent = 100f,
                                errorMessage = null
                            )
                        )
                    }
                    cancelNotification(id.toInt())
                }.onFailure { ex ->
                    val errorMsg = ex.message ?: "حدث خطأ أثناء تحميل الملف"
                    val currentDbEntity = downloadRepository.getById(id)
                    if (currentDbEntity != null) {
                        downloadRepository.update(
                            currentDbEntity.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = errorMsg
                            )
                        )
                    }
                    showErrorNotification(id.toInt(), download.title, errorMsg)
                }

            } catch (e: Exception) {
                val errorMsg = e.message ?: "خطأ غير معروف"
                val currentDbEntity = downloadRepository.getById(id)
                if (currentDbEntity != null) {
                    downloadRepository.update(
                        currentDbEntity.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = errorMsg
                        )
                    )
                }
            } finally {
                activeJobs.remove(id)
                processQueue()
            }
        }

        activeJobs[id] = job
    }

    private fun createInitialNotification(): Notification {
        val title = "PureDownload"
        val text = "جاري إدارة قائمة التحميل بالخلفية..."

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ShareReceiverActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        id: Int,
        title: String,
        progressPercent: Float,
        speedStr: String,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val progressInt = progressPercent.toInt()
        val text = if (speedStr.isNotEmpty()) {
            "التقدم: $progressInt% | السرعة: $speedStr | ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}"
        } else {
            "التقدم: $progressInt% | ${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressInt, false)
            .setOngoing(true)
            .setSilent(true) 
            .build()

        notificationManager.notify(id, notification)
    }

    private fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    private fun showErrorNotification(id: Int, title: String, errorMsg: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("فشل تحميل: $title")
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .build()

        notificationManager.notify(id, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "قناة مخصصة لعرض تقدم عمليات التحميل"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(speedBytesPerSec: Double): String {
        val speedKBs = speedBytesPerSec / 1024.0
        val speedMBs = speedKBs / 1024.0
        return when {
            speedMBs >= 1.0 -> String.format("%.2f MB/s", speedMBs)
            speedKBs >= 1.0 -> String.format("%.1f KB/s", speedKBs)
            else -> String.format("%.0f B/s", speedBytesPerSec)
        }
    }

    private fun formatSize(bytes: Long): String {
        val kbs = bytes / 1024.0
        val mbs = kbs / 1024.0
        return when {
            mbs >= 1.0 -> String.format("%.1f MB", mbs)
            kbs >= 1.0 -> String.format("%.1f KB", kbs)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
