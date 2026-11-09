package com.multikit.app.feature.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.FilePickButton
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ResizeMode(val label: String) {
    PERCENT("Percentage"),
    EXACT("Exact size"),
    PRESET("Presets"),
}

private data class Preset(val label: String, val width: Int, val height: Int)

private val presets = listOf(
    Preset("128×128", 128, 128),
    Preset("256×256", 256, 256),
    Preset("512×512", 512, 512),
    Preset("1024×1024", 1024, 1024),
    Preset("HD 1280×720", 1280, 720),
    Preset("FHD 1920×1080", 1920, 1080),
    Preset("4K 3840×2160", 3840, 2160),
    Preset("IG 1080×1080", 1080, 1080),
)

private data class ResizeResult(
    val file: File,
    val dims: ImageDims,
    val bytes: Long,
    val quality: Int,
    val missedTarget: Boolean,
)

/** Image resizer: percentage, exact dimensions and presets, with size targeting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageResizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var srcUri by remember { mutableStateOf<Uri?>(null) }
    var srcFile by remember { mutableStateOf<File?>(null) }
    var srcName by remember { mutableStateOf("") }
    var srcDims by remember { mutableStateOf<ImageDims?>(null) }
    var srcBytes by remember { mutableStateOf(0L) }

    var mode by remember { mutableStateOf(ResizeMode.PERCENT) }
    var percent by remember { mutableStateOf(100f) }
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var keepAspect by remember { mutableStateOf(true) }
    var presetIndex by remember { mutableStateOf(0) }

    var quality by remember { mutableStateOf(90f) }
    var format by remember { mutableStateOf<ImgFormat?>(null) } // null = keep original
    var maxKbText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ResizeResult?>(null) }

    val effectiveFormat: ImgFormat =
        format ?: ImageOps.formatForFileName(srcName.ifBlank { srcFile?.name ?: "" })
    val sizeTargetApplicable = effectiveFormat == ImgFormat.JPEG || effectiveFormat == ImgFormat.WEBP

    fun onWidthChange(text: String) {
        widthText = text.filter { it.isDigit() }.take(5)
        val d = srcDims
        if (keepAspect && d != null && d.width > 0) {
            val w = widthText.toIntOrNull()
            if (w != null && w > 0) {
                heightText = ((w.toDouble() * d.height / d.width).toLong().coerceAtLeast(1)).toString()
            }
        }
    }

    fun onHeightChange(text: String) {
        heightText = text.filter { it.isDigit() }.take(5)
        val d = srcDims
        if (keepAspect && d != null && d.height > 0) {
            val h = heightText.toIntOrNull()
            if (h != null && h > 0) {
                widthText = ((h.toDouble() * d.width / d.height).toLong().coerceAtLeast(1)).toString()
            }
        }
    }

    fun targetDims(): ImageDims? {
        val d = srcDims
        return when (mode) {
            ResizeMode.PERCENT -> {
                if (d == null) null
                else {
                    val p = percent.toInt().coerceIn(10, 400)
                    ImageDims(
                        (d.width.toLong() * p / 100).toInt().coerceAtLeast(1),
                        (d.height.toLong() * p / 100).toInt().coerceAtLeast(1),
                    )
                }
            }
            ResizeMode.EXACT -> {
                val w = widthText.toIntOrNull()
                val h = heightText.toIntOrNull()
                if (w == null || h == null || w < 1 || h < 1) null else ImageDims(w, h)
            }
            ResizeMode.PRESET -> {
                val p = presets[presetIndex]
                ImageDims(p.width, p.height)
            }
        }
    }

    fun resize() {
        val file = srcFile
        if (file == null) {
            task.error = "Choose an image first"
            return
        }
        if (mode == ResizeMode.PERCENT && srcDims == null) {
            task.error = "Source dimensions unknown — use Exact size or Presets"
            return
        }
        val dims = targetDims()
        if (dims == null) {
            task.error = "Enter valid target dimensions"
            return
        }
        if (dims.width > 16384 || dims.height > 16384) {
            task.error = "Target dimensions too large (max 16384 px per side)"
            return
        }
        if (dims.width.toLong() * dims.height > 64_000_000L) {
            task.error = "Target image too large (max 64 megapixels)"
            return
        }
        val fmt = effectiveFormat
        val q = quality.toInt()
        val targetBytes = if (sizeTargetApplicable) {
            maxKbText.toIntOrNull()?.takeIf { it > 0 }?.times(1024L)
        } else null
        task.launch(scope) {
            val res = withContext(Dispatchers.IO) {
                var bmp = ImageOps.decode(context, Uri.fromFile(file))
                val scaled =
                    if (bmp.width == dims.width && bmp.height == dims.height) bmp
                    else Bitmap.createScaledBitmap(bmp, dims.width, dims.height, true)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
                if (fmt == ImgFormat.JPEG) {
                    val flattened = ImageOps.flattenOnWhite(bmp)
                    if (flattened !== bmp) bmp.recycle()
                    bmp = flattened
                }
                try {
                    val (bytes, usedQ) =
                        if (targetBytes != null) ImageOps.compressToTarget(bmp, fmt, q, targetBytes)
                        else ImageOps.compress(bmp, fmt, q) to q
                    val out = File(Files.outputDir(context), "resized_${Files.timestamp()}.${fmt.ext}")
                    out.outputStream().use { it.write(bytes) }
                    ResizeResult(
                        file = out,
                        dims = dims,
                        bytes = bytes.size.toLong(),
                        quality = usedQ,
                        missedTarget = targetBytes != null && bytes.size > targetBytes,
                    )
                } finally {
                    bmp.recycle()
                }
            }
            result = res
            task.message = buildString {
                append("Resized to ${res.dims.width}×${res.dims.height} • ${Files.formatBytes(res.bytes)}")
                if (targetBytes != null) append(" • quality ${res.quality}")
                if (res.missedTarget) append(" (target size not reachable)")
            }
        }
    }

    fun saveResult() {
        val file = result?.file ?: return
        task.launch(scope) {
            val uri = withContext(Dispatchers.IO) { Files.exportToDownloads(context, file) }
            if (uri != null) task.message = "Saved ${file.name} to Downloads"
            else task.error = "Could not save ${file.name}"
        }
    }

    Box(Modifier.fillMaxSize()) {
        ToolScaffold(title = "Image Resizer", onBack = onBack) {
            SectionCard("Source image") {
                FilePickButton(label = "Choose image", mimeTypes = arrayOf("image/*")) { uri ->
                    if (task.busy) return@FilePickButton
                    srcUri = uri
                    result = null
                    task.error = null
                    task.message = null
                    task.launch(scope) {
                        val loaded = withContext(Dispatchers.IO) {
                            val f = Files.copyToCache(context, uri)
                            val d = ImageOps.decodeBounds(context, Uri.fromFile(f))
                            val n = Files.displayName(context, uri)
                            Triple(f, d, n)
                        }
                        srcFile?.delete()
                        srcFile = loaded.first
                        srcDims = loaded.second
                        srcName = loaded.third
                        srcBytes = loaded.first.length()
                        loaded.second?.let {
                            widthText = it.width.toString()
                            heightText = it.height.toString()
                        }
                        if (loaded.second == null) {
                            task.error = "Could not read image dimensions"
                        }
                    }
                }
                srcUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Source image",
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (srcName.isNotBlank()) {
                    Text(
                        srcName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        srcDims?.let { "${it.width}×${it.height} px • ${Files.formatBytes(srcBytes)}" }
                            ?: "Dimensions unknown • ${Files.formatBytes(srcBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("Resize mode") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResizeMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(m.label) },
                        )
                    }
                }
                when (mode) {
                    ResizeMode.PERCENT -> {
                        Text(
                            "Scale: ${percent.toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = percent,
                            onValueChange = { percent = it },
                            valueRange = 10f..400f,
                            steps = 389,
                            enabled = srcDims != null,
                        )
                        targetDims()?.let {
                            Text(
                                "Output: ${it.width}×${it.height} px",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ResizeMode.EXACT -> {
                        LabeledField(
                            label = "Width (px)",
                            value = widthText,
                            onValueChange = { onWidthChange(it) },
                            keyboardType = KeyboardType.Number,
                        )
                        LabeledField(
                            label = "Height (px)",
                            value = heightText,
                            onValueChange = { onHeightChange(it) },
                            keyboardType = KeyboardType.Number,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = keepAspect,
                                onCheckedChange = { checked ->
                                    keepAspect = checked
                                    if (checked) onWidthChange(widthText)
                                },
                            )
                            Text(
                                "  Keep aspect ratio",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    ResizeMode.PRESET -> {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            presets.forEachIndexed { i, p ->
                                FilterChip(
                                    selected = presetIndex == i,
                                    onClick = { presetIndex = i },
                                    label = { Text(p.label) },
                                )
                            }
                        }
                    }
                }
            }

            SectionCard("Output") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = format == null,
                        onClick = { format = null },
                        label = { Text("Original") },
                    )
                    listOf(ImgFormat.PNG, ImgFormat.JPEG, ImgFormat.WEBP).forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.label) },
                        )
                    }
                }
                Text("Quality: ${quality.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 1f..100f,
                    steps = 98,
                )
                if (sizeTargetApplicable) {
                    LabeledField(
                        label = "Target max file size (KB, optional)",
                        value = maxKbText,
                        onValueChange = { maxKbText = it.filter { c -> c.isDigit() }.take(6) },
                        keyboardType = KeyboardType.Number,
                    )
                    Text(
                        "When set, quality is reduced automatically (binary search) to try to stay under the target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { resize() },
                enabled = srcFile != null && !task.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Text("  Resize")
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)

            result?.let { res ->
                SectionCard("Result") {
                    AsyncImage(
                        model = res.file,
                        contentDescription = "Resized image",
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        "${res.dims.width}×${res.dims.height} px • ${Files.formatBytes(res.bytes)} • ${effectiveFormat.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { saveResult() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Text("  Save")
                        }
                        OutlinedButton(
                            onClick = { Files.share(context, res.file) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Text("  Share")
                        }
                    }
                }
            }
        }
        BusyOverlay(busy = task.busy, label = "Working…", progress = task.progress)
    }
}