@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import android.app.DatePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studyflow.app.p2p.StatsIndex
import com.studyflow.app.p2p.ymOf
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Statistics source for either me (everything, from local data) or another member (what they shared). */
@Composable
fun rememberIndex(vm: MainVM, pub: String): StatsIndex {
    val social = vm.social
    val mine = pub == social.me
    DisposableEffect(pub) {
        val un: () -> Unit = if (mine) ({}) else social.watchProfile(pub)
        onDispose { un() }
    }
    val local = remember(vm.sessions, vm.subjects) { localIndex(vm.sessions, vm.subjects) }
    return if (mine) local else (social.snap.stats[pub] ?: StatsIndex.EMPTY)
}

@Composable
fun ProfileScreen(vm: MainVM, pub: String, onBack: () -> Unit, onDay: (String) -> Unit) {
    val social = vm.social
    val snap = social.snap
    val mine = pub == social.me
    val idx = rememberIndex(vm, pub)
    val p = snap.profiles[pub]
    val live = snap.live[pub]
    val today = todayOf()
    var mode by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (mine) "Your profile" else "Profile", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mine) Avatar(social.cfg.name.ifBlank { "You" }, social.cfg.color, social.cfg.glyph, 64.dp, isLive(live))
                else UserAvatar(snap, pub, 64.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(if (mine) social.cfg.name.ifBlank { "You" } else nameOf(snap, pub), style = MaterialTheme.typography.headlineSmall)
                    val bio = if (mine) social.cfg.bio else p?.bio ?: ""
                    if (bio.isNotBlank()) Text(bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isLive(live) && live != null) {
                        Text(
                            "Studying" + (if (live.subject.isNotBlank()) " ${live.subject}" else "") + " now",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Segmented(listOf("Daily", "Monthly", "Custom"), mode) { mode = it }
            Spacer(Modifier.height(20.dp))
            if (!mine && idx.months.isEmpty()) {
                Note("No shared study time yet. It may still be arriving from the relays, or this person is not sharing stats.")
                Spacer(Modifier.height(16.dp))
            }
            when (mode) {
                0 -> DailyView(vm, idx, today, onDay)
                1 -> MonthlyView(idx, today)
                else -> CustomView(idx, today)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun monthName(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + d.year

@Composable
private fun DailyView(vm: MainVM, idx: StatsIndex, today: LocalDate, onDay: (String) -> Unit) {
    var anchor by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toEpochDay()) }
    val first = LocalDate.ofEpochDay(anchor)
    val n = first.lengthOfMonth()
    val days = (1..n).map { first.withDayOfMonth(it) }
    val secs = days.map { idx.sec(it) }
    val max = (secs.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val atCurrent = first.year == today.year && first.monthValue == today.monthValue
    val offset = ((first.dayOfWeek.value - vm.settings.weekStart) + 7) % 7
    val rule = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { anchor = first.minusMonths(1).toEpochDay() }) { Icon(Ico.ChevronLeft, "Previous month", Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(monthName(first), style = MaterialTheme.typography.titleMedium)
            Text(
                fmtDur(secs.sum()) + " · ${secs.count { it > 0 }} active days",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(enabled = !atCurrent, onClick = { anchor = first.plusMonths(1).toEpochDay() }) { Icon(Ico.ChevronRight, "Next month", Modifier.size(22.dp)) }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        for (i in 0 until 7) {
            val dow = java.time.DayOfWeek.of(((vm.settings.weekStart - 1 + i) % 7) + 1)
            Text(
                dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    val cells: List<LocalDate?> = List(offset) { null } + days
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val d = week.getOrNull(c)
                    if (d == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(0.85f))
                    } else {
                        val sec = idx.sec(d)
                        val future = d.isAfter(today)
                        val isToday = d == today
                        val frac = sec.toFloat() / max
                        Column(
                            Modifier
                                .weight(1f)
                                .aspectRatio(0.85f)
                                .background(if (sec > 0) accent.copy(alpha = 0.14f + 0.5f * frac) else Color.Transparent, MaterialTheme.shapes.extraSmall)
                                .border(if (isToday) 2.dp else 1.dp, if (isToday) MaterialTheme.colorScheme.onBackground else rule.copy(alpha = if (future) 0.35f else 1f), MaterialTheme.shapes.extraSmall)
                                .clickable { onDay(d.toString()) }
                                .padding(5.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(fmtShort(sec), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Note("Tap a day to see its sessions.")
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp)) {
        Caps(label)
        Text(value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatTiles(total: Long, spanDays: Long, active: Int, bestSec: Long, bestDay: LocalDate?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Tile("Total", fmtDur(total), Modifier.weight(1f))
            Tile("Daily average", fmtDur(total / spanDays.coerceAtLeast(1)), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Active days", "$active", Modifier.weight(1f))
            Tile("Best day", if (bestSec > 0 && bestDay != null) fmtDur(bestSec) + " · " + bestDay.dayOfMonth + " " + bestDay.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else "-", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MonthlyView(idx: StatsIndex, today: LocalDate) {
    val months = (11 downTo 0).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }
    val totals = months.map { m -> idx.sum(m, m.withDayOfMonth(m.lengthOfMonth())) }
    var sel by rememberSaveable { mutableStateOf(11) }
    val m = months[sel.coerceIn(0, 11)]
    val last = m.withDayOfMonth(m.lengthOfMonth())
    val dayList = (1..m.lengthOfMonth()).map { m.withDayOfMonth(it) }
    val secs = dayList.map { idx.sec(it) }
    val best = dayList.zip(secs).maxByOrNull { it.second }

    MiniBars(totals, Modifier.fillMaxWidth().height(110.dp), highlight = sel)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        months.forEachIndexed { i, d ->
            Text(
                d.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f).clickable { sel = i }.padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = if (i == sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(monthName(m), style = MaterialTheme.typography.titleMedium)
    val span = if (m.year == today.year && m.monthValue == today.monthValue) today.dayOfMonth.toLong() else m.lengthOfMonth().toLong()
    StatTiles(secs.sum(), span, secs.count { it > 0 }, best?.second ?: 0L, best?.first)
    SubjectBreakdown(idx, m, last)
    Note("Shows the last 12 months that have arrived on this phone.")
}

@Composable
private fun CustomView(idx: StatsIndex, today: LocalDate) {
    val ctx = LocalContext.current
    var from by rememberSaveable { mutableStateOf(today.minusDays(13).toEpochDay()) }
    var to by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val f = LocalDate.ofEpochDay(minOf(from, to))
    val t0 = LocalDate.ofEpochDay(maxOf(from, to))
    val t = if (java.time.temporal.ChronoUnit.DAYS.between(f, t0) > 365) f.plusDays(365) else t0
    val span = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1
    val days = (0 until span).map { f.plusDays(it) }
    val secs = days.map { idx.sec(it) }
    val best = days.zip(secs).maxByOrNull { it.second }

    fun pick(cur: Long, set: (Long) -> Unit) {
        val d = LocalDate.ofEpochDay(cur)
        DatePickerDialog(ctx, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd).toEpochDay()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(7, 14, 30, 90, 365).forEach { n ->
            FilterChip(
                selected = span == n.toLong() && t == today,
                onClick = { to = today.toEpochDay(); from = today.minusDays(n - 1L).toEpochDay() },
                label = { Text(if (n == 365) "1 year" else "$n days") },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick(from) { from = it } }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("From $f") }
        OutlinedButton(onClick = { pick(to) { to = it } }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("To $t") }
    }
    Spacer(Modifier.height(16.dp))
    val bars = if (days.size <= 62) secs else secs.chunked(7).map { it.sum() }
    MiniBars(bars, Modifier.fillMaxWidth().height(100.dp))
    Text(
        if (days.size <= 62) "Per day" else "Per week",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    StatTiles(secs.sum(), span, secs.count { it > 0 }, best?.second ?: 0L, best?.first)
    SubjectBreakdown(idx, f, t)
}

@Composable
private fun SubjectBreakdown(idx: StatsIndex, from: LocalDate, to: LocalDate) {
    val per = HashMap<String, Long>()
    var d = from
    var guard = 0
    while (!d.isAfter(to) && guard++ < 400) {
        idx.day(d)?.sessions?.forEach { per[it.subject] = (per[it.subject] ?: 0L) + it.durSec }
        d = d.plusDays(1)
    }
    if (per.isEmpty()) return
    val total = per.values.sum().coerceAtLeast(1L)
    Spacer(Modifier.height(8.dp))
    Caps("By subject")
    per.entries.sortedByDescending { it.value }.take(8).forEach { (name, sec) ->
        Column(Modifier.padding(top = 10.dp)) {
            Row {
                Text(name.ifBlank { "Other" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(fmtDur(sec), style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedBar(sec.toFloat() / total, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp))
        }
    }
}
