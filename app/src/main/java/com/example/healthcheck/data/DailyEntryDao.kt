package com.example.healthcheck.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyEntryDao {
    @Query("SELECT * FROM daily_entry ORDER BY date ASC")
    fun observeAll(): Flow<List<DailyEntry>>

    @Query("SELECT * FROM daily_entry ORDER BY date ASC")
    suspend fun getAll(): List<DailyEntry>

    @Query("SELECT * FROM daily_entry WHERE date = :date")
    suspend fun get(date: LocalDate): DailyEntry?

    @Upsert
    suspend fun upsert(entry: DailyEntry)

    @Query("DELETE FROM daily_entry WHERE date = :date")
    suspend fun delete(date: LocalDate)

    @Query("DELETE FROM daily_entry")
    suspend fun deleteAll()

    @Query("DELETE FROM daily_entry WHERE isDemo = 1")
    suspend fun deleteDemo()

    /** Read-modify-write in one transaction so measurement and manual saves never clobber each other. */
    @Transaction
    suspend fun update(date: LocalDate, transform: (DailyEntry?) -> DailyEntry?) {
        val updated = transform(get(date))
        if (updated == null) delete(date) else upsert(updated)
    }
}
