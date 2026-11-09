package com.toolbox.multitool.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Standard screen frame used by every tool. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        val base = Modifier.padding(pad).fillMaxSize().padding(16.dp)
        Column(
            if (scroll) base.verticalScroll(rememberScrollState()) else base,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Returns a function that opens the system document picker. */
@Composable
fun rememberPicker(
    mime: Array<String> = arrayOf("*/*"),
    onPicked: (List<Uri>) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) onPicked(it)
    }
    return { launcher.launch(mime) }
}

@Composable
fun LogBox(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
fun Busy(progress: Float?, label: String = "") {
    if (progress == null) return
    Column {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium)
        if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
