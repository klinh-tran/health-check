package com.example.healthcheck.ui.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.healthcheck.core.color.ColorMath
import com.example.healthcheck.core.companion.AnimationLevel
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionEngine
import com.example.healthcheck.core.companion.CompanionMood
import com.example.healthcheck.core.companion.CompanionRenderer
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.core.companion.CompanionStage
import com.example.healthcheck.core.companion.CompanionText
import com.example.healthcheck.core.companion.FrameSpec
import com.example.healthcheck.core.companion.PixelEcg
import com.example.healthcheck.core.companion.StagePixel
import com.example.healthcheck.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The user's saved companion settings, provided once at the app root. */
val LocalCompanionSettings = staticCompositionLocalOf { CompanionSettings() }

/** Holds the stepped animation state for one companion on screen. */
class CompanionClock internal constructor(val engine: CompanionEngine) {
    /** Mirrors engine.tick; reading it in a draw lambda redraws on every tick. */
    internal val frame: MutableIntState = mutableIntStateOf(engine.tick)
    /** Tick of the last detected heartbeat (measurement), or null. */
    internal var lastBeatTick: Int? = null
}

/**
 * A 10 fps stepped clock. Runs only while the screen is RESUMED (so it pauses when the screen is off or the
 * app is in the background) and never when the system "Remove animations" setting is on.
 */
@Composable
fun rememberCompanionClock(mood: CompanionMood, level: AnimationLevel, warmupTicks: Int = 0): CompanionClock {
    val reduce = AppTheme.reduceMotion
    val inPreview = LocalInspectionMode.current
    val clock = remember {
        val engine = CompanionEngine()
        repeat(warmupTicks) { engine.step(mood, level) }
        CompanionClock(engine)
    }
    val currentMood by rememberUpdatedState(mood)
    val currentLevel by rememberUpdatedState(level)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, reduce, inPreview, lifecycle) {
        if (reduce || inPreview) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(CompanionStage.TICK_MS)
                clock.engine.step(currentMood, currentLevel)
                clock.frame.intValue = clock.engine.tick
            }
        }
    }
    return clock
}

/** Largest whole-pixel size not above [preferred] that fits [columns] into [maxWidthPx]. */
private fun pixelSizePx(preferredPx: Float, maxWidthPx: Float, columns: Int): Float =
    max(1f, floor(min(preferredPx, maxWidthPx / columns)))

private fun DrawScope.drawPixels(pixels: List<StagePixel>, p: Float, columns: Int, rows: Int) {
    clipRect(0f, 0f, columns * p, rows * p) {
        for (px in pixels) {
            if (px.x < 0 || px.y < 0 || px.x >= columns || px.y >= rows) continue
            // Whole-pixel aligned rectangles: crisp nearest-neighbour look, no anti-aliased edges.
            drawRect(Color(px.color), Offset(px.x * p, px.y * p), Size(p, p))
        }
    }
}

