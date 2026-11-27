package com.example.healthcheck.ui.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.healthcheck.core.color.ThemeTokens

/** Compose-side mirror of [ThemeTokens]. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val ink: Color,
    val sub: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val onAccentSoft: Color,
    val warning: Color,
    val onWarning: Color,
)

fun ThemeTokens.toAppColors() = AppColors(
    isDark = isDark,
    background = Color(background),
    card = Color(card),
    ink = Color(ink),
    sub = Color(sub),
    line = Color(line),
    accent = Color(accent),
    accentSoft = Color(accentSoft),
    onAccent = Color(onAccent),
    onAccentSoft = Color(onAccentSoft),
    warning = Color(warning),
    onWarning = Color(onWarning),
)

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("AppColors not provided; wrap content in HealthCheckTheme")
}

/** True when the system "Remove animations" / animator duration scale 0 is set. */
val LocalReduceMotion = staticCompositionLocalOf { false }

object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
}

/** Returns [spec] normally, or an instant snap when animations are disabled. */
@Composable
fun <T> motion(spec: AnimationSpec<T>): AnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spec

@Composable
private fun animated(target: Color, reduce: Boolean): State<Color> =
    animateColorAsState(
        targetValue = target,
        animationSpec = if (reduce) snap() else tween(durationMillis = 450),
        label = "themeColor",
    )

@Composable
fun HealthCheckTheme(tokens: ThemeTokens, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun readReduceMotion() = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    var reduceMotion by remember(context) { mutableStateOf(readReduceMotion()) }
    // Re-check when returning to the app, in case "Remove animations" was toggled meanwhile.
    LifecycleResumeEffect(context) {
        reduceMotion = readReduceMotion()
        onPauseOrDispose { }
    }
    val target = tokens.toAppColors()
    // Smooth cross-fade between themes / colour choices.
    val colors = AppColors(
        isDark = target.isDark,
        background = animated(target.background, reduceMotion).value,
        card = animated(target.card, reduceMotion).value,
        ink = animated(target.ink, reduceMotion).value,
        sub = animated(target.sub, reduceMotion).value,
        line = animated(target.line, reduceMotion).value,
        accent = animated(target.accent, reduceMotion).value,
        accentSoft = animated(target.accentSoft, reduceMotion).value,
        onAccent = animated(target.onAccent, reduceMotion).value,
        onAccentSoft = animated(target.onAccentSoft, reduceMotion).value,
        warning = animated(target.warning, reduceMotion).value,
        onWarning = animated(target.onWarning, reduceMotion).value,
    )
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = Typography,
            content = content,
        )
    }
}

/** Map tokens into Material 3 so stock components (buttons, sliders, dialogs...) use them too. */
private fun AppColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        inversePrimary = accentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = onAccentSoft,
        background = background,
        onBackground = ink,
        surface = card,
        onSurface = ink,
        surfaceVariant = background,
        onSurfaceVariant = sub,
        surfaceTint = Color.Transparent,
        surfaceBright = card,
        surfaceDim = background,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = background,
        inverseSurface = ink,
        inverseOnSurface = background,
        outline = sub,
        outlineVariant = line,
        error = warning,
        onError = onWarning,
        errorContainer = warning,
        onErrorContainer = onWarning,
        scrim = Color.Black,
    )
}
