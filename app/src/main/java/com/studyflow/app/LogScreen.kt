package com.studyflow.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(vm: MainVM) {
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    val timeFmt = DateTimeFormatter.ofPattern(if (vm.settings.use24h) "HH:mm" else "h:mm a")
    val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
    val groups = vm.sessions.groupBy { it.start.day() }.entries.sortedByDescending { it.key.toEpochDay() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Study log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { showAdd = true }) { Text("+ Add session") }
            }
        }
        if (groups.isEmpty()) {
            item { Text("No sessions yet. Start the timer or add one manually.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        groups.forEach { (day, list) ->
            item(key = "h$day") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(day.format(dayFmt), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(fmtDur(list.sumOf { it.durSec }), color = MaterialTheme.colorScheme.primary)
                }
            }
            items(list, key = { it.id }) { ses ->
                val sub = vm.subjects.firstOrNull { it.id == ses.subjectId }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(Color(sub?.color ?: 0xFF888888)),
                            contentAlignment = Alignment.Center,
                        ) { Text(sub?.emoji ?: "❔") }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(sub?.name ?: "Deleted subject", fontWeight = FontWeight.SemiBold)
                            val at = Instant.ofEpochMilli(ses.start).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
                            Text(
                                "$at · ${if (ses.mode == 1) "Pomodoro" else "Stopwatch"}" + if (ses.note.isNotBlank()) " · ${ses.note}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(fmtDur(ses.durSec), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { deleting = ses }) { Text("🗑") }
                    }
                }
            }
        }
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
                        FilterChip(selected = it.id == subj, onClick = { subj = it.id }, label = { Text("${it.emoji} ${it.name}") })
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
                    onClick = {
                        DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    },
                ) { Text("Date: $date") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
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
