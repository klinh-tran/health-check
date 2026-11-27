package com.example.healthcheck.data

import com.example.healthcheck.core.model.DayRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

private class FakeDao : DailyEntryDao {
    val rows = MutableStateFlow<Map<LocalDate, DailyEntry>>(emptyMap())
    override fun observeAll(): Flow<List<DailyEntry>> = rows.map { it.values.sortedBy(DailyEntry::date) }
    override suspend fun getAll() = rows.value.values.sortedBy(DailyEntry::date)
    override suspend fun get(date: LocalDate) = rows.value[date]
    override suspend fun upsert(entry: DailyEntry) { rows.value = rows.value + (entry.date to entry) }
    override suspend fun delete(date: LocalDate) { rows.value = rows.value - date }
    override suspend fun deleteAll() { rows.value = emptyMap() }
    override suspend fun deleteDemo() { rows.value = rows.value.filterValues { !it.isDemo } }
}

class EntryRepositoryTest {
    private val day = LocalDate.of(2026, 11, 26)
    private val m = MeasurementValues(61.0, 44.0, null, "Good")

    @Test fun measurementAndManualValuesMergeOnTheSameDay() = runBlocking {
        val repo = EntryRepository(FakeDao()) { 1L }
        repo.saveManual(day, 8000, 7.5, LocalTime.of(23, 0))
        repo.saveMeasurement(day, m)
        val r = repo.get(day)!!
        assertEquals(8000, r.steps)
        assertEquals(61.0, r.heartRateBpm!!, 0.0)
        // Saving manual values again replaces them but keeps the measurement
        repo.saveManual(day, null, 6.0, null)
        val r2 = repo.get(day)!!
        assertNull(r2.steps)
        assertNull(r2.bedtime)
        assertEquals(6.0, r2.sleepHours!!, 0.0)
        assertEquals(44.0, r2.hrvRmssdMs!!, 0.0)
    }

    @Test fun clearingAllManualValuesOfAManualOnlyDayRemovesTheRow() = runBlocking {
        val repo = EntryRepository(FakeDao())
        repo.saveManual(day, 5000, null, null)
        repo.saveManual(day, null, null, null)
        assertNull(repo.get(day))
    }

    @Test fun demoRowsNeverOverwriteOrMixWithRealData() = runBlocking {
        val repo = EntryRepository(FakeDao())
        repo.saveManual(day, 1234, null, null)
        repo.insertDemo(listOf(DayRecord(day, 60.0, 40.0, steps = 9999), DayRecord(day.minusDays(1), 60.0, 40.0, steps = 7000)))
        assertEquals(1234, repo.get(day)!!.steps)           // real day untouched
        assertTrue(repo.get(day.minusDays(1))!!.isDemo)

        // Typing real steps on a demo day discards the fake measurement
        repo.saveManual(day.minusDays(1), 4000, null, null)
        val r = repo.get(day.minusDays(1))!!
        assertFalse(r.isDemo)
        assertNull(r.heartRateBpm)

        repo.insertDemo(listOf(DayRecord(day.minusDays(2), 60.0, 40.0)))
        repo.clearDemo()
        assertNull(repo.get(day.minusDays(2)))
        assertEquals(2, repo.allRecords().size)
    }

    @Test fun deleteAllClearsRealAndDemoRowsThenCompacts() = runBlocking {
        val dao = FakeDao()
        var compacted = 0
        val repo = EntryRepository(dao, clock = { 1L }, compact = { compacted++ })
        repo.saveMeasurement(day, m)
        repo.saveManual(day.minusDays(1), 4000, 7.0, null)
        repo.insertDemo(listOf(DayRecord(date = day.minusDays(5), heartRateBpm = 60.0, hrvRmssdMs = 40.0)))
        assertEquals(3, repo.allRecords().size)
        repo.deleteAll()
        assertTrue(repo.allRecords().isEmpty())
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(1, compacted)
    }

    @Test fun deleteAllOnAnEmptyDatabaseIsFineAndAFailedCompactionIsNotAnError() = runBlocking {
        val failures = mutableListOf<Exception>()
        val repo = EntryRepository(FakeDao(), compact = { error("disk full") }, onCompactFailed = { failures += it })
        repo.deleteAll() // must not throw
        assertTrue(repo.allRecords().isEmpty())
        assertEquals("disk full", failures.single().message)
        repo.delete(day) // deleting a missing day is fine too
    }
}
