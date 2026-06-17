package com.example

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val thumbnailUrl: String,
    val filePath: String,
    val formatId: String,
    val status: DownloadStatus,
    val progressPercent: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val subtitleLang: String? = null,
    val sponsorblockAction: String = "none",
    val sponsorblockCategories: String = "", // comma-separated strings
    val convertToMp3: Boolean = false
)