/** The 24x22 pixel stage: character, face, heart hug, accessories and particles. */
@Composable
fun CompanionStageView(
    character: CompanionCharacter,
    mood: CompanionMood,
    settings: CompanionSettings,
    clock: CompanionClock,
    modifier: Modifier = Modifier,
    description: String? = null,
    pixel: Dp = 8.dp,
    showEcg: Boolean = false,
) {
    val reduce = AppTheme.reduceMotion
    val stageColor = AppTheme.colors.background
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val p = with(density) { pixelSizePx(pixel.toPx(), constraints.maxWidth.toFloat(), CompanionStage.COLUMNS) }
        val w = with(density) { (p * CompanionStage.COLUMNS).toDp() }
        val h = with(density) { (p * CompanionStage.ROWS).toDp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier
                    .size(w, h)
                    .background(stageColor, RoundedCornerShape(12.dp))
                    .semantics { contentDescription = description ?: character.displayName },
            ) {
                val t = if (reduce) 0 else clock.frame.intValue
                val level = if (reduce) AnimationLevel.OFF else settings.level
                val beat = clock.lastBeatTick
                val bright = if (beat != null && t - beat in 0 until 20) (t - beat) < 3 else null
                val pixels = CompanionRenderer.frame(
                    FrameSpec(character, mood, level, settings.accessories, t, measureHeartBright = bright),
                )
                drawPixels(pixels, p, CompanionStage.COLUMNS, CompanionStage.ROWS)
                if (!reduce && level != AnimationLevel.OFF) {
                    drawPixels(clock.engine.pixels(), p, CompanionStage.COLUMNS, CompanionStage.ROWS)
                }
            }
            if (showEcg) {
                Spacer(Modifier.height(6.dp))
                val ep = pixelSizePx(p, p * CompanionStage.COLUMNS, PixelEcg.COLUMNS)
                val ew = with(density) { (ep * PixelEcg.COLUMNS).toDp() }
                val eh = with(density) { (ep * PixelEcg.ROWS).toDp() }
                Canvas(
                    Modifier.size(ew, eh).background(stageColor, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Heartbeat line" },
                ) {
                    val still = reduce || settings.level == AnimationLevel.OFF
                    val t = if (still) 0 else clock.frame.intValue
                    val pixels = PixelEcg.pixels(t).map { StagePixel(it.x, it.y, CompanionStage.RED) }
                    drawPixels(pixels, ep, PixelEcg.COLUMNS, PixelEcg.ROWS)
                }
            }
        }
    }
}

/** 16x16 sprite with open eyes at 4 dp per pixel (character picker). */
@Composable
fun CompanionThumbnail(character: CompanionCharacter, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val p = with(density) { max(1f, floor(4.dp.toPx())) }
    val side = with(density) { (p * 16).toDp() }
    val pixels = remember(character) { CompanionRenderer.thumbnail(character) }
    Canvas(modifier.size(side)) { drawPixels(pixels, p, 16, 16) }
}

/** Speech bubble: theme-coloured, text colour picked by the automatic black/white contrast rule. */
@Composable
fun SpeechBubble(name: String, text: String, modifier: Modifier = Modifier) {
    val bubble = AppTheme.colors.accentSoft
    val ink = Color(ColorMath.onColor(bubble.toArgb()))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 320.dp).background(bubble, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ink)
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = ink, textAlign = TextAlign.Center)
        }
        // Pixel-stepped tail pointing down at the companion.
        Canvas(Modifier.size(16.dp, 8.dp)) {
            val s = floor(4.dp.toPx())
            val cx = size.width / 2
            drawRect(bubble, Offset(cx - 2 * s, 0f), Size(4 * s, s))
            drawRect(bubble, Offset(cx - s, s), Size(2 * s, s))
        }
    }
}

/** Bubble (optional) + stage, as used on Home, Measurement and in Settings. */
@Composable
fun CompanionPanel(
    settings: CompanionSettings,
    mood: CompanionMood,
    modifier: Modifier = Modifier,
    bubbleText: String = CompanionText.bubble(mood, settings.character.voice),
    beatCount: Int? = null,
    showEcg: Boolean = false,
    warmupTicks: Int = 0,
) {
    val clock = rememberCompanionClock(mood, settings.level, warmupTicks)
    if (beatCount != null) {
        LaunchedEffect(beatCount) { if (beatCount > 0) clock.lastBeatTick = clock.engine.tick }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (settings.speechBubbles) {
            SpeechBubble(settings.displayName, bubbleText)
            Spacer(Modifier.height(2.dp))
        }
        CompanionStageView(
            character = settings.character,
            mood = mood,
            settings = settings,
            clock = clock,
            description = CompanionText.describe(settings.displayName, settings.character, mood) +
                if (settings.speechBubbles) "" else ". $bubbleText",
            showEcg = showEcg,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
