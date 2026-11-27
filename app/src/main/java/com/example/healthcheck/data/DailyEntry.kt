package com.example.healthcheck.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.healthcheck.core.model.DayRecord
import java.time.LocalDate
import java.time.LocalTime

/** One row per local day. [date] is the device's local calendar date. */
@Entity(tableName = "daily_entry")
data class DailyEntry(
    @PrimaryKey val date: LocalDate,
    val heartRateBpm: Double?,
    val hrvRmssdMs: Double?,
    val breathingRate: Double?,
    val signalQuality: String?,
    val steps: Int?,
    val sleepHours: Double?,
    val bedtime: LocalTime?,
    val updatedAt: Long,
    /** Developer demo rows, so "Clear demo data" removes only those. */
    @ColumnInfo(defaultValue = "0") val isDemo: Boolean = false,
)

fun DailyEntry.toRecord() = DayRecord(
    date = date,
    heartRateBpm = heartRateBpm,
    hrvRmssdMs = hrvRmssdMs,
    breathingRate = breathingRate,
    signalQuality = signalQuality,
    steps = steps,
    sleepHours = sleepHours,
    bedtime = bedtime,
    updatedAtEpochMs = updatedAt,
    isDemo = isDemo,
)

fun DayRecord.toEntity() = DailyEntry(
    date = date,
    heartRateBpm = heartRateBpm,
    hrvRmssdMs = hrvRmssdMs,
    breathingRate = breathingRate,
    signalQuality = signalQuality,
    steps = steps,
    sleepHours = sleepHours,
    bedtime = bedtime,
    updatedAt = updatedAtEpochMs,
    isDemo = isDemo,
)
