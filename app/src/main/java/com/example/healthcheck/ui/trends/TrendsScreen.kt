package com.example.healthcheck.ui.trends

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.Disclaimer
import com.example.healthcheck.ui.components.LineChart
import com.example.healthcheck.ui.components.PrimaryButton
import com.example.healthcheck.ui.components.ScreenPadding
import com.example.healthcheck.ui.log.LogFormat
import com.example.healthcheck.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(viewModel: TrendsViewModel, onStartMeasurement: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Trends", style = MaterialTheme.typography.headlineMedium, color = c.ink)
        when {
            s.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            s.error != null -> Text(s.error.orEmpty(), color = c.warning)
            !s.hasAnyData -> EmptyState(onStartMeasurement)
            else -> {
                Controls(s, viewModel)
                ChartCard(s)
                Disclaimer()
            }
        }
    }
}

@Composable
private fun Controls(s: TrendsState, vm: TrendsViewModel) {
    val c = AppTheme.colors
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(14, 30).forEachIndexed { i, n ->
            SegmentedButton(
                selected = s.days == n,
                onClick = { vm.selectDays(n) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.accentSoft, activeContentColor = c.onAccentSoft,
                    inactiveContainerColor = c.card, inactiveContentColor = c.ink,
                    activeBorderColor = c.line, inactiveBorderColor = c.line,
                ),
            ) { Text("$n days") }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendMetric.entries.forEach { m ->
            FilterChip(
                selected = s.metric == m,
                onClick = { vm.selectMetric(m) },
                label = { Text(m.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = c.card, labelColor = c.ink,
                    selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = s.metric == m, borderColor = c.line, selectedBorderColor = c.accentSoft,
                ),
            )
        }
    }
}

private fun fmt(v: Float, m: TrendMetric): String = when {
    m == TrendMetric.STEPS -> LogFormat.steps(v.roundToInt())
    m.decimals == 1 -> LogFormat.hours(v.toDouble())
    else -> v.roundToInt().toString()
}

@Composable
private fun ChartCard(s: TrendsState) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth()) {
        Text(s.metric.label, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("Last ${s.days} days", style = MaterialTheme.typography.bodySmall, color = c.sub)
        Spacer(Modifier.height(12.dp))
        if (s.values.all { it == null }) {
            Text(
                when (s.metric) {
                    TrendMetric.RECOVERY -> "No recovery scores in this period yet. Scores need 5 days of measurements plus a measurement on the day."
                    TrendMetric.SLEEP, TrendMetric.STEPS -> "Nothing logged in this period. Add steps and sleep in the Log tab."
                    else -> "No measurements in this period. Take one from Home."
                },
                style = MaterialTheme.typography.bodyMedium, color = c.sub,
            )
            return@AppCard
        }
        val unit = if (s.metric.unit.isEmpty()) "" else " ${s.metric.unit}"
        val range = if (s.metric == TrendMetric.RECOVERY) 0f..100f else null
        Row {
            Column(Modifier.weight(1f)) {
                Text("Average", style = MaterialTheme.typography.labelMedium, color = c.sub)
                Text(s.average?.let { fmt(it, s.metric) + unit } ?: "–", style = MaterialTheme.typography.titleLarge, color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("Latest", style = MaterialTheme.typography.labelMedium, color = c.sub)
                Text(s.latest?.let { fmt(it, s.metric) + unit } ?: "–", style = MaterialTheme.typography.titleLarge, color = c.ink)
            }
        }
        Spacer(Modifier.height(14.dp))
        LineChart(
            values = s.values,
            band = s.band,
            fixedRange = range,
            animationKey = Triple(s.metric, s.days, s.values),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Text(s.dates.firstOrNull()?.let(LogFormat::shortDate).orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.sub, modifier = Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = c.sub)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 18.dp, height = 10.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(c.accentSoft) }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.band != null) "Your usual range" + (s.band.let { " (${fmt(it.low, s.metric)}–${fmt(it.high, s.metric)}$unit)" })
                else "Your usual range appears after 5 days of data",
                style = MaterialTheme.typography.bodySmall, color = c.sub,
            )
        }
    }
}

@Composable
private fun EmptyState(onStartMeasurement: () -> Unit) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your trends will live here", style = MaterialTheme.typography.titleLarge, color = c.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Take your first 60-second measurement to start building your personal baseline. " +
                    "Each day adds a point to these charts.",
                style = MaterialTheme.typography.bodyMedium, color = c.sub, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Take your first measurement", onClick = onStartMeasurement, modifier = Modifier.fillMaxWidth())
        }
    }
}
