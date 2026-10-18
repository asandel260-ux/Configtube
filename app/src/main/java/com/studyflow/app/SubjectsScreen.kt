package com.studyflow.app

import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SubjectsScreen(vm: MainVM) {
    var editing by remember { mutableStateOf<Subject?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Subjects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { creating = true }) { IconLabel(Ico.Plus, "New subject") }
            }
        }
        items(vm.subjects, key = { it.id }) { sub ->
            val total = vm.sessions.filter { it.subjectId == sub.id }.sumOf { it.durSec }
            Card(Modifier.fillMaxWidth().clickable { editing = sub }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(sub.color, 14)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(sub.name + if (sub.archived) " (archived)" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Total ${fmtDur(total)}" + if (sub.dailyGoalMin > 0) " · goal ${sub.dailyGoalMin} min/day" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Ico.Pencil, "Edit subject", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (creating) {
        SubjectDialog(
            initial = null, canDelete = false,
            onSave = { vm.saveSubject(it); creating = false },
            onDelete = {}, onDismiss = { creating = false },
        )
    }
    editing?.let { sub ->
        SubjectDialog(
            initial = sub, canDelete = vm.subjects.size > 1,
            onSave = { vm.saveSubject(it); editing = null },
            onDelete = { vm.deleteSubject(sub.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectDialog(
    initial: Subject?,
    canDelete: Boolean,
    onSave: (Subject) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "📚") }
    var color by remember { mutableStateOf(initial?.color ?: PALETTE.first { it == 0xFF5C6BC0 }) }
    var goal by remember { mutableStateOf(initial?.dailyGoalMin ?: 0) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New subject" else "Edit subject") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Colour")
                ColorPicker(color) { color = it }
                SliderRow(
                    label = "Daily goal for this subject",
                    value = goal, min = 0, max = 480, step = 15, suffix = " min",
                ) { goal = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Archived")
                        Text("Hide from the timer, keep history", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(checked = archived, onCheckedChange = { archived = it })
                }
                if (canDelete) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete subject") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    val base = initial ?: Subject(name = name)
                    onSave(base.copy(name = name.trim(), emoji = emoji.ifBlank { "📚" }, color = color, dailyGoalMin = goal, archived = archived))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete subject?") },
            text = { Text("Past sessions stay in your log but will show as \"Deleted subject\".") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
