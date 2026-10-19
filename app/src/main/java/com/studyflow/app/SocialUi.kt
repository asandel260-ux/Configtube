@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.studyflow.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyflow.app.p2p.DayStat
import com.studyflow.app.p2p.Live
import com.studyflow.app.p2p.Period
import com.studyflow.app.p2p.RawSession
import com.studyflow.app.p2p.Snapshot
import com.studyflow.app.p2p.Social
import com.studyflow.app.p2p.StatsBuild
import com.studyflow.app.p2p.StatsIndex
import java.time.LocalDate
import java.time.ZoneId

val GLYPHS: List<ImageVector?> by lazy {
    listOf(null, Ico.User, Ico.BookOpen, Ico.Flame, Ico.Zap, Ico.Moon, Ico.Brain, Ico.Crown, Ico.Shield)
}

fun nameOf(snap: Snapshot, pub: String): String = snap.profiles[pub]?.name ?: ("User " + pub.take(4))

fun fallbackColor(key: String): Int =
    (key.take(2).toIntOrNull(16) ?: key.hashCode().let { if (it < 0) -it else it }) % PALETTE.size

/** Compact duration: "1h35", "42m", "". */
fun fmtShort(sec: Long): String = when {
    sec <= 0 -> ""
    sec >= 3600 -> "${sec / 3600}h${((sec % 3600) / 60).toString().padStart(2, '0')}"
    else -> "${maxOf(1, sec / 60)}m"
}

fun subjectColorByName(vm: MainVM, name: String): Color {
    val own = vm.subjects.firstOrNull { it.name == name }
    if (own != null) return Color(own.color)
    val h = name.hashCode().let { if (it < 0) -(it + 1) else it }
    return Color(PALETTE[h % PALETTE.size])
}

/** My own statistics with every detail, built from local sessions. */
fun localIndex(sessions: List<Session>, subjects: List<Subject>): StatsIndex {
    val names = subjects.associate { it.id to it.name }
    val raws = sessions.map { RawSession(it.start, it.durSec, names[it.subjectId] ?: "", it.mode, it.manual) }
    return StatsIndex(StatsBuild.build(raws, ZoneId.systemDefault(), true))
}

@Composable
fun Avatar(name: String, color: Int, glyph: Int, size: Dp, live: Boolean = false, modifier: Modifier = Modifier) {
    val bg = Color(PALETTE[color.mod(PALETTE.size)])
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            val g = GLYPHS.getOrNull(glyph)
            if (g != null) {
                Icon(g, null, Modifier.size(size * 0.5f), tint = Color.White)
            } else {
                Text(
                    name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (live) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(maxOf(8.dp, size * 0.24f))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .background(Color(0xFF22C55E)),
            )
        }
    }
}

@Composable
fun UserAvatar(snap: Snapshot, pub: String, size: Dp, modifier: Modifier = Modifier) {
    val p = snap.profiles[pub]
    val live = snap.live[pub]?.active(System.currentTimeMillis() / 1000) == true
    Avatar(p?.name ?: nameOf(snap, pub), p?.color ?: fallbackColor(pub), p?.glyph ?: 0, size, live, modifier)
}

fun isLive(l: Live?): Boolean = l?.active(System.currentTimeMillis() / 1000) == true

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Ico.Back, "Back", Modifier.size(22.dp)) }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun Caps(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Text tabs with an underline for the active one. */
@Composable
fun UnderlineTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            val c by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                animSpec(200), label = "utC",
            )
            val u by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.primary else Color.Transparent,
                animSpec(200), label = "utU",
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = c,
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .drawBehind {
                        val h = 2.dp.toPx()
                        drawRect(u, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
fun IconFab(icon: ImageVector, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(16.dp)
            .size(56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** The leaderboard entry point: a pulsing accent tile with a lightning bolt. */
@Composable
fun FlashButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val anims = LocalAnimations.current
    val inf = rememberInfiniteTransition(label = "flash")
    val pulse = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (anims) {
                val p = pulse.value
                val base = 34.dp.toPx()
                val grow = 12.dp.toPx() * p
                val s = base + grow
                drawRoundRect(
                    color = accent.copy(alpha = (1f - p) * 0.55f),
                    topLeft = Offset((size.width - s) / 2f, (size.height - s) / 2f),
                    size = Size(s, s),
                    cornerRadius = CornerRadius(6.dp.toPx() + grow / 2f),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.size(34.dp).clip(MaterialTheme.shapes.small).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Ico.Zap, "Leaderboard", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

data class RankEntry(val pub: String, val sec: Long)

@Composable
fun RankList(
    entries: List<RankEntry>,
    snap: Snapshot,
    me: String,
    onProfile: (String) -> Unit,
) {
    val max = (entries.maxOfOrNull { it.sec } ?: 0L).coerceAtLeast(1L)
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { i, e ->
            val mine = e.pub == me
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (mine) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                    .clickable { onProfile(e.pub) },
            ) {
                if (i > 0) HorizontalDivider(color = rule)
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                    )
                    UserAvatar(snap, e.pub, 36.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            nameOf(snap, e.pub) + if (mine) "  (you)" else "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isLive(snap.live[e.pub])) {
                            Text("Studying now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(if (e.sec > 0) fmtDur(e.sec) else "-", style = MaterialTheme.typography.titleMedium)
                }
                AnimatedBar(
                    e.sec.toFloat() / max, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** Little bar chart drawn straight to canvas. */
@Composable
fun MiniBars(values: List<Long>, modifier: Modifier = Modifier, highlight: Int = -1) {
    val anims = LocalAnimations.current
    var go by remember { mutableStateOf(!anims) }
    LaunchedEffect(Unit) { go = true }
    val t by androidx.compose.animation.core.animateFloatAsState(if (go) 1f else 0f, animSpec(500), label = "bars")
    val c = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val w = size.width / values.size
        val gap = (w * 0.18f).coerceAtMost(4.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = maxOf(if (v > 0) 2.dp.toPx() else 1.dp.toPx(), size.height * (v.toFloat() / max) * t)
            val col = if (highlight < 0 || i == highlight) (if (v > 0) c else dim) else c.copy(alpha = 0.35f)
            drawRect(col, Offset(i * w + gap / 2f, size.height - h), Size(maxOf(1f, w - gap), h))
        }
    }
}

// ---------------------------------------------------------------------------
// Profile gate: a name is needed before anything is shared
// ---------------------------------------------------------------------------

@Composable
fun ProfileGate(social: Social, content: @Composable (needProfile: (() -> Unit) -> Unit) -> Unit) {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    content { action ->
        if (social.cfg.name.isNotBlank() && social.cfg.share) action() else pending = action
    }
    val act = pending
    if (act != null) {
        var name by remember { mutableStateOf(social.cfg.name) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Choose a name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(24) },
                        label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Note(
                        "Groups show your name, avatar and the study time you record to other members. " +
                            "Anyone who has your public ID can see it. You can change or stop sharing at any time in Settings."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.trim().length >= 2,
                    onClick = {
                        social.updateCfg { it.copy(name = name.trim(), share = true) }
                        pending = null
                        act()
                    },
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

fun todayOf(): LocalDate = LocalDate.now()

fun DayStat?.secOrZero(): Long = this?.sec ?: 0L

val PERIOD_LABELS = listOf("Today", "Week", "Month")
fun periodOf(i: Int) = when (i) { 0 -> Period.DAY; 1 -> Period.WEEK; else -> Period.MONTH }
