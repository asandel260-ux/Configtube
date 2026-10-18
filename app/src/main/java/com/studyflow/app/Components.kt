package com.studyflow.app

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

val PALETTE: List<Long> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1,
    0xFF3949AB, 0xFF1E88E5, 0xFF00ACC1, 0xFF00897B,
    0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFB8C00,
    0xFFF4511E, 0xFF6D4C41, 0xFF546E7A, 0xFF5C6BC0,
)

val EMOJIS = listOf("📚", "🧮", "🔬", "🧪", "💻", "🌍", "🎨", "🎵", "✍️", "🗣️", "⚖️", "🩺", "📐", "📖", "🧠", "🏛️")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Long, onSelect: (Long) -> Unit) {
    var hex by remember(selected) { mutableStateOf("%06X".format(selected and 0xFFFFFFL)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PALETTE.forEach { c ->
                Row(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            if (c == selected) 3.dp else 0.dp,
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape
                        )
                        .clickable { onSelect(c) }
                ) {}
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                val h = v.removePrefix("#").uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                hex = h
                if (h.length == 6) h.toLongOrNull(16)?.let { onSelect(0xFF000000L or it) }
            },
            label = { Text("Custom colour (hex RRGGBB)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    onCommit: (Int) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text("${v.toInt()}$suffix", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
fun SwitchRow(label: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


@Composable
fun ColorDot(color: Long, size: Int = 10) {
    Row(Modifier.size(size.dp).background(Color(color))) {}
}

@Composable
fun IconLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
}

// ---------------------------------------------------------------------------
// Motion helpers (all respect the "Animations" setting)
// ---------------------------------------------------------------------------

val LocalAnimations = compositionLocalOf { true }

@Composable
fun <T> animSpec(ms: Int = 300, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
    if (LocalAnimations.current) tween(ms, easing = easing) else snap()

@Composable
fun enterT(): EnterTransition =
    if (LocalAnimations.current) expandVertically() + fadeIn() else EnterTransition.None

@Composable
fun exitT(): ExitTransition =
    if (LocalAnimations.current) shrinkVertically() + fadeOut() else ExitTransition.None

fun fadeSwap(anims: Boolean): ContentTransform =
    if (anims) fadeIn(tween(220)) togetherWith fadeOut(tween(160))
    else EnterTransition.None togetherWith ExitTransition.None

/** Fade + rise on first appearance, staggered by [index]. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val anims = LocalAnimations.current
    var shown by remember { mutableStateOf(!anims) }
    LaunchedEffect(Unit) {
        if (!shown) delay(index * 45L)
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, animSpec(340), label = "enterA")
    val y by animateFloatAsState(if (shown) 0f else 28f, animSpec(340), label = "enterY")
    return this.graphicsLayer { alpha = a; translationY = y }
}

/** Slight scale-down while pressed. */
fun Modifier.pressScale(source: InteractionSource, to: Float = 0.97f): Modifier = composed {
    val anims = LocalAnimations.current
    val pressed by source.collectIsPressedAsState()
    val sc by animateFloatAsState(
        if (pressed && anims) to else 1f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this.graphicsLayer { scaleX = sc; scaleY = sc }
}

@Composable
fun AnimatedBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val anims = LocalAnimations.current
    var go by remember { mutableStateOf(!anims) }
    LaunchedEffect(Unit) { go = true }
    val p = animateFloatAsState(if (go) progress else 0f, animSpec(700), label = "bar")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.drawBehind {
            drawRect(track)
            drawRect(color, size = Size(size.width * p.value.coerceIn(0f, 1f), size.height))
        }
    )
}

@Composable
fun GrowBar(target: Float, index: Int) {
    val anims = LocalAnimations.current
    var go by remember { mutableStateOf(!anims) }
    LaunchedEffect(Unit) {
        if (!go) delay(index * 18L)
        go = true
    }
    val h by animateFloatAsState(if (go) target else 2f, animSpec(500), label = "grow")
    Box(
        Modifier
            .fillMaxWidth()
            .height(h.dp)
            .background(MaterialTheme.colorScheme.primary, RectangleShape)
    )
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.primary else Color.Transparent, animSpec(220), label = "segBg"
            )
            val fg by animateColorAsState(
                if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                animSpec(220), label = "segFg"
            )
            Box(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(bg)
                    .clickable(enabled = enabled) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Primary floating action: pops in, sits bottom-right. */
@Composable
fun Fab(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val anims = LocalAnimations.current
    var shown by remember { mutableStateOf(!anims) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier.padding(16.dp),
        enter = if (anims) scaleIn(tween(260)) + fadeIn(tween(260)) else EnterTransition.None,
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Ico.Plus, contentDescription = null, modifier = Modifier.size(20.dp)) },
            text = { Text(text) },
        )
    }
}

/** Collapsible card section. */
@Composable
fun Section(title: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val rot by animateFloatAsState(if (open) 180f else 0f, animSpec(250), label = "chevron")
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(
                    Ico.ChevronDown,
                    contentDescription = if (open) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp).rotate(rot),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = open, enter = enterT(), exit = exitT()) {
                Column(
                    Modifier.padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Swiss primitives: hairline rules instead of cards
// ---------------------------------------------------------------------------

fun Modifier.topRule(color: Color, widthDp: Float = 1f): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = widthDp.dp.toPx())
}

/** Flat section: a 1dp rule on top, no fill, no shadow, no radius. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Column(modifier.topRule(line), content = content)
}
