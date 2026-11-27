package com.example.healthcheck.core.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * One local calendar day of data. Pure Kotlin (no Android) so scoring and CSV logic are JVM-testable.
 * A day can hold a measurement only, manual values only, or both: every field except [date] is optional.
 */
data class DayRecord(
    val date: LocalDate,
    val heartRateBpm: Double? = null,
    val hrvRmssdMs: Double? = null,
    val breathingRate: Double? = null,
    val signalQuality: String? = null,
    val steps: Int? = null,
    val sleepHours: Double? = null,
    val bedtime: LocalTime? = null,
    val updatedAtEpochMs: Long = 0L,
    val isDemo: Boolean = false,
) {
    val hasMeasurement: Boolean get() = heartRateBpm != null && hrvRmssdMs != null
    val hasManual: Boolean get() = steps != null || sleepHours != null || bedtime != null
}
