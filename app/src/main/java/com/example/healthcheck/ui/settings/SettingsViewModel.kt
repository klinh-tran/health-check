package com.example.healthcheck.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthcheck.BuildConfig
import com.example.healthcheck.core.color.ColorChoice
import com.example.healthcheck.core.color.ColorPreset
import com.example.healthcheck.core.color.PresetCodec
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.core.csv.CsvExporter
import com.example.healthcheck.core.model.DemoData
import com.example.healthcheck.data.AppSettings
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.data.SettingsRepository
import com.example.healthcheck.data.ThemeMode
import com.example.healthcheck.data.ThemeSlot
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val entries: EntryRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _slot = MutableStateFlow(ThemeSlot.LIGHT)
    val slot: StateFlow<ThemeSlot> = _slot.asStateFlow()

    /** Colour being dragged right now (for the live preview), before it is saved. */
    private val _draft = MutableStateFlow<ColorChoice?>(null)
    val draft: StateFlow<ColorChoice?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun updateCompanion(transform: (CompanionSettings) -> CompanionSettings) =
        viewModelScope.launch { settingsRepo.updateCompanion(transform) }

    fun setSignalDetails(on: Boolean) = viewModelScope.launch { settingsRepo.setSignalDetails(on) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun selectSlot(s: ThemeSlot) {
        _slot.value = s
        _draft.value = null
    }

    fun onDrag(choice: ColorChoice) { _draft.value = choice.clamped() }

    /** Finger lifted: persist, which re-themes the app with a cross-fade. */
    fun commitDraft() {
        val d = _draft.value ?: return
        apply(d)
    }

    fun apply(choice: ColorChoice) {
        val c = choice.clamped()
        _draft.value = c
        val s = _slot.value
        viewModelScope.launch { settingsRepo.setColor(s, c) }
    }

    fun surprise() = apply(
        ColorChoice(
            hue = Random.nextInt(0, 360).toFloat(),
            vibrancy = Random.nextInt(45, 96).toFloat(),
            tint = Random.nextInt(15, 91).toFloat(),
        ),
    )

    fun reset() = apply(ColorChoice.DEFAULT)

    fun savePreset(rawName: String, choice: ColorChoice): Boolean {
        val name = PresetCodec.cleanName(rawName)
        val current = settings.value?.presets(_slot.value).orEmpty()
        if (name.isEmpty()) return false
        if (current.size >= PresetCodec.MAX_PRESETS) {
            _message.value = "You can keep up to ${PresetCodec.MAX_PRESETS} presets. Delete one first."
            return false
        }
        val s = _slot.value
        viewModelScope.launch {
            settingsRepo.setPresets(s, current.filterNot { it.name == name } + ColorPreset(name, choice.clamped()))
            _message.value = "Preset \"$name\" saved"
        }
        return true
    }

    fun deletePreset(preset: ColorPreset) {
        val s = _slot.value
        val current = settings.value?.presets(s).orEmpty()
        viewModelScope.launch { settingsRepo.setPresets(s, current - preset) }
    }

    fun exportCsv(resolver: ContentResolver, uri: Uri) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val records = entries.allRecords()
                val csv = CsvExporter.export(records, ZoneId.systemDefault())
                withContext(Dispatchers.IO) {
                    // "wt" truncates an existing file; some providers only support plain "w".
                    val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: resolver.openOutputStream(uri, "w")
                        ?: error("Could not open file")
                    stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                _message.value = "Exported ${records.size} day${if (records.size == 1) "" else "s"} to CSV"
            } catch (e: Exception) {
                DebugLog.e("Settings", "export failed", e)
                _message.value = "Export failed. Please try another location."
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteAll() = viewModelScope.launch {
        try {
            entries.deleteAll()
            _message.value = "All entries deleted"
        } catch (e: Exception) {
            DebugLog.e("Settings", "delete failed", e)
            _message.value = "Couldn't delete data. Please try again."
        }
    }

    // ---- Developer demo data (debug builds only) ----

    fun fillDemo() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            entries.insertDemo(DemoData.generate(LocalDate.now()))
            _message.value = "Demo data added (days without real data only)"
        }
    }

    fun clearDemo() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            entries.clearDemo()
            _message.value = "Demo data cleared"
        }
    }

    fun messageShown() { _message.value = null }
}
