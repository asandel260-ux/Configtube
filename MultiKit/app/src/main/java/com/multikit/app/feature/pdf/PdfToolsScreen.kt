package com.multikit.app.feature.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.multikit.app.feature.image.ImageOps
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.FilePickButton
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.OutputBlock
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PdfMode(val label: String) {
    IMAGES_TO_PDF("Images → PDF"),
    PDF_TO_IMAGES("PDF → Images"),
    EXTRACT_IMAGES("Extract Images"),
    TEXT_INFO("Text & Info"),
}

private enum class PageSizeChoice(val label: String) {
    FIT("Fit image"),
    A4_PORTRAIT("A4 portrait"),
    A4_LANDSCAPE("A4 landscape"),
}

/** Four PDF tools: build from images, render pages, extract embedded images, text/info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var mode by remember { mutableStateOf(PdfMode.IMAGES_TO_PDF) }

    // Mode 1: images → PDF
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var imageNames by remember { mutableStateOf<Map<Uri, String>>(emptyMap()) }
    var pageSize by remember { mutableStateOf(PageSizeChoice.FIT) }
    var marginText by remember { mutableStateOf("24") }
    var createdPdf by remember { mutableStateOf<File?>(null) }
    var createdPages by remember { mutableStateOf(0) }

    // Shared PDF input for modes 2–4
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var pdfName by remember { mutableStateOf("") }

    // Mode 2: render pages
    var pageCount by remember { mutableStateOf<Int?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var dpi by remember { mutableStateOf(150) }
    var renderJpeg by remember { mutableStateOf(false) }
    var renderQuality by remember { mutableStateOf(90f) }
    var renderedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var renderedZip by remember { mutableStateOf<File?>(null) }

    // Mode 3: extract embedded images
    var extractedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var skippedCount by remember { mutableStateOf(0) }
    var extractedZip by remember { mutableStateOf<File?>(null) }

    // Mode 4: text & info
    var infoText by remember { mutableStateOf<String?>(null) }
    var pdfText by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            images = (images + uris).distinct()
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    uris.associateWith { Files.displayName(context, it) }
                }
                imageNames = imageNames + resolved
            }
        }
    }

    fun moveImage(index: Int, delta: Int) {
        val target = index + delta
        val list = images.toMutableList()
        if (target in list.indices) {
            val tmp = list[index]
            list[index] = list[target]
            list[target] = tmp
            images = list
        }
    }

    /** Render page 0 as a small preview and read the page count. */
    suspend fun loadPreview(file: File) {
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val scale = min(1f, 600f / max(page.width, page.height).toFloat())
                            val w = max(1, (page.width * scale).toInt())
                            val h = max(1, (page.height * scale).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val tmp = Files.tempFile(context, "pdf_preview_", "png")
                                tmp.outputStream().use { os ->
                                    check(bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                                        "Could not encode preview"
                                    }
                                }
                                previewFile = tmp
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Extract embedded images with PDFBox, deduplicating by COS object identity. */
    suspend fun extractEmbedded(file: File) {
        withContext(Dispatchers.IO) {
            val outDir = Files.outputDir(context)
            val ts = Files.timestamp()
            val found = mutableListOf<File>()
            var skipped = 0
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())

            fun visit(xo: PDXObject, depth: Int) {
                if (depth > 8) return
                when (xo) {
                    is PDImageXObject -> {
                        if (!seen.add(xo.cosObject)) return
                        try {
                            val img: Bitmap = xo.image
                            val w = img.width
                            val h = img.height
                            if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000L) {
                                skipped++
                                return
                            }
                            val bytes = bufferedImageToPng(img)
                            val out = File(outDir, "extracted_${ts}_${found.size + 1}.png")
                            out.outputStream().use { it.write(bytes) }
                            found += out
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            skipped++
                        }
                    }
                    is PDFormXObject -> {
                        if (!seen.add(xo.cosObject)) return
                        val res = xo.resources ?: return
                        for (name in res.xObjectNames) {
                            val child = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                            visit(child, depth + 1)
                        }
                    }
                    else -> Unit
                }
            }

            PDDocument.load(file).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    ensureActive()
                    task.progress = i.toFloat() / max(1, total)
                    val resources = doc.getPage(i).resources ?: continue
                    for (name in resources.xObjectNames) {
                        val xo = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                        visit(xo, 0)
                    }
                }
            }
            task.progress = 1f
            extractedFiles = found
            skippedCount = skipped
            extractedZip = null
            task.message = "Extracted ${found.size} image(s)" +
                if (skipped > 0) ", skipped $skipped unsupported/duplicate-failure item(s)" else ""
            if (found.isEmpty()) task.error = "No embedded images could be extracted"
        }
    }

    /** Extract the text layer and document metadata with PDFBox. */
    suspend fun extractTextInfo(file: File) {
        withContext(Dispatchers.IO) {
            PDDocument.load(file).use { doc ->
                val info = doc.documentInformation
                val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                fun date(cal: Calendar?): String = cal?.time?.let { df.format(it) } ?: "—"
                infoText = buildString {
                    appendLine("Title: ${info.title?.takeIf { it.isNotBlank() } ?: "—"}")
                    appendLine("Author: ${info.author?.takeIf { it.isNotBlank() } ?: "—"}")
                    appendLine("Subject: ${info.subject?.takeIf { it.isNotBlank() } ?: "—"}")
                    appendLine("Creator: ${info.creator?.takeIf { it.isNotBlank() } ?: "—"}")
                    appendLine("Producer: ${info.producer?.takeIf { it.isNotBlank() } ?: "—"}")
                    appendLine("Created: ${date(info.creationDate)}")
                    appendLine("Modified: ${date(info.modificationDate)}")
                    append("Pages: ${doc.numberOfPages}")
                }
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                pdfText = stripper.getText(doc)
            }
        }
    }

    /** Copy the picked PDF to cache, then run the current mode's extraction. */
    fun loadPdf(uri: Uri) {
        if (task.busy) return
        pageCount = null
        previewFile = null
        renderedFiles = emptyList()
        renderedZip = null
        extractedFiles = emptyList()
        extractedZip = null
        skippedCount = 0
        infoText = null
        pdfText = null
        task.launch(scope) {
            val loaded = withContext(Dispatchers.IO) {
                val f = Files.copyToCache(context, uri)
                f to Files.displayName(context, uri)
            }
            pdfFile?.delete()
            pdfFile = loaded.first
            pdfName = loaded.second
            when (mode) {
                PdfMode.PDF_TO_IMAGES -> loadPreview(loaded.first)
                PdfMode.EXTRACT_IMAGES -> extractEmbedded(loaded.first)
                PdfMode.TEXT_INFO -> extractTextInfo(loaded.first)
                PdfMode.IMAGES_TO_PDF -> Unit
            }
        }
    }

    fun createPdf() {
        if (images.isEmpty()) {
            task.error = "Add at least one image"
            return
        }
        val choice = pageSize
        val margin = marginText.toIntOrNull() ?: -1
        if (choice != PageSizeChoice.FIT && (margin < 0 || margin > 500)) {
            task.error = "Margin must be between 0 and 500 pt"
            return
        }
        val m = margin.coerceIn(0, 500)
        val inputs = images.toList()
        task.launch(scope) {
            val out = withContext(Dispatchers.IO) {
                val doc = PdfDocument()
                try {
                    inputs.forEachIndexed { i, uri ->
                        ensureActive()
                        task.progress = i.toFloat() / inputs.size
                        val bmp = ImageOps.decode(
                            context, uri,
                            maxDim = if (choice == PageSizeChoice.FIT) 8192 else 4096,
                        )
                        try {
                            val pw: Int
                            val ph: Int
                            val dst: RectF
                            when (choice) {
                                PageSizeChoice.FIT -> {
                                    val f = min(1f, 14400f / max(bmp.width, bmp.height).toFloat())
                                    pw = max(1, round(bmp.width * f).toInt())
                                    ph = max(1, round(bmp.height * f).toInt())
                                    dst = RectF(0f, 0f, pw.toFloat(), ph.toFloat())
                                }
                                else -> {
                                    pw = if (choice == PageSizeChoice.A4_PORTRAIT) 595 else 842
                                    ph = if (choice == PageSizeChoice.A4_PORTRAIT) 842 else 595
                                    val availW = (pw - 2 * m).coerceAtLeast(1)
                                    val availH = (ph - 2 * m).coerceAtLeast(1)
                                    val f = min(
                                        availW.toFloat() / bmp.width,
                                        availH.toFloat() / bmp.height,
                                    )
                                    val dw = bmp.width * f
                                    val dh = bmp.height * f
                                    val left = m + (availW - dw) / 2f
                                    val top = m + (availH - dh) / 2f
                                    dst = RectF(left, top, left + dw, top + dh)
                                }
                            }
                            val pageInfo = PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()
                            val page = doc.startPage(pageInfo)
                            try {
                                val canvas = page.canvas
                                canvas.drawColor(Color.WHITE)
                                canvas.drawBitmap(bmp, null, dst, Paint().apply { isFilterBitmap = true })
                            } finally {
                                doc.finishPage(page)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    val output = File(Files.outputDir(context), "images_${Files.timestamp()}.pdf")
                    output.outputStream().use { doc.writeTo(it) }
                    output
                } finally {
                    doc.close()
                }
            }
            createdPdf = out
            createdPages = inputs.size
            task.message = "Created PDF with $createdPages page(s) • ${Files.formatBytes(out.length())}"
        }
    }

    fun renderAllPages() {
        val file = pdfFile
        if (file == null) {
            task.error = "Choose a PDF first"
            return
        }
        val chosenDpi = dpi
        val jpeg = renderJpeg
        val q = renderQuality.toInt()
        task.launch(scope) {
            val files = mutableListOf<File>()
            withContext(Dispatchers.IO) {
                val outDir = Files.outputDir(context)
                val ts = Files.timestamp()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val total = renderer.pageCount
                        for (i in 0 until total) {
                            ensureActive()
                            task.progress = i.toFloat() / max(1, total)
                            renderer.openPage(i).use { page ->
                                var scale = chosenDpi / 72f
                                val longest = max(page.width, page.height) * scale
                                if (longest > 8192f) scale *= 8192f / longest
                                val w = max(1, round(page.width * scale).toInt())
                                val h = max(1, round(page.height * scale).toInt())
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                try {
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val out = File(
                                        outDir,
                                        "page_${ts}_${i + 1}.${if (jpeg) "jpg" else "png"}",
                                    )
                                    out.outputStream().use { os ->
                                        check(
                                            bmp.compress(
                                                if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                                                if (jpeg) q else 100,
                                                os,
                                            )
                                        ) { "Failed to encode page ${i + 1}" }
                                    }
                                    files += out
                                } finally {
                                    bmp.recycle()
                                }
                            }
                        }
                    }
                }
                task.progress = 1f
            }
            renderedFiles = files
            renderedZip = null
            task.message = "Rendered ${files.size} page(s) at $chosenDpi DPI"
            if (files.isEmpty()) task.error = "The PDF has no pages"
        }
    }

    fun bundleZip(files: List<File>, prefix: String, forRendered: Boolean) {
        if (files.isEmpty()) return
        task.launch(scope) {
            val zip = withContext(Dispatchers.IO) {
                val z = File(Files.outputDir(context), "${prefix}_${Files.timestamp()}.zip")
                PdfOps.zipFiles(files, z)
                z
            }
            if (forRendered) renderedZip = zip else extractedZip = zip
            task.message = "ZIP created • ${Files.formatBytes(zip.length())}"
        }
    }

    fun saveOne(file: File) {
        task.launch(scope) {
            val uri = withContext(Dispatchers.IO) { Files.exportToDownloads(context, file) }
            if (uri != null) task.message = "Saved ${file.name} to Downloads"
            else task.error = "Could not save ${file.name}"
        }
    }

    fun saveAll(files: List<File>) {
        if (files.isEmpty()) return
        task.launch(scope) {
            val ok = withContext(Dispatchers.IO) {
                PdfOps.exportAll(context, files) { task.progress = it }
            }
            task.message = "Saved $ok of ${files.size} file(s) to Downloads"
            if (ok < files.size) task.error = "${files.size - ok} file(s) could not be saved"
        }
    }

    fun saveTextFile() {
        val text = pdfText ?: return
        task.launch(scope) {
            val uri = withContext(Dispatchers.IO) {
                Files.saveTextToDownloads(context, "pdf_text_${Files.timestamp()}.txt", text)
            }
            if (uri != null) task.message = "Text saved to Downloads"
            else task.error = "Could not save the text file"
        }
    }

    Box(Modifier.fillMaxSize()) {
        ToolScaffold(title = "PDF Tools", onBack = onBack) {
            SectionCard("Mode") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PdfMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = {
                                mode = m
                                task.error = null
                                task.message = null
                            },
                            label = { Text(m.label) },
                        )
                    }
                }
            }

            when (mode) {
                PdfMode.IMAGES_TO_PDF -> {
                    SectionCard("Images (${images.size})") {
                        OutlinedButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Text("  Add images")
                        }
                        images.forEachIndexed { i, uri ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Text(
                                    "${i + 1}. ${imageNames[uri] ?: "image"}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { moveImage(i, -1) },
                                    enabled = i > 0,
                                ) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = { moveImage(i, 1) },
                                    enabled = i < images.lastIndex,
                                ) {
                                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                                }
                                IconButton(onClick = { images = images - uri }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                                }
                            }
                        }
                        if (images.isEmpty()) {
                            Text(
                                "Add one or more images; reorder them with the arrows.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SectionCard("Page layout") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PageSizeChoice.entries.forEach { p ->
                                FilterChip(
                                    selected = pageSize == p,
                                    onClick = { pageSize = p },
                                    label = { Text(p.label) },
                                )
                            }
                        }
                        if (pageSize != PageSizeChoice.FIT) {
                            LabeledField(
                                label = "Margin (pt)",
                                value = marginText,
                                onValueChange = { marginText = it.filter { c -> c.isDigit() }.take(3) },
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            )
                        } else {
                            Text(
                                "Each page matches its image size (capped at 14400 pt).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = { createPdf() },
                        enabled = images.isNotEmpty() && !task.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Text("  Create PDF (${images.size} page(s))")
                    }
                    createdPdf?.let { pdf ->
                        SectionCard("Created PDF") {
                            Text(
                                "$createdPages page(s) • ${Files.formatBytes(pdf.length())}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FileResultRow(
                                file = pdf,
                                onSave = { saveOne(pdf) },
                                onShare = { Files.share(context, pdf) },
                            )
                        }
                    }
                }

                PdfMode.PDF_TO_IMAGES -> {
                    SectionCard("PDF file") {
                        FilePickButton(
                            label = "Choose PDF",
                            mimeTypes = arrayOf("application/pdf"),
                        ) { uri -> loadPdf(uri) }
                        if (pdfName.isNotBlank()) {
                            Text(
                                pdfName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        previewFile?.let { preview ->
                            AsyncImage(
                                model = preview,
                                contentDescription = "First page preview",
                                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        pageCount?.let {
                            Text(
                                "Pages: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (pdfFile != null && previewFile == null && !task.busy) {
                            OutlinedButton(
                                onClick = {
                                    val f = pdfFile ?: return@OutlinedButton
                                    task.launch(scope) { loadPreview(f) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Load preview & page count")
                            }
                        }
                    }
                    SectionCard("Render options") {
                        Text("Resolution", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(72, 150, 300).forEach { d ->
                                FilterChip(
                                    selected = dpi == d,
                                    onClick = { dpi = d },
                                    label = { Text("$d DPI") },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !renderJpeg,
                                onClick = { renderJpeg = false },
                                label = { Text("PNG") },
                            )
                            FilterChip(
                                selected = renderJpeg,
                                onClick = { renderJpeg = true },
                                label = { Text("JPEG") },
                            )
                        }
                        if (renderJpeg) {
                            Text(
                                "Quality: ${renderQuality.toInt()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = renderQuality,
                                onValueChange = { renderQuality = it },
                                valueRange = 1f..100f,
                                steps = 98,
                            )
                        }
                    }
                    Button(
                        onClick = { renderAllPages() },
                        enabled = pdfFile != null && !task.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Text("  Render all pages")
                    }
                    val rendered = renderedFiles
                    if (rendered.isNotEmpty()) {
                        SectionCard("Rendered images (${rendered.size})") {
                            Text(
                                "Total: ${Files.formatBytes(rendered.sumOf { it.length() })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { saveAll(rendered) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = null)
                                    Text("  Save all")
                                }
                                OutlinedButton(
                                    onClick = { bundleZip(rendered, "pdf_pages", forRendered = true) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Bundle as ZIP")
                                }
                            }
                            rendered.take(12).forEach { file ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AsyncImage(
                                        model = file,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Text(
                                        file.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = { saveOne(file) }) {
                                        Icon(Icons.Filled.Save, contentDescription = "Save to Downloads")
                                    }
                                    IconButton(onClick = { Files.share(context, file) }) {
                                        Icon(Icons.Filled.Share, contentDescription = "Share")
                                    }
                                }
                            }
                            if (rendered.size > 12) {
                                Text(
                                    "+ ${rendered.size - 12} more (included in Save all / ZIP)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        renderedZip?.let { zip ->
                            SectionCard("ZIP bundle") {
                                FileResultRow(
                                    file = zip,
                                    onSave = { saveOne(zip) },
                                    onShare = { Files.share(context, zip) },
                                )
                            }
                        }
                    }
                }

                PdfMode.EXTRACT_IMAGES -> {
                    SectionCard("PDF file") {
                        FilePickButton(
                            label = "Choose PDF",
                            mimeTypes = arrayOf("application/pdf"),
                        ) { uri -> loadPdf(uri) }
                        if (pdfName.isNotBlank()) {
                            Text(
                                pdfName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (pdfFile != null && !task.busy) {
                            OutlinedButton(
                                onClick = {
                                    val f = pdfFile ?: return@OutlinedButton
                                    task.launch(scope) { extractEmbedded(f) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Extract embedded images")
                            }
                        }
                    }
                    val extracted = extractedFiles
                    if (extracted.isNotEmpty() || skippedCount > 0) {
                        SectionCard("Extracted (${extracted.size})") {
                            if (skippedCount > 0) {
                                Text(
                                    "$skippedCount image(s) skipped (unsupported codec or too large).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (extracted.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { saveAll(extracted) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Filled.Save, contentDescription = null)
                                        Text("  Save all")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            bundleZip(extracted, "pdf_extracted", forRendered = false)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Bundle as ZIP")
                                    }
                                }
                                extracted.take(60).forEach { file ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        AsyncImage(
                                            model = file,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                file.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                Files.formatBytes(file.length()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { saveOne(file) }) {
                                            Icon(Icons.Filled.Save, contentDescription = "Save to Downloads")
                                        }
                                        IconButton(onClick = { Files.share(context, file) }) {
                                            Icon(Icons.Filled.Share, contentDescription = "Share")
                                        }
                                    }
                                }
                                if (extracted.size > 60) {
                                    Text(
                                        "+ ${extracted.size - 60} more (included in Save all / ZIP)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        extractedZip?.let { zip ->
                            SectionCard("ZIP bundle") {
                                FileResultRow(
                                    file = zip,
                                    onSave = { saveOne(zip) },
                                    onShare = { Files.share(context, zip) },
                                )
                            }
                        }
                    }
                }

                PdfMode.TEXT_INFO -> {
                    SectionCard("PDF file") {
                        FilePickButton(
                            label = "Choose PDF",
                            mimeTypes = arrayOf("application/pdf"),
                        ) { uri -> loadPdf(uri) }
                        if (pdfName.isNotBlank()) {
                            Text(
                                pdfName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (pdfFile != null && !task.busy) {
                            OutlinedButton(
                                onClick = {
                                    val f = pdfFile ?: return@OutlinedButton
                                    task.launch(scope) { extractTextInfo(f) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Extract text & info")
                            }
                        }
                    }
                    infoText?.let { info ->
                        SectionCard("Document info") {
                            OutputBlock(info)
                        }
                    }
                    pdfText?.let { text ->
                        SectionCard("Text (${text.length} characters)") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { Files.copyToClipboard(context, "PDF text", text) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Text("  Copy")
                                }
                                OutlinedButton(
                                    onClick = { saveTextFile() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = null)
                                    Text("  Save .txt")
                                }
                            }
                            OutputBlock(
                                if (text.length > 50_000) {
                                    text.take(50_000) + "\n… preview truncated — Copy/Save includes the full text"
                                } else text
                            )
                        }
                    }
                }
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)
        }
        BusyOverlay(busy = task.busy, label = "Working…", progress = task.progress)
    }
}

/** Name + size with Save / Share actions for a finished output file. */
@Composable
private fun FileResultRow(file: File, onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Files.formatBytes(file.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Filled.Save, contentDescription = "Save to Downloads")
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Share")
        }
    }
}

/** Encode a Bitmap as PNG. */
private fun bufferedImageToPng(img: Bitmap): ByteArray {
    val baos = ByteArrayOutputStream()
    check(img.compress(Bitmap.CompressFormat.PNG, 100, baos)) { "PNG encoding failed" }
    return baos.toByteArray()
}