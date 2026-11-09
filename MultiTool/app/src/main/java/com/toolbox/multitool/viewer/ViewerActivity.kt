package com.toolbox.multitool.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.junrar.Archive
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.MultiToolTheme
import com.toolbox.multitool.ui.ToolScaffold
import com.toolbox.multitool.ui.rememberPicker
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
        Text("Open a PDF, a text/code file, or an archive (ZIP, RAR, 7z). The same viewers are offered by Android's “Open with” menu.")
        Button(onClick = pick) { Text("Choose file") }
    }
}

private enum class Kind { Pdf, Text, Archive }

private fun kindOf(name: String, mime: String): Kind {
    val n = name.lowercase()
    return when {
        mime == "application/pdf" || n.endsWith(".pdf") -> Kind.Pdf
        n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".epub") || mime.contains("zip") || mime.contains("rar") || mime.contains("7z") -> Kind.Archive
        else -> Kind.Text
    }
}

@Composable
private fun FileView(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(uri) { Files.displayName(ctx, uri) }
    val mime = remember(uri) { ctx.contentResolver.getType(uri).orEmpty() }
    when (kindOf(name, mime)) {
        Kind.Pdf -> PdfView(uri, name, onClose)
        Kind.Archive -> ArchiveView(uri, name, onClose)
        Kind.Text -> TextView(uri, name, onClose)
    }
}

// ---------- Text viewer / editor (reads with a size cap, saves back in place when the provider allows) ----------
@Composable
private fun TextView(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(uri) {
        text = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes(8 * 1024 * 1024).toString(Charsets.UTF_8) } }
                .getOrElse { "Cannot open: ${it.message}" }
        }
    }
    ToolScaffold(name, onClose, scroll = false) {
        val t = text
        if (t == null) CircularProgressIndicator()
        else {
            OutlinedTextField(
                t, { text = it; dirty = true }, modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = dirty, onClick = {
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write((text ?: "").toByteArray()) }; dirty = false; "Saved" }
                                .getOrElse { "Cannot write in place (${it.message}). Use Save copy." }
                        }
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = Files.tempFile(ctx, name).also { it.writeText(text ?: "") }
                        Files.saveToDownloads(ctx, f, name, "text/plain"); f.delete(); status = "Copy saved to Downloads/MultiTool"
                    }
                }) { Text("Save copy") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024); var total = 0
    while (total < limit) { val r = read(buf, 0, minOf(buf.size, limit - total)); if (r < 0) break; out.write(buf, 0, r); total += r }
    return out.toByteArray()
}

// ---------- PDF viewer: native PdfRenderer, pages rendered lazily and only when scrolled into view ----------
private class PdfPages(val pfd: ParcelFileDescriptor) {
    val renderer = PdfRenderer(pfd)
    private val lock = Mutex() // PdfRenderer allows one open page at a time
    val count get() = renderer.pageCount
    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            renderer.openPage(index).use { p ->
                val h = (widthPx * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }
    fun close() { renderer.close(); pfd.close() }
}

@Composable
private fun PdfView(uri: Uri, name: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf<PdfPages?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")!!
            pages = PdfPages(pfd)
        }.onFailure { error = it.message }
    }
    DisposableEffect(Unit) { onDispose { pages?.close() } }

    ToolScaffold(name, onClose, scroll = false) {
        error?.let { Text("Cannot open PDF: $it") }
        val p = pages
        if (p != null) {
            val widthPx = with(LocalDensity.current) { (ctx.resources.displayMetrics.widthPixels).coerceAtMost(1600) }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.count) { i ->
                    val bmp by produceState<Bitmap?>(null, i) { value = p.render(i, widthPx) }
                    bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                        ?: Box(Modifier.fillMaxWidth().height(400.dp)) { CircularProgressIndicator(Modifier.padding(24.dp)) }
                }
            }
        }
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
        if (list == null && status.isEmpty()) CircularProgressIndicator()
        if (list != null) {
            Text("${list.count { !it.dir }} files · ${Files.human(list.sumOf { it.size })}")
            Button(onClick = {
                scope.launch {
                    status = "Extracting…"
                    status = runCatching { withContext(Dispatchers.IO) { extractAll(ctx, cached!!, lower, Files.baseName(name)) } }
                        .map { "Extracted to $it" }.getOrElse { "✗ ${it.message}" }
                }
            }) { Text("Extract all") }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) { e ->
                    ListItem(
                        headlineContent = { Text(e.path, maxLines = 1) },
                        supportingContent = { if (!e.dir) Text(Files.human(e.size)) },
                    )
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
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
