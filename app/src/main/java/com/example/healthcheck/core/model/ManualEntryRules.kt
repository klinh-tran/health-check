package com.example.healthcheck.core.model

import java.math.BigDecimal

/**
 * The rules for manually entered values, shared by the Log screen and CSV import so both accept exactly
 * the same values. Each function returns (value, error); blank text means "no value".
 */
object ManualEntryRules {
    const val MAX_STEPS = 100_000
    const val MAX_SLEEP = 24.0

    fun steps(text: String): Pair<Int?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to null
        val v = t.toIntOrNull() ?: return null to "Enter a whole number, e.g. 8250"
        if (v !in 0..MAX_STEPS) return null to "Steps must be between 0 and 100,000"
        return v to null
    }

    fun sleep(text: String): Pair<Double?, String?> {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null to null
        val v = t.toBigDecimalOrNull() ?: return null to "Enter hours, e.g. 7.5"
        if (v.scale() > 1) return null to "Use at most one decimal, e.g. 7.5"
        if (v < BigDecimal.ZERO || v > BigDecimal.valueOf(MAX_SLEEP)) return null to "Sleep must be between 0 and 24 hours"
        return v.toDouble() to null
    }
}
