package com.toolbox.multitool.pdf

import com.toolbox.multitool.ui.*
import com.toolbox.multitool.ui.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.image.ImageEngine
import com.toolbox.multitool.image.ImgFormat
import com.toolbox.multitool.ui.Busy
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold
import com.toolbox.multitool.ui.rememberPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PdfExtractScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(arrayOf("application/pdf")) { uris ->
        scope.launch {
            log = ""; progress = -1f
            runCatching { extractImages(ctx, uris.first()) { log += it + "\n" } }
                .onFailure { log += "✗ ${it.message}" }
            progress = null
        }
    }
    ToolScaffold("PDF → images", onBack) {
        Text("Extracts every embedded image at its original resolution and saves PNGs to Downloads/MultiTool.")
        FilePickButton("Choose PDF", onClick = pick)
        Busy(progress)
        LogBox(log)
    }
}

private suspend fun extractImages(ctx: Context, uri: Uri, report: (String) -> Unit) {
    val base = Files.baseName(Files.displayName(ctx, uri))
    val file = Files.copyToCache(ctx, uri)
    val found = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx)
        val saved = mutableListOf<Pair<java.io.File, String>>()
        PDDocument.load(file).use { doc ->
            fun walk(res: PDResources?, page: Int, depth: Int) {
                if (res == null || depth > 4) return
                for (xName in res.xObjectNames) {
                    when (val x = res.getXObject(xName)) {
                        is PDImageXObject -> runCatching {
                            val bmp = x.image
                            val out = "${base}_p${page}_${saved.size + 1}.png"
                            val tmp = Files.tempFile(ctx, out)
                            ImageEngine.write(bmp, ImgFormat.PNG, 100, tmp)
                            saved += tmp to out
                        }
                        is PDFormXObject -> walk(x.resources, page, depth + 1)
                        else -> Unit
                    }
                }
            }
            doc.pages.forEachIndexed { i, p -> walk(p.resources, i + 1, 0) }
        }
        saved
    }
    found.forEach { (tmp, name) ->
        Files.saveToDownloads(ctx, tmp, name, "image/png")
        report("✓ $name  ${Files.human(tmp.length())}")
        tmp.delete()
    }
    file.delete()
    report(if (found.isEmpty()) "No embedded images found (pages may be vector graphics)." else "Done: ${found.size} image(s).")
}

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<Uri>() }
    var fitA4 by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(arrayOf("image/*")) { images.addAll(it) }

    ToolScaffold("Images → PDF", onBack) {
        FilePickButton("Add images", onClick = pick)
        SwitchRow(if (fitA4) "A4 pages" else "Page = image size", fitA4) { fitA4 = it }
        images.forEachIndexed { i, u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${Files.displayName(ctx, u)}", Modifier.weight(1f), maxLines = 1)
                TextButton(enabled = i > 0, onClick = { images.add(i - 1, images.removeAt(i)) }) { Text("↑") }
                TextButton(onClick = { images.removeAt(i) }) { Text("✕") }
            }
        }
        Button(enabled = images.isNotEmpty() && progress == null, onClick = {
            scope.launch {
                progress = 0f; log = ""
                runCatching { buildPdf(ctx, images.toList(), fitA4) { progress = it } }
                    .onSuccess { log = "✓ Saved $it to Downloads/MultiTool" }.onFailure { log = "✗ ${it.message}" }
                progress = null
            }
        }) { Text("Create PDF") }
        Busy(progress)
        LogBox(log)
    }
}

private suspend fun buildPdf(ctx: Context, uris: List<Uri>, a4: Boolean, onProgress: (Float) -> Unit): String {
    val name = "images_${System.currentTimeMillis() / 1000}.pdf"
    val tmp = Files.tempFile(ctx, name)
    val pdf = PdfDocument()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    uris.forEachIndexed { i, u ->
        val bmp = ImageEngine.decode(ctx, u, maxSide = 2600) // bounds memory for huge photos
        withContext(Dispatchers.Default) {
            val (pw, ph) = if (a4) 595 to 842 else bmp.width to bmp.height
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val c: Canvas = page.canvas
            c.drawColor(Color.WHITE)
            val s = minOf(pw / bmp.width.toFloat(), ph / bmp.height.toFloat())
            val w = bmp.width * s; val h = bmp.height * s
            c.drawBitmap(bmp, null, RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), paint)
            pdf.finishPage(page)
        }
        bmp.recycle()
        onProgress((i + 1f) / uris.size)
    }
    withContext(Dispatchers.IO) { tmp.outputStream().use { pdf.writeTo(it) } }
    pdf.close()
    Files.saveToDownloads(ctx, tmp, name, "application/pdf")
    tmp.delete()
    return name
}
