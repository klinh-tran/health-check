package com.example.healthcheck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.healthcheck.BuildConfig
import com.example.healthcheck.appContainer
import com.example.healthcheck.ui.companion.CompanionGalleryScreen
import com.example.healthcheck.ui.components.AppIcons
import com.example.healthcheck.ui.home.HomeScreen
import com.example.healthcheck.ui.home.HomeViewModel
import com.example.healthcheck.ui.log.LogScreen
import com.example.healthcheck.ui.log.LogViewModel
import com.example.healthcheck.ui.measure.MeasureScreen
import com.example.healthcheck.ui.measure.MeasureViewModel
import com.example.healthcheck.ui.settings.HowItWorksScreen
import com.example.healthcheck.ui.settings.SettingsScreen
import com.example.healthcheck.ui.settings.SettingsViewModel
import com.example.healthcheck.ui.theme.AppTheme
import com.example.healthcheck.ui.trends.TrendsScreen
import com.example.healthcheck.ui.trends.TrendsViewModel

enum class Tab(val label: String) { HOME("Home"), TRENDS("Trends"), LOG("Log"), SETTINGS("Settings") }

/** Full-screen routes shown above the tabs. */
enum class Overlay { NONE, MEASURE, HOW_IT_WORKS, COMPANION_GALLERY }

@Composable
fun AppRoot() {
    val container = LocalContext.current.appContainer
    val factory = remember(container) { ViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val trendsVm: TrendsViewModel = viewModel(factory = factory)
    val logVm: LogViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val measureVm: MeasureViewModel = viewModel(factory = factory)
    val appSettings by settingsVm.settings.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion

    BackHandler(enabled = overlay == Overlay.NONE && tab != Tab.HOME) { tab = Tab.HOME }

    AnimatedContent(
        targetState = overlay,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else if (targetState != Overlay.NONE) {
                (fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 12 }) togetherWith fadeOut(tween(200))
            } else {
                fadeIn(tween(250)) togetherWith (fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 12 })
            }
        },
        label = "overlay",
        modifier = Modifier.fillMaxSize().background(c.background),
    ) { current ->
        when (current) {
            Overlay.MEASURE -> MeasureScreen(
                viewModel = measureVm,
                showSignalDetails = appSettings?.signalDetails == true,
                onClose = { overlay = Overlay.NONE },
                onAddManual = {
                    logVm.selectDate(java.time.LocalDate.now())
                    tab = Tab.LOG
                    overlay = Overlay.NONE
                },
            )
            Overlay.HOW_IT_WORKS -> HowItWorksScreen(onBack = { overlay = Overlay.NONE })
            // Developer-only screen; unreachable in release builds.
            Overlay.COMPANION_GALLERY -> if (BuildConfig.DEBUG) {
                CompanionGalleryScreen(onBack = { overlay = Overlay.NONE })
            }
            Overlay.NONE -> Scaffold(
                containerColor = c.background,
                contentColor = c.ink,
                bottomBar = { BottomBar(tab) { tab = it } },
            ) { padding ->
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (reduce) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                    },
                    label = "tab",
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) { t ->
                    Box(Modifier.fillMaxSize()) {
                        when (t) {
                            Tab.HOME -> HomeScreen(homeVm, onStartMeasurement = { overlay = Overlay.MEASURE })
                            Tab.TRENDS -> TrendsScreen(trendsVm, onStartMeasurement = { overlay = Overlay.MEASURE })
                            Tab.LOG -> LogScreen(logVm)
                            Tab.SETTINGS -> SettingsScreen(
                                settingsVm,
                                onOpenHowItWorks = { overlay = Overlay.HOW_IT_WORKS },
                                onOpenCompanionGallery = { if (BuildConfig.DEBUG) overlay = Overlay.COMPANION_GALLERY },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        NavigationBar(containerColor = c.card, tonalElevation = 0.dp) {
            Tab.entries.forEach { t ->
                val icon: ImageVector = when (t) {
                    Tab.HOME -> Icons.Filled.Home
                    Tab.TRENDS -> AppIcons.Trends
                    Tab.LOG -> AppIcons.Log
                    Tab.SETTINGS -> Icons.Filled.Settings
                }
                NavigationBarItem(
                    selected = selected == t,
                    onClick = { onSelect(t) },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.onAccentSoft,
                        selectedTextColor = c.ink,
                        indicatorColor = c.accentSoft,
                        unselectedIconColor = c.sub,
                        unselectedTextColor = c.sub,
                    ),
                )
            }
        }
    }
}
