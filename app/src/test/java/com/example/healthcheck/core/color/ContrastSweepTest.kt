package com.example.healthcheck.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastSweepTest {

    private fun sweep(): Sequence<ColorChoice> = sequence {
        var h = 0f
        while (h <= 360f) {
            var v = 20f
            while (v <= 100f) {
                var t = 0f
                while (t <= 100f) {
                    yield(ColorChoice(h, v, t))
                    t += 5f
                }
                v += 5f
            }
            h += 5f
        }
    }

    private fun checkTheme(name: String, gen: (ColorChoice) -> ThemeTokens) {
        var checked = 0
        for (choice in sweep()) {
            val tokens = gen(choice)
            for ((pair, fg, bg) in ThemeTokenGenerator.textPairs(tokens)) {
                val ratio = ColorMath.contrast(fg, bg)
                assertTrue(
                    "$name $pair at $choice is ${"%.2f".format(ratio)}:1",
                    ratio >= ThemeTokenGenerator.MIN_TEXT_CONTRAST,
                )
                checked++
            }
            // Text on accent must never reuse the screen background colour (the old invisible-label bug).
            assertNotEquals("$name onAccent equals background at $choice", tokens.background, tokens.onAccent)
            assertTrue(tokens.onAccent == ColorMath.BLACK || tokens.onAccent == ColorMath.WHITE)
            assertTrue(tokens.onAccentSoft == ColorMath.BLACK || tokens.onAccentSoft == ColorMath.WHITE)
        }
        assertTrue(checked > 10_000)
    }

    @Test fun lightThemeAllPairsMeetWcagAA() = checkTheme("light", ThemeTokenGenerator::light)

    @Test fun darkThemeAllPairsMeetWcagAA() = checkTheme("dark", ThemeTokenGenerator::dark)

    @Test fun defaultLightPrimaryButtonLabelIsVisible() {
        val t = ThemeTokenGenerator.light(ColorChoice.DEFAULT)
        assertEquals(ColorMath.WHITE, t.onAccent)
        assertTrue(ColorMath.contrast(t.onAccent, t.accent) >= 4.5)
        // Label is clearly not the light background colour
        assertTrue(ColorMath.contrast(t.background, t.accent) >= 4.5)
    }

    @Test fun yellowLightAccentIsDarkenedUntilReadable() {
        val t = ThemeTokenGenerator.light(ColorChoice(60f, 100f, 0f))
        assertTrue(ColorMath.contrast(t.accent, t.card) >= 4.5)
        assertTrue(ColorMath.contrast(t.onAccent, t.accent) >= 4.5)
    }

    @Test fun lightTokensFollowSpecFormula() {
        val t = ThemeTokenGenerator.light(ColorChoice(222f, 85f, 60f))
        assertEquals(ColorMath.hsl(222.0, 60.0, 95.0), t.background)
        assertEquals(ColorMath.WHITE, t.card)
        assertEquals(ColorMath.hsl(222.0, 85.0, 40.0), t.accent) // already readable, unchanged
        assertEquals(ColorMath.hsl(222.0, 82.5, 91.0), t.accentSoft)
        assertEquals(0xFF12161C.toInt(), t.ink)
    }

    @Test fun darkTokensFollowSpecFormula() {
        val t = ThemeTokenGenerator.dark(ColorChoice(222f, 85f, 60f))
        assertEquals(ColorMath.hsl(222.0, 37.0, 8.4), t.background)
        assertEquals(ColorMath.hsl(222.0, 34.0, 13.0), t.card)
        assertEquals(ColorMath.hsl(222.0, 55.5, 20.0), t.accentSoft)
        assertEquals(0xFFEEF1F6.toInt(), t.ink)
        assertTrue(ColorMath.contrast(t.accent, t.background) >= 4.5)
    }

    @Test fun contrastMath() {
        assertEquals(21.0, ColorMath.contrast(ColorMath.BLACK, ColorMath.WHITE), 1e-6)
        assertEquals(1.0, ColorMath.contrast(ColorMath.WHITE, ColorMath.WHITE), 1e-9)
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsl(0.0, 100.0, 50.0))
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsl(240.0, 100.0, 50.0))
    }
}
