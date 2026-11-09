package com.multikit.app.feature.viewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.FilePickButton
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One entry of the internal viewer back stack. */
data class ViewerTarget(
    val uri: Uri,
    val nameHint: String? = null,
    val forceText: Boolean = false,
)

/** Resolved metadata for the target currently on top of the stack. */
data class FileMeta(
    val name: String,
    val mime: String,
    val size: Long,
)

enum class ViewerKind { PDF, TEXT, EPUB, ARCHIVE, IMAGE, VIDEO, AUDIO, UNKNOWN }

private val textExtensions = setOf(
    "txt", "md", "json", "xml", "csv", "log", "kt", "kts", "java", "py", "js", "mjs",
    "html", "htm", "css", "yaml", "yml", "toml", "ini", "srt", "ass", "vtt",
    "ts", "tsx", "jsx", "c", "cpp", "h", "sh", "bat", "gradle", "properties", "conf", "svg",
)

private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "xz")

private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts")
private val audioExtensions = setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac", "mka", "wma")

/** Decide which viewer to use from the file extension first, then the MIME type. */
fun viewerKindFor(name: String, mime: String): ViewerKind {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    val type = mime.lowercase(Locale.US)
    return when {
        ext == "pdf" || type == "application/pdf" -> ViewerKind.PDF
        ext == "epub" || type == "application/epub+zip" -> ViewerKind.EPUB
        ext in archiveExtensions || type in setOf(
            "application/zip", "application/x-zip-compressed", "application/vnd.rar",
            "application/x-rar-compressed", "application/x-7z-compressed",
            "application/x-tar", "application/gzip", "application/x-gzip", "application/x-xz",
        ) -> ViewerKind.ARCHIVE
        ext in textExtensions || type.startsWith("text/") || type in setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-javascript", "application/x-sh", "application/x-shellscript",
            "application/xhtml+xml",
        ) -> ViewerKind.TEXT
        ext in imageExtensions || type.startsWith("image/") -> ViewerKind.IMAGE
        ext in videoExtensions || type.startsWith("video/") -> ViewerKind.VIDEO
        ext in audioExtensions || type.startsWith("audio/") -> ViewerKind.AUDIO
        else -> ViewerKind.UNKNOWN
    }
}

/** Blocking metadata lookup; always call on Dispatchers.IO. */
fun resolveFileMeta(context: Context, uri: Uri, nameHint: String?): FileMeta {
    val name = nameHint ?: Files.displayName(context, uri)
    val mime = runCatching { Files.mime(context, uri) }.getOrDefault("application/octet-stream")
    var size = -1L
    if (uri.scheme == "file") {
        uri.path?.let { path ->
            val file = File(path)
            if (file.exists()) size = file.length()
        }
    } else {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) size = c.getLong(idx)
            }
        }
    }
    return FileMeta(name, mime, size)
}

/**
 * Height for lazy lists embedded in [ToolScaffold]'s scrollable column. A LazyColumn
 * cannot be measured with infinite height constraints, so we reserve space for the
 * surrounding controls and give the list a bounded height.
 */
@Composable
fun viewerListHeight(reserved: Dp = 280.dp): Dp {
    val screen = LocalConfiguration.current.screenHeightDp.dp
    return (screen - reserved).coerceAtLeast(280.dp)
}

/** Label / value row used across the viewer screens. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
        )
    }
}

/** Copy content Uris into the cache when needed, then share through the FileProvider. */
suspend fun shareUriAsFile(context: Context, uri: Uri, meta: FileMeta) {
    val file = if (uri.scheme == "file") {
        File(uri.path ?: error("Invalid file path"))
    } else {
        withContext(Dispatchers.IO) { Files.copyToCache(context, uri, meta.name) }
    }
    Files.share(context, file)
}

/**
 * Universal file viewer. Handles Open-with / share Intents (uriString or sharedText),
 * keeps an internal back stack so files extracted from archives can be opened in place,
 * and falls back to a file picker when launched without input.
 */
