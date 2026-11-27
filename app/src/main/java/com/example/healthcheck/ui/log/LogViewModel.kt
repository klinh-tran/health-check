package com.example.healthcheck.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthcheck.core.model.DayRecord
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

data class LogForm(
    val date: LocalDate = LocalDate.now(),
    val steps: String = "",
    val sleep: String = "",
    val bedtime: LocalTime? = null,
    val stepsError: String? = null,
    val sleepError: String? = null,
    val formError: String? = null,
    val existing: DayRecord? = null,
    val saving: Boolean = false,
)

object LogValidation {
    const val MAX_STEPS = 100_000
    const val MAX_SLEEP = 24.0

    /** Returns (value, error). Blank means "no value". */
    fun steps(text: String): Pair<Int?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to null
        val v = t.toIntOrNull() ?: return null to "Enter a whole number, e.g. 8250"
        if (v !in 0..MAX_STEPS) return null to "Steps must be between 0 and 100,000"
        return v to null
    }

    fun sleep(text: String): Pair<Double?, String?> {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null to null
        val v = t.toBigDecimalOrNull() ?: return null to "Enter hours, e.g. 7.5"
        if (v.scale() > 1) return null to "Use at most one decimal, e.g. 7.5"
        if (v < BigDecimal.ZERO || v > BigDecimal.valueOf(MAX_SLEEP)) return null to "Sleep must be between 0 and 24 hours"
        return v.toDouble() to null
    }
}

class LogViewModel(private val repo: EntryRepository) : ViewModel() {

    private val _form = MutableStateFlow(LogForm())
    val form: StateFlow<LogForm> = _form.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Newest first. */
    val history: StateFlow<List<DayRecord>?> = repo.records
        .map { it.sortedByDescending { r -> r.date } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        selectDate(LocalDate.now())
    }

    fun selectDate(date: LocalDate) {
        val safe = minOf(date, LocalDate.now())
        viewModelScope.launch {
            val existing = runCatching { repo.get(safe) }.getOrNull()
            _form.value = LogForm(
                date = safe,
                steps = existing?.steps?.toString().orEmpty(),
                sleep = existing?.sleepHours?.let { formatSleep(it) }.orEmpty(),
                bedtime = existing?.bedtime,
                existing = existing,
            )
        }
    }

    fun onSteps(text: String) = _form.update { it.copy(steps = text.filter { c -> c.isDigit() }.take(6), stepsError = null, formError = null) }
    fun onSleep(text: String) = _form.update { it.copy(sleep = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(4), sleepError = null, formError = null) }
    fun onBedtime(time: LocalTime?) = _form.update { it.copy(bedtime = time, formError = null) }

    fun save() {
        val f = _form.value
        if (f.saving) return
        val (steps, stepsErr) = LogValidation.steps(f.steps)
        val (sleep, sleepErr) = LogValidation.sleep(f.sleep)
        if (stepsErr != null || sleepErr != null) {
            _form.update { it.copy(stepsError = stepsErr, sleepError = sleepErr) }
            return
        }
        if (steps == null && sleep == null && f.bedtime == null && f.existing == null) {
            _form.update { it.copy(formError = "Enter steps or sleep hours to save.") }
            return
        }
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repo.saveManual(f.date, steps, sleep, f.bedtime)
                _message.value = "Saved for ${LogFormat.longDate(f.date)}"
                selectDate(f.date)
            } catch (e: Exception) {
                DebugLog.e("Log", "save failed", e)
                _form.update { it.copy(saving = false, formError = "Couldn't save. Please try again.") }
            }
        }
    }

    fun delete(date: LocalDate) {
        viewModelScope.launch {
            try {
                repo.delete(date)
                _message.value = "Entry deleted"
                if (_form.value.date == date) selectDate(date)
            } catch (e: Exception) {
                DebugLog.e("Log", "delete failed", e)
                _message.value = "Couldn't delete. Please try again."
            }
        }
    }

    fun messageShown() { _message.value = null }

    private fun formatSleep(h: Double): String =
        if (h % 1.0 == 0.0) h.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", h)
}
