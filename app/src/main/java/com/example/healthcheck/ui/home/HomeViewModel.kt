package com.example.healthcheck.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthcheck.core.scoring.DayAssessment
import com.example.healthcheck.core.scoring.RecoveryScorer
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

sealed interface HomeState {
    data object Loading : HomeState
    data class Error(val message: String) : HomeState
    data class Ready(
        val today: LocalDate,
        val assessment: DayAssessment,
        /** Recovery score for the last 14 days, oldest first (null = no score that day). */
        val trend: List<Int?>,
    ) : HomeState
}

class HomeViewModel(repo: EntryRepository, today: Flow<LocalDate>) : ViewModel() {
    private val scorer = RecoveryScorer()

    val state: StateFlow<HomeState> = combine(repo.records, today) { records, day ->
        val trendDates = (13 downTo 0).map { day.minusDays(it.toLong()) }
        HomeState.Ready(
            today = day,
            assessment = scorer.assess(records, day),
            trend = scorer.scores(records, trendDates).map { it.second },
        ) as HomeState
    }
        .catch { e ->
            DebugLog.e("Home", "load failed", e)
            emit(HomeState.Error("Your data couldn't be loaded. Please restart the app."))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState.Loading)
}
