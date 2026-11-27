package com.example.healthcheck.core.csv

import com.example.healthcheck.core.model.DayRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RFC 4180-style CSV, one row per day, oldest first. Numbers use '.' regardless of phone locale.
 * Demo days are left out: they are fake, and the file has no column to mark them, so an import would
 * otherwise bring them back as real data.
 */
object CsvExporter {
    val HEADER = listOf(
        "date", "heart_rate_bpm", "hrv_rmssd_ms", "breathing_rate_per_min", "signal_quality",
        "steps", "sleep_hours", "bedtime", "updated_at",
    )

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    fun export(records: List<DayRecord>, zone: ZoneId): String = buildString {
        append(HEADER.joinToString(",")).append("\r\n")
        for (r in records.filterNot { it.isDemo }.sortedBy { it.date }) {
            val cells = listOf(
                r.date.toString(),
                r.heartRateBpm.fmt(1),
                r.hrvRmssdMs.fmt(1),
                r.breathingRate.fmt(1),
                r.signalQuality.orEmpty(),
                r.steps?.toString().orEmpty(),
                r.sleepHours.fmt(1),
                r.bedtime?.let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) }.orEmpty(),
                if (r.updatedAtEpochMs > 0) Instant.ofEpochMilli(r.updatedAtEpochMs).atZone(zone).format(timestampFormat) else "",
            )
            append(cells.joinToString(",") { escape(it) }).append("\r\n")
        }
    }

    fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

    private fun Double?.fmt(decimals: Int): String =
        this?.let { String.format(Locale.ROOT, "%.${decimals}f", it) }.orEmpty()
}
