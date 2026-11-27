package com.example.healthcheck.core.scoring

import com.example.healthcheck.core.math.Stats
import com.example.healthcheck.core.model.DayRecord
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class Metric { HEART_RATE, HRV, SLEEP, STEPS }

/** All tunable numbers in one place. */
object ScoringConfig {
    const val MIN_BASELINE_DAYS = 5
    const val MAX_BASELINE_DAYS = 14
    /** Values older than this are ignored for the baseline (so a long break doesn't use stale data). */
    const val BASELINE_LOOKBACK_DAYS = 60L

    const val MAD_TO_SD = 1.4826
    const val Z_CLAMP = 3.0

    const val WEIGHT_HRV = 0.40
    const val WEIGHT_HEART_RATE = 0.30
    const val WEIGHT_SLEEP = 0.20
    const val WEIGHT_STEPS = 0.10

    /** Steps only count when |z| exceeds this (very low or very high days). */
    const val STEPS_DEADBAND_Z = 1.5
    /** Sleeping longer than usual can lower strain, but by at most this much. */
    const val SLEEP_MAX_BENEFIT = 1.0

    /** Score for a day exactly at baseline (strain 0). */
    const val SCORE_AT_BASELINE = 72.0
    /** Score points per unit of strain index. */
    const val SCORE_PER_STRAIN = 20.0

    /** Label thresholds on the 0-100 Recovery score. */
    const val LOW_STRAIN_MIN_SCORE = 75
    const val NORMAL_STRAIN_MIN_SCORE = 45

    /** Contributions smaller than this are not mentioned in the reason sentence. */
    const val REASON_MIN_CONTRIBUTION = 0.08
    /** |strain| above this colours a metric card as improving/worsening. */
    const val DIRECTION_THRESHOLD = 0.3

    fun weight(m: Metric) = when (m) {
        Metric.HRV -> WEIGHT_HRV
        Metric.HEART_RATE -> WEIGHT_HEART_RATE
        Metric.SLEEP -> WEIGHT_SLEEP
        Metric.STEPS -> WEIGHT_STEPS
    }

    /** Floor on the robust spread so a very consistent history cannot blow up z-scores. */
    fun minSpread(m: Metric, median: Double) = when (m) {
        Metric.HEART_RATE -> 2.0
        Metric.HRV -> max(3.0, 0.08 * median)
        Metric.SLEEP -> 0.5
        Metric.STEPS -> max(800.0, 0.15 * median)
    }
}

data class Baseline(
    val median: Double,
    /** Unscaled median absolute deviation. */
    val mad: Double,
    /** Robust SD actually used: max(1.4826 * MAD, floor). */
    val spread: Double,
    val count: Int,
)

enum class StrainLabel(val text: String) {
    LOW("Low strain"), NORMAL("Normal strain"), ELEVATED("Elevated strain");

    companion object {
        fun forScore(score: Int) = when {
            score >= ScoringConfig.LOW_STRAIN_MIN_SCORE -> LOW
            score >= ScoringConfig.NORMAL_STRAIN_MIN_SCORE -> NORMAL
            else -> ELEVATED
        }
    }
}

enum class Direction { IMPROVING, WORSENING, NEUTRAL }

data class MetricAssessment(
    val metric: Metric,
    val value: Double?,
    val baseline: Baseline?,
    /** Robust z-score, clamped to +/-3. Null without value or baseline. */
    val z: Double?,
    /** Oriented so positive = worse for me. */
    val strain: Double?,
) {
    val differenceFromUsual: Double? get() = if (value != null && baseline != null) value - baseline.median else null
    val direction: Direction
        get() = when {
            strain == null -> Direction.NEUTRAL
            strain > ScoringConfig.DIRECTION_THRESHOLD -> Direction.WORSENING
            strain < -ScoringConfig.DIRECTION_THRESHOLD -> Direction.IMPROVING
            else -> Direction.NEUTRAL
        }
}

enum class DayStatus {
    /** Fewer than 5 earlier days with a measurement: no score yet. */
    BUILDING_BASELINE,
    /** Baseline exists, but this day has no valid measurement: show manual metrics only. */
    NEEDS_MEASUREMENT,
    SCORED,
}

data class DayAssessment(
    val date: LocalDate,
    val record: DayRecord?,
    val metrics: Map<Metric, MetricAssessment>,
    val status: DayStatus,
    /** Earlier days with a measurement, capped at MIN_BASELINE_DAYS ("n of 5 days"). */
    val baselineDays: Int,
    val strainIndex: Double?,
    val score: Int?,
    val label: StrainLabel?,
    val reason: String?,
)

fun DayRecord.value(m: Metric): Double? = when (m) {
    Metric.HEART_RATE -> heartRateBpm
    Metric.HRV -> hrvRmssdMs
    Metric.SLEEP -> sleepHours
    Metric.STEPS -> steps?.toDouble()
}

object BaselineCalculator {

    /** Earlier values for [m], newest first, within the lookback and capped at 14. Today is excluded. */
    fun priorValues(records: List<DayRecord>, m: Metric, forDate: LocalDate): List<Double> {
        val earliest = forDate.minusDays(ScoringConfig.BASELINE_LOOKBACK_DAYS)
        return records.asSequence()
            .filter { it.date < forDate && it.date >= earliest }
            .sortedByDescending { it.date }
            .mapNotNull { it.value(m) }
            .take(ScoringConfig.MAX_BASELINE_DAYS)
            .toList()
    }

