package com.toolbox.multitool.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.image.ImageEngine
import com.toolbox.multitool.image.ImgFormat
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class Found(val file: File, val name: String, val w: Int, val h: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfExtractScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val saved = remember { mutableStateListOf<Saved>() }

    DisposableEffect(Unit) { onDispose { found.forEach { it.file.delete() } } }

    val pick = rememberPicker(arrayOf("application/pdf")) { uris ->
        val uri = uris.first()
        scope.launch {
            found.forEach { it.file.delete() }
            found.clear(); selected.clear(); saved.clear()
            pdfName = Files.displayName(ctx, uri); busy = true; status = "Reading PDF…"
            runCatching { extractImages(ctx, uri) { page, total -> status = "Scanning page $page of $total…" } }
                .onSuccess { list ->
                    found.addAll(list); list.forEach { selected[it.name] = true }
                    status = if (list.isEmpty()) "No embedded images found. The pages may be vector graphics or text only." else ""
                }
                .onFailure { status = "✗ ${it.message}" }
            busy = false
        }
    }

    ToolScaffold("PDF → images", onBack) {
        if (pdfName.isEmpty()) {
            EmptyState(Icons.Filled.PictureAsPdf, "Extract images from a PDF", "Every embedded picture is pulled out at its original resolution. You choose which ones to keep.")
            FilePickButton("Choose PDF", onClick = pick)
            return@ToolScaffold
        }
        FileInfoCard(pdfName, if (busy) "Scanning…" else "${found.size} image${if (found.size == 1) "" else "s"} found", Icons.Filled.PictureAsPdf, onChange = pick)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (found.isNotEmpty()) {
            val count = found.count { selected[it.name] == true }
            SectionCard("Tap to select · $count of ${found.size}") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found.forEach { f ->
                        val on = selected[f.name] == true
                        Box(
                            Modifier.size(100.dp).clip(MaterialTheme.shapes.medium)
                                .border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
                                .clickable { selected[f.name] = !on },
                        ) {
                            Thumb(Uri.fromFile(f.file), Modifier.fillMaxSize(), 300)
                            if (on) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "${f.w}×${f.h}", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
                                color = Color.White, fontSize = 10.sp,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Select all", { found.forEach { selected[it.name] = true } }, Modifier.weight(1f))
                    SecondaryButton("None", { found.forEach { selected[it.name] = false } }, Modifier.weight(1f))
                }
            }
            PrimaryButton("Save $count image${if (count == 1) "" else "s"}", icon = Icons.Filled.Save, enabled = count > 0, onClick = {
                scope.launch {
                    saved.clear()
                    found.filter { selected[it.name] == true }.forEach { f ->
                        Files.saveToDownloads(ctx, f.file, f.name, "image/png")?.let { saved.add(Saved(f.name, it, "image/png")) }
                    }
                }
            })
        }
        ResultsCard(saved)
    }
}

private suspend fun extractImages(ctx: Context, uri: Uri, onPage: (Int, Int) -> Unit): List<Found> = withContext(Dispatchers.IO) {
    PDFBoxResourceLoader.init(ctx)
    val base = Files.baseName(Files.displayName(ctx, uri))
    val file = Files.copyToCache(ctx, uri)
    val out = mutableListOf<Found>()
    try {
        PDDocument.load(file).use { doc ->
            fun walk(res: PDResources?, page: Int, depth: Int) {
                if (res == null || depth > 4) return
                for (xName in res.xObjectNames) {
                    when (val x = res.getXObject(xName)) {
                        is PDImageXObject -> runCatching {
                            val bmp = x.image
                            val name = "${base}_p${page}_${out.size + 1}.png"
                            val tmp = Files.tempFile(ctx, name)
                            ImageEngine.write(bmp, ImgFormat.PNG, 100, tmp)
                            out += Found(tmp, name, bmp.width, bmp.height)
                            bmp.recycle()
                        }
                        is PDFormXObject -> walk(x.resources, page, depth + 1)
                        else -> Unit
                    }
                }
            }
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, p -> onPage(i + 1, total); walk(p.resources, i + 1, 0) }
        }
    } finally { file.delete() }
    out
}

// ---------------------------------------------------------------------------------------------

