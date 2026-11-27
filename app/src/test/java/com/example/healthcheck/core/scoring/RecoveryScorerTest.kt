package com.example.healthcheck.core.scoring

import com.example.healthcheck.core.math.Stats
import com.example.healthcheck.core.model.DayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecoveryScorerTest {

    private val today = LocalDate.of(2026, 11, 26)
    private val scorer = RecoveryScorer()

    /** n earlier days with typical values (slight variation so MAD > 0). */
    private fun history(n: Int, hr: Double = 60.0, hrv: Double = 45.0, sleep: Double? = 7.5, steps: Int? = 8000) =
        (1..n).map { i ->
            val wobble = if (i % 2 == 0) 1.0 else -1.0
            DayRecord(
                date = today.minusDays(i.toLong()),
                heartRateBpm = hr + wobble,
                hrvRmssdMs = hrv + 2 * wobble,
                sleepHours = sleep?.plus(0.25 * wobble),
                steps = steps?.plus((500 * wobble).toInt()),
            )
        }

    @Test fun medianAndMad() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        // |x - 3| = 2,1,0,1,6 -> median 1
        assertEquals(1.0, Stats.mad(listOf(1.0, 2.0, 3.0, 4.0, 9.0)), 0.0)
    }

    @Test fun baselineNeedsFiveDaysAndExcludesToday() {
        val records = history(4) + DayRecord(today, heartRateBpm = 99.0, hrvRmssdMs = 10.0)
        assertNull(BaselineCalculator.baseline(records, Metric.HEART_RATE, today))
        val five = history(5) + DayRecord(today, heartRateBpm = 99.0, hrvRmssdMs = 10.0)
        val b = BaselineCalculator.baseline(five, Metric.HEART_RATE, today)!!
        assertEquals(5, b.count)
        assertTrue("today's value must not be in its own baseline", b.median < 62)
    }

    @Test fun baselineUsesAtMost14MostRecentDays() {
        val old = (15..30).map { DayRecord(today.minusDays(it.toLong()), heartRateBpm = 90.0, hrvRmssdMs = 20.0) }
        val recent = (1..14).map { DayRecord(today.minusDays(it.toLong()), heartRateBpm = 60.0, hrvRmssdMs = 50.0) }
        val b = BaselineCalculator.baseline(old + recent, Metric.HEART_RATE, today)!!
        assertEquals(14, b.count)
        assertEquals(60.0, b.median, 0.0)
    }

    @Test fun baselinePerMetricHandlesPartialDays() {
        // 6 days with measurements but only 3 with sleep
        val records = (1..6).map { i ->
            DayRecord(today.minusDays(i.toLong()), heartRateBpm = 60.0, hrvRmssdMs = 45.0,
                sleepHours = if (i <= 3) 7.0 else null)
        }
        assertNotNull(BaselineCalculator.baseline(records, Metric.HRV, today))
        assertNull(BaselineCalculator.baseline(records, Metric.SLEEP, today))
    }

    @Test fun robustZUsesSpreadFloorAndClamps() {
        // Identical history -> MAD 0 -> floor (2 bpm) used, no division by zero
        val records = (1..7).map { DayRecord(today.minusDays(it.toLong()), heartRateBpm = 60.0, hrvRmssdMs = 45.0) }
        val b = BaselineCalculator.baseline(records, Metric.HEART_RATE, today)!!
        assertEquals(0.0, b.mad, 0.0)
        assertEquals(2.0, b.spread, 0.0)
        assertEquals(1.0, BaselineCalculator.robustZ(62.0, b), 1e-9)
        assertEquals(3.0, BaselineCalculator.robustZ(200.0, b), 0.0)
        assertEquals(-3.0, BaselineCalculator.robustZ(0.0, b), 0.0)
    }

    @Test fun robustZUsesScaledMad() {
        val b = Baseline(median = 50.0, mad = 5.0, spread = 1.4826 * 5.0, count = 7)
        assertEquals(10.0 / (1.4826 * 5.0), BaselineCalculator.robustZ(60.0, b), 1e-9)
    }

    @Test fun orientationPositiveMeansWorse() {
        assertTrue(BaselineCalculator.strain(Metric.HRV, -1.0) > 0)       // lower HRV = worse
        assertTrue(BaselineCalculator.strain(Metric.HEART_RATE, 1.0) > 0) // higher HR = worse
        assertTrue(BaselineCalculator.strain(Metric.SLEEP, -1.0) > 0)     // shorter sleep = worse
        assertEquals(0.0, BaselineCalculator.strain(Metric.STEPS, 1.2), 0.0) // within deadband
        assertTrue(BaselineCalculator.strain(Metric.STEPS, -3.0) > 0)
        assertTrue(BaselineCalculator.strain(Metric.STEPS, 3.0) > 0)
        assertEquals(-1.0, BaselineCalculator.strain(Metric.SLEEP, 3.0), 0.0) // capped benefit
    }

    @Test fun dayAtBaselineScoresAbout72AndIsNormal() {
        val records = history(10) + DayRecord(today, heartRateBpm = 60.0, hrvRmssdMs = 45.0, sleepHours = 7.5, steps = 8000)
        val a = scorer.assess(records, today)
        assertEquals(DayStatus.SCORED, a.status)
        assertEquals(72, a.score)
        assertTrue(a.score!! in 70..75)
        assertEquals(StrainLabel.NORMAL, a.label)
        assertEquals("Your readings are close to your usual.", a.reason)
    }

    @Test fun lowHrvAndShortSleepIsElevatedWithReason() {
        val records = history(10) + DayRecord(today, heartRateBpm = 66.0, hrvRmssdMs = 30.0, sleepHours = 5.0, steps = 8000)
        val a = scorer.assess(records, today)
        assertEquals(StrainLabel.ELEVATED, a.label)
        assertTrue(a.score!! < ScoringConfig.NORMAL_STRAIN_MIN_SCORE)
        assertTrue(a.reason!!, a.reason!!.startsWith("HRV is lower than your usual"))
    }

    @Test fun goodDayIsLowStrain() {
        val records = history(10) + DayRecord(today, heartRateBpm = 55.0, hrvRmssdMs = 58.0, sleepHours = 8.5, steps = 8000)
        val a = scorer.assess(records, today)
        assertEquals(StrainLabel.LOW, a.label)
        assertTrue(a.score!! >= ScoringConfig.LOW_STRAIN_MIN_SCORE)
        assertTrue(a.reason!!.contains("HRV is higher"))
    }

    @Test fun labelThresholds() {
        assertEquals(StrainLabel.LOW, StrainLabel.forScore(75))
        assertEquals(StrainLabel.NORMAL, StrainLabel.forScore(74))
        assertEquals(StrainLabel.NORMAL, StrainLabel.forScore(45))
        assertEquals(StrainLabel.ELEVATED, StrainLabel.forScore(44))
        assertEquals(StrainLabel.LOW, StrainLabel.forScore(100))
        assertEquals(StrainLabel.ELEVATED, StrainLabel.forScore(0))
    }

    @Test fun scoreIsClamped() {
        assertEquals(100, RecoveryScorer.scoreFor(-10.0))
        assertEquals(0, RecoveryScorer.scoreFor(10.0))
        assertEquals(72, RecoveryScorer.scoreFor(0.0))
    }

    @Test fun weightsAreRenormalisedOverPresentMetrics() {
        // No sleep/steps at all: HRV and HR only (0.4 / 0.7 and 0.3 / 0.7)
        val records = history(10, sleep = null, steps = null) + DayRecord(today, heartRateBpm = 60.0, hrvRmssdMs = 45.0)
        val a = scorer.assess(records, today)
        assertEquals(DayStatus.SCORED, a.status)
        assertEquals(72, a.score)
        // HRV -3 (clamped) only -> strain = 0.4*3/0.7 + HR 0
        val bad = history(10, sleep = null, steps = null) + DayRecord(today, heartRateBpm = 60.0, hrvRmssdMs = 5.0)
        val b = scorer.assess(bad, today)
        assertEquals(0.4 * 3 / 0.7, b.strainIndex!!, 1e-9)
    }

    @Test fun manualOnlyDayHasNoScore() {
        val records = history(10) + DayRecord(today, sleepHours = 5.0, steps = 3000)
        val a = scorer.assess(records, today)
        assertEquals(DayStatus.NEEDS_MEASUREMENT, a.status)
        assertNull(a.score)
        assertNull(a.label)
        // Manual metrics still get compared with their own baseline
        assertNotNull(a.metrics.getValue(Metric.SLEEP).z)
        assertTrue(a.metrics.getValue(Metric.SLEEP).direction == Direction.WORSENING)
    }

    @Test fun buildingBaselineShowsProgressButNoScore() {
        val records = history(3) + DayRecord(today, heartRateBpm = 60.0, hrvRmssdMs = 45.0)
        val a = scorer.assess(records, today)
        assertEquals(DayStatus.BUILDING_BASELINE, a.status)
        assertEquals(3, a.baselineDays)
        assertNull(a.score)
    }

    @Test fun manualOnlyHistoryDoesNotCountTowardsMeasurementBaseline() {
        val records = (1..8).map { DayRecord(today.minusDays(it.toLong()), sleepHours = 7.0, steps = 5000) }
        val a = scorer.assess(records, today)
        assertEquals(DayStatus.BUILDING_BASELINE, a.status)
        assertEquals(0, a.baselineDays)
    }

    @Test fun emptyDataNeverCrashes() {
        val a = scorer.assess(emptyList(), today)
        assertEquals(DayStatus.BUILDING_BASELINE, a.status)
        assertNull(a.record)
        assertEquals(0, a.baselineDays)
        assertTrue(scorer.scores(emptyList(), listOf(today)).all { it.second == null })
    }

    @Test fun differenceFromUsual() {
        val records = history(10) + DayRecord(today, heartRateBpm = 54.0, hrvRmssdMs = 45.0)
        val hr = scorer.assess(records, today).metrics.getValue(Metric.HEART_RATE)
        assertEquals(-6.0, hr.differenceFromUsual!!, 1e-9)
        assertEquals(Direction.IMPROVING, hr.direction)
    }
}
