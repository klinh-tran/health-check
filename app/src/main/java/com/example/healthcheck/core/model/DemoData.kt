package com.example.healthcheck.core.model

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * DEVELOPER HELPER ONLY (debug builds). Generates plausible fake days so the full UI can be seen
 * before five real days exist. Rows are flagged isDemo and can be removed with "Clear demo data".
 */
object DemoData {
    fun generate(today: LocalDate, days: Int = 14, seed: Int = 7): List<DayRecord> {
        val rnd = Random(seed)
        return (0 until days).map { i ->
            val date = today.minusDays(i.toLong())
            val tired = i % 5 == 1
            val sleep = ((if (tired) 5.6 else 7.3) + rnd.nextDouble(-0.6, 0.6)).roundTo1()
            DayRecord(
                date = date,
                heartRateBpm = ((if (tired) 66.0 else 60.0) + rnd.nextDouble(-2.5, 2.5)).roundTo1(),
                hrvRmssdMs = ((if (tired) 34.0 else 46.0) + rnd.nextDouble(-5.0, 5.0)).roundTo1(),
                breathingRate = null,
                signalQuality = "Good",
                steps = (7500 + rnd.nextInt(-2500, 3500)),
                sleepHours = sleep,
                bedtime = LocalTime.of(22 + rnd.nextInt(0, 2), rnd.nextInt(0, 4) * 15),
                isDemo = true,
            )
        }
    }

    private fun Double.roundTo1() = (this * 10).roundToInt() / 10.0
}
