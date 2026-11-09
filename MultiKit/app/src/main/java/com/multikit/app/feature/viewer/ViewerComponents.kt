package com.multikit.app.feature.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.OutputBlock
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SharedTextView(text: String) {
    SectionCard("Shared text") { OutputBlock(text) }
}

@Composable
fun TextViewer(uri: Uri, meta: FileMeta) {
    val context = LocalContext.current
    var content by remember(uri) { mutableStateOf<String?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { Files.readText(context, uri) } }
            .onSuccess { content = it }
            .onFailure { error = it.message ?: "Could not read file" }
    }
    ErrorBanner(error)
    content?.let { SectionCard(meta.name) { OutputBlock(it) } }
}

@Composable
fun ImageViewer(uri: Uri, meta: FileMeta) {
    SectionCard(meta.name) {
        AsyncImage(
            model = uri,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NotSupportedYet(label: String, meta: FileMeta) {
    SectionCard(meta.name) {
        Text(
            "The $label viewer is not implemented yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun PdfViewer(uri: Uri, meta: FileMeta) = NotSupportedYet("PDF", meta)

@Composable
fun EpubViewer(uri: Uri, meta: FileMeta) = NotSupportedYet("EPUB", meta)

@Composable
fun ArchiveBrowser(uri: Uri, meta: FileMeta, onOpenFile: (java.io.File) -> Unit) =
    NotSupportedYet("archive", meta)

@Composable
fun MediaViewer(uri: Uri, meta: FileMeta, isVideo: Boolean) =
    NotSupportedYet(if (isVideo) "video" else "audio", meta)
