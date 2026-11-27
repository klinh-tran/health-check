package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord
import com.example.healthcheck.core.model.ManualEntryRules
import com.example.healthcheck.core.signal.SignalQuality
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Reads a file written by [CsvExporter] back into [DayRecord]s. The whole file is checked before anything is
 * returned: either every row is valid ([Result.Success]) or nothing is ([Result.Failure], with a message for
 * the user). It never throws on bad input.
 *
 * The exporter writes plain RFC 4180 cells and does no formula escaping (every cell is a date, a number,
 * a time or a fixed quality label), so there is nothing to undo here beyond normal CSV quoting.
 */
object CsvImporter {
    /** A real export is about 60 bytes per day, so 2 MB is decades of data. */
    const val MAX_BYTES = 2 * 1024 * 1024
    val MIN_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    /** One day of slack for moving to a phone in a time zone that is already "tomorrow". */
    const val MAX_DAYS_AHEAD = 1L

    // Wider than anything PpgAnalyzer can produce (IBIs 300-1500 ms, breathing 0.1-0.5 Hz), so a
    // future tweak to PpgConfig never makes an older export unreadable.
    val HEART_RATE_RANGE = 30.0..220.0
    val HRV_RANGE = 0.0..1200.0
    val BREATHING_RANGE = 3.0..60.0
    val QUALITY_LABELS: Set<String> = SignalQuality.entries.map { it.label }.toSet()

    sealed interface Result {
        data class Success(val records: List<DayRecord>) : Result
        data class Failure(val message: String) : Result
    }

    const val EMPTY = "This file is empty."
    const val TOO_LARGE = "This file is too large to be a Health Check export (over 2 MB)."
    const val NOT_TEXT = "This file isn't a text CSV file. Choose a file exported from Health Check."
    const val BAD_HEADER = "This doesn't look like a Health Check export: the column names don't match. " +
        "If you opened and saved it in a spreadsheet app, export it again from Health Check and import that file."
    const val NO_DAYS = "This file has no days to import."

    /** UTF-8 byte-order mark that some apps put at the start of a file (written as a char code so lint is happy). */
    val BOM: String = Char(0xFEFF).toString()

    private val updatedFormat =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val decimal = Regex("""\d{1,6}(\.\d{1,6})?""")
    private val time = Regex("""(\d{1,2}):(\d{2})(:00)?""")

