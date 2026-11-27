package com.example.healthcheck.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.healthcheck.core.companion.AnimationLevel
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionMood
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.core.companion.CompanionText
import com.example.healthcheck.ui.companion.CompanionPanel
import com.example.healthcheck.ui.companion.CompanionThumbnail
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.SectionTitle
import com.example.healthcheck.ui.theme.AppTheme

@Composable
fun CompanionSettingsCard(settings: CompanionSettings, onChange: ((CompanionSettings) -> CompanionSettings) -> Unit) {
    val c = AppTheme.colors
    var previewMood by rememberSaveable { mutableStateOf(CompanionMood.GREAT) }
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Companion")
        // Live preview; key() restarts the animation (and confetti etc.) when the mood chip changes.
        key(previewMood) { CompanionPanel(settings, previewMood) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            listOf(CompanionMood.GREAT to "Great", CompanionMood.NORMAL to "Normal", CompanionMood.TIRED to "Tired")
                .forEach { (mood, label) ->
                    FilterChip(
                        selected = previewMood == mood,
                        onClick = { previewMood = mood },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = c.card, labelColor = c.ink,
                            selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft,
                        ),
                    )
                }
        }

        Spacer(Modifier.height(16.dp))
        Text("Character", style = MaterialTheme.typography.labelLarge, color = c.sub)
        Spacer(Modifier.height(8.dp))
        CharacterPicker(settings.character) { ch -> onChange { it.copy(character = ch) } }

        Spacer(Modifier.height(16.dp))
        Text("Animation", style = MaterialTheme.typography.labelLarge, color = c.sub)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val levels = AnimationLevel.entries
            levels.forEachIndexed { i, level ->
                SegmentedButton(
                    selected = settings.level == level,
                    onClick = { onChange { it.copy(level = level) } },
                    shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = c.accentSoft, activeContentColor = c.onAccentSoft,
                        inactiveContainerColor = c.card, inactiveContentColor = c.ink,
                        activeBorderColor = c.line, inactiveBorderColor = c.line,
                    ),
                ) { Text(level.label, maxLines = 1) }
            }
        }
        if (AppTheme.reduceMotion) {
            Spacer(Modifier.height(6.dp))
            Text(
                "System \"Remove animations\" is on, so your companion stays still.",
                style = MaterialTheme.typography.bodySmall, color = c.sub,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Accessories", style = MaterialTheme.typography.labelLarge, color = c.sub)
        val a = settings.accessories
        ToggleRow("Orange", a.orange) { v -> onChange { it.copy(accessories = it.accessories.copy(orange = v)) } }
        ToggleRow("Party hat", a.hat) { v -> onChange { it.copy(accessories = it.accessories.copy(hat = v)) } }
        ToggleRow("Sunglasses", a.sunglasses) { v -> onChange { it.copy(accessories = it.accessories.copy(sunglasses = v)) } }
        ToggleRow("Bow", a.bow) { v -> onChange { it.copy(accessories = it.accessories.copy(bow = v)) } }

        Spacer(Modifier.height(8.dp))
        ToggleRow("Speech bubbles", settings.speechBubbles) { v -> onChange { it.copy(speechBubbles = v) } }

        Spacer(Modifier.height(10.dp))
        var name by rememberSaveable { mutableStateOf(settings.name) }
        OutlinedTextField(
            value = name,
            onValueChange = { raw ->
                name = CompanionText.cleanName(raw)
                onChange { it.copy(name = name) }
            },
            singleLine = true,
            label = { Text("Buddy name") },
            placeholder = { Text(settings.character.displayName) },
            supportingText = { Text("${name.length}/${CompanionText.MAX_NAME_LENGTH} · empty uses the character's name") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CharacterPicker(selected: CompanionCharacter, onSelect: (CompanionCharacter) -> Unit) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompanionCharacter.pickerOrder.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch ->
                    val isSelected = ch == selected
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .background(if (isSelected) c.accentSoft else c.background, shape)
                            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) c.accent else c.line, shape)
                            .clickable(role = Role.RadioButton) { onSelect(ch) }
                            .semantics {
                                this.selected = isSelected
                                contentDescription = "${ch.displayName}, ${ch.species}"
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CompanionThumbnail(ch)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            ch.displayName, style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) c.onAccentSoft else c.ink, textAlign = TextAlign.Center, maxLines = 1,
                        )
                    }
                }
                // Keep the last row's cells the same width as the full rows.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.sub, uncheckedTrackColor = c.card, uncheckedBorderColor = c.line,
            ),
        )
    }
}
