package com.studyflow.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Main screen. Never scrolls: every region is either fixed-height or takes the
 * remaining space (weight), and the time numerals are sized from the space they
 * actually get (and divided by font scale), so it fits any screen size,
 * orientation and text-size setting.
 */
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

    val barColorTarget = when {
        t.phase != 0 -> MaterialTheme.colorScheme.onBackground
        s.subjectColorRing -> Color(subject.color)
        else -> MaterialTheme.colorScheme.primary
    }
    val barColor = animateColorAsState(barColorTarget, animSpec(400), label = "barColor")
    val barProgress = animateFloatAsState(
        progress,
        if (anims) tween(280, easing = LinearEasing) else snap(),
        label = "barProgress",
    )
    val timeAlpha by animateFloatAsState(if (!t.running && !idle) 0.5f else 1f, animSpec(300), label = "timeAlpha")

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val landscape = maxWidth > maxHeight * 1.1f
        val tight = !landscape && maxHeight.value < 560f * fontScale.coerceAtLeast(1f)
        val extras = !landscape && !tight

        val tabs: @Composable () -> Unit = {
            ModeTabs(selected = t.mode, enabled = idle) { tap(); vm.setMode(it) }
        }

        val subjects: @Composable () -> Unit = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.subjects.filter { !it.archived }, key = { it.id }) { sub ->
                    PickerChip(
                        label = sub.name,
                        selected = sub.id == t.subjectId,
                        enabled = !t.running,
                        dot = sub.color,
                    ) { vm.selectSubject(sub.id) }
                }
            }
        }

        val time: @Composable (Modifier) -> Unit = { m ->
            TimeBlock(
                modifier = m,
                phase = phaseLabel,
                time = clock(shown, s.showSeconds),
                timeAlpha = timeAlpha,
                bar = s.barWidth,
                color = barColor,
                progress = barProgress,
                left = if (pomo) "Elapsed ${clock(el, true)}" else subject.name,
                right = if (pomo) "Target ${clock(target, true)}" else "",
            )
        }

        val controls: @Composable () -> Unit = {
            val src = remember { MutableInteractionSource() }
            val label = if (t.running) "Pause" else if (idle) "Start" else "Resume"
            val bg by animateColorAsState(
                if (t.running) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                animSpec(250), label = "mainBg",
            )
            val fg by animateColorAsState(
                if (t.running) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onPrimary,
                animSpec(250), label = "mainFg",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { tap(); if (t.running) vm.pause() else vm.start() },
                    interactionSource = src,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                    modifier = Modifier.fillMaxWidth().height(if (tight) 52.dp else 60.dp).pressScale(src),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = label,
                        transitionSpec = { fadeSwap(anims) },
                        label = "mainLabel",
                    ) { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconLabel(if (l == "Pause") Ico.Pause else Ico.Play, l)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { tap(); vm.reset() },
                        enabled = !idle,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) { Text("Reset") }
                    if (t.phase == 0) {
                        OutlinedButton(
                            onClick = { tap(); if (s.askNote) askNote = true else vm.finish("") },
                            enabled = !idle,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { Text("Finish") }
                    } else {
                        OutlinedButton(
                            onClick = { tap(); vm.skipBreak() },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { Text("Skip break") }
                    }
                }
            }
        }

        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                time(Modifier.weight(1.25f).fillMaxHeight())
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tabs()
                        subjects()
                    }
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
                tabs()
                Spacer(Modifier.height(if (tight) 8.dp else 16.dp))
                subjects()
                time(Modifier.weight(1f).fillMaxWidth())
                if (extras) {
                    AnimatedVisibility(visible = idle && pomo && t.phase == 0, enter = enterT(), exit = exitT()) {
                        Row(
                            Modifier.padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MicroLabel("Length")
                            Spacer(Modifier.width(4.dp))
                            listOf(15, 25, 45, 60).forEach { m ->
                                PickerChip(label = "$m", selected = s.focusMin == m) {
                                    vm.update { it.copy(focusMin = m) }
                                }
                            }
                            MicroLabel("min")
                        }
                    }
                }
                controls()
                if (extras) {
                    Spacer(Modifier.height(20.dp))
                    DayStrip(todaySec, goalSec, todayCount, streak(vm.sessions))
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
private fun MicroLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Text tabs with a 2dp underline: the active state is typographic, not a pill. */
@Composable
private fun ModeTabs(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf("Stopwatch", "Pomodoro").forEachIndexed { i, label ->
            val sel = i == selected
            val c by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                animSpec(200), label = "tabText",
            )
            val u by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.primary else Color.Transparent,
                animSpec(200), label = "tabLine",
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = c,
                modifier = Modifier
                    .alpha(if (enabled || sel) 1f else 0.45f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                    ) { onSelect(i) }
                    .drawBehind {
                        val h = 2.dp.toPx()
                        drawRect(u, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    }
                    .padding(bottom = 8.dp),
            )
        }
    }
}

/** Solid selected state (inverted), hairline outline otherwise. */
@Composable
private fun PickerChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    dot: Long? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent, animSpec(200), label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
        animSpec(200), label = "chipFg",
    )
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .alpha(if (enabled || selected) 1f else 0.45f)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            ColorDot(dot, 8)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** The hero: numerals sized to the space available, with a flat progress rule. */
@Composable
private fun TimeBlock(
    modifier: Modifier,
    phase: String,
    time: String,
    timeAlpha: Float,
    bar: Int,
    color: State<Color>,
    progress: State<Float>,
    left: String,
    right: String,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(modifier) {
        val fs = LocalDensity.current.fontScale
        val byWidth = maxWidth.value / (time.length * 0.56f) / fs
        val byHeight = (maxHeight.value - 96f) / 1.15f / fs
        val size = minOf(byWidth, byHeight, 132f).coerceAtLeast(24f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(
                phase.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = color.value,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                time,
                style = TextStyle(
                    fontSize = size.sp,
                    lineHeight = (size * 1.05f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-size * 0.03f).sp,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alpha(timeAlpha),
            )
            Spacer(Modifier.height(16.dp))
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(bar.dp)
                    .drawBehind {
                        drawRect(track)
                        drawRect(color.value, size = Size(size.width * progress.value.coerceIn(0f, 1f), size.height))
                    }
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    left,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    right,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DayStrip(todaySec: Long, goalSec: Long, count: Int, streak: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .topRule(MaterialTheme.colorScheme.outline)
            .padding(top = 12.dp),
    ) {
        StripCell("Today", fmtDur(todaySec), if (goalSec > 0) "of ${fmtDur(goalSec)}" else "", Modifier.weight(1.4f))
        StripCell("Sessions", count.toString(), "", Modifier.weight(1f))
        StripCell("Streak", "$streak d", "", Modifier.weight(1f))
    }
}

@Composable
private fun StripCell(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        MicroLabel(label)
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(sub.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
