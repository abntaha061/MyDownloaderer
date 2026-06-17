package com.example

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
@TypeConverters(DownloadTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        // أضف هنا أي Migration عند تغيير المخطط مستقبلًا
        // For example:
        // val MIGRATION_1_2 = object : Migration(1, 2) {
        //     override fun migrate(db: SupportSQLiteDatabase) {
        //         // Add migration queries here when changing columns
        //     }
        // }
    }
}
