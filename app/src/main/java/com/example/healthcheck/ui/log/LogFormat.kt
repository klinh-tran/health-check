package com.example.healthcheck.ui.log

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object LogFormat {
    // Formatters are created per call so a locale change while the app runs is respected.
    fun longDate(d: LocalDate): String = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(d)
    fun shortDate(d: LocalDate): String = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(d)
    fun time(t: LocalTime): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(t)

    fun relativeDate(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> shortDate(d)
    }

    fun steps(v: Int): String = String.format(Locale.getDefault(), "%,d", v)
    fun hours(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
    fun whole(v: Double): String = v.roundToInt().toString()

    /** "+3 vs usual" / "-0.5 vs usual" / "Same as usual". Uses a real minus sign. */
    fun delta(diff: Double, decimals: Int): String {
        val rounded = if (decimals == 0) diff.roundToInt().toDouble() else (diff * 10).roundToInt() / 10.0
        if (rounded == 0.0) return "Same as usual"
        val sign = if (rounded > 0) "+" else "−"
        val body = if (decimals == 0) String.format(Locale.getDefault(), "%,d", abs(rounded).toInt())
        else String.format(Locale.getDefault(), "%.1f", abs(rounded))
        return "$sign$body vs usual"
    }
}
