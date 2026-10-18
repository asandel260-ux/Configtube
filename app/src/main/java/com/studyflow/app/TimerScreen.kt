package com.studyflow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun TimerScreen(vm: MainVM) {
    val t = vm.timer
    val s = vm.settings
    val el = vm.elapsed()
    val target = vm.targetMs()
    val pomo = t.mode == 1
    val shown = if (pomo) (target - el).coerceAtLeast(0) else el
    val progress = if (pomo && target > 0) (el.toFloat() / target).coerceIn(0f, 1f)
    else ((el % 3_600_000L) / 3_600_000f)
    val subject = vm.subjects.firstOrNull { it.id == t.subjectId } ?: vm.subjects.first()
    val idle = !t.running && t.baseMs == 0L
    var askNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val ringColor = when {
        t.phase != 0 -> Color(0xFF43A047)
        s.subjectColorRing -> Color(subject.color)
        else -> MaterialTheme.colorScheme.primary
    }
    val phaseLabel = when {
        !pomo -> "Stopwatch"
        t.phase == 0 -> "Focus · round ${t.cycles % s.cycles + 1} of ${s.cycles}"
        t.phase == 1 -> "Short break"
        else -> "Long break"
    }

    val todaySec = vm.sessions.secOn(LocalDate.now()) + if (t.phase == 0) el / 1000 else 0
    val goalSec = s.dailyGoal * 60L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !pomo, enabled = idle, onClick = { vm.setMode(0) }, label = { Text("Stopwatch") })
            FilterChip(selected = pomo, enabled = idle, onClick = { vm.setMode(1) }, label = { Text("Pomodoro") })
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.subjects.filter { !it.archived }, key = { it.id }) { sub ->
                FilterChip(
                    selected = sub.id == t.subjectId,
                    enabled = !t.running,
                    onClick = { vm.selectSubject(sub.id) },
                    label = { Text(sub.name) },
                    leadingIcon = { ColorDot(sub.color) },
                )
            }
        }

        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.surfaceVariant
            Canvas(Modifier.fillMaxSize()) {
                val sw = s.ringWidth.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(track, 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (progress > 0f) {
                    drawArc(ringColor, -90f, 360f * progress, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(subject.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clock(shown, s.showSeconds), fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Text(phaseLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.reset() }, enabled = !idle) { Text("Reset") }
            Button(
                onClick = { if (t.running) vm.pause() else vm.start() },
                modifier = Modifier.height(56.dp).widthIn(min = 140.dp),
            ) {
                IconLabel(if (t.running) Ico.Pause else Ico.Play, if (t.running) "Pause" else if (idle) "Start" else "Resume")
            }
            if (t.phase == 0) {
                OutlinedButton(
                    onClick = { if (s.askNote) askNote = true else vm.finish("") },
                    enabled = !idle,
                ) { Text("Finish") }
            } else {
                OutlinedButton(onClick = { vm.skipBreak() }) { Text("Skip") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Today", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        if (goalSec > 0) "${fmtDur(todaySec)} / ${fmtDur(goalSec)}" else fmtDur(todaySec),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (goalSec > 0) {
                    LinearProgressIndicator(
                        progress = { (todaySec.toFloat() / goalSec).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
            }
        }
    }

    if (askNote) {
        AlertDialog(
            onDismissRequest = { askNote = false },
            title = { Text("Session note") },
            text = {
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("What did you work on?") }, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.finish(note); note = ""; askNote = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askNote = false }) { Text("Cancel") } },
        )
    }
}
