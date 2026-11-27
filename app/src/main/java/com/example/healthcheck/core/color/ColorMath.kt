package com.example.healthcheck.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure-Kotlin colour helpers (no Android dependencies) so they can be unit tested on the JVM.
 * Colours are packed 0xAARRGGBB ints.
 */
object ColorMath {

    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    /** h in degrees (any value, wrapped), s and l in percent (0-100). */
    fun hsl(h: Double, s: Double, l: Double): Int {
        val hh = ((h % 360.0) + 360.0) % 360.0
        val ss = (s / 100.0).coerceIn(0.0, 1.0)
        val ll = (l / 100.0).coerceIn(0.0, 1.0)
        val c = (1.0 - abs(2.0 * ll - 1.0)) * ss
        val hp = hh / 60.0
        val x = c * (1.0 - abs(hp % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = ll - c / 2.0
        return argb(r1 + m, g1 + m, b1 + m)
    }

    fun argb(r: Double, g: Double, b: Double): Int {
        val ri = (r.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val gi = (g.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val bi = (b.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** WCAG 2.x relative luminance. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    /** WCAG contrast ratio, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * Black or white, whichever contrasts more with [surface]. Mathematically this always
     * reaches at least ~4.58:1, so it satisfies WCAG AA for normal text on any surface.
     */
    fun onColor(surface: Int): Int =
        if (contrast(BLACK, surface) >= contrast(WHITE, surface)) BLACK else WHITE

    /**
     * Returns the HSL colour (h, s, l) with its lightness moved (darker when [darken], else lighter)
     * in small steps until it reaches [minRatio] against every colour in [against].
     * Falls back to black/white, which always works for at least one direction.
     */
    fun hslWithContrast(
        h: Double,
        s: Double,
        l: Double,
        against: List<Int>,
        minRatio: Double,
        darken: Boolean,
    ): Int {
        var light = l
        while (light in 0.0..100.0) {
            val c = hsl(h, s, light)
            if (against.all { contrast(c, it) >= minRatio }) return c
            light += if (darken) -0.5 else 0.5
        }
        return if (darken) BLACK else WHITE
    }

    /** Adjust an existing colour's lightness until it meets the ratio (keeps hue and saturation). */
    fun ensureContrast(color: Int, against: List<Int>, minRatio: Double, darken: Boolean): Int {
        if (against.all { contrast(color, it) >= minRatio }) return color
        val (h, s, l) = toHsl(color)
        return hslWithContrast(h, s, l, against, minRatio, darken)
    }

    /** Returns h (deg), s (%), l (%). */
    fun toHsl(c: Int): Triple<Double, Double, Double> {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2.0
        val d = mx - mn
        if (d == 0.0) return Triple(0.0, 0.0, l * 100.0)
        val s = d / (1.0 - abs(2.0 * l - 1.0))
        val h = when (mx) {
            r -> 60.0 * (((g - b) / d) % 6.0)
            g -> 60.0 * (((b - r) / d) + 2.0)
            else -> 60.0 * (((r - g) / d) + 4.0)
        }
        return Triple((h + 360.0) % 360.0, s * 100.0, l * 100.0)
    }
}