private enum class PageSize(val label: String, val w: Int, val h: Int) {
    A4("A4", 595, 842), Letter("Letter", 612, 792), Image("Same as image", 0, 0)
}

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<Uri>() }
    var page by remember { mutableStateOf(PageSize.A4) }
    var margin by remember { mutableFloatStateOf(16f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf("") }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf("image/*")) { images.addAll(it); saved.clear() }

    ToolScaffold("Images → PDF", onBack) {
        if (images.isEmpty()) {
            EmptyState(Icons.Filled.PhotoLibrary, "Build a PDF from pictures", "Add images, put them in order and see how a page will look before creating the file.")
            FilePickButton("Add images", onClick = pick)
            return@ToolScaffold
        }

        SectionCard("${images.size} page${if (images.size == 1) "" else "s"}") {
            images.toList().forEachIndexed { i, u ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Thumb(u, Modifier.size(52.dp))
                    Text(Files.displayName(ctx, u), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    IconButton(enabled = i > 0, onClick = { images.add(i - 1, images.removeAt(i)) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = i < images.size - 1, onClick = { images.add(i + 1, images.removeAt(i)) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { images.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Add more", pick, Modifier.weight(1f), Icons.Filled.AddPhotoAlternate)
                SecondaryButton("Sort by name", { val s = images.sortedBy { Files.displayName(ctx, it).lowercase() }; images.clear(); images.addAll(s) }, Modifier.weight(1f), Icons.Filled.SortByAlpha)
            }
        }

        SectionCard("Page") {
            ChipRow { PageSize.entries.forEach { p -> FilterChip(page == p, { page = p }, { Text(p.label) }) } }
            if (page != PageSize.Image) {
                Text("Margin ${margin.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                Slider(margin, { margin = it }, valueRange = 0f..64f)
            }
            // live layout preview of the first page
            val ratio = if (page == PageSize.Image) 3f / 4f else page.w.toFloat() / page.h
            val previewH = 220.dp
            val scale = previewH.value * ratio / (if (page == PageSize.Image) 595f else page.w.toFloat())
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.height(previewH).aspectRatio(ratio).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline)
                        .padding(if (page == PageSize.Image) 0.dp else (margin * scale).dp),
                ) { Thumb(images.first(), Modifier.fillMaxSize(), 640, ContentScale.Fit) }
            }
        }

        Busy(progress, "Creating PDF…")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton("Create PDF", icon = Icons.Filled.PictureAsPdf, loading = progress != null, onClick = {
            scope.launch {
                progress = 0f; error = ""; saved.clear()
                runCatching { buildPdf(ctx, images.toList(), page, margin) { progress = it } }
                    .onSuccess { saved.add(it) }.onFailure { error = "✗ ${it.message}" }
                progress = null
            }
        })
        ResultsCard(saved)
    }
}

private suspend fun buildPdf(ctx: Context, uris: List<Uri>, size: PageSize, margin: Float, onProgress: (Float) -> Unit): Saved {
    val name = "images_${System.currentTimeMillis() / 1000}.pdf"
    val tmp = Files.tempFile(ctx, name)
    val pdf = PdfDocument()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    uris.forEachIndexed { i, u ->
        val bmp = ImageEngine.decode(ctx, u, maxSide = 2600) // bounds memory for huge photos
        withContext(Dispatchers.Default) {
            val pw = if (size == PageSize.Image) bmp.width else size.w
            val ph = if (size == PageSize.Image) bmp.height else size.h
            val m = if (size == PageSize.Image) 0f else margin
            val pg = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val c: Canvas = pg.canvas
            c.drawColor(AColor.WHITE)
            val availW = pw - 2 * m; val availH = ph - 2 * m
            val s = minOf(availW / bmp.width, availH / bmp.height)
            val w = bmp.width * s; val h = bmp.height * s
            c.drawBitmap(bmp, null, RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), paint)
            pdf.finishPage(pg)
        }
        bmp.recycle()
        onProgress((i + 1f) / uris.size)
    }
    withContext(Dispatchers.IO) { tmp.outputStream().use { pdf.writeTo(it) } }
    pdf.close()
    val uri = Files.saveToDownloads(ctx, tmp, name, "application/pdf") ?: error("Could not save PDF")
    tmp.delete()
    return Saved(name, uri, "application/pdf")
}