    /**
     * Reads at most [limit] bytes. Pass one more than [MAX_BYTES] (the default) so [parse] can tell an
     * over-sized file apart from one that is exactly at the limit, without reading a huge file into memory.
     */
    fun readLimited(input: InputStream, limit: Int = MAX_BYTES + 1): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray, today: LocalDate, zone: ZoneId): Result {
        if (bytes.isEmpty()) return Result.Failure(EMPTY)
        if (bytes.size > MAX_BYTES) return Result.Failure(TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return Result.Failure(NOT_TEXT)
        }
        return parse(text, today, zone)
    }

    fun parse(raw: String, today: LocalDate, zone: ZoneId): Result {
        val text = raw.removePrefix(BOM)
        if (text.isBlank()) return Result.Failure(EMPTY)
        if (text.length > MAX_BYTES) return Result.Failure(TOO_LARGE)
        if (text.any { it == '\u0000' }) return Result.Failure(NOT_TEXT)

        val rows = try {
            tokenize(text).filterNot { it.cells.size == 1 && it.cells[0].isBlank() }
        } catch (e: MalformedCsv) {
            return Result.Failure("Line ${e.line} ${e.message}.")
        }
        if (rows.isEmpty()) return Result.Failure(EMPTY)
        if (rows[0].cells.map { it.trim() } != CsvExporter.HEADER) return Result.Failure(BAD_HEADER)
        if (rows.size == 1) return Result.Failure(NO_DAYS)

        val seen = HashSet<LocalDate>()
        val records = ArrayList<DayRecord>(rows.size - 1)
        for (row in rows.drop(1)) {
            try {
                records += parseRow(row.cells, today, zone, seen)
            } catch (e: BadRow) {
                return Result.Failure("Line ${row.line}: ${e.message}.")
            }
        }
        return Result.Success(records.sortedBy { it.date })
    }

    private fun parseRow(raw: List<String>, today: LocalDate, zone: ZoneId, seen: MutableSet<LocalDate>): DayRecord {
        val n = CsvExporter.HEADER.size
        if (raw.size != n) bad("has ${raw.size} value${if (raw.size == 1) "" else "s"} instead of $n")
        val c = raw.map { it.trim() }

        val date = try {
            LocalDate.parse(c[0])
        } catch (_: DateTimeParseException) {
            bad("\"${c[0].short()}\" isn't a valid date (expected YYYY-MM-DD)")
        }
        if (date < MIN_DATE) bad("the date $date is too far in the past")
        if (date > today.plusDays(MAX_DAYS_AHEAD)) bad("the date $date is in the future")
        if (!seen.add(date)) bad("the date $date appears more than once in the file")

        val hr = decimal(c[1], "heart rate", HEART_RATE_RANGE, "bpm")
        val hrv = decimal(c[2], "HRV", HRV_RANGE, "ms")
        val breathing = decimal(c[3], "breathing rate", BREATHING_RANGE, "breaths per minute")
        val quality = c[4].ifEmpty { null }
        if (quality != null && quality !in QUALITY_LABELS) bad("\"${quality.short()}\" isn't a signal quality the app uses")
        if (hr == null && (hrv != null || breathing != null || quality != null)) bad("has measurement values but no heart rate")

        val (steps, stepsError) = ManualEntryRules.steps(c[5])
        if (stepsError != null) bad("steps \"${c[5].short()}\" must be a whole number from 0 to 100,000")
        val (sleep, sleepError) = ManualEntryRules.sleep(c[6])
        if (sleepError != null) bad("sleep \"${c[6].short()}\" must be 0 to 24 hours with at most one decimal")

        val bedtime = c[7].ifEmpty { null }?.let { t ->
            val m = time.matchEntire(t) ?: bad("bedtime \"${t.short()}\" must be a time like 22:45")
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) bad("bedtime \"${t.short()}\" must be a time like 22:45")
            LocalTime.of(h, min)
        }

        val updatedAt = c[8].ifEmpty { null }?.let { t ->
            try {
                LocalDateTime.parse(t, updatedFormat).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                bad("updated_at \"${t.short()}\" must look like 2026-11-26T08:30:00")
            }
        } ?: 0L

        if (hr == null && steps == null && sleep == null && bedtime == null) bad("has a date but no values")

        return DayRecord(
            date = date,
            heartRateBpm = hr,
            hrvRmssdMs = hrv,
            breathingRate = breathing,
            signalQuality = quality,
            steps = steps,
            sleepHours = sleep,
            bedtime = bedtime,
            updatedAtEpochMs = updatedAt,
            isDemo = false,
        )
    }

    private fun decimal(text: String, name: String, range: ClosedFloatingPointRange<Double>, unit: String): Double? {
        if (text.isEmpty()) return null
        val v = text.takeIf { decimal.matches(it) }?.toDouble()
        if (v == null || v !in range) {
            bad("$name \"${text.short()}\" must be a number from ${range.start.fmt()} to ${range.endInclusive.fmt()} $unit")
        }
        return v
    }

    private fun Double.fmt() = if (this % 1.0 == 0.0) toLong().toString() else toString()

    /** Keeps messages short when a cell holds something unexpected. */
    private fun String.short() = if (length <= 24) this else take(24) + "…"

    private class BadRow(message: String) : Exception(message)
    private fun bad(message: String): Nothing = throw BadRow(message)

    private class Row(val line: Int, val cells: List<String>)
    private class MalformedCsv(val line: Int, message: String) : Exception(message)

    /**
     * RFC 4180 tokenizer: commas separate cells, CRLF, LF or a lone CR ends a row, and a quoted cell may
     * hold commas, line breaks and doubled quotes. [Row.line] is the line the row starts on (1-based).
     */
    private fun tokenize(text: String): List<Row> {
        val rows = ArrayList<Row>()
        var cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var closedQuote = false // the current cell was quoted and its closing quote has been read
        var line = 1
        var rowStart = 1
        var i = 0

        fun endCell() {
            cells += cell.toString()
            cell.setLength(0)
            closedQuote = false
        }
        fun endRow() {
            endCell()
            rows += Row(rowStart, cells)
            cells = ArrayList()
        }

        while (i < text.length) {
            val ch = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else null
            if (inQuotes) {
                when {
                    ch == '"' && next == '"' -> { cell.append('"'); i += 2 }
                    ch == '"' -> { inQuotes = false; closedQuote = true; i++ }
                    else -> {
                        if (ch == '\n' || (ch == '\r' && next != '\n')) line++
                        cell.append(ch)
                        i++
                    }
                }
                continue
            }
            when (ch) {
                ',' -> { endCell(); i++ }
                '\r', '\n' -> {
                    endRow()
                    i += if (ch == '\r' && next == '\n') 2 else 1
                    line++
                    rowStart = line
                }
                '"' -> {
                    if (cell.isNotEmpty() || closedQuote) throw MalformedCsv(line, "has a quote mark in the wrong place")
                    inQuotes = true
                    i++
                }
                else -> {
                    if (closedQuote) throw MalformedCsv(line, "has text after a closing quote mark")
                    cell.append(ch)
                    i++
                }
            }
        }
        if (inQuotes) throw MalformedCsv(rowStart, "has a quote mark that is never closed")
        if (cell.isNotEmpty() || cells.isNotEmpty() || closedQuote) endRow()
        return rows
    }
}
