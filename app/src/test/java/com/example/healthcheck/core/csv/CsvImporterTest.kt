package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class CsvImporterTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 27)
    private val header = CsvExporter.HEADER.joinToString(",")

    private fun ok(csv: String): List<DayRecord> = when (val r = CsvImporter.parse(csv, today, zone)) {
        is CsvImporter.Result.Success -> r.records
        is CsvImporter.Result.Failure -> { fail("expected success, got: ${r.message}"); error("") }
    }

    private fun failure(csv: String): String = failure(csv.toByteArray(Charsets.UTF_8))

    private fun failure(bytes: ByteArray): String = when (val r = CsvImporter.parse(bytes, today, zone)) {
        is CsvImporter.Result.Failure -> r.message
        is CsvImporter.Result.Success -> { fail("expected failure, got ${r.records.size} records"); error("") }
    }

    private fun file(vararg rows: String) = (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    // ---- Round trip ----

    @Test fun roundTripRestoresEveryExportedFieldExactly() {
        val records = listOf(
            DayRecord(LocalDate.of(2026, 11, 26), heartRateBpm = 61.2, hrvRmssdMs = 44.1, breathingRate = 14.3,
                signalQuality = "Good", steps = 8123, sleepHours = 7.5, bedtime = LocalTime.of(22, 45),
                updatedAtEpochMs = epoch(2026, 11, 26, 8, 30, 5)),
            DayRecord(LocalDate.of(2026, 11, 25), steps = 4000),                                   // manual only, no timestamp
            DayRecord(LocalDate.of(2026, 11, 24), heartRateBpm = 72.0, hrvRmssdMs = null,          // HRV null stays null
                signalQuality = "Lower confidence", updatedAtEpochMs = epoch(2026, 11, 24, 7, 0, 0)),
            DayRecord(LocalDate.of(2026, 6, 1), sleepHours = 0.0, steps = 0, bedtime = LocalTime.MIDNIGHT,
                updatedAtEpochMs = epoch(2026, 6, 1, 23, 59, 59)),                                 // zeros and BST
            DayRecord(LocalDate.of(2026, 11, 23), heartRateBpm = 55.5, hrvRmssdMs = 80.0, signalQuality = "Fair",
                steps = 100_000, sleepHours = 24.0, updatedAtEpochMs = 1L * 1000),                 // limits
        )
        val imported = ok(CsvExporter.export(records, zone))
        assertEquals(records.sortedBy { it.date }, imported)
    }

    @Test fun reExportingAnImportGivesTheSameFileEvenInAnotherLocale() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            // Full-precision values from a measurement are written with one decimal; that text survives exactly.
            val csv = CsvExporter.export(
                listOf(DayRecord(LocalDate.of(2026, 11, 26), 61.2449, 44.06, 15.96, "Good", 8123, 7.5,
                    LocalTime.of(22, 45), epoch(2026, 11, 26, 8, 30, 0))),
                zone,
            )
            assertEquals(csv, CsvExporter.export(ok(csv), zone))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun importedRowsAreNeverDemo() {
        assertTrue(ok(file("2026-11-20,,,,,4000,,,")).none { it.isDemo })
    }

    // ---- Line endings, BOM, quoting ----

    @Test fun acceptsCrlfLfAndCrLineEndings() {
        val rows = listOf(header, "2026-11-20,,,,,4000,,,", "2026-11-21,,,,,5000,,,")
        for (eol in listOf("\r\n", "\n", "\r")) {
            assertEquals(eol, 2, ok(rows.joinToString(eol)).size)              // no trailing line break
            assertEquals(eol, 2, ok(rows.joinToString(eol, postfix = eol)).size)
        }
        assertEquals(2, ok(rows.joinToString("\n", postfix = "\n\n\n")).size) // blank lines are ignored
    }

    @Test fun ignoresAByteOrderMark() {
        val csv = CsvImporter.BOM + file("2026-11-20,,,,,4000,,,")
        assertEquals(listOf(0xEF, 0xBB, 0xBF), csv.toByteArray(Charsets.UTF_8).take(3).map { it.toInt() and 0xFF })
        assertEquals(4000, ok(csv).single().steps)
        assertEquals(4000, (CsvImporter.parse(csv.toByteArray(Charsets.UTF_8), today, zone) as CsvImporter.Result.Success).records.single().steps)
    }

    @Test fun readsQuotedFields() {
        // A spreadsheet app may quote every cell; doubled quotes and empty quoted cells are valid CSV.
        val quoted = CsvExporter.HEADER.joinToString(",") { "\"$it\"" } + "\r\n" +
            "\"2026-11-20\",\"61.0\",\"\",\"\",\"Lower confidence\",\"4000\",\"7.5\",\"23:15\",\"\"\r\n"
        val r = ok(quoted).single()
        assertEquals("Lower confidence", r.signalQuality)
        assertEquals(61.0, r.heartRateBpm!!, 0.0)
        assertEquals(LocalTime.of(23, 15), r.bedtime)
    }

    @Test fun commasQuotesAndLineBreaksInsideQuotedFieldsAreReadAsOneCell() {
        // These aren't valid values, but they must be read as one cell and rejected politely, not crash or shift columns.
        assertTrue(failure(file("2026-11-20,,,,,\"8,000\",,,")).startsWith("Line 2: steps \"8,000\""))
        assertTrue(failure(file("2026-11-20,,,,\"say \"\"hi\"\"\",4000,,,")).contains("\"say \"hi\"\"")) // shown unescaped
        // A line break inside quotes doesn't end the row; later errors still point at the right line.
        val msg = failure(file("2026-11-20,,,,\"two\nlines\",4000,,,", "2026-11-21,,,,,x,,,"))
        assertTrue(msg, msg.startsWith("Line 2:"))
        // Row 3 spans lines 3-4 (a valid cell with a quoted line break), so the bad row starts on line 5.
        val msg2 = failure(file("2026-11-20,,,,,4000,,,", "2026-11-21,,,,,\"4000\r\n\",,,", "2026-11-22,,,,,x,,,"))
        assertTrue(msg2, msg2.startsWith("Line 5:"))
    }

    @Test fun rejectsBrokenQuoting() {
        assertTrue(failure(file("2026-11-20,,,,\"Good,4000,,,")).contains("never closed"))
        assertTrue(failure(file("2026-11-20,,,,Go\"od,4000,,,")).contains("quote mark in the wrong place"))
        assertTrue(failure(file("2026-11-20,,,,\"Good\"x,4000,,,")).contains("after a closing quote"))
    }

    // ---- Empty, huge and non-text files ----

    @Test fun emptyFiles() {
        assertEquals(CsvImporter.EMPTY, failure(ByteArray(0)))
        assertEquals(CsvImporter.EMPTY, failure(CsvImporter.BOM))
        assertEquals(CsvImporter.EMPTY, failure("\r\n\r\n"))
        assertEquals(CsvImporter.NO_DAYS, failure(header + "\r\n"))
        assertEquals(CsvImporter.NO_DAYS, failure(CsvExporter.export(emptyList(), zone))) // an export of no data
    }

    @Test fun hugeFilesAreRejectedWithoutReadingThemWhole() {
        assertEquals(CsvImporter.TOO_LARGE, failure(ByteArray(CsvImporter.MAX_BYTES + 1) { 'a'.code.toByte() }))

        // An endless stream: only MAX_BYTES + 1 bytes are ever read.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len); served += len; return len
            }
        }
        val bytes = CsvImporter.readLimited(endless)
        assertEquals(CsvImporter.MAX_BYTES + 1, bytes.size)
        assertEquals(CsvImporter.MAX_BYTES + 1L, served)
        assertEquals(CsvImporter.TOO_LARGE, failure(bytes))

        // A normal file is read completely.
        val small = file("2026-11-20,,,,,4000,,,").toByteArray()
        assertEquals(small.toList(), CsvImporter.readLimited(ByteArrayInputStream(small)).toList())
    }

    @Test fun aLargeButValidFileImports() {
        val start = LocalDate.of(2001, 1, 1)
        val rows = (0 until 9000).map { "${start.plusDays(it.toLong())},62.0,45.0,,Good,${it % 20000},7.0,22:30,2026-01-01T08:00:00" }
        val csv = file(*rows.toTypedArray())
        assertTrue(csv.length < CsvImporter.MAX_BYTES)
        assertEquals(9000, ok(csv).size)
    }

    @Test fun binaryFilesAreRejected() {
        assertEquals(CsvImporter.NOT_TEXT, failure(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte())))
        assertEquals(CsvImporter.NOT_TEXT, failure(header + "\r\n2026-11-20,,,,,4000\u0000,,,\r\n"))
    }

    // ---- Header ----

    @Test fun rejectsAWrongHeader() {
        assertEquals(CsvImporter.BAD_HEADER, failure("date,steps\r\n2026-11-20,4000\r\n"))
        assertEquals(CsvImporter.BAD_HEADER, failure(header.replace(",", ";") + "\r\n"))                      // spreadsheet re-save
        assertEquals(CsvImporter.BAD_HEADER, failure(header.uppercase() + "\r\n2026-11-20,,,,,4000,,,\r\n"))
        assertEquals(CsvImporter.BAD_HEADER, failure("$header,extra\r\n2026-11-20,,,,,4000,,,,\r\n"))         // no invented columns
        assertEquals(CsvImporter.BAD_HEADER, failure("2026-11-20,,,,,4000,,,\r\n"))                            // header missing
    }

    // ---- Rows ----

    @Test fun rejectsMalformedRows() {
        assertTrue(failure(file("2026-11-20,,,,,4000,,")).startsWith("Line 2: has 8 values instead of 9"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,,,")).startsWith("Line 2: has 10 values instead of 9"))
        assertTrue(failure(file("2026-11-20")).startsWith("Line 2: has 1 value instead of 9"))
        assertTrue(failure(file("2026-11-20,,,,,,,,")).contains("has a date but no values"))
        assertTrue(failure(file("2026-11-20,,44.0,,Good,4000,,,")).contains("no heart rate"))
        assertTrue(failure(file("2026-11-20,,,,Excellent,4000,,,")).contains("isn't a signal quality"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,,", "2026-11-20,,,,,5000,,,")).startsWith("Line 3: the date 2026-11-20 appears more than once"))
    }

    @Test fun rejectsInvalidOrFarFutureDates() {
        for (bad in listOf("2026-02-30", "26-11-20", "20/11/2026", "2026-11-20T00:00", "", "2026-13-01")) {
            assertTrue(bad, failure(file("$bad,,,,,4000,,,")).contains("isn't a valid date"))
        }
        assertEquals(1, ok(file("${today.plusDays(1)},,,,,4000,,,")).size)      // time-zone slack
        assertTrue(failure(file("${today.plusDays(2)},,,,,4000,,,")).contains("is in the future"))
        assertTrue(failure(file("2999-01-01,,,,,4000,,,")).contains("is in the future"))
        assertTrue(failure(file("1999-12-31,,,,,4000,,,")).contains("too far in the past"))
    }

    @Test fun reusesTheManualEntryRulesForStepsAndSleep() {
        assertEquals(0, ok(file("2026-11-20,,,,,0,,,")).single().steps)
        assertEquals(100_000, ok(file("2026-11-20,,,,,100000,,,")).single().steps)
        for (bad in listOf("100001", "-1", "12.5", "abc", "1e3")) {
            assertTrue(bad, failure(file("2026-11-20,,,,,$bad,,,")).contains("steps"))
        }
        assertEquals(24.0, ok(file("2026-11-20,,,,,,24,,")).single().sleepHours!!, 0.0)
        for (bad in listOf("24.1", "7.25", "-1", "seven")) {
            assertTrue(bad, failure(file("2026-11-20,,,,,,$bad,,")).contains("sleep"))
        }
    }

    @Test fun rejectsOutOfRangeMeasurementsAndBadTimes() {
        assertTrue(failure(file("2026-11-20,300.0,,,Good,,,,")).contains("heart rate \"300.0\""))
        assertTrue(failure(file("2026-11-20,-60,,,Good,,,,")).contains("heart rate"))
        assertTrue(failure(file("2026-11-20,NaN,,,Good,,,,")).contains("heart rate"))
        assertTrue(failure(file("2026-11-20,60.0,5000,,Good,,,,")).contains("HRV"))
        assertTrue(failure(file("2026-11-20,60.0,40.0,99,Good,,,,")).contains("breathing rate"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,25:00,")).contains("bedtime"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,10pm,")).contains("bedtime"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,,yesterday")).contains("updated_at"))
        assertTrue(failure(file("2026-11-20,,,,,4000,,,2026-02-30T08:00:00")).contains("updated_at"))
    }

    @Test fun messagesShortenLongGarbage() {
        val msg = failure(file("2026-11-20,,,,,${"9".repeat(500)},,,"))
        assertTrue(msg, msg.length < 150)
    }
}