    fun baseline(records: List<DayRecord>, m: Metric, forDate: LocalDate): Baseline? {
        val values = priorValues(records, m, forDate)
        if (values.size < ScoringConfig.MIN_BASELINE_DAYS) return null
        val median = Stats.median(values)
        val mad = Stats.mad(values)
        val spread = max(ScoringConfig.MAD_TO_SD * mad, ScoringConfig.minSpread(m, median))
        return Baseline(median, mad, spread, values.size)
    }

    fun robustZ(value: Double, b: Baseline): Double =
        ((value - b.median) / b.spread).coerceIn(-ScoringConfig.Z_CLAMP, ScoringConfig.Z_CLAMP)

    /** Orient z so that positive = worse. */
    fun strain(m: Metric, z: Double): Double = when (m) {
        Metric.HEART_RATE -> z
        Metric.HRV -> -z
        Metric.SLEEP -> max(-z, -ScoringConfig.SLEEP_MAX_BENEFIT)
        Metric.STEPS -> max(0.0, abs(z) - ScoringConfig.STEPS_DEADBAND_Z)
    }
}

class RecoveryScorer {

    fun assess(records: List<DayRecord>, date: LocalDate): DayAssessment {
        val record = records.firstOrNull { it.date == date }
        val metrics = Metric.entries.associateWith { m ->
            val value = record?.value(m)
            val baseline = BaselineCalculator.baseline(records, m, date)
            val z = if (value != null && baseline != null) BaselineCalculator.robustZ(value, baseline) else null
            MetricAssessment(m, value, baseline, z, z?.let { BaselineCalculator.strain(m, it) })
        }
        val measuredDays = BaselineCalculator.priorValues(records, Metric.HRV, date).size
        val baselineDays = minOf(measuredDays, ScoringConfig.MIN_BASELINE_DAYS)
        val physioReady = metrics.getValue(Metric.HRV).baseline != null ||
            metrics.getValue(Metric.HEART_RATE).baseline != null

        if (!physioReady) {
            return DayAssessment(date, record, metrics, DayStatus.BUILDING_BASELINE, baselineDays, null, null, null, null)
        }
        // A score needs a valid heart-rate/HRV measurement on that day.
        val hasPhysio = metrics.getValue(Metric.HRV).strain != null || metrics.getValue(Metric.HEART_RATE).strain != null
        if (record?.hasMeasurement != true || !hasPhysio) {
            return DayAssessment(date, record, metrics, DayStatus.NEEDS_MEASUREMENT, baselineDays, null, null, null, null)
        }

        val present = metrics.values.filter { it.strain != null }
        val totalWeight = present.sumOf { ScoringConfig.weight(it.metric) }
        val contributions = present.associate { it.metric to ScoringConfig.weight(it.metric) * it.strain!! / totalWeight }
        val strainIndex = contributions.values.sum()
        val score = scoreFor(strainIndex)
        val label = StrainLabel.forScore(score)
        val reason = reason(strainIndex, contributions, metrics)
        return DayAssessment(date, record, metrics, DayStatus.SCORED, baselineDays, strainIndex, score, label, reason)
    }

    /** Recovery score for each date (null where no score is possible). */
    fun scores(records: List<DayRecord>, dates: List<LocalDate>): List<Pair<LocalDate, Int?>> =
        dates.map { it to assess(records, it).score }

    companion object {
        fun scoreFor(strainIndex: Double): Int =
            (ScoringConfig.SCORE_AT_BASELINE - ScoringConfig.SCORE_PER_STRAIN * strainIndex)
                .roundToInt().coerceIn(0, 100)

        fun reason(
            strainIndex: Double,
            contributions: Map<Metric, Double>,
            metrics: Map<Metric, MetricAssessment>,
        ): String {
            val worse = strainIndex >= 0
            val top = contributions.entries
                .filter { if (worse) it.value > ScoringConfig.REASON_MIN_CONTRIBUTION else it.value < -ScoringConfig.REASON_MIN_CONTRIBUTION }
                .sortedByDescending { abs(it.value) }
                .take(2)
                .map { phrase(it.key, it.value > 0, metrics.getValue(it.key)) }
            if (top.isEmpty()) return "Your readings are close to your usual."
            val sentence = top.joinToString(" and ")
            return sentence.replaceFirstChar { it.uppercase() } + "."
        }

        private fun phrase(m: Metric, worse: Boolean, a: MetricAssessment): String = when (m) {
            Metric.HRV -> if (worse) "HRV is lower than your usual" else "HRV is higher than your usual"
            Metric.HEART_RATE -> if (worse) "resting heart rate is higher than your usual"
            else "resting heart rate is lower than your usual"
            Metric.SLEEP -> if (worse) "sleep was short" else "you slept longer than usual"
            Metric.STEPS -> if ((a.differenceFromUsual ?: 0.0) < 0) "steps were much lower than usual"
            else "steps were much higher than usual"
        }
    }
}
