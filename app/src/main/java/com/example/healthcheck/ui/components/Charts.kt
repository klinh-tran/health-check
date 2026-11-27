package com.example.healthcheck.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.example.healthcheck.ui.theme.AppTheme

data class Band(val low: Float, val high: Float)

/**
 * Minimal line chart drawn with Canvas. [values] are evenly spaced days (null = no data that day,
 * which breaks the line). The optional [band] is drawn as a subtle horizontal strip (the baseline).
 * The line draws in from left to right whenever [animationKey] changes.
 */
@Composable
fun LineChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    band: Band? = null,
    fixedRange: ClosedFloatingPointRange<Float>? = null,
    animationKey: Any? = values,
    showDots: Boolean = true,
    strokeWidthDp: Float = 2.5f,
) {
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(animationKey) {
        if (reduce) progress.snapTo(1f) else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier) {
        val present = values.filterNotNull()
        val padV = 6.dp.toPx()
        val (lo, hi) = run {
            if (fixedRange != null) return@run fixedRange.start to fixedRange.endInclusive
            val all = present + listOfNotNull(band?.low, band?.high)
            if (all.isEmpty()) return@run 0f to 1f
            var mn = all.min()
            var mx = all.max()
            if (mx - mn < 1e-3f) { mn -= 1f; mx += 1f }
            val pad = (mx - mn) * 0.12f
            (mn - pad) to (mx + pad)
        }
        fun y(v: Float) = padV + (size.height - 2 * padV) * (1f - (v - lo) / (hi - lo))
        val n = values.size
        fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)

        band?.let {
            val top = y(it.high).coerceIn(0f, size.height)
            val bottom = y(it.low).coerceIn(0f, size.height)
            drawRect(c.accentSoft, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(1f)), alpha = 0.7f)
        }
        // Baseline grid line
        drawLine(c.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())

        val path = Path()
        var penDown = false
        values.forEachIndexed { i, v ->
            if (v == null) { penDown = false; return@forEachIndexed }
            if (!penDown) { path.moveTo(x(i), y(v)); penDown = true } else path.lineTo(x(i), y(v))
        }
        clipRect(right = size.width * progress.value + 8.dp.toPx()) {
            drawPath(path, c.accent, style = Stroke(strokeWidthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (showDots) {
                values.forEachIndexed { i, v ->
                    if (v != null) {
                        // Isolated points (no neighbours) would otherwise be invisible.
                        val isolated = (i == 0 || values[i - 1] == null) && (i == n - 1 || values[i + 1] == null)
                        drawCircle(c.card, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
                        drawCircle(c.accent, radius = if (isolated) 4.dp.toPx() else 3.dp.toPx(), center = Offset(x(i), y(v)))
                    }
                }
            }
        }
    }
}
