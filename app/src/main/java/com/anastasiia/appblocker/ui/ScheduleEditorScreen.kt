package com.anastasiia.appblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.ALL_DAYS
import com.anastasiia.appblocker.core.GateAction
import com.anastasiia.appblocker.core.MORNING_PRESET
import com.anastasiia.appblocker.core.NIGHT_PRESET
import com.anastasiia.appblocker.core.ScheduleWindow
import com.anastasiia.appblocker.core.formatMinute
import com.anastasiia.appblocker.core.scheduleSaveNeedsGate

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Per-app window editor. Save applies instantly when it only adds blocking;
 * when it removes any, the new window list becomes a gate request instead.
 */
@Composable
fun ScheduleEditorScreen(
    viewModel: MainViewModel,
    pkg: String,
    label: String,
    onDone: () -> Unit,
    onGate: (GateAction) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val saved = state.schedules[pkg].orEmpty()
    val wasAlways = pkg in state.blockedPackages
    var windows by remember(saved) { mutableStateOf(saved) }
    var picking by remember { mutableStateOf<TimePick?>(null) }

    BackHandler(onBack = onDone)

    val allValid = windows.none { it.isEmpty }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (wasAlways) "Currently blocked always. Saving a schedule replaces that."
                else "Blocked during these windows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { windows = windows + MORNING_PRESET }, modifier = Modifier.weight(1f)) {
                    Text("Morning 08–11", maxLines = 1)
                }
                OutlinedButton(onClick = { windows = windows + NIGHT_PRESET }, modifier = Modifier.weight(1f)) {
                    Text("Night 21:30–01", maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(windows) { index, window ->
                    WindowCard(
                        window = window,
                        onChange = { changed -> windows = windows.toMutableList().also { it[index] = changed } },
                        onRemove = { windows = windows.toMutableList().also { it.removeAt(index) } },
                        onPickStart = { picking = TimePick(index, start = true) },
                        onPickEnd = { picking = TimePick(index, start = false) },
                    )
                }
                item {
                    TextButton(onClick = { windows = windows + ScheduleWindow(ALL_DAYS, 9 * 60, 10 * 60) }) {
                        Text("Add window")
                    }
                }
            }
            if (!allValid) {
                Text(
                    "Each window needs at least one day and a different start and end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (scheduleSaveNeedsGate(state.enabled, wasAlways, saved, windows)) {
                        onGate(GateAction.SetSchedule(pkg, windows))
                    } else {
                        viewModel.setSchedule(pkg, windows)
                        onDone()
                    }
                },
                enabled = allValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    val pick = picking
    val pickedWindow = pick?.let { windows.getOrNull(it.index) }
    if (pick != null && pickedWindow != null) {
        val window = pickedWindow
        val initial = if (pick.start) window.startMinute else window.endMinute
        TimeDialog(
            title = if (pick.start) "Starts" else "Ends",
            initialMinute = initial,
            onDismiss = { picking = null },
            onConfirm = { minute ->
                val changed = if (pick.start) window.copy(startMinute = minute) else window.copy(endMinute = minute)
                windows = windows.toMutableList().also { it[pick.index] = changed }
                picking = null
            },
        )
    }
}

private data class TimePick(val index: Int, val start: Boolean)

@Composable
private fun WindowCard(
    window: ScheduleWindow,
    onChange: (ScheduleWindow) -> Unit,
    onRemove: () -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Seven chips must share the card width on narrow phones, so each takes an equal slice.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (day in 1..7) {
                    FilterChip(
                        selected = day in window.days,
                        onClick = {
                            val days = if (day in window.days) window.days - day else window.days + day
                            onChange(window.copy(days = days))
                        },
                        label = { Text(DAY_LETTERS[day - 1], maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickStart) { Text(formatMinute(window.startMinute)) }
                    Text("to")
                    OutlinedButton(onClick = onPickEnd) { Text(formatMinute(window.endMinute)) }
                    if (window.crossesMidnight) {
                        Text(
                            "next day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
