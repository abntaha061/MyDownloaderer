package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadSchedulerReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadRepository: DownloadRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("DownloadScheduler", "Device rebooted. Rescheduling all scheduled downloads...")
            rescheduleAllAlarms(context)
        } else if (intent.action == DownloadSchedulerHelper.ACTION_TRIGGER_SCHEDULED_DOWNLOAD) {
            val downloadId = intent.getLongExtra(DownloadSchedulerHelper.EXTRA_DOWNLOAD_ID, -1L)
            Log.d("DownloadScheduler", "Alarm triggered for download ID: $downloadId")
            if (downloadId != -1L) {
                triggerDownload(context, downloadId)
            }
        }
    }

    private fun rescheduleAllAlarms(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduledDownloads = downloadRepository.getAllOnce().filter { 
                    it.status == DownloadStatus.SCHEDULED && it.scheduledAt != null && it.scheduledAt > System.currentTimeMillis()
                }
                scheduledDownloads.forEach { download ->
                    DownloadSchedulerHelper.scheduleAlarm(context, download.id, download.scheduledAt!!)
                }
                Log.d("DownloadScheduler", "Rescheduled ${scheduledDownloads.size} future downloads successfully.")
            } catch (e: Exception) {
                Log.e("DownloadScheduler", "Failed to reschedule on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun triggerDownload(context: Context, downloadId: Long) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entity = downloadRepository.getById(downloadId)
                if (entity != null && entity.status == DownloadStatus.SCHEDULED) {
                    val updated = entity.copy(
                        status = DownloadStatus.QUEUED,
                        errorMessage = null
                    )
                    downloadRepository.update(updated)

                    val serviceIntent = Intent(context, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("DownloadScheduler", "Successfully launched DownloadService for $downloadId")
                }
            } catch (e: Exception) {
                Log.e("DownloadScheduler", "Failed to start scheduled download $downloadId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
