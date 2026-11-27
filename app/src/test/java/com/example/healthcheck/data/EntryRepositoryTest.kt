package com.example.healthcheck.data

import com.example.healthcheck.core.csv.CsvExporter
import com.example.healthcheck.core.csv.CsvImporter
import com.example.healthcheck.core.csv.DuplicateMode
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
    /** Simulates the database failing during an import write (e.g. disk full). */
    var failBulkWrites = false
    override fun observeAll(): Flow<List<DailyEntry>> = rows.map { it.values.sortedBy(DailyEntry::date) }
    override suspend fun getAll() = rows.value.values.sortedBy(DailyEntry::date)
    override suspend fun get(date: LocalDate) = rows.value[date]
    override suspend fun upsert(entry: DailyEntry) { rows.value = rows.value + (entry.date to entry) }
    override suspend fun upsertAll(entries: List<DailyEntry>) {
        // Like Room inside a transaction: the whole batch is applied, or (on failure) none of it.
        if (failBulkWrites) error("disk full")
        rows.value = rows.value + entries.associateBy { it.date }
    }
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

    // ---- CSV import ----

    private fun imported(date: LocalDate, steps: Int) = DayRecord(date, steps = steps, updatedAtEpochMs = 5L)

    @Test fun importKeepExistingSkipsDaysThatAlreadyHaveRealData() = runBlocking {
        val repo = EntryRepository(FakeDao()) { 1L }
        repo.saveManual(day, 1234, null, null)
        val file = listOf(imported(day, 9999), imported(day.minusDays(1), 4000))

        val preview = repo.previewImport(file)
        assertEquals(1, preview.skipped)
        assertNull(repo.get(day.minusDays(1))) // a preview writes nothing

        val plan = repo.importRecords(file, DuplicateMode.KEEP_EXISTING)
        assertEquals(1, plan.imported)
        assertEquals(1, plan.skipped)
        assertEquals(1234, repo.get(day)!!.steps)                // existing day kept
        assertEquals(4000, repo.get(day.minusDays(1))!!.steps)   // new day added
    }

    @Test fun importReplaceExistingOverwritesWholeDays() = runBlocking {
        val repo = EntryRepository(FakeDao()) { 1L }
        repo.saveMeasurement(day, m)
        repo.saveManual(day, 1234, 7.0, null)
        val plan = repo.importRecords(listOf(imported(day, 9999), imported(day.minusDays(1), 4000)), DuplicateMode.REPLACE_EXISTING)
        assertEquals(2, plan.imported)
        assertEquals(0, plan.skipped)
        assertEquals(1, plan.replaced)
        val r = repo.get(day)!!
        assertEquals(9999, r.steps)
        assertNull(r.heartRateBpm) // the file's version of the day replaces the stored one completely
        assertNull(r.sleepHours)
        assertEquals(5L, r.updatedAtEpochMs) // the file's timestamp is restored, not "now"
    }

    @Test fun importedRowsAreRealDataAndReplaceDemoRowsInEitherMode() = runBlocking {
        val repo = EntryRepository(FakeDao())
        repo.insertDemo(listOf(DayRecord(day, 60.0, 40.0, steps = 7000)))
        val plan = repo.importRecords(listOf(imported(day, 4000).copy(isDemo = true)), DuplicateMode.KEEP_EXISTING)
        assertEquals(1, plan.replacedDemo)
        assertEquals(0, plan.skipped)
        val r = repo.get(day)!!
        assertFalse(r.isDemo)
        assertEquals(4000, r.steps)
        assertNull(r.heartRateBpm) // no fake values mixed in

        // "Clear demo data" leaves imported days alone; "Delete all data" removes them.
        repo.insertDemo(listOf(DayRecord(day.minusDays(3), 60.0, 40.0)))
        repo.clearDemo()
        assertEquals(listOf(day), repo.allRecords().map { it.date })
        repo.deleteAll()
        assertTrue(repo.allRecords().isEmpty())
    }

    @Test fun aFailedImportWriteLeavesExistingDataUntouched() = runBlocking {
        val dao = FakeDao()
        val repo = EntryRepository(dao) { 1L }
        repo.saveManual(day, 1234, 6.5, null)
        val before = dao.rows.value
        dao.failBulkWrites = true
        val failed = runCatching {
            repo.importRecords(listOf(imported(day, 9999), imported(day.minusDays(1), 4000)), DuplicateMode.REPLACE_EXISTING)
        }
        assertTrue(failed.isFailure)
        assertEquals(before, dao.rows.value)
    }

    @Test fun aRejectedFileNeverReachesTheDatabase() = runBlocking {
        val dao = FakeDao()
        val repo = EntryRepository(dao) { 1L }
        repo.saveManual(day, 1234, null, null)
        val before = dao.rows.value
        // Row 2 is fine, row 3 is not: the whole file is rejected before any write.
        val csv = CsvExporter.HEADER.joinToString(",") + "\r\n" +
            "2026-11-20,,,,,4000,,,\r\n" +
            "2026-11-21,,,,,999999,,,\r\n"
        val result = CsvImporter.parse(csv, day, java.time.ZoneOffset.UTC)
        assertTrue(result is CsvImporter.Result.Failure)
        assertEquals(before, dao.rows.value)
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
