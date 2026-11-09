package com.toolbox.multitool.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).notes() }
    val scope = rememberCoroutineScope()
    val notes by dao.notes().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Note?>(null) }

    BackHandler(enabled = editing != null) { editing = null }

    val e = editing
    if (e != null) {
        var title by remember(e.id) { mutableStateOf(e.title) }
        var body by remember(e.id) { mutableStateOf(e.body) }
        // Autosave on every change, debounced by key restart
        LaunchedEffect(title, body) {
            kotlinx.coroutines.delay(400)
            dao.save(e.copy(title = title, body = body, updatedAt = System.currentTimeMillis()))
        }
        Scaffold(topBar = { TopAppBar(title = { Text("Note") }, navigationIcon = { TextButton(onClick = { editing = null }) { Text("Done") } }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notes") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { scope.launch { editing = Note(id = dao.save(Note())) } }) { Icon(Icons.Filled.Add, "New note") } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(notes, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text(n.title.ifBlank { "Untitled" }) },
                    supportingContent = { Text(n.body.take(80), maxLines = 2) },
                    trailingContent = { IconButton(onClick = { scope.launch { dao.delete(n) } }) { Icon(Icons.Filled.Delete, "Delete") } },
                    modifier = Modifier.clickable { editing = n },
                )
            }
        }
    }
}
