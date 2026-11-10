package com.toolbox.multitool.notes

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).notes() }
    val scope = rememberCoroutineScope()
    val notes by dao.notes().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Note?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }

    fun delete(n: Note) {
        scope.launch {
            dao.delete(n); editing = null
            if (n.title.isNotBlank() || n.body.isNotBlank()) {
                val r = snack.showSnackbar("Note deleted", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) dao.save(n)
            }
        }
    }

    BackHandler(enabled = editing != null) { editing = null }
    val e = editing
    if (e != null) {
        NoteEditor(e, dao, onClose = { editing = null }, onDelete = { delete(it) })
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notes") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { editing = Note(id = dao.save(Note())) } },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New note") },
            )
        },
    ) { pad ->
        val shown = notes.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search notes") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (shown.isEmpty()) item {
                if (notes.isEmpty()) EmptyState(Icons.Filled.EditNote, "No notes yet", "Tap New note to write your first one. Everything saves automatically.")
                else EmptyState(Icons.Filled.SearchOff, "Nothing found", "No note matches “$query”.")
            }
            items(shown, key = { it.id }) { n ->
                Card(
                    onClick = { editing = n }, shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateUtils.getRelativeTimeSpanString(n.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { delete(n) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(note: Note, dao: NoteDao, onClose: () -> Unit, onDelete: (Note) -> Unit) {
    val ctx = LocalContext.current
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var saved by remember { mutableStateOf(true) }

    LaunchedEffect(title, body) {
        if (title == note.title && body == note.body) return@LaunchedEffect
        saved = false
        delay(400)
        dao.save(note.copy(title = title, body = body, updatedAt = System.currentTimeMillis()))
        saved = true
    }
    fun finish() { if (title.isBlank() && body.isBlank()) onDelete(note.copy(title = "", body = "")) else onClose() }
    BackHandler { finish() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (saved) "Saved" else "Saving…", style = MaterialTheme.typography.titleSmall) },
            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { copyText(ctx, "$title\n\n$body".trim()) }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                IconButton(onClick = { shareText(ctx, "$title\n\n$body".trim()) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { onDelete(note.copy(title = title, body = body)) }) { Icon(Icons.Filled.Delete, "Delete") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).imePadding().padding(horizontal = 16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium)
            OutlinedTextField(body, { body = it }, label = { Text("Write something…") }, modifier = Modifier.fillMaxWidth().weight(1f))
            val words = body.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            Text("$words words · ${body.length} characters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
