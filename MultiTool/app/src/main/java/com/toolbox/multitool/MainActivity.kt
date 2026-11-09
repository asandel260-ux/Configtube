package com.toolbox.multitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.ui.MultiToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MultiToolTheme { App() } }
    }
}

@Composable
private fun App() {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val tool = ToolRegistry.firstOrNull { it.id == openId }
    BackHandler(enabled = tool != null) { openId = null }
    if (tool != null) tool.content { openId = null } else Home { openId = it }
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
    Scaffold(topBar = { TopAppBar(title = { Text("MultiTool") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search tools") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Category.entries.forEach { cat ->
                val tools = filtered.filter { it.category == cat }
                if (tools.isNotEmpty()) {
                    item(key = "h_${cat.name}") {
                        Text(cat.label, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                    items(tools, key = { it.id }) { t ->
                        ListItem(
                            headlineContent = { Text(t.title) },
                            supportingContent = { Text(t.subtitle) },
                            leadingContent = { Icon(t.icon, null) },
                            modifier = Modifier.clickable { onOpen(t.id) },
                        )
                    }
                }
            }
        }
    }
}
