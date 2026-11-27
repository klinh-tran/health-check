package com.example.healthcheck.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.healthcheck.core.scoring.ScoringConfig
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.theme.AppTheme

@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c = AppTheme.colors
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
            Text("How it works", style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(
                "Measuring your pulse with the camera",
                "The flash shines light into your fingertip and the camera sees the light that comes back. " +
                    "With every heartbeat a little more blood flows into the fingertip and absorbs a little more light, " +
                    "so the image gets very slightly darker and lighter in rhythm with your pulse. This is called " +
                    "photoplethysmography (PPG). The app averages the colour of the middle of each frame, filters out " +
                    "everything slower or faster than a heartbeat (about 42–210 beats per minute), finds each beat and " +
                    "measures the time between beats.",
            )
            Section(
                "Heart rate and HRV",
                "Heart rate is the average number of beats per minute over the measurement. HRV (heart rate " +
                    "variability) is reported as RMSSD: how much the time between one beat and the next changes, in " +
                    "milliseconds. Higher HRV compared with your own usual generally means you are more recovered. " +
                    "HRV differs a lot between people, so only compare it with yourself.",
            )
            Section(
                "Quality checks",
                "The first 5 seconds are a warm-up and are ignored. A reading is only saved if the fingertip covered the " +
                    "lens the whole time, there was little movement, the pulse was regular and clear, and enough clean " +
                    "beats were found. Beats that look implausible are dropped. If too many are dropped, the reading is " +
                    "rejected rather than saved. Breathing rate is only shown when it can be estimated confidently; " +
                    "otherwise you'll see \"–\".",
            )
            Section(
                "Your baseline",
                "After ${ScoringConfig.MIN_BASELINE_DAYS} days with data, your usual for each value is the median of your most " +
                    "recent ${ScoringConfig.MAX_BASELINE_DAYS} days (today is never part of its own baseline). The typical spread " +
                    "is measured with the median absolute deviation, which isn't thrown off by one unusual day. Each " +
                    "value is baselined separately, so a missing sleep entry doesn't affect your HRV baseline.",
            )
            Section(
                "What the Recovery score means",
                "Each of today's values is compared with your usual. Lower HRV, higher resting heart rate and shorter " +
                    "sleep count as more strain; steps only count when they are far above or below your normal. These " +
                    "are combined (HRV 40%, heart rate 30%, sleep 20%, steps 10%) into a Recovery score from 0 to 100, " +
                    "where a day just like your usual scores about ${ScoringConfig.SCORE_AT_BASELINE.toInt()}. " +
                    "${ScoringConfig.LOW_STRAIN_MIN_SCORE} or more is \"Low strain\", " +
                    "${ScoringConfig.NORMAL_STRAIN_MIN_SCORE}–${ScoringConfig.LOW_STRAIN_MIN_SCORE - 1} is \"Normal strain\" and " +
                    "below ${ScoringConfig.NORMAL_STRAIN_MIN_SCORE} is \"Elevated strain\". There's no score on days without a valid " +
                    "measurement. One day says little on its own: look at the trend.",
            )
            Section(
                "Limits of a phone camera",
                "• Camera PPG is less accurate than a chest strap or medical ECG, especially for HRV. Small timing errors " +
                    "(the camera takes about 30 pictures per second) add noise to HRV.\n" +
                    "• Movement, pressing too hard, cold fingers, talking, and poor finger placement all distort the signal.\n" +
                    "• Irregular heart rhythms can't be measured reliably and are often rejected.\n" +
                    "• The phone's camera adjusts brightness automatically; the app tries to lock this after a couple of " +
                    "seconds, but not every camera honours that request.\n" +
                    "• The flash warms up during a measurement; that's normal.\n" +
                    "• Steps and sleep are only as accurate as the numbers you type in.",
            )
            Section(
                "Not medical advice",
                "Health Check is a personal trend tool, not a medical device. It does not diagnose, treat or rule out any " +
                    "condition, and it does not judge your mood or health from a single day. If you feel unwell or worried " +
                    "about your heart, please contact a healthcare professional.",
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.sub)
    }
}
