package com.example.healthcheck.data

import com.example.healthcheck.core.model.DayRecord
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

data class MeasurementValues(
    val heartRateBpm: Double,
    /** Null when the run had too few clean beat pairs for HRV. */
    val hrvRmssdMs: Double?,
    val breathingRate: Double?,
    val signalQuality: String,
)

class EntryRepository(
    private val dao: DailyEntryDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Physically removes deleted data from storage (VACUUM); a no-op in tests. */
    private val compact: suspend () -> Unit = {},
    private val onCompactFailed: (Exception) -> Unit = { DebugLog.e("Data", "compact after delete failed", it) },
) {
    /** All days, oldest first. */
    val records: Flow<List<DayRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

    suspend fun allRecords(): List<DayRecord> = dao.getAll().map { it.toRecord() }

    suspend fun get(date: LocalDate): DayRecord? = dao.get(date)?.toRecord()

    /** Saves a valid measurement into that day's entry, keeping any manual values. */
    suspend fun saveMeasurement(date: LocalDate, m: MeasurementValues) = dao.update(date) { old ->
        realOrEmpty(old, date).copy(
            heartRateBpm = m.heartRateBpm,
            hrvRmssdMs = m.hrvRmssdMs,
            breathingRate = m.breathingRate,
            signalQuality = m.signalQuality,
            updatedAt = clock(),
            isDemo = false,
        )
    }

    /** Replaces the manual values for that day, keeping any measurement. */
    suspend fun saveManual(date: LocalDate, steps: Int?, sleepHours: Double?, bedtime: LocalTime?) =
        dao.update(date) { old ->
            val updated = realOrEmpty(old, date).copy(
                steps = steps,
                sleepHours = sleepHours,
                bedtime = bedtime,
                updatedAt = clock(),
                isDemo = false,
            )
            // Never keep a completely empty row.
            val empty = updated.heartRateBpm == null && steps == null && sleepHours == null && bedtime == null
            if (empty) null else updated
        }

    suspend fun delete(date: LocalDate) = dao.delete(date)

    suspend fun deleteAll() {
        dao.deleteAll()
        // The rows are already gone; a failed compaction must not be reported as a failed delete.
        try {
            compact()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCompactFailed(e)
        }
    }

    /** Inserts demo rows only on days that have no data yet; real entries are never overwritten. */
    suspend fun insertDemo(records: List<DayRecord>) {
        for (r in records) {
            dao.update(r.date) { old -> old ?: r.copy(isDemo = true, updatedAtEpochMs = clock()).toEntity() }
        }
    }

    suspend fun clearDemo() = dao.deleteDemo()

    /** Real data is built on; a demo row is discarded so fake values never mix with real ones. */
    private fun realOrEmpty(old: DailyEntry?, date: LocalDate) = old?.takeUnless { it.isDemo } ?: empty(date)

    private fun empty(date: LocalDate) = DailyEntry(
        date = date, heartRateBpm = null, hrvRmssdMs = null, breathingRate = null,
        signalQuality = null, steps = null, sleepHours = null, bedtime = null, updatedAt = clock(),
    )
}
