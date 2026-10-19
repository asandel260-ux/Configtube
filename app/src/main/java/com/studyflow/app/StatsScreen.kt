package com.studyflow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsScreen(vm: MainVM) {
    val s = vm.settings
    var range by rememberSaveable { mutableStateOf(7) } // 7, 30, 0 = all
    val today = LocalDate.now()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(s.weekStart)))
    val sessions = vm.sessions

    val todaySec = sessions.secOn(today)
    val weekSec = sessions.filter { !it.start.day().isBefore(weekStart) }.sumOf { it.durSec }
    val from: LocalDate? = if (range == 0) null else today.minusDays(range - 1L)
    val inRange = if (from == null) sessions else sessions.filter { !it.start.day().isBefore(from) }
    val total = inRange.sumOf { it.durSec }
    val spanDays: Long = if (from != null) range.toLong()
    else sessions.minOfOrNull { it.start.day().toEpochDay() }?.let { today.toEpochDay() - it + 1 } ?: 1L
    val avg = total / spanDays.coerceAtLeast(1)

    val chartDays = if (range == 7) 7 else 30
    val chart = (chartDays - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to sessions.secOn(it) }
    val maxSec = (chart.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val bySubject = inRange.groupBy { it.subjectId }.map { (id, l) -> id to l.sumOf { it.durSec } }
        .sortedByDescending { it.second }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ScreenHeader("Statistics", "Your study time at a glance")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = range == 7, onClick = { range = 7 }, label = { Text("7 days") })
            FilterChip(selected = range == 30, onClick = { range = 30 }, label = { Text("30 days") })
            FilterChip(selected = range == 0, onClick = { range = 0 }, label = { Text("All time") })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Today", fmtDur(todaySec), Modifier.weight(1f))
            StatTile("This week", fmtDur(weekSec), Modifier.weight(1f))
            StatTile("Streak", "${streak(sessions)} d", Modifier.weight(1f))
        }

        if (s.dailyGoal > 0 || s.weeklyGoal > 0) {
            Card(Modifier.fillMaxWidth().enter(2)) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Goals", fontWeight = FontWeight.Bold)
                    if (s.dailyGoal > 0) GoalBar("Daily", todaySec, s.dailyGoal * 60L)
                    if (s.weeklyGoal > 0) GoalBar("Weekly", weekSec, s.weeklyGoal * 60L)
                }
            }
        }

        Card(Modifier.fillMaxWidth().enter(4)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(if (range == 7) "Last 7 days" else "Last 30 days", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("avg ${fmtDur(avg)}/day", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (chartDays == 7) 8.dp else 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    chart.forEachIndexed { i, (d, sec) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            GrowBar(maxOf(2f, 110f * sec / maxSec.toFloat()), i)
                            val label = when {
                                chartDays == 7 -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
                                i % 5 == 0 -> d.dayOfMonth.toString()
                                else -> ""
                            }
                            Text(label, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Total", fmtDur(total), Modifier.weight(1f))
            StatTile("Sessions", inRange.size.toString(), Modifier.weight(1f))
            StatTile("Longest", fmtDur(inRange.maxOfOrNull { it.durSec } ?: 0L), Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth().enter(6)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("By subject", fontWeight = FontWeight.Bold)
                if (bySubject.isEmpty()) Text("Nothing tracked in this range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                bySubject.forEach { (id, sec) ->
                    val sub = vm.subjects.firstOrNull { it.id == id }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(sub?.name ?: "Deleted subject", modifier = Modifier.weight(1f))
                            Text("${fmtDur(sec)} · ${if (total > 0) sec * 100 / total else 0}%")
                        }
                        AnimatedBar(if (total > 0) sec.toFloat() / total else 0f, Color(sub?.color ?: 0xFF888888), Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }

        val withGoals = vm.subjects.filter { it.dailyGoalMin > 0 && !it.archived }
        if (withGoals.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().enter(8)) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Subject goals today", fontWeight = FontWeight.Bold)
                    withGoals.forEach { sub ->
                        val sec = sessions.filter { it.subjectId == sub.id && it.start.day() == today }.sumOf { it.durSec }
                        GoalBar(sub.name, sec, sub.dailyGoalMin * 60L, Color(sub.color))
                    }
                }
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
    }
}

@Composable
fun GoalBar(label: String, sec: Long, goalSec: Long, color: Color = MaterialTheme.colorScheme.primary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text("${fmtDur(sec)} / ${fmtDur(goalSec)}", style = MaterialTheme.typography.bodySmall)
        }
        AnimatedBar((sec.toFloat() / goalSec).coerceIn(0f, 1f), color, Modifier.fillMaxWidth().height(8.dp))
    }
}
