package com.example.healthcheck.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.core.scoring.DayAssessment
import com.example.healthcheck.core.scoring.DayStatus
import com.example.healthcheck.core.scoring.Direction
import com.example.healthcheck.core.scoring.Metric
import com.example.healthcheck.core.scoring.MetricAssessment
import com.example.healthcheck.core.scoring.ScoringConfig
import com.example.healthcheck.core.companion.CompanionMotion
import com.example.healthcheck.core.companion.CompanionText
import com.example.healthcheck.ui.companion.CompanionPanel
import com.example.healthcheck.ui.companion.LocalCompanionSettings
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.Disclaimer
import com.example.healthcheck.ui.components.LineChart
import com.example.healthcheck.ui.components.PrimaryButton
import com.example.healthcheck.ui.components.ScreenPadding
import com.example.healthcheck.ui.components.animatedNumber
import com.example.healthcheck.ui.components.staggeredEntrance
import com.example.healthcheck.ui.log.LogFormat
import com.example.healthcheck.ui.theme.AppTheme
import com.example.healthcheck.ui.theme.MetricNumberStyle
import java.time.LocalTime
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: HomeViewModel, onStartMeasurement: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AppTheme.colors
    when (val s = state) {
        HomeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = c.accent)
        }
        is HomeState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(s.message, color = c.warning, textAlign = TextAlign.Center)
        }
        is HomeState.Ready -> HomeContent(s, onStartMeasurement)
    }
}

@Composable
private fun HomeContent(s: HomeState.Ready, onStartMeasurement: () -> Unit) {
    val c = AppTheme.colors
    val a = s.assessment
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Text(LogFormat.longDate(s.today), style = MaterialTheme.typography.bodyLarge, color = c.sub)
        }
        CompanionCard(a)
        RecoveryCard(a)
        MetricGrid(a)
        TrendCard(s.trend)
        PrimaryButton("Start measurement", onClick = onStartMeasurement, modifier = Modifier.fillMaxWidth())
        Disclaimer(Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** The pixel companion, reacting to today's verdict. */
@Composable
private fun CompanionCard(a: DayAssessment) {
    val companion = LocalCompanionSettings.current
    val label = if (a.status == DayStatus.SCORED) a.label else null
    val mood = CompanionMotion.moodFor(label)
    val bubble = if (a.status == DayStatus.BUILDING_BASELINE) {
        CompanionText.buildingBaseline(a.baselineDays, ScoringConfig.MIN_BASELINE_DAYS)
    } else {
        CompanionText.bubble(mood, companion.character.voice)
    }
    AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        CompanionPanel(companion, mood, bubbleText = bubble)
    }
}

