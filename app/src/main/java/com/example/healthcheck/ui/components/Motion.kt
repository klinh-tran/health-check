package com.example.healthcheck.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.healthcheck.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** Counts up from 0 to [target] when first shown (or jumps there when animations are off). */
@Composable
fun animatedNumber(target: Float, durationMs: Int = 900): Float {
    val reduce = AppTheme.reduceMotion
    val anim = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) anim.snapTo(target) else anim.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return anim.value
}

/** Fade + slide in after [index] * 90 ms. */
@Composable
fun Modifier.staggeredEntrance(index: Int): Modifier {
    val reduce = AppTheme.reduceMotion
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay(index * 90L)
            shown = true
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (reduce) 0 else 350), label = "alpha")
    val offset by animateFloatAsState(if (shown) 0f else 1f, tween(if (reduce) 0 else 350, easing = FastOutSlowInEasing), label = "offset")
    val px = with(LocalDensity.current) { 16.dp.toPx() }
    return this.graphicsLayer {
        this.alpha = alpha
        translationY = offset * px
    }
}
