package com.multikit.app.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.util.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Internal navigation: the notes tool never touches the app nav controller. */
private sealed interface NotesDestination {
    data object NoteList : NotesDestination
    data class EditNote(val item: NoteItem) : NotesDestination
    data class EditMap(val item: NoteItem) : NotesDestination
}

private val ListDateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

/**
 * Notes & Mind Maps entry point: saved-item list with search, create FABs and
 * delete confirmation; routes internally to the note editor or the mind-map
 * canvas and refreshes the list when an editor closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NoteStore(context) }

    var destination by remember { mutableStateOf<NotesDestination>(NotesDestination.NoteList) }
    var items by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NoteItem?>(null) }

    fun refresh() {
        scope.launch {
            try {
                items = store.loadAll()
                error = null
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = "Could not load notes: ${t.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun openItem(item: NoteItem) {
        destination = if (item.type == NoteTypes.MINDMAP) {
            NotesDestination.EditMap(item)
        } else {
            NotesDestination.EditNote(item)
        }
    }

    fun createItem(item: NoteItem) {
        scope.launch {
            try {
                store.save(item)
                openItem(item)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = "Could not create item: ${t.message}"
            }
        }
    }

    when (val d = destination) {
        NotesDestination.NoteList -> {
            ToolScaffold(title = "Notes & Mind Maps", onBack = onBack) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search titles…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            createItem(NoteItem(type = NoteTypes.NOTE, title = "Untitled note"))
                        },
                        icon = { Icon(Icons.Filled.Notes, contentDescription = null) },
                        text = { Text("New note") },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            createItem(
                                NoteItem(
                                    type = NoteTypes.MINDMAP,
                                    title = "Untitled mind map",
                                    root = MindNode(text = "Central idea"),
                                )
                            )
                        },
                        icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                        text = { Text("New mind map") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
                ErrorBanner(error)
                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = items.filter {
                        query.isBlank() || it.title.contains(query, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        SectionCard {
                            Text(
                                if (items.isEmpty()) {
                                    "No saved items yet. Create a note or a mind map with the buttons above."
                                } else {
                                    "No items match \"$query\"."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    filtered.forEach { item ->
                        NoteCard(
                            item = item,
                            onClick = { openItem(item) },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
        }

        is NotesDestination.EditNote -> {
            NoteEditorScreen(
                item = d.item,
                store = store,
                onDone = {
                    destination = NotesDestination.NoteList
                    refresh()
                },
            )
        }

        is NotesDestination.EditMap -> {
            MindMapEditorScreen(
                item = d.item,
                store = store,
                onDone = {
                    destination = NotesDestination.NoteList
                    refresh()
                },
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.title}\"?") },
            text = { Text("This permanently removes the item${if (target.type == NoteTypes.MINDMAP) " and all of its mind-map nodes" else ""}.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        try {
                            store.delete(target.id)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            error = "Delete failed: ${t.message}"
                        }
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    item: NoteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val isMap = item.type == NoteTypes.MINDMAP
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDelete),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (isMap) Icons.Filled.AccountTree else Icons.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            if (isMap) "MAP" else "NOTE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    ListDateFormat.format(Date(item.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    previewOf(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun previewOf(item: NoteItem): String =
    if (item.type == NoteTypes.MINDMAP) {
        val rootText = item.root?.text?.ifBlank { "Mind map" } ?: "Mind map"
        "$rootText · ${countNodes(item.root)} nodes"
    } else {
        item.body.replace('\n', ' ').trim().take(120).ifBlank { "Empty note" }
    }

/**
 * Plain note editor: title + large body, debounced autosave (800ms), explicit
 * save, word/char counts and share. Back (bar or system) saves before leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorScreen(
    item: NoteItem,
    store: NoteStore,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(item.title) }
    var body by remember { mutableStateOf(item.body) }
    var dirty by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun persist(showMessage: Boolean) {
        item.title = title.ifBlank { "Untitled note" }
        item.body = body
        item.updatedAt = System.currentTimeMillis()
        store.save(item)
        dirty = false
        if (showMessage) savedMessage = "Saved"
    }

    fun persistAndExit() {
        scope.launch {
            try {
                persist(false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Best effort on exit.
            }
            onDone()
        }
    }

    BackHandler { persistAndExit() }

    // Debounced autosave: every keystroke restarts the 800ms timer.
    LaunchedEffect(title, body) {
        if (dirty) {
            delay(800)
            try {
                persist(false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                errorMessage = "Autosave failed: ${t.message}"
            }
        }
    }
    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            delay(2000)
            savedMessage = null
        }
    }

    val wordCount = remember(body) {
        if (body.isBlank()) 0 else body.trim().split(Regex("\\s+")).size
    }

    ToolScaffold(
        title = "Edit note",
        onBack = { persistAndExit() },
        actions = {
            IconButton(onClick = {
                scope.launch {
                    try {
                        persist(true)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        errorMessage = "Save failed: ${t.message}"
                    }
                }
            }) {
                Icon(Icons.Filled.Save, contentDescription = "Save")
            }
            IconButton(onClick = {
                Files.shareText(context, "${title.ifBlank { "Untitled note" }}\n\n$body".trim())
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
        },
    ) {
        LabeledField(
            label = "Title",
            value = title,
            onValueChange = {
                title = it
                dirty = true
            },
        )
        LabeledField(
            label = "Body",
            value = body,
            onValueChange = {
                body = it
                dirty = true
            },
            singleLine = false,
            minLines = 12,
        )
        Text(
            "$wordCount words · ${body.length} characters · autosaves as you type",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SuccessBanner(savedMessage)
        ErrorBanner(errorMessage)
    }
}