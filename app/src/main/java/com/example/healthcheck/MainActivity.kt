package com.example.healthcheck

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.core.color.ThemeTokenGenerator
import com.example.healthcheck.data.AppSettings
import com.example.healthcheck.data.ThemeMode
import com.example.healthcheck.ui.AppRoot
import com.example.healthcheck.ui.companion.LocalCompanionSettings
import com.example.healthcheck.ui.theme.HealthCheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = appContainer.settings.settings
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            // Wait for the stored theme (a few ms) so the app never flashes the wrong theme.
            settings?.let { ThemedApp(it, onDarkChanged = ::applySystemBars) }
        }
    }

    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun ThemedApp(settings: AppSettings, onDarkChanged: (Boolean) -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val tokens = remember(dark, settings.light, settings.dark) {
        if (dark) ThemeTokenGenerator.dark(settings.dark) else ThemeTokenGenerator.light(settings.light)
    }
    LaunchedEffect(dark) { onDarkChanged(dark) }
    HealthCheckTheme(tokens) {
        CompanionLocalProvider(settings) { AppRoot() }
    }
}

@Composable
private fun CompanionLocalProvider(settings: AppSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCompanionSettings provides settings.companion, content = content)
}
