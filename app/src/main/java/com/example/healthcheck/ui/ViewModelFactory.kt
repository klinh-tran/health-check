package com.example.healthcheck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.healthcheck.AppContainer
import com.example.healthcheck.ui.home.HomeViewModel
import com.example.healthcheck.ui.log.LogViewModel
import com.example.healthcheck.ui.measure.MeasureViewModel
import com.example.healthcheck.ui.settings.SettingsViewModel
import com.example.healthcheck.ui.trends.TrendsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

class ViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(c.entries, todayFlow())
        TrendsViewModel::class.java -> TrendsViewModel(c.entries, todayFlow())
        LogViewModel::class.java -> LogViewModel(c.entries)
        SettingsViewModel::class.java -> SettingsViewModel(c.settings, c.entries)
        MeasureViewModel::class.java -> MeasureViewModel(c.entries)
        else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
    } as T
}

/**
 * The device's local date, re-emitted when it changes (midnight, or a time-zone change),
 * so Home and Trends roll over to the new day without restarting.
 */
fun todayFlow(): Flow<LocalDate> = flow {
    while (true) {
        emit(LocalDate.now())
        delay(30_000)
    }
}.distinctUntilChanged()
