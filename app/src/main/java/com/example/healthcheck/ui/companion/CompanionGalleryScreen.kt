package com.example.healthcheck.ui.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.healthcheck.core.companion.Accessories
import com.example.healthcheck.core.companion.AnimationLevel
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionMood
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.theme.AppTheme

/**
 * DEBUG BUILDS ONLY (opened from the hidden developer dialog: long-press the version in Settings).
 * Shows all 7 characters side by side with switchable mood, animation level and accessories.
 */
@Composable
fun CompanionGalleryScreen(onBack: () -> Unit) {
    val c = AppTheme.colors
    var mood by rememberSaveable { mutableStateOf(CompanionMood.GREAT) }
    var level by rememberSaveable { mutableStateOf(AnimationLevel.PLAYFUL) }
    var orange by rememberSaveable { mutableStateOf(false) }
    var hat by rememberSaveable { mutableStateOf(false) }
    var sunglasses by rememberSaveable { mutableStateOf(false) }
    var bow by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
            Text("Companion gallery (debug)", style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompanionMood.entries.forEach { m ->
                    GalleryChip(m.name.lowercase(), mood == m) { mood = m }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimationLevel.entries.forEach { l -> GalleryChip(l.label, level == l) { level = l } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GalleryChip("Orange", orange) { orange = !orange }
                GalleryChip("Party hat", hat) { hat = !hat }
                GalleryChip("Sunglasses", sunglasses) { sunglasses = !sunglasses }
                GalleryChip("Bow", bow) { bow = !bow }
            }
            val accessories = Accessories(orange = orange, hat = hat, sunglasses = sunglasses, bow = bow)
            CompanionCharacter.pickerOrder.forEach { ch ->
                AppCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                    Text("${ch.displayName} · ${ch.species}", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    val settings = CompanionSettings(character = ch, level = level, accessories = accessories)
                    // Restart the animation on mood/level change so entry effects (confetti) replay.
                    key(mood, level) { CompanionPanel(settings, mood, showEcg = mood == CompanionMood.MEASURING) }
                }
            }
        }
    }
}

@Composable
private fun GalleryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.card, labelColor = c.ink,
            selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft,
        ),
    )
}
