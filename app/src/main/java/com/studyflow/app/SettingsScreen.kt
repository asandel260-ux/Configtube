package com.studyflow.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainVM) {
    val s = vm.settings
    val ctx = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }

    fun write(uri: android.net.Uri?, text: String) {
        if (uri == null) return
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            Toast.makeText(ctx, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it, vm.exportJson()) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { write(it, vm.exportCsv()) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                vm.importJson(text)
            } catch (e: Exception) {
                false
            }
            Toast.makeText(ctx, if (ok) "Backup restored" else "Could not read that file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Section("Appearance") {
            Text("Theme")
            Row3(
                listOf("System", "Light", "Dark"), s.themeMode,
            ) { i -> vm.update { it.copy(themeMode = i) } }
            SwitchRow("Dynamic colour", "Use wallpaper colours (Android 12+)", s.dynamic) { v -> vm.update { it.copy(dynamic = v) } }
            SwitchRow("Pure black background", "Saves battery on OLED screens (dark theme)", s.amoled) { v -> vm.update { it.copy(amoled = v) } }
            Text("Accent colour")
            ColorPicker(s.accent) { c -> vm.update { it.copy(accent = c) } }
            SliderRow("Text size", (s.fontScale * 100).roundToInt(), 80, 140, 5, "%") { v -> vm.update { it.copy(fontScale = v / 100f) } }
            SliderRow("Corner roundness", s.corner, 0, 32, 2, " dp") { v -> vm.update { it.copy(corner = v) } }
            SliderRow("Timer ring thickness", s.ringWidth, 6, 32, 2, " dp") { v -> vm.update { it.copy(ringWidth = v) } }
            SwitchRow("Ring uses subject colour", null, s.subjectColorRing) { v -> vm.update { it.copy(subjectColorRing = v) } }
        }

        Section("Timer") {
            Text("Default mode")
            Row3(listOf("Stopwatch", "Pomodoro"), s.defaultMode) { i ->
                vm.update { it.copy(defaultMode = i) }
                if (vm.timer.baseMs == 0L && !vm.timer.running) vm.setMode(i)
            }
            SliderRow("Focus length", s.focusMin, 1, 120, 1, " min") { v -> vm.update { it.copy(focusMin = v) } }
            SliderRow("Short break", s.shortMin, 1, 30, 1, " min") { v -> vm.update { it.copy(shortMin = v) } }
            SliderRow("Long break", s.longMin, 1, 60, 1, " min") { v -> vm.update { it.copy(longMin = v) } }
            SliderRow("Rounds before long break", s.cycles, 2, 8, 1, "") { v -> vm.update { it.copy(cycles = v) } }
            SwitchRow("Auto-start next phase", "Continue straight into breaks and focus rounds", s.autoStart) { v -> vm.update { it.copy(autoStart = v) } }
            SliderRow("Ignore sessions shorter than", s.minSaveSec, 0, 300, 10, " s") { v -> vm.update { it.copy(minSaveSec = v) } }
        }

        Section("Goals") {
            SliderRow("Daily goal (0 = off)", s.dailyGoal, 0, 720, 15, " min") { v -> vm.update { it.copy(dailyGoal = v) } }
            SliderRow("Weekly goal (0 = off)", s.weeklyGoal, 0, 5040, 30, " min") { v -> vm.update { it.copy(weeklyGoal = v) } }
        }

        Section("Behaviour") {
            SwitchRow("Vibrate when a phase ends", null, s.vibrate) { v -> vm.update { it.copy(vibrate = v) } }
            SwitchRow("Notification when a phase ends", "Works even if the app is closed", s.notify) { v -> vm.update { it.copy(notify = v) } }
            SwitchRow("Keep screen on while timing", null, s.keepOn) { v -> vm.update { it.copy(keepOn = v) } }
            SwitchRow("Ask for a note when finishing", null, s.askNote) { v -> vm.update { it.copy(askNote = v) } }
            SwitchRow("Show seconds", null, s.showSeconds) { v -> vm.update { it.copy(showSeconds = v) } }
            SwitchRow("24-hour clock", null, s.use24h) { v -> vm.update { it.copy(use24h = v) } }
            Text("Week starts on")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    FilterChip(
                        selected = s.weekStart == d,
                        onClick = { vm.update { it.copy(weekStart = d) } },
                        label = { Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                    )
                }
            }
        }

        Section("Tabs") {
            SwitchRow("Show Log tab", null, s.showLog) { v -> vm.update { it.copy(showLog = v) } }
            SwitchRow("Show Stats tab", null, s.showStats) { v -> vm.update { it.copy(showStats = v) } }
        }

        Section("Data") {
            OutlinedButton(onClick = { exportJson.launch("studyflow-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export backup (JSON)") }
            OutlinedButton(onClick = { exportCsv.launch("studyflow-sessions.csv") }, modifier = Modifier.fillMaxWidth()) { Text("Export sessions (CSV)") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            TextButton(onClick = { confirmReset = true }) { Text("Erase all data") }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Erase everything?") },
            text = { Text("All subjects, sessions and settings will be deleted. Export a backup first if you might want them back.") },
            confirmButton = { TextButton(onClick = { vm.resetAll(); confirmReset = false }) { Text("Erase") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row3(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(l) })
        }
    }
}
