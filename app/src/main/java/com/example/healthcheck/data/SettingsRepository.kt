package com.example.healthcheck.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.healthcheck.core.color.ColorChoice
import com.example.healthcheck.core.color.ColorPreset
import com.example.healthcheck.core.color.PresetCodec
import com.example.healthcheck.core.companion.Accessories
import com.example.healthcheck.core.companion.AnimationLevel
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.core.companion.CompanionText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which theme a colour choice / preset belongs to. */
enum class ThemeSlot { LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val light: ColorChoice = ColorChoice.DEFAULT,
    val dark: ColorChoice = ColorChoice.DEFAULT,
    val lightPresets: List<ColorPreset> = emptyList(),
    val darkPresets: List<ColorPreset> = emptyList(),
    val companion: CompanionSettings = CompanionSettings(),
    /** Shows the raw signal numbers during a measurement (for working out why a reading fails). */
    val signalDetails: Boolean = false,
) {
    fun choice(slot: ThemeSlot) = if (slot == ThemeSlot.LIGHT) light else dark
    fun presets(slot: ThemeSlot) = if (slot == ThemeSlot.LIGHT) lightPresets else darkPresets
}

// A corrupted settings file is replaced with defaults instead of crashing on the next write.
// Only theme and companion preferences live here; health data is in Room.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        fun hue(s: ThemeSlot) = floatPreferencesKey("${s.name.lowercase()}_hue")
        fun vibrancy(s: ThemeSlot) = floatPreferencesKey("${s.name.lowercase()}_vibrancy")
        fun tint(s: ThemeSlot) = floatPreferencesKey("${s.name.lowercase()}_tint")
        fun presets(s: ThemeSlot) = stringPreferencesKey("${s.name.lowercase()}_presets")

        val companionCharacter = stringPreferencesKey("companion_character")
        val companionLevel = stringPreferencesKey("companion_level")
        val companionOrange = booleanPreferencesKey("companion_orange")
        val companionHat = booleanPreferencesKey("companion_hat")
        val companionSunglasses = booleanPreferencesKey("companion_sunglasses")
        val companionBow = booleanPreferencesKey("companion_bow")
        val companionBubbles = booleanPreferencesKey("companion_bubbles")
        val companionName = stringPreferencesKey("companion_name")
        val signalDetails = booleanPreferencesKey("measure_signal_details")
    }

    private fun readCompanion(p: Preferences) = CompanionSettings(
        character = p[Keys.companionCharacter]?.let { v -> CompanionCharacter.entries.firstOrNull { it.name == v } }
            ?: CompanionCharacter.PIP,
        level = p[Keys.companionLevel]?.let { v -> AnimationLevel.entries.firstOrNull { it.name == v } }
            ?: AnimationLevel.PLAYFUL,
        accessories = Accessories(
            orange = p[Keys.companionOrange] ?: false,
            hat = p[Keys.companionHat] ?: false,
            sunglasses = p[Keys.companionSunglasses] ?: false,
            bow = p[Keys.companionBow] ?: false,
        ),
        speechBubbles = p[Keys.companionBubbles] ?: true,
        name = CompanionText.cleanName(p[Keys.companionName].orEmpty()),
    )

    private fun writeCompanion(p: MutablePreferences, c: CompanionSettings) {
        p[Keys.companionCharacter] = c.character.name
        p[Keys.companionLevel] = c.level.name
        p[Keys.companionOrange] = c.accessories.orange
        p[Keys.companionHat] = c.accessories.hat
        p[Keys.companionSunglasses] = c.accessories.sunglasses
        p[Keys.companionBow] = c.accessories.bow
        p[Keys.companionBubbles] = c.speechBubbles
        p[Keys.companionName] = CompanionText.cleanName(c.name)
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            fun choice(s: ThemeSlot) = ColorChoice(
                p[Keys.hue(s)] ?: ColorChoice.DEFAULT.hue,
                p[Keys.vibrancy(s)] ?: ColorChoice.DEFAULT.vibrancy,
                p[Keys.tint(s)] ?: ColorChoice.DEFAULT.tint,
            ).clamped()
            AppSettings(
                themeMode = p[Keys.themeMode]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } }
                    ?: ThemeMode.SYSTEM,
                light = choice(ThemeSlot.LIGHT),
                dark = choice(ThemeSlot.DARK),
                lightPresets = PresetCodec.decode(p[Keys.presets(ThemeSlot.LIGHT)]),
                darkPresets = PresetCodec.decode(p[Keys.presets(ThemeSlot.DARK)]),
                companion = readCompanion(p),
                signalDetails = p[Keys.signalDetails] ?: false,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setColor(slot: ThemeSlot, choice: ColorChoice) {
        val c = choice.clamped()
        store.edit {
            it[Keys.hue(slot)] = c.hue
            it[Keys.vibrancy(slot)] = c.vibrancy
            it[Keys.tint(slot)] = c.tint
        }
    }

    suspend fun setPresets(slot: ThemeSlot, presets: List<ColorPreset>) {
        store.edit { it[Keys.presets(slot)] = PresetCodec.encode(presets) }
    }

    suspend fun setSignalDetails(on: Boolean) {
        store.edit { it[Keys.signalDetails] = on }
    }

    /** Atomic read-modify-write, so quick successive changes (typing a name) never lose each other. */
    suspend fun updateCompanion(transform: (CompanionSettings) -> CompanionSettings) {
        store.edit { p -> writeCompanion(p, transform(readCompanion(p))) }
    }
}
