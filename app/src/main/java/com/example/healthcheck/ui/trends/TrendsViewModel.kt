package com.example.healthcheck.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthcheck.core.math.Stats
import com.example.healthcheck.core.model.DayRecord
import com.example.healthcheck.core.scoring.BaselineCalculator
import com.example.healthcheck.core.scoring.Metric
import com.example.healthcheck.core.scoring.RecoveryScorer
import com.example.healthcheck.core.scoring.ScoringConfig
import com.example.healthcheck.core.scoring.value
import com.example.healthcheck.ui.components.Band
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.max

enum class TrendMetric(val label: String, val unit: String, val decimals: Int) {
    RECOVERY("Recovery score", "", 0),
    HEART_RATE("Heart rate", "bpm", 0),
    HRV("HRV", "ms", 0),
    SLEEP("Sleep", "h", 1),
    STEPS("Steps", "", 0),
}

data class TrendsState(
    val loading: Boolean = true,
    val error: String? = null,
    val metric: TrendMetric = TrendMetric.RECOVERY,
    val days: Int = 14,
    val dates: List<LocalDate> = emptyList(),
    val values: List<Float?> = emptyList(),
    val band: Band? = null,
    val hasAnyData: Boolean = false,
    val average: Float? = null,
    val latest: Float? = null,
)

class TrendsViewModel(repo: EntryRepository, today: Flow<LocalDate>) : ViewModel() {
    private val scorer = RecoveryScorer()
    private val metric = MutableStateFlow(TrendMetric.RECOVERY)
    private val days = MutableStateFlow(14)

    val state: StateFlow<TrendsState> = combine(repo.records, today, metric, days) { records, day, m, n ->
        build(records, day, m, n)
    }
        .catch { e ->
            DebugLog.e("Trends", "load failed", e)
            emit(TrendsState(loading = false, error = "Trends couldn't be loaded."))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())

    fun selectMetric(m: TrendMetric) { metric.value = m }
    fun selectDays(n: Int) { days.value = n }

    private fun build(records: List<DayRecord>, today: LocalDate, m: TrendMetric, n: Int): TrendsState {
        val dates = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val values: List<Float?> = when (m) {
            TrendMetric.RECOVERY -> scorer.scores(records, dates).map { it.second?.toFloat() }
            else -> {
                val byDate = records.associateBy { it.date }
                dates.map { d -> byDate[d]?.value(m.toMetric())?.toFloat() }
            }
        }
        val band = when (m) {
            TrendMetric.RECOVERY -> scoreBand(values)
            else -> BaselineCalculator.baseline(records, m.toMetric(), today)?.let {
                Band((it.median - it.spread).toFloat(), (it.median + it.spread).toFloat())
            }
        }
        val present = values.filterNotNull()
        return TrendsState(
            loading = false,
            metric = m,
            days = n,
            dates = dates,
            values = values,
            band = band,
            hasAnyData = records.isNotEmpty(),
            average = if (present.isEmpty()) null else present.average().toFloat(),
            latest = present.lastOrNull(),
        )
    }

    /** "Your usual" for the score itself: median +/- robust spread of the scores shown. */
    private fun scoreBand(values: List<Float?>): Band? {
        val v = values.filterNotNull().map { it.toDouble() }
        if (v.size < ScoringConfig.MIN_BASELINE_DAYS) return null
        val med = Stats.median(v)
        val spread = max(ScoringConfig.MAD_TO_SD * Stats.mad(v), 3.0)
        return Band((med - spread).toFloat().coerceAtLeast(0f), (med + spread).toFloat().coerceAtMost(100f))
    }
}

private fun TrendMetric.toMetric(): Metric = when (this) {
    TrendMetric.HEART_RATE -> Metric.HEART_RATE
    TrendMetric.HRV -> Metric.HRV
    TrendMetric.SLEEP -> Metric.SLEEP
    TrendMetric.STEPS -> Metric.STEPS
    TrendMetric.RECOVERY -> error("Recovery is not a raw metric")
}
