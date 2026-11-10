package com.toolbox.multitool.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ImageItem(val uri: Uri, val name: String, val w: Int, val h: Int, val bytes: Long)

internal suspend fun loadItems(ctx: Context, uris: List<Uri>): List<ImageItem> = withContext(Dispatchers.IO) {
    uris.map { u ->
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) } }
        ImageItem(u, Files.displayName(ctx, u), o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0), Files.size(ctx, u))
    }
}

@Composable
private fun ImageRow(item: ImageItem, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumb(item.uri, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text("${item.w}×${item.h} · ${Files.human(item.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
    }
}

@Composable
private fun ImageList(items: SnapshotStateList<ImageItem>, onAdd: () -> Unit) {
    SectionCard("${items.size} image${if (items.size == 1) "" else "s"}") {
        items.toList().forEach { item -> ImageRow(item) { items.remove(item) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Add more", onAdd, Modifier.weight(1f), Icons.Filled.AddPhotoAlternate)
            SecondaryButton("Clear", { items.clear() }, Modifier.weight(1f), Icons.Filled.Delete)
        }
    }
}

@Composable
private fun FormatChooser(selected: ImgFormat, onSelect: (ImgFormat) -> Unit) {
    SectionCard("Format") {
        ChipRow { ImgFormat.entries.forEach { f -> FilterChip(selected == f, { onSelect(f) }, { Text(f.label) }) } }
        Text(
            when (selected) {
                ImgFormat.PNG -> "Lossless, keeps transparency. Largest files."
                ImgFormat.JPEG -> "Small and universal. No transparency."
                ImgFormat.WEBP -> "Smallest at the same quality. Keeps transparency."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class EncodedPreview(val bmp: Bitmap, val bytes: Long)

private suspend fun encodePreview(src: Bitmap, format: ImgFormat, quality: Int): EncodedPreview = withContext(Dispatchers.Default) {
    val b = if (format == ImgFormat.JPEG) ImageEngine.flattenForJpeg(src) else src
    val bos = ByteArrayOutputStream()
    ImageEngine.compress(b, format, quality, bos)
    val bytes = bos.toByteArray()
    val sample = (maxOf(src.width, src.height) / 900).coerceAtLeast(1)
    val shown = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    EncodedPreview(shown ?: src, bytes.size.toLong())
}

@Composable
private fun PreviewCard(source: ImageItem?, p: EncodedPreview?) {
    SectionCard("Preview (first image)") {
        if (p == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium).checkerboard()) {
                Image(p.bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            val before = source?.bytes ?: 0L
            val delta = if (before > 0) ((p.bytes - before) * 100 / before).toInt() else 0
            ChipRow {
                Pill("Before ${Files.human(before)}", icon = Icons.Filled.Image)
                Pill("After ${Files.human(p.bytes)}", icon = Icons.Filled.Compress)
                Pill("${if (delta > 0) "+" else ""}$delta%")
            }
        }
    }
}

private suspend fun convertOne(
    ctx: Context, item: ImageItem, format: ImgFormat, quality: Int,
    suffix: String = "", transform: (Bitmap) -> Bitmap = { it },
): Saved {
    val bmp = ImageEngine.decode(ctx, item.uri)
    val out = withContext(Dispatchers.Default) { transform(bmp) }
    val name = Files.baseName(item.name) + suffix + "." + format.ext
    val tmp = Files.tempFile(ctx, name)
    withContext(Dispatchers.IO) { ImageEngine.write(out, format, quality, tmp) }
    val uri = Files.saveToDownloads(ctx, tmp, name, format.mime) ?: error("Could not save $name")
    tmp.delete()
    return Saved(name, uri, format.mime)
}

/** Batch-convert, with a live before/after preview of the first picked image. */
@Composable
fun ImageConvertScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ImageItem>() }
    var format by remember { mutableStateOf(ImgFormat.WEBP) }
    var quality by remember { mutableFloatStateOf(85f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<EncodedPreview?>(null) }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf("image/*")) { uris -> scope.launch { items.addAll(loadItems(ctx, uris)); saved.clear() } }

    val first = items.firstOrNull()
    val source by produceState<Bitmap?>(null, first?.uri) { value = first?.let { runCatching { ImageEngine.decode(ctx, it.uri) }.getOrNull() } }
    LaunchedEffect(source, format, quality) {
        val s = source ?: run { preview = null; return@LaunchedEffect }
        delay(250)
        preview = encodePreview(s, format, quality.toInt())
    }

    ToolScaffold("Convert image", onBack) {
        if (items.isEmpty()) {
            EmptyState(Icons.Filled.Image, "Convert any image", "Pick one or many pictures. You will see the result and the new file size before saving.")
            FilePickButton("Choose images", onClick = pick)
            return@ToolScaffold
        }
        ImageList(items, pick)
        FormatChooser(format) { format = it }
        if (format != ImgFormat.PNG) {
            SectionCard("Quality") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(quality, { quality = it }, valueRange = 10f..100f, modifier = Modifier.weight(1f))
                    Pill("${quality.toInt()}", Modifier.padding(start = 12.dp))
                }
            }
        }
        PreviewCard(first, preview)
        Busy(progress, "Converting…")
        if (log.isNotEmpty()) Text(log, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        PrimaryButton("Convert ${items.size} image${if (items.size == 1) "" else "s"}", icon = Icons.Filled.Check, loading = progress != null, onClick = {
            scope.launch {
                saved.clear(); log = ""
                val list = items.toList()
                list.forEachIndexed { i, item ->
                    progress = i / list.size.toFloat()
                    runCatching { saved.add(convertOne(ctx, item, format, quality.toInt())) }
                        .onFailure { log += "✗ ${item.name}: ${it.message}\n" }
                }
                progress = null
            }
        })
        ResultsCard(saved)
    }
}

private fun targetSize(w: Int, h: Int, mode: Int, percent: Float, wTxt: String, hTxt: String): Pair<Int, Int>? {
    if (w <= 0 || h <= 0) return null
    fun cl(v: Int) = v.coerceIn(1, 16000)
    return if (mode == 0) cl((w * percent / 100).toInt()) to cl((h * percent / 100).toInt())
    else {
        val ww = wTxt.toIntOrNull(); val hh = hTxt.toIntOrNull()
        when {
            ww != null && hh != null -> cl(ww) to cl(hh)
            ww != null -> cl(ww) to cl((h * ww.toFloat() / w).toInt())
            hh != null -> cl((w * hh.toFloat() / h).toInt()) to cl(hh)
            else -> null
        }
    }
}

@Composable
fun ImageResizeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ImageItem>() }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = percent, 1 = exact pixels
    var percent by remember { mutableFloatStateOf(50f) }
    var w by rememberSaveable { mutableStateOf("1080") }
    var h by rememberSaveable { mutableStateOf("") }
    var lock by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(ImgFormat.JPEG) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf("image/*")) { uris -> scope.launch { items.addAll(loadItems(ctx, uris)); saved.clear() } }
    val first = items.firstOrNull()
    val target = first?.let { targetSize(it.w, it.h, mode, percent, w, h) }

    ToolScaffold("Resize image", onBack) {
        if (items.isEmpty()) {
            EmptyState(Icons.Filled.PhotoSizeSelectLarge, "Resize images", "Scale by percentage or set exact pixels. The preview shows the new size relative to the original.")
            FilePickButton("Choose images", onClick = pick)
            return@ToolScaffold
        }
        ImageList(items, pick)

        SectionCard("New size") {
            ChipRow {
                FilterChip(mode == 0, { mode = 0 }, { Text("Percent") })
                FilterChip(mode == 1, { mode = 1 }, { Text("Exact pixels") })
            }
            if (mode == 0) {
                ChipRow { listOf(10f, 25f, 50f, 75f, 100f, 150f).forEach { p -> FilterChip(percent == p, { percent = p }, { Text("${p.toInt()}%") }) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(percent, { percent = it.toInt().toFloat() }, valueRange = 5f..200f, modifier = Modifier.weight(1f))
                    Pill("${percent.toInt()}%", Modifier.padding(start = 12.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        w, { v ->
                            w = v.filter(Char::isDigit).take(5)
                            if (lock && first != null && first.w > 0) h = w.toIntOrNull()?.let { (it * first.h.toFloat() / first.w).toInt().toString() } ?: ""
                        },
                        label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconToggleButton(lock, { lock = it }) { Icon(if (lock) Icons.Filled.Lock else Icons.Filled.LockOpen, "Keep aspect ratio") }
                    OutlinedTextField(
                        h, { v ->
                            h = v.filter(Char::isDigit).take(5)
                            if (lock && first != null && first.h > 0) w = h.toIntOrNull()?.let { (it * first.w.toFloat() / first.h).toInt().toString() } ?: ""
                        },
                        label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (first != null && target != null) {
            SectionCard("Preview") {
                val scale = (target.first.toFloat() / first.w).coerceIn(0.15f, 1f)
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Thumb(first.uri, Modifier.fillMaxHeight(scale).aspectRatio(target.first.toFloat() / target.second), maxSide = 640, contentScale = ContentScale.Fit)
                }
                ChipRow {
                    Pill("${first.w}×${first.h}", icon = Icons.Filled.PhotoSizeSelectLarge)
                    Pill("→ ${target.first}×${target.second}", icon = Icons.Filled.AspectRatio)
                }
            }
        }

        FormatChooser(format) { format = it }
        Busy(progress, "Resizing…")
        if (log.isNotEmpty()) Text(log, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        PrimaryButton("Resize ${items.size} image${if (items.size == 1) "" else "s"}", icon = Icons.Filled.Check, loading = progress != null, enabled = target != null, onClick = {
            scope.launch {
                saved.clear(); log = ""
                val list = items.toList()
                list.forEachIndexed { i, item ->
                    progress = i / list.size.toFloat()
                    runCatching {
                        val t = targetSize(item.w, item.h, mode, percent, w, h) ?: error("Enter a width or height")
                        saved.add(convertOne(ctx, item, format, 92, "_${t.first}x${t.second}") { ImageEngine.resize(it, t.first, t.second) })
                    }.onFailure { log += "✗ ${item.name}: ${it.message}\n" }
                }
                progress = null
            }
        })
        ResultsCard(saved)
    }
}
