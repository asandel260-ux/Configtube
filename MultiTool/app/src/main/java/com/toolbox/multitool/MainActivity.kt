package com.toolbox.multitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.MultiToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Thread { com.toolbox.multitool.core.Files.cleanStale(applicationContext) }.start()
        setContent { MultiToolTheme { App() } }
    }
}

@Composable
private fun App() {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val tool = ToolRegistry.firstOrNull { it.id == openId }
    BackHandler(enabled = tool != null) { openId = null }
    Surface(color = MaterialTheme.colorScheme.background) {
        Crossfade(targetState = tool, label = "screen") { t ->
            if (t != null) t.content { openId = null } else Home { openId = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query) {
        ToolRegistry.filter {
            query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true)
        }
    }
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("MultiTool") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = behavior,
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = full) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                    placeholder = { Text("Search ${ToolRegistry.size} tools") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filtered.isEmpty()) {
                item(span = full) {
                    Text("No tool matches “$query”", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
            Category.entries.forEach { cat ->
                val tools = filtered.filter { it.category == cat }
                if (tools.isNotEmpty()) {
                    item(key = "h_${cat.name}", span = full) {
                        Text(
                            cat.label, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                    items(tools, key = { it.id }) { tool -> ToolCard(tool, cat.ordinal) { onOpen(tool.id) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(tool: Tool, tone: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Each category gets its own accent so the grid is scannable at a glance.
    val (bg, fg) = when (tone % 3) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = fg)
            }
            Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tool.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
