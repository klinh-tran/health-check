package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class CsvExporterTest {
    private val zone = ZoneId.of("Europe/London")

    @Test fun formatsRowsOldestFirstWithDotDecimalsInAnyLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY) // uses ',' as decimal separator
        try {
            val updated = ZonedDateTime.of(2026, 11, 26, 8, 30, 0, 0, zone).toInstant().toEpochMilli()
            val records = listOf(
                DayRecord(LocalDate.of(2026, 11, 26), heartRateBpm = 61.24, hrvRmssdMs = 44.06, signalQuality = "Good",
                    steps = 8123, sleepHours = 7.5, bedtime = LocalTime.of(22, 45), updatedAtEpochMs = updated),
                DayRecord(LocalDate.of(2026, 11, 25), steps = 4000), // partial day: manual only
            )
            val lines = CsvExporter.export(records, zone).split("\r\n")
            assertEquals(
                "date,heart_rate_bpm,hrv_rmssd_ms,breathing_rate_per_min,signal_quality,steps,sleep_hours,bedtime,updated_at",
                lines[0],
            )
            assertEquals("2026-11-25,,,,,4000,,,", lines[1])
            assertEquals("2026-11-26,61.2,44.1,,Good,8123,7.5,22:45,2026-11-26T08:30:00", lines[2])
            assertEquals("", lines[3]) // trailing CRLF
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun emptyExportHasHeaderOnly() {
        assertEquals(CsvExporter.HEADER.joinToString(",") + "\r\n", CsvExporter.export(emptyList(), zone))
    }

    @Test fun leavesOutDemoDaysAndKeepsTheSameColumns() {
        val real = DayRecord(LocalDate.of(2026, 11, 25), steps = 4000)
        val demo = DayRecord(LocalDate.of(2026, 11, 26), heartRateBpm = 60.0, hrvRmssdMs = 40.0, steps = 7000, isDemo = true)
        val csv = CsvExporter.export(listOf(demo, real), zone)
        assertEquals(CsvExporter.HEADER.joinToString(",") + "\r\n2026-11-25,,,,,4000,,,\r\n", csv)
        // Only demo days: header only, the same as an empty export.
        assertEquals(CsvExporter.export(emptyList(), zone), CsvExporter.export(listOf(demo), zone))
        // The file still imports, and nothing fake comes back.
        val imported = (CsvImporter.parse(csv, LocalDate.of(2026, 11, 27), zone) as CsvImporter.Result.Success).records
        assertEquals(listOf(real), imported)
    }

    @Test fun anOlderExportThatStillContainsDemoDaysImports() {
        // Files exported before this change could include demo days. Same columns, so they still import (as real data).
        val older = CsvExporter.HEADER.joinToString(",") + "\r\n" +
            "2026-11-25,,,,,4000,,,\r\n" +
            "2026-11-26,60.0,40.0,,Good,7000,7.0,22:30,\r\n"
        val imported = (CsvImporter.parse(older, LocalDate.of(2026, 11, 27), zone) as CsvImporter.Result.Success).records
        assertEquals(2, imported.size)
    }

    @Test fun escapesSpecialCharacters() {
        assertEquals("plain", CsvExporter.escape("plain"))
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvExporter.escape("two\nlines"))
    }
}
