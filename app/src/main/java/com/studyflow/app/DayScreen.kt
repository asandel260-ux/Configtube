@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DayScreen(vm: MainVM, pub: String, date: LocalDate, onBack: () -> Unit) {
    val social = vm.social
    val snap = social.snap
    val idx = rememberIndex(vm, pub)
    val day = idx.day(date)
    val sessions = day?.sessions ?: emptyList()
    val fmt = DateTimeFormatter.ofPattern(if (vm.settings.use24h) "HH:mm" else "h:mm a")
    fun at(min: Int): String = LocalTime.of((min / 60).coerceIn(0, 23), min % 60).format(fmt)
    val title = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", " + date.dayOfMonth + " " +
        date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + date.year
    val rule = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pub == social.me) Avatar(social.cfg.name.ifBlank { "You" }, social.cfg.color, social.cfg.glyph, 28.dp)
                else UserAvatar(snap, pub, 28.dp)
                Text(
                    if (pub == social.me) "You" else nameOf(snap, pub),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Caps("Studied")
            Text(if ((day?.sec ?: 0L) > 0) fmtDur(day!!.sec) else "No study", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))

            if (day != null && day.sec > 0 && sessions.isEmpty()) {
                Note("Only the daily total is shared, not the individual sessions.")
            }
            if (sessions.isNotEmpty()) {
                val longest = sessions.maxOf { it.durSec }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) { Caps("Sessions"); Text("${sessions.size}", style = MaterialTheme.typography.headlineSmall) }
                    Column(Modifier.weight(1f)) { Caps("Longest"); Text(fmtDur(longest.toLong()), style = MaterialTheme.typography.headlineSmall) }
                    Column(Modifier.weight(1f)) { Caps("First start"); Text(at(sessions.minOf { it.startMin }), style = MaterialTheme.typography.headlineSmall) }
                }

                Spacer(Modifier.height(24.dp))
                Caps("Timeline")
                val line = MaterialTheme.colorScheme.outline
                val colors = sessions.map { subjectColorByName(vm, it.subject) }
                Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(36.dp)) {
                    drawRect(line.copy(alpha = 0.18f), size = size)
                    sessions.forEachIndexed { i, s ->
                        val x = size.width * s.startMin / 1440f
                        val w = maxOf(2.dp.toPx(), size.width * (s.durSec / 60f) / 1440f)
                        drawRect(colors[i], Offset(x, 0f), Size(minOf(w, size.width - x), size.height))
                    }
                    for (h in 0..24 step 6) {
                        val x = size.width * h / 24f
                        drawLine(line, Offset(x, size.height), Offset(x, size.height + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
                }

                Spacer(Modifier.height(24.dp))
                Caps("By subject")
                val per = sessions.groupBy { it.subject }.mapValues { e -> e.value.sumOf { it.durSec.toLong() } }
                val total = per.values.sum().coerceAtLeast(1L)
                per.entries.sortedByDescending { it.value }.forEach { (name, sec) ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Row {
                            Dot(subjectColorByName(vm, name))
                            Text(name.ifBlank { "Other" }, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(fmtDur(sec) + "  ·  " + (sec * 100 / total) + "%", style = MaterialTheme.typography.bodyMedium)
                        }
                        AnimatedBar(sec.toFloat() / total, subjectColorByName(vm, name), Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))
                Caps("Sessions")
                sessions.sortedBy { it.startMin }.forEach { s ->
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = rule)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(at(s.startMin) + " - " + at((s.startMin + s.durSec / 60) % 1440), Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                        Column(Modifier.weight(1f)) {
                            Text(s.subject.ifBlank { "Other" }, style = MaterialTheme.typography.titleSmall)
                            Text(if (s.mode == 1) "Pomodoro" else "Stopwatch", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        Text(fmtDur(s.durSec.toLong()), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Dot(c: Color) {
    Box(Modifier.size(8.dp).background(c))
}
