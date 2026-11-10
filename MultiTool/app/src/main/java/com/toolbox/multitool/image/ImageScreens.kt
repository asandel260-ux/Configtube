package com.toolbox.multitool.image

import com.toolbox.multitool.ui.*
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.Busy
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold
import com.toolbox.multitool.ui.rememberPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val imageMime = arrayOf("image/*")

/** Batch-converts every picked image to the chosen format. */
@Composable
fun ImageConvertScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ImgFormat.WEBP) }
    var quality by remember { mutableFloatStateOf(90f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(imageMime) { uris ->
        scope.launch {
            log = ""
            uris.forEachIndexed { i, uri ->
                progress = i / uris.size.toFloat()
                runCatching {
                    val bmp = ImageEngine.decode(ctx, uri)
                    val name = Files.baseName(Files.displayName(ctx, uri)) + "." + format.ext
                    val tmp = Files.tempFile(ctx, name)
                    withContext(Dispatchers.IO) { ImageEngine.write(bmp, format, quality.toInt(), tmp) }
                    Files.saveToDownloads(ctx, tmp, name, format.mime)
                    log += "✓ $name  ${Files.human(tmp.length())}\n"
                    tmp.delete()
                }.onFailure { log += "✗ ${Files.displayName(ctx, uri)}: ${it.message}\n" }
            }
            progress = null
            log += "Saved to Downloads/MultiTool"
        }
    }

    ToolScaffold("Convert image", onBack) {
        FormatChooser(format) { format = it }
        if (format != ImgFormat.PNG) {
            Text("Quality ${quality.toInt()}")
            Slider(quality, { quality = it }, valueRange = 10f..100f)
        }
        FilePickButton("Choose images", onClick = pick)
        Busy(progress)
        LogBox(log)
    }
}

@Composable
fun ImageResizeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = percent, 1 = exact
    var percent by remember { mutableFloatStateOf(50f) }
    var w by rememberSaveable { mutableStateOf("1080") }
    var h by rememberSaveable { mutableStateOf("") }
    var format by remember { mutableStateOf(ImgFormat.JPEG) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(imageMime) { uris ->
        scope.launch {
            log = ""
            uris.forEachIndexed { i, uri ->
                progress = i / uris.size.toFloat()
                runCatching {
                    val bmp = ImageEngine.decode(ctx, uri)
                    val (tw, th) = if (mode == 0) {
                        (bmp.width * percent / 100).toInt() to (bmp.height * percent / 100).toInt()
                    } else {
                        val ww = w.toIntOrNull(); val hh = h.toIntOrNull()
                        when {
                            ww != null && hh != null -> ww to hh
                            ww != null -> ww to (bmp.height * ww.toFloat() / bmp.width).toInt()
                            hh != null -> (bmp.width * hh.toFloat() / bmp.height).toInt() to hh
                            else -> error("Enter width and/or height")
                        }
                    }
                    val scaled = withContext(Dispatchers.Default) { ImageEngine.resize(bmp, tw.coerceAtLeast(1), th.coerceAtLeast(1)) }
                    val name = Files.baseName(Files.displayName(ctx, uri)) + "_${tw}x$th." + format.ext
                    val tmp = Files.tempFile(ctx, name)
                    withContext(Dispatchers.IO) { ImageEngine.write(scaled, format, 92, tmp) }
                    Files.saveToDownloads(ctx, tmp, name, format.mime)
                    log += "✓ $name (${bmp.width}×${bmp.height} → $tw×$th)\n"
                    tmp.delete()
                }.onFailure { log += "✗ ${Files.displayName(ctx, uri)}: ${it.message}\n" }
            }
            progress = null
        }
    }

    ToolScaffold("Resize image", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0 }, { Text("Percent") })
            FilterChip(mode == 1, { mode = 1 }, { Text("Exact pixels") })
        }
        if (mode == 0) {
            Text("${percent.toInt()} %")
            Slider(percent, { percent = it }, valueRange = 5f..200f)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(h, { h = it.filter(Char::isDigit) }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Leave one empty to keep the aspect ratio.", style = MaterialTheme.typography.bodySmall)
        }
        FormatChooser(format) { format = it }
        FilePickButton("Choose images", onClick = pick)
        Busy(progress)
        LogBox(log)
    }
}

@Composable
private fun FormatChooser(selected: ImgFormat, onSelect: (ImgFormat) -> Unit) {
    ChipRow {
        ImgFormat.entries.forEach { f -> FilterChip(selected == f, { onSelect(f) }, { Text(f.label) }) }
    }
}
