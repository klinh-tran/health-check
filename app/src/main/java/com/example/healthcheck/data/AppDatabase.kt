package com.example.healthcheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Migration strategy: the schema JSON is exported to app/schemas/. When the entity changes,
 * bump [version] and add either `autoMigrations = [AutoMigration(from = 1, to = 2)]` here or a
 * manual Migration via addMigrations(). Destructive fallback is deliberately NOT enabled, so a
 * missing migration fails loudly during development instead of silently wiping personal data.
 */
@Database(entities = [DailyEntry::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyEntryDao(): DailyEntryDao

    /**
     * After deleting everything: fold the WAL back into the database and rebuild the file, so deleted
     * rows don't linger in free pages or the write-ahead log.
     */
    suspend fun compact() = withContext(Dispatchers.IO) {
        val db = openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        db.execSQL("VACUUM")
    }

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "health_check.db")
                .build()
    }
}
