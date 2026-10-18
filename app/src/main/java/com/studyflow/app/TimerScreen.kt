package com.studyflow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
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
    val anims = LocalAnimations.current
    val haptic = LocalHapticFeedback.current
    fun tap() {
        if (s.vibrate) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var askNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val ringTarget = when {
        t.phase != 0 -> Color(0xFF43A047)
        s.subjectColorRing -> Color(subject.color)
        else -> MaterialTheme.colorScheme.primary
    }
    val ringColor by animateColorAsState(ringTarget, animSpec(400), label = "ringColor")
    val ringProg by animateFloatAsState(
        progress,
        if (anims) tween(280, easing = LinearEasing) else snap(),
        label = "ring",
    )
    val timeAlpha by animateFloatAsState(if (!t.running && !idle) 0.55f else 1f, animSpec(300), label = "timeAlpha")
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue",
    )
    val halo = t.running && anims

    val phaseLabel = when {
        !pomo -> "Stopwatch"
        t.phase == 0 -> "Focus · round ${t.cycles % s.cycles + 1} of ${s.cycles}"
        t.phase == 1 -> "Short break"
        else -> "Long break"
    }

    val today = LocalDate.now()
    val todaySec = vm.sessions.secOn(today) + if (t.phase == 0) el / 1000 else 0
    val todayCount = vm.sessions.count { it.start.day() == today }
    val goalSec = s.dailyGoal * 60L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Segmented(
            options = listOf("Stopwatch", "Pomodoro"),
            selected = t.mode,
            enabled = idle,
            onSelect = { tap(); vm.setMode(it) },
        )

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

        AnimatedVisibility(visible = idle && pomo && t.phase == 0, enter = enterT(), exit = exitT()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(15, 25, 45, 60).forEach { m ->
                    FilterChip(
                        selected = s.focusMin == m,
                        onClick = { vm.update { it.copy(focusMin = m) } },
                        label = { Text("$m min") },
                    )
                }
            }
        }

        Box(Modifier.size(280.dp).enter(1), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.surfaceVariant
            Canvas(Modifier.fillMaxSize()) {
                val sw = s.ringWidth.dp.toPx()
                val tl = Offset(sw / 2, sw / 2)
                val sz = Size(size.width - sw, size.height - sw)
                if (halo) {
                    drawCircle(
                        ringColor.copy(alpha = 0.04f + 0.09f * pulse.value),
                        radius = size.minDimension / 2 - sw,
                    )
                }
                drawArc(track, 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (ringProg > 0f) {
                    drawArc(ringColor, -90f, 360f * ringProg, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    subject.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    clock(shown, s.showSeconds),
                    style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    modifier = Modifier.alpha(timeAlpha),
                )
                AnimatedContent(
                    targetState = phaseLabel,
                    transitionSpec = { fadeSwap(anims) },
                    label = "phase",
                ) { label ->
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Primary action: full width, thumb-reachable
        val src = remember { MutableInteractionSource() }
        val mainLabel = if (t.running) "Pause" else if (idle) "Start" else "Resume"
        val btnBg by animateColorAsState(
            if (t.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
            animSpec(250), label = "btnBg",
        )
        val btnFg by animateColorAsState(
            if (t.running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
            animSpec(250), label = "btnFg",
        )
        Button(
            onClick = { tap(); if (t.running) vm.pause() else vm.start() },
            interactionSource = src,
            colors = ButtonDefaults.buttonColors(containerColor = btnBg, contentColor = btnFg),
            modifier = Modifier.fillMaxWidth().height(64.dp).pressScale(src),
        ) {
            AnimatedContent(
                targetState = mainLabel,
                transitionSpec = { fadeSwap(anims) },
                label = "mainBtn",
            ) { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconLabel(if (l == "Pause") Ico.Pause else Ico.Play, l)
                }
            }
        }

        // Secondary actions: text only, evenly split
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { tap(); vm.reset() },
                enabled = !idle,
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
            if (t.phase == 0) {
                OutlinedButton(
                    onClick = { tap(); if (s.askNote) askNote = true else vm.finish("") },
                    enabled = !idle,
                    modifier = Modifier.weight(1f),
                ) { Text("Finish") }
            } else {
                OutlinedButton(
                    onClick = { tap(); vm.skipBreak() },
                    modifier = Modifier.weight(1f),
                ) { Text("Skip break") }
            }
        }

        Card(Modifier.fillMaxWidth().enter(3)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Today", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        if (goalSec > 0) "${fmtDur(todaySec)} / ${fmtDur(goalSec)}" else fmtDur(todaySec),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (goalSec > 0) {
                    AnimatedBar(
                        (todaySec.toFloat() / goalSec).coerceIn(0f, 1f),
                        MaterialTheme.colorScheme.primary,
                        Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    MiniStat("Sessions", todayCount.toString(), Modifier.weight(1f))
                    MiniStat("Streak", "${streak(vm.sessions)} d", Modifier.weight(1f))
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

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
