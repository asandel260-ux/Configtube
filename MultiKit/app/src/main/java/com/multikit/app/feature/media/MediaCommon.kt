package com.multikit.app.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.util.Ffmpeg
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared building blocks for the media feature screens: media probing, player
 * previews, dropdown selectors, export/share actions and bitmap helpers.
 * Everything here is private to the media package.
 */

/** Normalized probe result used across the media tools. */
data class MediaInfo(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val pixFmt: String?,
    val tags: Map<String, String>,
) {
    val durationSec: Double? get() = durationMs?.let { it / 1000.0 }
    val hasAlpha: Boolean
        get() = pixFmt?.lowercase(Locale.US)?.let {
            it.contains("yuva") || it.contains("rgba") || it.contains("argb") ||
                it.contains("bgra") || it.contains("abgr") || it == "pal8"
        } ?: false
}

/** Probe a media file defensively; never throws. Runs the native probe on IO. */
suspend fun probeMedia(path: String): MediaInfo {
    val info = Ffmpeg.probe(path)
        ?: return MediaInfo(null, null, null, false, false, null, emptyMap())
    val durMs = runCatching { info.duration?.toDouble()?.times(1000)?.toLong() }.getOrNull()
    var width: Int? = null
    var height: Int? = null
    var hasAudio = false
    var hasVideo = false
    var pixFmt: String? = null
    runCatching {
        info.streams?.forEach { s ->
            when (s.type) {
                "video" -> {
                    hasVideo = true
                    if (width == null) {
                        width = s.width?.toInt()
                        height = s.height?.toInt()
                        pixFmt = runCatching { s.getStringProperty("pix_fmt") }.getOrNull()
                    }
                }
                "audio" -> hasAudio = true
            }
        }
    }
    val tags = runCatching {
        val json = info.tags ?: return@runCatching emptyMap<String, String>()
        val map = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k.lowercase(Locale.US)] = json.optString(k, "")
        }
        map
    }.getOrDefault(emptyMap())
    return MediaInfo(durMs, width, height, hasAudio, hasVideo, pixFmt, tags)
}

/** An ffmpeg argument list (output path is appended by the caller) plus output extension. */
data class FfmpegCommand(val args: List<String>, val ext: String, val progressMs: Long?)

/** Format a double for ffmpeg arguments without locale surprises. */
fun fx(d: Double): String = String.format(Locale.US, "%.3f", d)

fun parseDouble(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

fun parseInt(text: String): Int? = text.trim().toIntOrNull()

/** Build an atempo chain valid for any speed in [0.25, 4] (each instance 0.5..2). */
fun atempoChain(speed: Double): String {
    val parts = mutableListOf<String>()
    var remaining = speed
    while (remaining > 2.0) {
        parts.add("atempo=2.0")
        remaining /= 2.0
    }
    while (remaining < 0.5) {
        parts.add("atempo=0.5")
        remaining /= 0.5
    }
    parts.add("atempo=" + fx(remaining))
    return parts.joinToString(",")
}

/** Escape a path for the concat demuxer list file (single-quote wrapping). */
fun concatEscape(path: String): String = path.replace("'", "'\\''")

/** ExoPlayer-backed preview that releases its player when leaving composition. */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaPlayerCard(file: File?, modifier: Modifier = Modifier, heightDp: Int = 220) {
    if (file == null || !file.exists()) return
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(file) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                this.player = player
            }
        },
        update = { view -> view.player = player },
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    )
}

/** Stable-API labeled dropdown built from an OutlinedButton + DropdownMenu. */
@Composable
fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    optionLabel(selected),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        leadingIcon = if (option == selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }
}

/** Save-to-Downloads + Share buttons with their own busy/error/message state. */
@Composable
fun ExportShareRow(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(file) { mutableStateOf(false) }
    var message by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        message = null
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                Files.exportToDownloads(context, file)
                            }
                            message = if (uri != null) "Saved to Downloads" else "Could not save file"
                        } catch (t: Throwable) {
                            error = t.message ?: "Save failed"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text("  Save", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = {
                    error = null
                    runCatching { Files.share(context, file) }
                        .onFailure { error = it.message ?: "Share failed" }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("  Share", modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        SuccessBanner(message)
        ErrorBanner(error)
    }
}

/** Standard result card: name, size, export/share and an optional player preview. */
@Composable
fun ResultCard(file: File?, preview: Boolean = true, title: String = "Result") {
    if (file == null || !file.exists()) return
    SectionCard(title) {
        Text(file.name, style = MaterialTheme.typography.titleSmall)
        Text(
            Files.formatBytes(file.length()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExportShareRow(file)
        if (preview) MediaPlayerCard(file)
    }
}

// ---- Bitmap helpers (all blocking; call inside Dispatchers.IO) ----

/** Decode a bitmap preserving alpha (ARGB_8888). */
fun decodeBitmap(file: File): Bitmap? {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

/** Scale so the longest side equals [maxSide]; the other side stays <= maxSide. */
fun scaleToLongest(src: Bitmap, maxSide: Int): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= 0 || maxSide <= 0) return src
    val ratio = maxSide.toFloat() / longest.toFloat()
    val w = max(1, (src.width * ratio).roundToInt())
    val h = max(1, (src.height * ratio).roundToInt())
    if (w == src.width && h == src.height) return src
    return Bitmap.createScaledBitmap(src, w, h, true)
}

/** Composite onto white so JPEG export does not produce black backgrounds. */
fun flattenOnWhite(src: Bitmap): Bitmap {
    if (!src.hasAlpha()) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(src, 0f, 0f, null)
    return out
}

/** WebP compression format chosen in the sticker image tools. */
enum class WebpMode { LOSSLESS, LOSSY }

/** Compress a bitmap to [out] as webp (mode/quality) honoring API level. */
fun compressWebp(bitmap: Bitmap, out: File, mode: WebpMode, quality: Int) {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (mode == WebpMode.LOSSLESS) Bitmap.CompressFormat.WEBP_LOSSLESS
        else Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    out.outputStream().use { bitmap.compress(format, quality.coerceIn(0, 100), it) }
}

fun compressPng(bitmap: Bitmap, out: File) {
    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

fun compressJpeg(bitmap: Bitmap, out: File, quality: Int) {
    val flat = flattenOnWhite(bitmap)
    out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), it) }
}