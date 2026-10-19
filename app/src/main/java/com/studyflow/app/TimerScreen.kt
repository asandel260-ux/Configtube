package com.studyflow.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Main screen. Never scrolls. Centre piece is an instrument-style dial:
 * 60 ticks around a circle. Pomodoro: ticks fill as the session progresses.
 * Stopwatch: a sweep hand with a fading trail marks the current second.
 */
@Composable
fun TimerScreen(vm: MainVM) {
    val t = vm.timer
    val s = vm.settings
    val el = vm.elapsed()
    val target = vm.targetMs()
    val pomo = t.mode == 1
    val shown = if (pomo) (target - el).coerceAtLeast(0) else el
    val subject = vm.subjects.firstOrNull { it.id == t.subjectId } ?: vm.subjects.first()
    val idle = !t.running && t.baseMs == 0L
    val anims = LocalAnimations.current
    val haptic = LocalHapticFeedback.current
    fun tap() {
        if (s.vibrate) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var askNote by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // Monotonic value (fraction of session, or minutes elapsed) animated forward only,
    // so the sweep never spins backwards across a wrap.
    val unwrapped = if (pomo) (if (target > 0) (el.toFloat() / target).coerceIn(0f, 1f) else 0f) else el / 60000f
    var last by remember { mutableFloatStateOf(unwrapped) }
    val forward = unwrapped >= last
    SideEffect { last = unwrapped }
    val head = animateFloatAsState(
        unwrapped,
        if (anims && forward) tween(280, easing = LinearEasing) else snap(),
        label = "head",
    )
    val accentTarget = when {
        t.phase != 0 -> MaterialTheme.colorScheme.onBackground
        s.subjectColorRing -> Color(subject.color)
        else -> MaterialTheme.colorScheme.primary
    }
    val accent = animateColorAsState(accentTarget, animSpec(400), label = "accent")
    val timeAlpha by animateFloatAsState(if (!t.running && !idle) 0.5f else 1f, animSpec(300), label = "timeAlpha")

    val caption = when {
        !pomo -> "Elapsed"
        t.phase == 1 -> "Short break"
        t.phase == 2 -> "Long break"
        else -> "of ${clock(target, true)}"
    }

    val today = LocalDate.now()
    val todaySec = vm.sessions.secOn(today) + if (t.phase == 0) el / 1000 else 0
    val todayCount = vm.sessions.count { it.start.day() == today }
    val goalSec = s.dailyGoal * 60L

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val landscape = maxWidth > maxHeight * 1.1f
        val tight = !landscape && maxHeight.value < 600f * fontScale.coerceAtLeast(1f)
        val extras = !landscape && !tight

        val tabs: @Composable () -> Unit = {
            ModeTabs(selected = t.mode, enabled = idle) { tap(); vm.setMode(it) }
        }

        val subjects: @Composable () -> Unit = {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
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

        val dial: @Composable (Modifier) -> Unit = { m ->
            Dial(
                modifier = m,
                head = head,
                sweep = !pomo,
                active = !idle,
                color = accent,
                subject = subject.name,
                subjectColor = subject.color,
                time = clock(shown, s.showSeconds),
                timeAlpha = timeAlpha,
                caption = caption,
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
            val textBtn = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
            val pad = PaddingValues(horizontal = 8.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TextButton(
                        onClick = { tap(); vm.reset() },
                        enabled = !idle,
                        colors = textBtn,
                        contentPadding = pad,
                    ) { Text("Reset") }
                }
                Button(
                    onClick = { tap(); if (t.running) vm.pause() else vm.start() },
                    interactionSource = src,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                    modifier = Modifier.width(136.dp).height(if (tight) 48.dp else 56.dp).pressScale(src),
                ) {
                    AnimatedContent(
                        targetState = label,
                        transitionSpec = { fadeSwap(anims) },
                        label = "mainLabel",
                    ) { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconLabel(if (l == "Pause") Ico.Pause else Ico.Play, l)
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (t.phase == 0) {
                        TextButton(
                            onClick = { tap(); if (s.askNote) askNote = true else vm.finish("") },
                            enabled = !idle,
                            colors = textBtn,
                            contentPadding = pad,
                        ) { Text("Finish") }
                    } else {
                        TextButton(
                            onClick = { tap(); vm.skipBreak() },
                            colors = textBtn,
                            contentPadding = pad,
                        ) { Text("Skip") }
                    }
                }
            }
        }

        if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                dial(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    tabs()
                    Spacer(Modifier.height(16.dp))
                    subjects()
                    Spacer(Modifier.height(24.dp))
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { tabs() }
                Spacer(Modifier.height(if (tight) 8.dp else 16.dp))
                subjects()
                dial(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
                if (extras) {
                    AnimatedVisibility(visible = idle && pomo && t.phase == 0, enter = enterT(), exit = exitT()) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            listOf(15, 25, 45, 60).forEach { m ->
                                PickerChip(label = "$m min", selected = s.focusMin == m) {
                                    vm.update { it.copy(focusMin = m) }
                                }
                            }
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

/** Centered text tabs; the active state is an underline, not a pill. */
@Composable
private fun ModeTabs(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
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

/** Solid inverted when selected, hairline outline otherwise. */
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

@Composable
private fun Dial(
    modifier: Modifier,
    head: State<Float>,
    sweep: Boolean,
    active: Boolean,
    color: State<Color>,
    subject: String,
    subjectColor: Long,
    time: String,
    timeAlpha: Float,
    caption: String,
) {
    val unlit = MaterialTheme.colorScheme.onSurfaceVariant
    val fs = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val s: Dp = minOf(maxWidth * 0.8f, maxHeight, 300.dp)
        val sDp = s.value
        Box(Modifier.size(s), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.minDimension
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = w / 2f - 2.dp.toPx()
                val short = w * 0.04f
                val long = w * 0.07f
                val p = head.value
                val frac = if (sweep) p - floor(p) else p.coerceIn(0f, 1f)
                val pos = frac * 60f
                val lit = color.value
                for (i in 0 until 60) {
                    val isLong = i % 5 == 0
                    val len = if (isLong) long else short
                    val a = Math.toRadians(i * 6.0 - 90.0)
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    val amount = if (!active) 0f else if (sweep) {
                        val behind = (pos - i + 60f) % 60f
                        if (behind < 22f) 1f - behind / 22f else 0f
                    } else {
                        when {
                            i + 1 <= pos -> 1f
                            i < pos -> pos - i
                            else -> 0f
                        }
                    }
                    val base = unlit.copy(alpha = if (isLong) 0.45f else 0.22f)
                    drawLine(
                        color = if (amount > 0f) lerp(base, lit, amount) else base,
                        start = Offset(c.x + dx * (r - len), c.y + dy * (r - len)),
                        end = Offset(c.x + dx * r, c.y + dy * r),
                        strokeWidth = (if (isLong) 2.5f else 1.75f).dp.toPx(),
                    )
                }
                // Head marker: a longer, heavier tick that moves continuously.
                val ha = Math.toRadians(frac.toDouble() * 360.0 - 90.0)
                val hx = cos(ha).toFloat()
                val hy = sin(ha).toFloat()
                val hl = w * 0.105f
                drawLine(
                    color = lit,
                    start = Offset(c.x + hx * (r - hl), c.y + hy * (r - hl)),
                    end = Offset(c.x + hx * r, c.y + hy * r),
                    strokeWidth = 3.dp.toPx(),
                )
            }
            val numSp = minOf(sDp * 0.74f / (time.length * 0.6f), sDp * 0.27f) / fs
            Column(
                Modifier.widthIn(max = s * 0.64f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(subjectColor, 6)
                    Spacer(Modifier.width(6.dp))
                    MicroLabel(subject)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    time,
                    style = TextStyle(
                        fontFamily = Plex,
                        fontSize = numSp.sp,
                        lineHeight = (numSp * 1.1f).sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-numSp * 0.02f).sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alpha(timeAlpha),
                )
                Spacer(Modifier.height(6.dp))
                MicroLabel(caption)
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
        Text(
            sub.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
