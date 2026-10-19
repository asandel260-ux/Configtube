package com.studyflow.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(vm: MainVM) {
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    val use24 = vm.settings.use24h
    val timeFmt = DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a")
    val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    val today = LocalDate.now()
    val groups = vm.sessions.groupBy { it.start.day() }.entries.sortedByDescending { it.key.toEpochDay() }
    val last7 = vm.sessions.filter { !it.start.day().isBefore(today.minusDays(6)) }.sumOf { it.durSec }
    val rule = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
        ) {
            item {
                ScreenHeader(
                    "Study log",
                    "${fmtDur(last7)} in the last 7 days · ${vm.sessions.size} sessions",
                )
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "No sessions yet. Start the timer or add one manually.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            groups.forEach { (day, list) ->
                item(key = "h$day") {
                    val label = when (day) {
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> day.format(dayFmt)
                    }
                    Row(
                        Modifier.fillMaxWidth().animateItemPlacement().padding(top = 28.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(fmtDur(list.sumOf { it.durSec }), style = MaterialTheme.typography.labelMedium)
                    }
                }
                items(list, key = { it.id }) { ses ->
                    val sub = vm.subjects.firstOrNull { it.id == ses.subjectId }
                    val at = Instant.ofEpochMilli(ses.start).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
                    Row(
                        Modifier.fillMaxWidth().animateItemPlacement().topRule(rule).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            at,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 1,
                            modifier = Modifier.width(if (use24) 48.dp else 72.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ColorDot(sub?.color ?: 0xFF888888, 8)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    sub?.name ?: "Deleted subject",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                )
                            }
                            val meta = (if (ses.mode == 1) "Pomodoro" else "Stopwatch") +
                                if (ses.note.isNotBlank()) " · ${ses.note}" else ""
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                        }
                        Text(fmtDur(ses.durSec), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { deleting = ses }) {
                            Icon(Ico.Trash, "Delete session", Modifier.size(18.dp), tint = muted)
                        }
                    }
                }
            }
        }
        Fab("Add session", Modifier.align(Alignment.BottomEnd)) { showAdd = true }
    }

    if (showAdd) AddSessionDialog(vm) { showAdd = false }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete session?") },
            text = { Text("${fmtDur(d.durSec)} will be removed from your history.") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(d.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSessionDialog(vm: MainVM, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var subj by remember { mutableStateOf(vm.timer.subjectId) }
    var mins by remember { mutableStateOf("30") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.subjects.filter { !it.archived }.forEach {
                        FilterChip(
                            selected = it.id == subj,
                            onClick = { subj = it.id },
                            label = { Text(it.name) },
                            leadingIcon = { ColorDot(it.color) },
                        )
                    }
                }
                OutlinedTextField(
                    value = mins, onValueChange = { mins = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    },
                ) { Text("Date: $date") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        TimePickerDialog(ctx, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, vm.settings.use24h).show()
                    },
                ) { Text("Start time: $time") }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = mins.toLongOrNull() ?: 0L
                if (m > 0) {
                    val st = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    vm.addSession(subj, st, m * 60, note, 0)
                    onDismiss()
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
