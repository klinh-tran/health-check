package com.example.healthcheck.data

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Dates stored as ISO "yyyy-MM-dd" text (sorts correctly); times as "HH:mm". */
class Converters {
    @TypeConverter fun dateToText(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun textToDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
    @TypeConverter fun timeToText(t: LocalTime?): String? =
        t?.let { String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) }
    @TypeConverter fun textToTime(s: String?): LocalTime? = s?.let(LocalTime::parse)
}
