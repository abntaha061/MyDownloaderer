package com.example

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object DownloadSchedulerHelper {
    const val ACTION_TRIGGER_SCHEDULED_DOWNLOAD = "com.example.ACTION_TRIGGER_SCHEDULED_DOWNLOAD"
    const val EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleAlarm(context: Context, downloadId: Long, triggerTimeMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        
        val intent = Intent(context, DownloadSchedulerReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULED_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            downloadId.toInt(),
            intent,
            flags
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                    Log.d("DownloadSchedulerHelper", "Scheduled exact alarm for download $downloadId at $triggerTimeMs")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                    Log.d("DownloadSchedulerHelper", "Scheduled inexact alarm (no exact permission) for download $downloadId")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
                Log.d("DownloadSchedulerHelper", "Scheduled exact alarm (M+) for download $downloadId at $triggerTimeMs")
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
                Log.d("DownloadSchedulerHelper", "Scheduled exact alarm (pre-M) for download $downloadId at $triggerTimeMs")
            }
        } catch (e: Exception) {
            Log.e("DownloadSchedulerHelper", "Failed to schedule alarm for $downloadId", e)
        }
    }

    fun cancelAlarm(context: Context, downloadId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        
        val intent = Intent(context, DownloadSchedulerReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULED_DOWNLOAD
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            downloadId.toInt(),
            intent,
            flags
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("DownloadSchedulerHelper", "Canceled alarm for download $downloadId")
        }
    }
}
