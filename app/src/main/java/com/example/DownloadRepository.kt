package com.example

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllAsFlow()

    suspend fun insert(download: DownloadEntity): Long {
        return downloadDao.insert(download)
    }

    suspend fun update(download: DownloadEntity) {
        downloadDao.update(download)
    }

    suspend fun getById(id: Long): DownloadEntity? {
        return downloadDao.getById(id)
    }

    suspend fun getActiveDownloads(): List<DownloadEntity> {
        return downloadDao.getActiveDownloads()
    }

    suspend fun deleteById(id: Long) {
        downloadDao.deleteById(id)
    }
}
