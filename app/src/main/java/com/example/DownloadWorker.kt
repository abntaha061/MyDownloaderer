package com.example

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun downloadRepository(): DownloadRepository
}

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return Result.failure()

        // Access Repository using structural Hilt EntryPoint
        val entryPoint = EntryPointAccessors.fromApplication(context, ServiceEntryPoint::class.java)
        val downloadRepository = entryPoint.downloadRepository()

        val download = downloadRepository.getById(downloadId)
        if (download != null && (download.status == DownloadStatus.QUEUED || download.status == DownloadStatus.RUNNING)) {
            // Wake up or restart the foreground task service
            val serviceIntent = Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START_DOWNLOAD
                putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Ignore any start restriction exceptions, WorkManager will try again
            }
        }

        return Result.success()
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
