package com.multikit.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.multikit.app.navigation.Routes

private data class Tool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val category: String,
)

private val tools = listOf(
    Tool("Video Editor", "Trim, crop, rotate, speed, convert", Icons.Filled.Movie, Routes.VIDEO_EDITOR, "Media"),
    Tool("Audio Editor", "Cut, join, fade, volume, convert", Icons.Filled.AudioFile, Routes.AUDIO_EDITOR, "Media"),
    Tool("Music Tags", "Title, artist, album, cover, length", Icons.Filled.Tag, Routes.MUSIC_META, "Media"),
    Tool("Circle Video", "Video ↔ circle video messages", Icons.Filled.Circle, Routes.CIRCLE_VIDEO, "Media"),
    Tool("Sticker Lab", "Image/video ↔ stickers", Icons.Filled.EmojiEmotions, Routes.STICKERS, "Media"),
    Tool("Image Converter", "PNG, JPG, WebP, BMP, GIF", Icons.Filled.Image, Routes.IMAGE_CONVERT, "Images & Documents"),
    Tool("Image Resizer", "Scale, compress, exact dimensions", Icons.Filled.PhotoSizeSelectLarge, Routes.IMAGE_RESIZE, "Images & Documents"),
    Tool("PDF Tools", "Images → PDF, extract PDF images", Icons.Filled.Description, Routes.PDF_TOOLS, "Images & Documents"),
    Tool("File Viewer", "PDF, books, text, zip/rar/7z", Icons.Filled.Description, Routes.viewer(), "Images & Documents"),
    Tool("RTL Text Fixer", "Persian/English mixed text alignment", Icons.Filled.SortByAlpha, Routes.RTL_TEXT, "Text & Notes"),
    Tool("Notes & Mind Maps", "Infinite canvas, expandable nodes", Icons.Filled.AccountTree, Routes.NOTES, "Text & Notes"),
    Tool("QR Scanner", "Scan & generate QR codes", Icons.Filled.QrCodeScanner, Routes.QR, "Utilities"),
    Tool("Network Analyzer", "Ping, traceroute, ports, DNS, WHOIS", Icons.Filled.Language, Routes.NETWORK, "Utilities"),
    Tool("Crypto & Encoding", "Encrypt/decrypt, hash, encode/decode", Icons.Filled.Lock, Routes.CRYPTO, "Utilities"),
    Tool("Screenshot", "Capture + Quick Settings tile", Icons.Filled.Screenshot, Routes.SCREENSHOT, "System"),
    Tool("Cleaner", "App caches & junk file analysis", Icons.Filled.CleaningServices, Routes.CLEANER, "System"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = tools.filter {
        query.isBlank() ||
            it.title.contains(query, ignoreCase = true) ||
            it.subtitle.contains(query, ignoreCase = true)
    }
    val grouped = filtered.groupBy { it.category }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("MultiKit", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search tools…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                grouped.forEach { (category, list) ->
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(list, key = { it.route + it.title }) { tool ->
                        ToolCard(tool = tool, onClick = { onOpen(tool.route) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().height(128.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    tool.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(tool.title, style = MaterialTheme.typography.titleSmall)
            Text(
                tool.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}