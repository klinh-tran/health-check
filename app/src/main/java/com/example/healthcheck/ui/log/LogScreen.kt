package com.example.healthcheck.ui.log

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.core.model.DayRecord
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.PrimaryButton
import com.example.healthcheck.ui.components.ScreenPadding
import com.example.healthcheck.ui.components.SecondaryButton
import com.example.healthcheck.ui.components.SectionTitle
import com.example.healthcheck.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@Composable
fun LogScreen(viewModel: LogViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val c = AppTheme.colors

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().imePadding(),
        ) {
            item {
                Text("Log", style = MaterialTheme.typography.headlineMedium, color = c.ink)
                Text("Steps and sleep from your phone's health app", style = MaterialTheme.typography.bodyMedium, color = c.sub)
            }
            item { EntryForm(form, viewModel) }
            item { Spacer(Modifier.height(4.dp)); SectionTitle("History") }
            val list = history
            if (list != null && list.isEmpty()) {
                item {
                    Text(
                        "No entries yet. Save steps and sleep above, or take a measurement from Home.",
                        style = MaterialTheme.typography.bodyMedium, color = c.sub,
                    )
                }
            }
            items(list.orEmpty(), key = { it.date.toEpochDay() }) { r ->
                HistoryRow(
                    record = r,
                    onEdit = {
                        viewModel.selectDate(r.date)
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    onDelete = { pendingDelete = r.date.toString() },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    pendingDelete?.let { iso ->
        val date = LocalDate.parse(iso)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("All data for ${LogFormat.longDate(date)} (measurement, steps, sleep) will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(date); pendingDelete = null }) {
                    Text("Delete", color = c.warning)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryForm(form: LogForm, vm: LogViewModel) {
    val c = AppTheme.colors
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.sub,
        cursorColor = c.accent, errorBorderColor = c.warning, errorLabelColor = c.warning,
        errorSupportingTextColor = c.warning, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
    )

    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Date", style = MaterialTheme.typography.labelMedium, color = c.sub)
                Text(LogFormat.relativeDate(form.date) + " · " + LogFormat.shortDate(form.date),
                    style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            TextButton(onClick = { showDate = true }) { Text("Change", color = c.accent) }
        }
        if (form.existing?.hasMeasurement == true) {
            Text(
                "This day has a measurement. Saving keeps it.",
                style = MaterialTheme.typography.bodySmall, color = c.sub,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.steps,
            onValueChange = vm::onSteps,
            label = { Text("Steps") },
            placeholder = { Text("e.g. 8250") },
            singleLine = true,
            isError = form.stepsError != null,
            supportingText = { Text(form.stepsError ?: "0 to 100,000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            colors = fieldColors,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.sleep,
            onValueChange = vm::onSleep,
            label = { Text("Sleep (hours)") },
            placeholder = { Text("e.g. 7.5") },
            singleLine = true,
            isError = form.sleepError != null,
            supportingText = { Text(form.sleepError ?: "0 to 24, one decimal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            colors = fieldColors,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bedtime (optional)", style = MaterialTheme.typography.labelMedium, color = c.sub)
                Text(form.bedtime?.let(LogFormat::time) ?: "Not set", style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
            if (form.bedtime != null) TextButton(onClick = { vm.onBedtime(null) }) { Text("Clear", color = c.sub) }
            TextButton(onClick = { showTime = true }) { Text(if (form.bedtime == null) "Set" else "Change", color = c.accent) }
        }
        form.formError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = c.warning, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (form.date != LocalDate.now()) {
                SecondaryButton("Today", onClick = { vm.selectDate(LocalDate.now()) }, modifier = Modifier.weight(1f))
            }
            PrimaryButton(
                if (form.saving) "Saving…" else "Save",
                onClick = vm::save,
                enabled = !form.saving,
                modifier = Modifier.weight(2f),
            )
        }
    }

    if (showDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    LocalDate.ofEpochDay(Math.floorDiv(utcTimeMillis, 86_400_000L)) <= today
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.selectDate(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L))) }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val context = LocalContext.current
        val initial = form.bedtime ?: LocalTime.of(23, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Bedtime") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { vm.onBedtime(LocalTime.of(state.hour, state.minute)); showTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(record: DayRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth().clickable(onClick = onEdit), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(LogFormat.relativeDate(record.date), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Spacer(Modifier.width(8.dp))
                    StatusChip(record)
                }
                Spacer(Modifier.height(4.dp))
                val parts = buildList {
                    if (record.hasMeasurement) {
                        add("${LogFormat.whole(record.heartRateBpm!!)} bpm")
                        add("HRV ${LogFormat.whole(record.hrvRmssdMs!!)} ms")
                    }
                    record.steps?.let { add("${LogFormat.steps(it)} steps") }
                    record.sleepHours?.let { add("${LogFormat.hours(it)} h sleep") }
                }
                Text(parts.joinToString(" · ").ifEmpty { "No values" }, style = MaterialTheme.typography.bodyMedium, color = c.sub)
                if (record.isDemo) Text("Demo data", style = MaterialTheme.typography.labelSmall, color = c.sub)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${LogFormat.longDate(record.date)}", tint = c.sub) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${LogFormat.longDate(record.date)}", tint = c.sub) }
        }
    }
}

@Composable
private fun StatusChip(record: DayRecord) {
    val c = AppTheme.colors
    val measured = record.hasMeasurement
    Text(
        if (measured) "Measured" else "No measurement",
        style = MaterialTheme.typography.labelSmall,
        color = if (measured) c.onAccentSoft else c.sub,
        modifier = Modifier
            .background(if (measured) c.accentSoft else c.background, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
