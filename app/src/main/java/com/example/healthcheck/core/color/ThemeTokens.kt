package com.example.healthcheck.core.color

import kotlin.math.min

/** The user's three colour sliders. Hue 0-360, vibrancy 20-100, tint 0-100. */
data class ColorChoice(val hue: Float, val vibrancy: Float, val tint: Float) {
    fun clamped() = ColorChoice(
        hue.coerceIn(HUE_MIN, HUE_MAX),
        vibrancy.coerceIn(VIBRANCY_MIN, VIBRANCY_MAX),
        tint.coerceIn(TINT_MIN, TINT_MAX),
    )

    companion object {
        const val HUE_MIN = 0f
        const val HUE_MAX = 360f
        const val VIBRANCY_MIN = 20f
        const val VIBRANCY_MAX = 100f
        const val TINT_MIN = 0f
        const val TINT_MAX = 100f
        val DEFAULT = ColorChoice(hue = 222f, vibrancy = 85f, tint = 60f)
    }
}

/**
 * Colour tokens used by every screen. All are packed ARGB ints so this stays pure Kotlin.
 *
 * Text/surface pairs used in the UI (and verified by ContrastSweepTest):
 *  ink, sub, accent, warning   on background and card
 *  onAccent                    on accent       (buttons, selected items)
 *  onAccentSoft                on accentSoft   (chips, soft highlights)
 *  onWarning                   on warning
 */
data class ThemeTokens(
    val isDark: Boolean,
    val background: Int,
    val card: Int,
    val ink: Int,
    val sub: Int,
    val line: Int,
    val accent: Int,
    val accentSoft: Int,
    val onAccent: Int,
    val onAccentSoft: Int,
    val warning: Int,
    val onWarning: Int,
)

object ThemeTokenGenerator {

    /** WCAG AA for normal-size text. */
    const val MIN_TEXT_CONTRAST = 4.5

    private const val LIGHT_INK = 0xFF12161C.toInt()
    private const val LIGHT_SUB = 0xFF5C6573.toInt()
    private const val LIGHT_LINE = 0xFFE4E8EF.toInt()
    private const val LIGHT_WARNING = 0xFFB3261E.toInt()

    private const val DARK_INK = 0xFFEEF1F6.toInt()
    private const val DARK_SUB = 0xFF98A2B1.toInt()
    private const val DARK_LINE = 0xFF252B35.toInt()
    private const val DARK_WARNING = 0xFFFFB4AB.toInt()

    fun light(choice: ColorChoice): ThemeTokens {
        val c = choice.clamped()
        val h = c.hue.toDouble()
        val v = c.vibrancy.toDouble()
        val t = c.tint.toDouble()
        val background = ColorMath.hsl(h, 15 + 0.75 * t, 98 - 0.05 * t)
        val card = ColorMath.WHITE
        val surfaces = listOf(background, card)
        // Accent is used both as a filled surface and as text/icons on background & card,
        // so darken it if the requested 40% lightness is too light (e.g. yellow hues).
        val accent = ColorMath.hslWithContrast(h, v, 40.0, surfaces, MIN_TEXT_CONTRAST, darken = true)
        val accentSoft = ColorMath.hsl(h, 40 + 0.5 * v, 91.0)
        val sub = ColorMath.ensureContrast(LIGHT_SUB, surfaces, MIN_TEXT_CONTRAST, darken = true)
        val warning = ColorMath.ensureContrast(LIGHT_WARNING, surfaces, MIN_TEXT_CONTRAST, darken = true)
        return ThemeTokens(
            isDark = false,
            background = background,
            card = card,
            ink = ColorMath.ensureContrast(LIGHT_INK, surfaces, MIN_TEXT_CONTRAST, darken = true),
            sub = sub,
            line = LIGHT_LINE,
            accent = accent,
            accentSoft = accentSoft,
            onAccent = ColorMath.onColor(accent),
            onAccentSoft = ColorMath.onColor(accentSoft),
            warning = warning,
            onWarning = ColorMath.onColor(warning),
        )
    }

    fun dark(choice: ColorChoice): ThemeTokens {
        val c = choice.clamped()
        val h = c.hue.toDouble()
        val v = c.vibrancy.toDouble()
        val t = c.tint.toDouble()
        val background = ColorMath.hsl(h, 10 + 0.45 * t, 6 + 0.04 * t)
        val card = ColorMath.hsl(h, 10 + 0.4 * t, 10 + 0.05 * t)
        val surfaces = listOf(background, card)
        val accent = ColorMath.hslWithContrast(
            h, min(100.0, v + 5), 68.0, surfaces, MIN_TEXT_CONTRAST, darken = false,
        )
        val accentSoft = ColorMath.hsl(h, 30 + 0.3 * v, 20.0)
        val sub = ColorMath.ensureContrast(DARK_SUB, surfaces, MIN_TEXT_CONTRAST, darken = false)
        val warning = ColorMath.ensureContrast(DARK_WARNING, surfaces, MIN_TEXT_CONTRAST, darken = false)
        return ThemeTokens(
            isDark = true,
            background = background,
            card = card,
            ink = ColorMath.ensureContrast(DARK_INK, surfaces, MIN_TEXT_CONTRAST, darken = false),
            sub = sub,
            line = DARK_LINE,
            accent = accent,
            accentSoft = accentSoft,
            onAccent = ColorMath.onColor(accent),
            onAccentSoft = ColorMath.onColor(accentSoft),
            warning = warning,
            onWarning = ColorMath.onColor(warning),
        )
    }

    /** Every (foreground, background) text pair the UI draws. Used by tests. */
    fun textPairs(t: ThemeTokens): List<Triple<String, Int, Int>> = buildList {
        for ((sName, s) in listOf("background" to t.background, "card" to t.card)) {
            add(Triple("ink/$sName", t.ink, s))
            add(Triple("sub/$sName", t.sub, s))
            add(Triple("accent/$sName", t.accent, s))
            add(Triple("warning/$sName", t.warning, s))
        }
        add(Triple("onAccent/accent", t.onAccent, t.accent))
        add(Triple("onAccentSoft/accentSoft", t.onAccentSoft, t.accentSoft))
        add(Triple("onWarning/warning", t.onWarning, t.warning))
    }
}
