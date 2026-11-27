package com.example.healthcheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class ConvertersTest {
    private val c = Converters()

    @Test fun datesRoundTripAsSortableIsoText() {
        val d = LocalDate.of(2026, 1, 5)
        assertEquals("2026-01-05", c.dateToText(d))
        assertEquals(d, c.textToDate("2026-01-05"))
        // Text order equals date order (needed for ORDER BY date).
        val dates = listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 2))
        assertEquals(dates, dates.map { c.dateToText(it)!! }.sorted().map { c.textToDate(it)!! })
    }

    @Test fun timesAreStoredAsHourMinuteInAnyLocale() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.ROOT, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"))) {
                Locale.setDefault(locale)
                assertEquals("09:05", c.timeToText(LocalTime.of(9, 5, 59)))
                assertEquals(LocalTime.of(23, 45), c.textToTime("23:45"))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun nullsStayNull() {
        assertNull(c.dateToText(null))
        assertNull(c.textToDate(null))
        assertNull(c.timeToText(null))
        assertNull(c.textToTime(null))
    }
}