@Composable
fun FileViewerScreen(
    uriString: String?,
    sharedText: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Seed the stack synchronously so the first frame already shows the right target.
    val stack = remember(uriString) {
        mutableStateListOf<ViewerTarget>().apply {
            if (!uriString.isNullOrBlank()) {
                runCatching { Uri.parse(uriString) }.getOrNull()?.let { add(ViewerTarget(it)) }
            }
        }
    }

    var meta by remember { mutableStateOf<FileMeta?>(null) }
    var metaError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val current = stack.lastOrNull()

    LaunchedEffect(current?.uri, current?.nameHint, attempt) {
        meta = null
        metaError = null
        if (current != null) {
            val result = withContext(Dispatchers.IO) {
                runCatching { resolveFileMeta(context, current.uri, current.nameHint) }
            }
            result
                .onSuccess { meta = it }
                .onFailure { metaError = it.message ?: "Cannot read this file" }
        }
    }

    val handleBack: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }

    val pushFile: (File) -> Unit = { file ->
        stack.add(ViewerTarget(Uri.fromFile(file), nameHint = file.name))
    }

    ToolScaffold(
        title = meta?.name ?: current?.nameHint ?: "File Viewer",
        onBack = handleBack,
    ) {
        ErrorBanner(metaError)

        val target = current
        val resolved = meta
        when {
            target == null && !sharedText.isNullOrBlank() -> SharedTextView(sharedText)

            target == null -> SectionCard("Open a file") {
                Text(
                    "View PDFs, EPUB books, text & code, images, audio/video and archives " +
                        "(zip, rar, 7z, tar, gz). You can also share or \"Open with\" MultiKit " +
                        "from any other app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilePickButton(label = "Choose a file") { uri -> stack.add(ViewerTarget(uri)) }
            }

            metaError != null -> SectionCard("Could not open the file") {
                Text(
                    "The file may have been moved, or MultiKit no longer has permission to read it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
                    OutlinedButton(onClick = { stack.add(target.copy(forceText = true)) }) {
                        Text("Try as text")
                    }
                }
            }

            resolved == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> key(target.uri, target.forceText) {
                val kind = if (target.forceText) ViewerKind.TEXT else viewerKindFor(resolved.name, resolved.mime)
                when (kind) {
                    ViewerKind.PDF -> PdfViewer(target.uri, resolved)
                    ViewerKind.TEXT -> TextViewer(target.uri, resolved)
                    ViewerKind.EPUB -> EpubViewer(target.uri, resolved)
                    ViewerKind.ARCHIVE -> ArchiveBrowser(target.uri, resolved, onOpenFile = pushFile)
                    ViewerKind.IMAGE -> ImageViewer(target.uri, resolved)
                    ViewerKind.VIDEO -> MediaViewer(target.uri, resolved, isVideo = true)
                    ViewerKind.AUDIO -> MediaViewer(target.uri, resolved, isVideo = false)
                    ViewerKind.UNKNOWN -> UnknownFileView(
                        uri = target.uri,
                        meta = resolved,
                        onOpenAsText = { stack.add(target.copy(forceText = true)) },
                    )
                }
            }
        }
    }
}

/** Fallback screen for unsupported types: metadata, text attempt and sharing. */
@Composable
private fun UnknownFileView(
    uri: Uri,
    meta: FileMeta,
    onOpenAsText: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    SectionCard("File info") {
        InfoRow("Name", meta.name)
        InfoRow("Type", meta.mime)
        InfoRow("Size", if (meta.size >= 0) Files.formatBytes(meta.size) else "Unknown")
        Text(
            "MultiKit has no dedicated viewer for this file type.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ErrorBanner(task.error)
    SuccessBanner(task.message)

    Button(onClick = onOpenAsText, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Description, contentDescription = null)
        Text("  Open as text", modifier = Modifier.padding(start = 4.dp))
    }
    OutlinedButton(
        onClick = { task.launch(scope) { shareUriAsFile(context, uri, meta) } },
        enabled = !task.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text("  Share file", modifier = Modifier.padding(start = 4.dp))
    }
    if (task.busy) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.height(32.dp))
        }
    }
}