@Composable
private fun RecoveryCard(a: DayAssessment) {
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion
    val target = when (a.status) {
        DayStatus.SCORED -> (a.score ?: 0) / 100f
        DayStatus.BUILDING_BASELINE -> a.baselineDays.toFloat() / ScoringConfig.MIN_BASELINE_DAYS
        DayStatus.NEEDS_MEASUREMENT -> 0f
    }
    val ring = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) ring.snapTo(target) else ring.animateTo(target, tween(1300, easing = FastOutSlowInEasing))
    }

    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp)) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(c.line, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    if (ring.value > 0f) {
                        drawArc(
                            c.accent, -90f, 360f * ring.value, false, Offset(inset, inset), arc,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (a.status) {
                        DayStatus.SCORED -> {
                            val score = animatedNumber((a.score ?: 0).toFloat(), 1300).roundToInt()
                            Text(
                                "$score", fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                                modifier = Modifier.semantics { contentDescription = "Recovery score ${a.score} out of 100" },
                            )
                            Text("Recovery", style = MaterialTheme.typography.labelLarge, color = c.sub)
                        }
                        DayStatus.BUILDING_BASELINE -> {
                            Text(
                                "${a.baselineDays} of ${ScoringConfig.MIN_BASELINE_DAYS}",
                                fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                            )
                            Text("days measured", style = MaterialTheme.typography.labelLarge, color = c.sub)
                        }
                        DayStatus.NEEDS_MEASUREMENT -> {
                            Text("–", fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = c.sub)
                            Text("Recovery", style = MaterialTheme.typography.labelLarge, color = c.sub)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when (a.status) {
                DayStatus.SCORED -> {
                    Text(a.label?.text.orEmpty(), style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(a.reason.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.sub, textAlign = TextAlign.Center)
                }
                DayStatus.BUILDING_BASELINE -> {
                    Text("Building your baseline", style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { ring.value },
                        modifier = Modifier.fillMaxWidth(0.6f).height(6.dp),
                        color = c.accent,
                        trackColor = c.line,
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Measure on ${ScoringConfig.MIN_BASELINE_DAYS} different days to learn your usual. " +
                            "Until then you'll see your raw values, without a score.",
                        style = MaterialTheme.typography.bodyMedium, color = c.sub, textAlign = TextAlign.Center,
                    )
                }
                DayStatus.NEEDS_MEASUREMENT -> {
                    Text("No measurement today", style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Take a 60-second measurement to see today's recovery.",
                        style = MaterialTheme.typography.bodyMedium, color = c.sub, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(a: DayAssessment) {
    val cards = listOf(Metric.HEART_RATE, Metric.HRV, Metric.SLEEP, Metric.STEPS)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { col, m ->
                    MetricCard(
                        a.metrics.getValue(m),
                        Modifier.weight(1f).staggeredEntrance(row * 2 + col),
                    )
                }
            }
        }
    }
}

private data class MetricInfo(val title: String, val unit: String, val decimals: Int, val missing: String)

private fun info(m: Metric) = when (m) {
    Metric.HEART_RATE -> MetricInfo("Heart rate", "bpm", 0, "Not measured today")
    Metric.HRV -> MetricInfo("HRV", "ms", 0, "Not measured today")
    Metric.SLEEP -> MetricInfo("Sleep", "h", 1, "Not logged today")
    Metric.STEPS -> MetricInfo("Steps", "", 0, "Not logged today")
}

@Composable
private fun MetricCard(m: MetricAssessment, modifier: Modifier) {
    val c = AppTheme.colors
    val i = info(m.metric)
    AppCard(modifier, contentPadding = 16.dp) {
        Text(i.title, style = MaterialTheme.typography.labelLarge, color = c.sub)
        Spacer(Modifier.height(6.dp))
        val v = m.value
        Row(verticalAlignment = Alignment.Bottom) {
            if (v == null) {
                Text("–", style = MetricNumberStyle, color = c.sub)
            } else {
                val shown = animatedNumber(v.toFloat())
                val text = when {
                    m.metric == Metric.STEPS -> LogFormat.steps(shown.roundToInt())
                    i.decimals == 1 -> LogFormat.hours(shown.toDouble())
                    else -> shown.roundToInt().toString()
                }
                Text(text, style = MetricNumberStyle, color = c.ink, maxLines = 1)
                if (i.unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(i.unit, style = MaterialTheme.typography.bodyMedium, color = c.sub, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val diff = m.differenceFromUsual
        val (text, color) = when {
            v == null -> i.missing to c.sub
            m.baseline == null -> "Learning your usual" to c.sub
            diff != null -> LogFormat.delta(diff, i.decimals) to directionColor(m.direction)
            else -> "" to c.sub
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
    }
}

@Composable
private fun directionColor(d: Direction): Color = when (d) {
    Direction.IMPROVING -> AppTheme.colors.accent
    Direction.WORSENING -> AppTheme.colors.warning
    Direction.NEUTRAL -> AppTheme.colors.sub
}

@Composable
private fun TrendCard(trend: List<Int?>) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth().staggeredEntrance(4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recovery · 14 days", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            trend.lastOrNull { it != null }?.let {
                Text("Latest $it", style = MaterialTheme.typography.labelLarge, color = c.sub)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (trend.count { it != null } < 2) {
            Text(
                "Your trend line appears once you have recovery scores on at least two days.",
                style = MaterialTheme.typography.bodyMedium, color = c.sub,
            )
        } else {
            LineChart(
                values = trend.map { it?.toFloat() },
                fixedRange = 0f..100f,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }
}
