package com.studyflow.app

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
import androidx.compose.material3.Card
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
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
