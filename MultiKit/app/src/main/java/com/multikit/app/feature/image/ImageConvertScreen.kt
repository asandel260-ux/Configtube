package com.multikit.app.feature.image

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Files
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ConvertOutput(val sourceName: String, val file: File)

/** Batch image converter: PNG / JPEG / WebP / BMP / GIF with per-file results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageConvertScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var names by remember { mutableStateOf<Map<Uri, String>>(emptyMap()) }
    var format by remember { mutableStateOf(ImgFormat.PNG) }
    var quality by remember { mutableStateOf(90f) }
    var flatten by remember { mutableStateOf(true) }
    var outputs by remember { mutableStateOf<List<ConvertOutput>>(emptyList()) }
    var fileErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            picked = (picked + uris).distinct()
            scope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    uris.associateWith { Files.displayName(context, it) }
                }
                names = names + resolved
            }
        }
    }

    fun convert() {
        if (picked.isEmpty()) {
            task.error = "Pick at least one image first"
            return
        }
        val inputs = picked.toList()
        val fmt = format
        val q = quality.toInt()
        val flat = flatten
        task.launch(scope) {
            val results = mutableListOf<ConvertOutput>()
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val outDir = Files.outputDir(context)
                val ts = Files.timestamp()
                inputs.forEachIndexed { i, uri ->
                    ensureActive()
                    task.progress = i.toFloat() / inputs.size
                    val name = names[uri] ?: Files.displayName(context, uri)
                    try {
                        val input = Files.copyToCache(context, uri)
                        try {
                            val base = ImageOps.safeBaseName(name)
                            val outFile = File(outDir, "${base}_${ts}_${i + 1}.${fmt.ext}")
                            if (fmt == ImgFormat.GIF) {
                                ImageOps.toGif(input, outFile)
                            } else {
                                var bmp = ImageOps.decode(context, Uri.fromFile(input))
                                try {
                                    if (fmt == ImgFormat.JPEG && flat) {
                                        val flattened = ImageOps.flattenOnWhite(bmp)
                                        if (flattened !== bmp) bmp.recycle()
                                        bmp = flattened
                                    }
                                    val bytes = ImageOps.compress(bmp, fmt, q)
                                    outFile.outputStream().use { it.write(bytes) }
                                } finally {
                                    bmp.recycle()
                                }
                            }
                            results += ConvertOutput(name, outFile)
                        } finally {
                            input.delete()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        errors += "$name: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
                task.progress = 1f
            }
            outputs = results
            fileErrors = errors
            if (results.isNotEmpty()) {
                task.message = "Converted ${results.size} of ${inputs.size} image(s)"
            } else {
                task.error = "All conversions failed — see details below"
            }
        }
    }

    fun saveOne(file: File) {
        task.launch(scope) {
            val uri = withContext(Dispatchers.IO) { Files.exportToDownloads(context, file) }
            if (uri != null) task.message = "Saved ${file.name} to Downloads"
            else task.error = "Could not save ${file.name}"
        }
    }

    fun saveAll() {
        val files = outputs.map { it.file }
        if (files.isEmpty()) return
        task.launch(scope) {
            var ok = 0
            withContext(Dispatchers.IO) {
                files.forEachIndexed { i, file ->
                    ensureActive()
                    task.progress = i.toFloat() / files.size
                    if (Files.exportToDownloads(context, file) != null) ok++
                }
            }
            task.message = "Saved $ok of ${files.size} file(s) to Downloads"
            if (ok < files.size) task.error = "${files.size - ok} file(s) could not be saved"
        }
    }

    Box(Modifier.fillMaxSize()) {
        ToolScaffold(title = "Image Converter", onBack = onBack) {
            SectionCard("Input images") {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("  Add images")
                }
                if (picked.isEmpty()) {
                    Text(
                        "No images selected yet. You can pick several at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                picked.forEach { uri ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            names[uri] ?: "image",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { picked = picked - uri }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
                if (picked.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            picked = emptyList()
                            outputs = emptyList()
                            fileErrors = emptyList()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear list")
                    }
                }
            }

            SectionCard("Output format") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ImgFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.label) },
                        )
                    }
                }
                if (format == ImgFormat.JPEG || format == ImgFormat.WEBP) {
                    Text("Quality: ${quality.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = quality,
                        onValueChange = { quality = it },
                        valueRange = 1f..100f,
                        steps = 98,
                    )
                }
                if (format == ImgFormat.JPEG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = flatten, onCheckedChange = { flatten = it })
                        Text(
                            "  Flatten transparency onto white",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (format == ImgFormat.GIF) {
                    Text(
                        "GIF is encoded with FFmpeg; dimensions are rounded to even values.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { convert() },
                enabled = picked.isNotEmpty() && !task.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (picked.isEmpty()) "Convert" else "Convert ${picked.size} image(s)")
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)
            ErrorBanner(fileErrors.joinToString("\n").ifBlank { null })

            if (outputs.isNotEmpty()) {
                SectionCard("Results (${outputs.size})") {
                    OutlinedButton(onClick = { saveAll() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text("  Save all to Downloads")
                    }
                    outputs.forEach { output ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AsyncImage(
                                model = output.file,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    output.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    Files.formatBytes(output.file.length()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { saveOne(output.file) }) {
                                Icon(Icons.Filled.Save, contentDescription = "Save to Downloads")
                            }
                            IconButton(onClick = { Files.share(context, output.file) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share")
                            }
                        }
                    }
                }
            }
        }
        BusyOverlay(busy = task.busy, label = "Working…", progress = task.progress)
    }
}