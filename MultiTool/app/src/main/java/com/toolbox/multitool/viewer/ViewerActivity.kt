package com.toolbox.multitool.viewer

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.junrar.Archive
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

/** Shows up in "Open with" for PDF, text, ZIP/RAR/7z. Also reachable from the app's File viewer tool. */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data
        setContent { MultiToolTheme { if (uri == null) Text("No file") else FileView(uri) { finish() } } }
    }
}

/** In-app entry: pick any file and view it with the same viewers. */
@Composable
fun OpenFileScreen(onBack: () -> Unit) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberPicker { uri = it.first() }
    val u = uri
    if (u != null) { FileView(u) { uri = null }; return }
    ToolScaffold("File viewer", onBack) {
        EmptyState(Icons.Filled.FolderOpen, "Open a file", "PDF, text and code files, and ZIP, RAR or 7z archives. The same viewers appear in Android's “Open with” menu.")
        FilePickButton("Choose file", onClick = pick)
    }
}

private enum class Type { Pdf, Text, Archive }

private fun typeOf(name: String, mime: String): Type {
    val n = name.lowercase()
    return when {
        mime == "application/pdf" || n.endsWith(".pdf") -> Type.Pdf
        n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".epub") || mime.contains("zip") || mime.contains("rar") || mime.contains("7z") -> Type.Archive
        else -> Type.Text
    }
}

@Composable
private fun FileView(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(uri) { Files.displayName(ctx, uri) }
    val mime = remember(uri) { ctx.contentResolver.getType(uri).orEmpty() }
    when (typeOf(name, mime)) {
        Type.Pdf -> PdfView(uri, name, onClose)
        Type.Archive -> ArchiveView(uri, name, onClose)
        Type.Text -> TextView(uri, name, onClose)
    }
}

// ---------- Text viewer / editor ----------
@Composable
private fun TextView(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(uri) {
        text = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readLimited(8 * 1024 * 1024).toString(Charsets.UTF_8) } }
                .getOrElse { "Cannot open: ${it.message}" }
        }
    }
    ToolScaffold(name, onClose, scroll = false) {
        val t = text
        if (t == null) { CircularProgressIndicator(); return@ToolScaffold }
        ChipRow {
            FilterChip(editable, { editable = !editable }, { Text(if (editable) "Editing" else "Read only") },
                leadingIcon = { Icon(if (editable) Icons.Filled.Edit else Icons.Filled.Lock, null, Modifier.size(18.dp)) })
            FilledTonalIconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(9f) }) { Icon(Icons.Filled.TextDecrease, "Smaller text") }
            FilledTonalIconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(30f) }) { Icon(Icons.Filled.TextIncrease, "Larger text") }
            Pill("${t.lines().size} lines")
            Pill("${fontSize.toInt()} sp")
        }
        OutlinedTextField(
            t, { text = it; dirty = true }, readOnly = !editable, modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write((text ?: "").toByteArray()) }; dirty = false; "Saved" }
                            .getOrElse { "Cannot write in place (${it.message}). Use Save copy." }
                    }
                }
            }, Modifier.weight(1f), Icons.Filled.Save, enabled = dirty)
            TonalButton("Save copy", {
                scope.launch {
                    val f = Files.tempFile(ctx, name).also { it.writeText(text ?: "") }
                    Files.saveToDownloads(ctx, f, name, "text/plain"); f.delete(); status = "Copy saved to Downloads/MultiTool"
                }
            }, Modifier.weight(1f), Icons.Filled.FileCopy)
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024); var total = 0
    while (total < limit) { val r = read(buf, 0, minOf(buf.size, limit - total)); if (r < 0) break; out.write(buf, 0, r); total += r }
    return out.toByteArray()
}

