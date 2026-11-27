package com.example.healthcheck.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.BuildConfig
import com.example.healthcheck.core.color.ColorChoice
import com.example.healthcheck.core.color.PresetCodec
import com.example.healthcheck.core.color.ThemeTokenGenerator
import com.example.healthcheck.core.csv.DuplicateMode
import com.example.healthcheck.data.ThemeMode
import com.example.healthcheck.data.ThemeSlot
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.DangerTextButton
import com.example.healthcheck.ui.components.PrimaryButton
import com.example.healthcheck.ui.components.ScreenPadding
import com.example.healthcheck.ui.components.SecondaryButton
import com.example.healthcheck.ui.components.SectionTitle
import com.example.healthcheck.ui.theme.AppTheme
import com.example.healthcheck.ui.theme.toAppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenHowItWorks: () -> Unit,
    onOpenCompanionGallery: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val slot by viewModel.slot.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val c = AppTheme.colors
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showDemo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(context.contentResolver, uri)
    }
    // The system file picker grants access to the one chosen file, so no storage permission is needed.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readImportFile(context.contentResolver, uri)
    }
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()

    val s = settings ?: return
    val choice = draft ?: s.choice(slot)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = c.ink)

            AppCard(Modifier.fillMaxWidth()) {
                SectionTitle("Theme")
                Segmented(
                    options = listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "System"),
                    selected = s.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionTitle("Colours")
                Segmented(
                    options = listOf(ThemeSlot.LIGHT to "Light theme", ThemeSlot.DARK to "Dark theme"),
                    selected = slot,
                    onSelect = viewModel::selectSlot,
                )
                Spacer(Modifier.height(14.dp))
                ThemePreview(slot, choice)
                Spacer(Modifier.height(14.dp))
                ColorSlider("Hue", choice.hue, ColorChoice.HUE_MIN..ColorChoice.HUE_MAX, "°", viewModel::commitDraft) {
                    viewModel.onDrag(choice.copy(hue = it))
                }
                ColorSlider("Vibrancy", choice.vibrancy, ColorChoice.VIBRANCY_MIN..ColorChoice.VIBRANCY_MAX, "", viewModel::commitDraft) {
                    viewModel.onDrag(choice.copy(vibrancy = it))
                }
                ColorSlider("Background tint", choice.tint, ColorChoice.TINT_MIN..ColorChoice.TINT_MAX, "", viewModel::commitDraft) {
                    viewModel.onDrag(choice.copy(tint = it))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Surprise me", onClick = viewModel::surprise, modifier = Modifier.weight(1f))
                    SecondaryButton("Reset", onClick = viewModel::reset, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Presets(viewModel, s.presets(slot), choice)
            }

            CompanionSettingsCard(s.companion) { viewModel.updateCompanion(it) }

            AppCard(Modifier.fillMaxWidth()) {
                SectionTitle("Measurement")
                ToggleRow("Show signal details", s.signalDetails, viewModel::setSignalDetails)
                Text(
                    "Shows the brightness, pulse strength, quality score and the reason a reading was rejected, " +
                        "to help you find a finger position that works.",
                    style = MaterialTheme.typography.bodySmall, color = c.sub,
                )
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionTitle("Your data")
                Text(
                    "Everything stays on this phone. Nothing is uploaded or backed up to the cloud.",
                    style = MaterialTheme.typography.bodyMedium, color = c.sub,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    if (busy) "Exporting…" else "Export all data (CSV)",
                    onClick = { exportLauncher.launch("health-check-${LocalDate.now()}.csv") },
                    enabled = !busy && !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    if (importing) "Importing…" else "Import data (CSV)",
                    onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                    enabled = !busy && !importing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                DangerTextButton("Delete all data…", onClick = { confirmDelete = true })
            }

            AppCard(Modifier.fillMaxWidth()) {
                SectionTitle("About")
                TextButton(onClick = onOpenHowItWorks, modifier = Modifier.fillMaxWidth()) {
                    Text("How it works and its limits", color = c.accent, modifier = Modifier.fillMaxWidth())
                }
                VersionRow(onLongPress = { if (BuildConfig.DEBUG) showDemo = true })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Health Check gives estimates for personal trend tracking only. It is not a medical device, " +
                        "does not diagnose any condition and is not medical advice. If you feel unwell, talk to a doctor.",
                    style = MaterialTheme.typography.bodySmall, color = c.sub,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all data?") },
            text = {
                Text(
                    "This permanently removes every entry: all measurements, steps, sleep and bedtimes. " +
                        "Your theme, colour and companion settings are kept. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAll(); confirmDelete = false }) { Text("Delete everything", color = c.warning) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    importPreview?.let { preview ->
        ImportConfirmDialog(preview, onConfirm = viewModel::confirmImport, onDismiss = viewModel::cancelImport)
    }

    importError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::importErrorShown,
            title = { Text("Couldn't import this file") },
            text = { Text("$error\n\nNothing on this phone was changed.") },
            confirmButton = { TextButton(onClick = viewModel::importErrorShown) { Text("OK") } },
        )
    }

    if (showDemo && BuildConfig.DEBUG) {
        AlertDialog(
            onDismissRequest = { showDemo = false },
            title = { Text("Developer: demo data") },
            text = {
                Text(
                    "DEBUG BUILD ONLY. Fills the last 14 days with realistic FAKE entries (marked \"Demo data\") so you can " +
                        "preview the full app. Days that already have your real data are not touched. " +
                        "\"Clear demo data\" removes only the fake rows.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.fillDemo(); showDemo = false }) { Text("Fill 14 days") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDemo = false; onOpenCompanionGallery() }) { Text("Companion gallery") }
                    TextButton(onClick = { viewModel.clearDemo(); showDemo = false }) { Text("Clear demo data", color = c.warning) }
                    TextButton(onClick = { showDemo = false }) { Text("Close") }
                }
            },
        )
    }
}

/** CSV files show up under different types depending on the app that saved them. Every file is checked anyway. */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel", "text/plain", "text/*",
)

@Composable
private fun ImportConfirmDialog(preview: ImportPreview, onConfirm: (DuplicateMode) -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var mode by rememberSaveable(preview) { mutableStateOf(DuplicateMode.KEEP_EXISTING) }
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    fun days(n: Int) = "$n day${if (n == 1) "" else "s"}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val range = if (preview.first == preview.last) "on ${preview.first.format(dates)}"
                else "from ${preview.first.format(dates)} to ${preview.last.format(dates)}"
                Text("Found ${days(preview.days)} $range.")
                if (preview.existing == 0) {
                    Text("None of these dates have data on this phone yet.")
                } else {
                    Text(
                        "${preview.existing} of these dates already ${if (preview.existing == 1) "has" else "have"} " +
                            "data on this phone.",
                    )
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            DuplicateMode.KEEP_EXISTING to "Keep existing days",
                            DuplicateMode.REPLACE_EXISTING to "Replace existing days",
                        ).forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(selected = mode == value, onClick = { mode = value }, role = Role.RadioButton)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = mode == value,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.sub),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                }
                if (preview.demo > 0) Text("${days(preview.demo)} of demo data will be replaced by the imported days.")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = AppTheme.colors
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.accentSoft, activeContentColor = c.onAccentSoft,
                    inactiveContainerColor = c.card, inactiveContentColor = c.ink,
                    activeBorderColor = c.line, inactiveBorderColor = c.line,
                ),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onFinished: () -> Unit,
    onChange: (Float) -> Unit,
) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
        Text("${value.roundToInt()}$suffix", style = MaterialTheme.typography.bodyMedium, color = c.sub)
    }
    val state = remember(range) { SliderState(value, 0, range) }
    // Follow external changes (Surprise me, Reset, presets, switching theme slot).
    SideEffect { if (state.value != value) state.value = value }
    Slider(
        state = state,
        onValueChange = { onChange(it.roundToInt().toFloat()) },
        onValueChangeFinished = onFinished,
        colors = SliderDefaults.colors(
            thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line,
        ),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

/** A miniature screen rendered with the colours being edited (not the app's current theme). */
@Composable
private fun ThemePreview(slot: ThemeSlot, choice: ColorChoice) {
    val tokens = remember(slot, choice) {
        if (slot == ThemeSlot.LIGHT) ThemeTokenGenerator.light(choice) else ThemeTokenGenerator.dark(choice)
    }.toAppColors()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().background(tokens.background, shape).border(1.dp, AppTheme.colors.line, shape).padding(14.dp),
    ) {
        Text("Preview", style = MaterialTheme.typography.labelMedium, color = tokens.sub)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().background(tokens.card, RoundedCornerShape(14.dp))
                .border(1.dp, tokens.line, RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recovery", style = MaterialTheme.typography.labelMedium, color = tokens.sub)
                    Text("78", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = tokens.ink)
                }
                Text(
                    "Low strain",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.onAccentSoft,
                    modifier = Modifier.background(tokens.accentSoft, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text("+4 vs usual", style = MaterialTheme.typography.bodySmall, color = tokens.accent, modifier = Modifier.weight(1f))
                Text("−6 vs usual", style = MaterialTheme.typography.bodySmall, color = tokens.warning)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().background(tokens.accent, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Start measurement", color = tokens.onAccent, style = MaterialTheme.typography.labelLarge) }
        }
    }
}


@Composable
private fun Presets(vm: SettingsViewModel, presets: List<com.example.healthcheck.core.color.ColorPreset>, current: ColorChoice) {
    val c = AppTheme.colors
    var naming by rememberSaveable { mutableStateOf(false) }
    Text("Presets (${presets.size}/${PresetCodec.MAX_PRESETS})", style = MaterialTheme.typography.labelLarge, color = c.sub)
    Spacer(Modifier.height(6.dp))
    if (presets.isEmpty()) {
        Text("Save colours you like to switch back quickly.", style = MaterialTheme.typography.bodySmall, color = c.sub)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { p ->
            val selected = p.choice == current
            InputChip(
                selected = selected,
                onClick = { vm.apply(p.choice) },
                label = { Text(p.name) },
                leadingIcon = {
                    val tokens = ThemeTokenGenerator.light(p.choice)
                    Box(Modifier.width(14.dp).height(14.dp).background(Color(tokens.accent), RoundedCornerShape(50)))
                },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Delete preset ${p.name}",
                        modifier = Modifier.clickable { vm.deletePreset(p) },
                    )
                },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = c.card, labelColor = c.ink, trailingIconColor = c.sub,
                    selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft,
                    selectedTrailingIconColor = c.onAccentSoft,
                ),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    SecondaryButton(
        "Save current as preset",
        onClick = { naming = true },
        enabled = presets.size < PresetCodec.MAX_PRESETS,
        modifier = Modifier.fillMaxWidth(),
    )
    if (naming) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this preset") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PresetCodec.MAX_NAME_LENGTH) },
                    singleLine = true,
                    placeholder = { Text("e.g. Calm blue") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (vm.savePreset(name, current)) naming = false },
                    enabled = PresetCodec.cleanName(name).isNotEmpty(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionRow(onLongPress: () -> Unit) {
    val c = AppTheme.colors
    Text(
        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodyMedium,
        color = c.sub,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
