package com.example.healthcheck.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.healthcheck.core.companion.Accessories
import com.example.healthcheck.core.companion.AnimationLevel
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionSettingsPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    /** A fresh repository over the same file, like an app restart. */
    private fun repo(file: File): SettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    private fun restart() = scopes.removeAt(scopes.lastIndex).cancel()

    @After fun tearDown() = scopes.forEach { it.cancel() }

    @Test fun defaults() = runBlocking {
        val s = repo(File(tmp.root, "a.preferences_pb")).settings.first().companion
        assertEquals(CompanionSettings(), s)
        assertEquals(CompanionCharacter.PIP, s.character)
        assertEquals(AnimationLevel.PLAYFUL, s.level)
        assertEquals(true, s.speechBubbles)
        assertEquals("Pip", s.displayName)
    }

    @Test fun everythingSurvivesARestart() = runBlocking {
        val file = File(tmp.root, "b.preferences_pb")
        val wanted = CompanionSettings(
            character = CompanionCharacter.COMET,
            level = AnimationLevel.CALM,
            accessories = Accessories(orange = true, hat = false, sunglasses = true, bow = true),
            speechBubbles = false,
            name = "Stardust",
        )
        repo(file).updateCompanion { wanted }
        restart()
        val read = repo(file).settings.first().companion
        assertEquals(wanted, read)
        assertEquals("Stardust", read.displayName)
    }

    @Test fun successiveUpdatesDoNotLoseEachOther() = runBlocking {
        val r = repo(File(tmp.root, "c.preferences_pb"))
        r.updateCompanion { it.copy(character = CompanionCharacter.TULIP) }
        r.updateCompanion { it.copy(accessories = it.accessories.copy(hat = true)) }
        r.updateCompanion { it.copy(name = "This name is far too long") }
        val s = r.settings.first().companion
        assertEquals(CompanionCharacter.TULIP, s.character)
        assertEquals(true, s.accessories.hat)
        assertEquals("This name is", s.name)
    }

    @Test fun companionChangesKeepThemeSettings() = runBlocking {
        val r = repo(File(tmp.root, "d.preferences_pb"))
        r.setThemeMode(ThemeMode.DARK)
        r.updateCompanion { it.copy(level = AnimationLevel.OFF) }
        val s = r.settings.first()
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(AnimationLevel.OFF, s.companion.level)
    }
}