// ---------- PDF viewer: native PdfRenderer, pages rendered lazily, cached, zoomable ----------
private class PdfPages(val pfd: ParcelFileDescriptor) {
    val renderer = PdfRenderer(pfd)
    private val lock = Mutex() // PdfRenderer allows one open page at a time
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    val count get() = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return lock.withLock {
            cache.get(key) ?: withContext(Dispatchers.Default) {
                renderer.openPage(index).use { p ->
                    val h = (widthPx * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).apply { eraseColor(AColor.WHITE) }
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.also { cache.put(key, it) }
        }
    }
    fun close() { renderer.close(); pfd.close() }
}

@Composable
private fun PdfView(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<PdfPages?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(uri) {
        runCatching { pages = PdfPages(ctx.contentResolver.openFileDescriptor(uri, "r")!!) }.onFailure { error = it.message }
    }
    DisposableEffect(Unit) { onDispose { pages?.close() } }

    ToolScaffold(name, onClose, scroll = false) {
        error?.let { Text("Cannot open PDF: $it") }
        val p = pages ?: run { if (error == null) CircularProgressIndicator(); return@ToolScaffold }
        val listState = rememberLazyListState()
        val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }
        val contentDp = LocalConfiguration.current.screenWidthDp - 32
        val density = LocalDensity.current.density
        val widthPx = (contentDp * density * zoom).toInt().coerceAtMost(2800)

        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width((contentDp * zoom).dp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.count) { i ->
                    val bmp by produceState<Bitmap?>(null, i, widthPx) { value = p.render(i, widthPx) }
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    else Box(Modifier.fillMaxWidth().height((contentDp * 1.4f).dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = current.toFloat(), onValueChange = { v -> scope.launch { listState.scrollToItem(v.toInt()) } },
                valueRange = 0f..(p.count - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f), enabled = p.count > 1,
            )
            Pill("${current + 1} / ${p.count}", Modifier.padding(start = 12.dp))
        }
        ChipRow { listOf(1f, 1.5f, 2f, 3f).forEach { z -> FilterChip(zoom == z, { zoom = z }, { Text("${(z * 100).toInt()}%") }, leadingIcon = if (z == 1f) ({ Icon(Icons.Filled.ZoomIn, null, Modifier.size(18.dp)) }) else null) } }
    }
}

// ---------- Archive viewer: ZIP (java.util.zip), 7z (commons-compress), RAR (junrar) ----------
private data class Entry(val path: String, val size: Long, val dir: Boolean)

@Composable
private fun ArchiveView(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cached by remember { mutableStateOf<File?>(null) }
    val lower = name.lowercase()

    LaunchedEffect(uri) {
        runCatching {
            val f = Files.copyToCache(ctx, uri, name); cached = f
            entries = withContext(Dispatchers.IO) { listEntries(f, lower) }
        }.onFailure { status = "Cannot open archive: ${it.message}" }
    }
    DisposableEffect(Unit) { onDispose { cached?.delete() } }

    ToolScaffold(name, onClose, scroll = false) {
        val list = entries
        if (list == null) { if (status.isEmpty()) CircularProgressIndicator() else Text(status, color = MaterialTheme.colorScheme.error); return@ToolScaffold }
        ChipRow { Pill("${list.count { !it.dir }} files", icon = Icons.Filled.Description); Pill(Files.human(list.sumOf { it.size }), icon = Icons.Filled.Storage) }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Filter files") }, singleLine = true, shape = MaterialTheme.shapes.large,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(list.filter { query.isBlank() || it.path.contains(query, true) }) { e ->
                ListItem(
                    leadingContent = { Icon(if (e.dir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(e.path.trimEnd('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (!e.dir) Text(Files.human(e.size)) },
                )
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        PrimaryButton("Extract all", icon = Icons.Filled.Unarchive, loading = busy, onClick = {
            scope.launch {
                busy = true; status = "Extracting…"
                status = runCatching { withContext(Dispatchers.IO) { extractAll(ctx, cached!!, lower, Files.baseName(name)) } }
                    .map { "Extracted to $it" }.getOrElse { "✗ ${it.message}" }
                busy = false
            }
        })
    }
}

private fun listEntries(f: File, lower: String): List<Entry> = when {
    lower.endsWith(".7z") -> SevenZFile(f).use { z -> generateSequence { z.nextEntry }.map { Entry(it.name, it.size, it.isDirectory) }.toList() }
    lower.endsWith(".rar") -> Archive(f).use { a -> a.fileHeaders.map { Entry(it.fileName, it.fullUnpackSize, it.isDirectory) } }
    else -> ZipFile(f).use { z -> z.entries().asSequence().map { Entry(it.name, it.size, it.isDirectory) }.toList() }
}

private fun extractAll(ctx: android.content.Context, f: File, lower: String, base: String): String {
    val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "extracted/$base").apply { mkdirs() }
    fun safe(path: String): File {
        val out = File(root, path)
        require(out.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Blocked unsafe path: $path" } // zip-slip guard
        return out
    }
    when {
        lower.endsWith(".7z") -> SevenZFile(f).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = safe(e.name)
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = z.read(b); if (n < 0) break; o.write(b, 0, n) } } }
            }
        }
        lower.endsWith(".rar") -> Archive(f).use { a ->
            for (h in a.fileHeaders) {
                val out = safe(h.fileName.replace('\\', '/'))
                if (h.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { a.extractFile(h, it) } }
            }
        }
        else -> ZipFile(f).use { z ->
            for (e in z.entries()) {
                val out = safe(e.name)
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it, 64 * 1024) } } }
            }
        }
    }
    return root.absolutePath
}
