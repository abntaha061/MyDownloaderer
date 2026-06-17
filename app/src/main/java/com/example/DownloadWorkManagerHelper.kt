package com.example

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object DownloadWorkManagerHelper {
    fun scheduleWatchdog(context: Context, downloadId: Long) {
        val data = Data.Builder()
            .putLong(DownloadWorker.EXTRA_DOWNLOAD_ID, downloadId)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
