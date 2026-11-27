package com.example.healthcheck.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.example.healthcheck.core.color.ColorChoice
import com.example.healthcheck.core.color.ThemeTokenGenerator
import com.example.healthcheck.core.companion.Accessories
import com.example.healthcheck.core.companion.CompanionCharacter
import com.example.healthcheck.core.companion.CompanionMood
import com.example.healthcheck.core.companion.CompanionSettings
import com.example.healthcheck.ui.theme.AppTheme
import com.example.healthcheck.ui.theme.LocalAppColors
import com.example.healthcheck.ui.theme.LocalReduceMotion
import com.example.healthcheck.ui.theme.Typography
import com.example.healthcheck.ui.theme.toAppColors

/** Every character x every mood (7 x 5 = 35 previews). */
class CharacterMoodProvider : PreviewParameterProvider<Pair<CompanionCharacter, CompanionMood>> {
    override val values = CompanionCharacter.pickerOrder.asSequence()
        .flatMap { ch -> CompanionMood.entries.asSequence().map { ch to it } }
}

/** Every accessory on every character (4 x 7 = 28 previews). */
class CharacterAccessoryProvider : PreviewParameterProvider<Pair<CompanionCharacter, Pair<String, Accessories>>> {
    override val values = CompanionCharacter.pickerOrder.asSequence()
        .flatMap { ch -> Accessories.ALL_SINGLE.asSequence().map { ch to it } }
}

class CharacterProvider : PreviewParameterProvider<CompanionCharacter> {
    override val values = CompanionCharacter.pickerOrder.asSequence()
}

@Composable
private fun PreviewTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val colors = (if (dark) ThemeTokenGenerator.dark(ColorChoice.DEFAULT) else ThemeTokenGenerator.light(ColorChoice.DEFAULT))
        .toAppColors()
    CompositionLocalProvider(LocalAppColors provides colors, LocalReduceMotion provides false) {
        MaterialTheme(typography = Typography) {
            Column(Modifier.background(AppTheme.colors.card).padding(12.dp).width(240.dp)) { content() }
        }
    }
}

@Composable
private fun PreviewCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.sub)
}

@Preview(name = "Character x mood")
@Composable
private fun CompanionMoodPreview(
    @PreviewParameter(CharacterMoodProvider::class) item: Pair<CompanionCharacter, CompanionMood>,
) {
    val (ch, mood) = item
    PreviewTheme {
        PreviewCaption("${ch.displayName} · ${mood.name.lowercase()}")
        // A few ticks in, so the bob, confetti and particles are visible in the still preview.
        CompanionPanel(
            CompanionSettings(character = ch), mood,
            showEcg = mood == CompanionMood.MEASURING, warmupTicks = 3,
        )
    }
}

@Preview(name = "Character x accessory")
@Composable
private fun CompanionAccessoryPreview(
    @PreviewParameter(CharacterAccessoryProvider::class) item: Pair<CompanionCharacter, Pair<String, Accessories>>,
) {
    val (ch, acc) = item
    PreviewTheme {
        PreviewCaption("${ch.displayName} · ${acc.first}")
        CompanionPanel(CompanionSettings(character = ch, accessories = acc.second, speechBubbles = false), CompanionMood.NORMAL)
    }
}

@Preview(name = "Dark theme")
@Composable
private fun CompanionDarkPreview(@PreviewParameter(CharacterProvider::class) ch: CompanionCharacter) {
    PreviewTheme(dark = true) {
        CompanionPanel(CompanionSettings(character = ch), CompanionMood.GREAT, warmupTicks = 3)
    }
}

@Preview(name = "Picker thumbnails")
@Composable
private fun CompanionThumbnailPreview(@PreviewParameter(CharacterProvider::class) ch: CompanionCharacter) {
    PreviewTheme { CompanionThumbnail(ch) }
}
