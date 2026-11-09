package com.multikit.app.feature.media

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.multikit.app.ui.components.BusyOverlay
import com.multikit.app.ui.components.ErrorBanner
import com.multikit.app.ui.components.FilePickButton
import com.multikit.app.ui.components.LabeledField
import com.multikit.app.ui.components.OutputBlock
import com.multikit.app.ui.components.SectionCard
import com.multikit.app.ui.components.SuccessBanner
import com.multikit.app.ui.components.ToolScaffold
import com.multikit.app.ui.components.rememberTaskState
import com.multikit.app.util.Ffmpeg
import com.multikit.app.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class StickerMode(val label: String) {
    IMAGE_TO_STICKER("Image → sticker"),
    VIDEO_TO_STICKER("Video → sticker"),
    STICKER_TO_IMAGE("Sticker → image"),
    STICKER_TO_VIDEO("Sticker → video"),
}

/** Create static/video stickers and convert stickers back to images or looping videos. */
@Composable
fun StickerLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var mode by remember { mutableStateOf(StickerMode.IMAGE_TO_STICKER) }
    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }

    // Mode 1 options
    var stickerFormat by remember { mutableStateOf("webp") }
    var webpLossless by remember { mutableStateOf(true) }
    var webpQuality by remember { mutableStateOf("90") }
    // Mode 2 options
    var stickerDuration by remember { mutableStateOf("3") }
    // Mode 3 options
    var outImageFormat by remember { mutableStateOf("png") }
    var outImageSize by remember { mutableStateOf("512") }
    var jpegQuality by remember { mutableStateOf("90") }
    // Mode 4 options
    var loopSeconds by remember { mutableStateOf("3") }
    var outContainer by remember { mutableStateOf("mp4") }

    fun resetResult() {
        output = null
        logs = null
        task.error = null
        task.message = null
    }

    val pickMimes = when (mode) {
        StickerMode.IMAGE_TO_STICKER -> arrayOf("image/*")
        StickerMode.VIDEO_TO_STICKER -> arrayOf("video/*")
        StickerMode.STICKER_TO_IMAGE -> arrayOf("image/webp", "image/png")
        StickerMode.STICKER_TO_VIDEO -> arrayOf("image/webp", "image/png")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ToolScaffold(title = "Sticker Lab", onBack = onBack) {
            SectionCard("Mode") {
                LabeledDropdown(
                    label = "Conversion",
                    options = StickerMode.entries.toList(),
                    selected = mode,
                    optionLabel = { it.label },
                    onSelect = { mode = it; resetResult() },
                )
            }

            SectionCard("Input") {
                FilePickButton("Choose file", pickMimes) { uri: Uri ->
                    task.launch(scope) {
                        val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                        val file = withContext(Dispatchers.IO) { Files.copyToCache(context, uri) }
                        picked = file
                        pickedName = name
                        resetResult()
                        media = probeMedia(file.absolutePath)
                    }
                }
                pickedName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                if (picked != null) {
                    if (mode == StickerMode.VIDEO_TO_STICKER) {
                        MediaPlayerCard(picked)
                    } else {
                        AsyncImage(
                            model = picked,
                            contentDescription = "Selected image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                    }
                }
            }

            if (picked != null) {
                SectionCard("Options") {
                    when (mode) {
                        StickerMode.IMAGE_TO_STICKER -> {
                            LabeledDropdown(
                                label = "Sticker format",
                                options = listOf("webp", "png"),
                                selected = stickerFormat,
                                optionLabel = { it.uppercase() },
                                onSelect = { stickerFormat = it; resetResult() },
                            )
                            if (stickerFormat == "webp") {
                                LabeledDropdown(
                                    label = "WebP encoding",
                                    options = listOf("lossless", "lossy"),
                                    selected = if (webpLossless) "lossless" else "lossy",
                                    optionLabel = { it.replaceFirstChar { c -> c.uppercase() } },
                                    onSelect = { webpLossless = it == "lossless"; resetResult() },
                                )
                                if (!webpLossless) {
                                    LabeledField("Quality (0–100)", webpQuality, { webpQuality = it }, keyboardType = KeyboardType.Number)
                                }
                            }
                            Text("Scaled so the longest side is 512 px; transparency is preserved.", style = MaterialTheme.typography.bodySmall)
                        }

                        StickerMode.VIDEO_TO_STICKER -> {
                            LabeledField("Duration (seconds, max 3)", stickerDuration, { stickerDuration = it }, keyboardType = KeyboardType.Decimal)
                            Text("512×512 WebM (VP9), no audio. Keep it under 256 KB for best compatibility.", style = MaterialTheme.typography.bodySmall)
                        }

                        StickerMode.STICKER_TO_IMAGE -> {
                            LabeledDropdown(
                                label = "Output format",
                                options = listOf("png", "jpeg"),
                                selected = outImageFormat,
                                optionLabel = { it.uppercase() },
                                onSelect = { outImageFormat = it; resetResult() },
                            )
                            LabeledField("Longest side (px)", outImageSize, { outImageSize = it }, keyboardType = KeyboardType.Number)
                            if (outImageFormat == "jpeg") {
                                LabeledField("Quality (0–100)", jpegQuality, { jpegQuality = it }, keyboardType = KeyboardType.Number)
                            }
                        }

                        StickerMode.STICKER_TO_VIDEO -> {
                            LabeledField("Length (seconds)", loopSeconds, { loopSeconds = it }, keyboardType = KeyboardType.Decimal)
                            LabeledDropdown(
                                label = "Output container",
                                options = listOf("mp4", "webm"),
                                selected = outContainer,
                                optionLabel = { it.uppercase() },
                                onSelect = { outContainer = it; resetResult() },
                            )
                            Text(
                                if ((media?.durationMs ?: 0L) > 0L) "Animated input detected: converting directly."
                                else "Static image: looping into a video.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            task.launch(scope) {
                                val input = picked ?: error("Pick a file first")
                                when (mode) {
                                    StickerMode.IMAGE_TO_STICKER -> {
                                        val bmp = withContext(Dispatchers.IO) { decodeBitmap(input) }
                                            ?: error("Could not decode the image")
                                        val scaled = withContext(Dispatchers.IO) { scaleToLongest(bmp, 512) }
                                        val q = parseInt(webpQuality) ?: 90
                                        if (q !in 0..100) throw IllegalArgumentException("Quality must be between 0 and 100")
                                        val ext = if (stickerFormat == "png") "png" else "webp"
                                        val out = withContext(Dispatchers.IO) {
                                            File(Files.outputDir(context), "sticker_${Files.timestamp()}.$ext")
                                        }
                                        withContext(Dispatchers.IO) {
                                            if (stickerFormat == "png") compressPng(scaled, out)
                                            else compressWebp(scaled, out, if (webpLossless) WebpMode.LOSSLESS else WebpMode.LOSSY, q)
                                        }
                                        output = out
                                        val bytes = withContext(Dispatchers.IO) { out.length() }
                                        task.message = "Sticker ${scaled.width}×${scaled.height} (${Files.formatBytes(bytes)})"
                                    }

                                    StickerMode.VIDEO_TO_STICKER -> {
                                        val d = parseDouble(stickerDuration) ?: throw IllegalArgumentException("Duration must be a number")
                                        if (d <= 0 || d > 3) throw IllegalArgumentException("Duration must be between 0 and 3 seconds")
                                        val alpha = media?.hasAlpha ?: false
                                        val pixFmt = if (alpha) "yuva420p" else "yuv420p"
                                        val padColor = if (alpha) "black@0" else "black"
                                        val vf = "scale=512:512:force_original_aspect_ratio=decrease," +
                                            "pad=512:512:(ow-iw)/2:(oh-ih)/2:color=$padColor,setsar=1,format=$pixFmt"
                                        val args = mutableListOf("-y", "-i", input.absolutePath, "-t", fx(d), "-vf", vf, "-an",
                                            "-c:v", "libvpx-vp9", "-pix_fmt", pixFmt, "-b:v", "256k", "-crf", "40")
                                        if (alpha) args += listOf("-auto-alt-ref", "0")
                                        val out = withContext(Dispatchers.IO) {
                                            File(Files.outputDir(context), "sticker_${Files.timestamp()}.webm")
                                        }
                                        val result = Ffmpeg.run(args + out.absolutePath, (d * 1000).toLong()) { task.progress = it }
                                        if (!result.success) {
                                            logs = result.logs
                                            error("FFmpeg failed (${result.returnCode}). See logs below.")
                                        }
                                        output = out
                                        val bytes = withContext(Dispatchers.IO) { out.length() }
                                        task.message = if (bytes > 256 * 1024) {
                                            "Created ${out.name} (${Files.formatBytes(bytes)}) — larger than 256 KB, may not be accepted as a sticker."
                                        } else {
                                            "Created ${out.name} (${Files.formatBytes(bytes)})"
                                        }
                                    }

                                    StickerMode.STICKER_TO_IMAGE -> {
                                        val target = parseInt(outImageSize) ?: throw IllegalArgumentException("Size must be a whole number")
                                        if (target < 16 || target > 4096) throw IllegalArgumentException("Size must be between 16 and 4096")
                                        val q = parseInt(jpegQuality) ?: 90
                                        if (q !in 0..100) throw IllegalArgumentException("Quality must be between 0 and 100")
                                        val bmp = withContext(Dispatchers.IO) { decodeBitmap(input) }
                                            ?: error("Could not decode the sticker")
                                        val scaled = withContext(Dispatchers.IO) { scaleToLongest(bmp, target) }
                                        val ext = if (outImageFormat == "jpeg") "jpg" else "png"
                                        val out = withContext(Dispatchers.IO) {
                                            File(Files.outputDir(context), "sticker_${Files.timestamp()}.$ext")
                                        }
                                        withContext(Dispatchers.IO) {
                                            if (outImageFormat == "jpeg") compressJpeg(scaled, out, q) else compressPng(scaled, out)
                                        }
                                        output = out
                                        val bytes = withContext(Dispatchers.IO) { out.length() }
                                        task.message = "Image ${scaled.width}×${scaled.height} (${Files.formatBytes(bytes)})"
                                    }

                                    StickerMode.STICKER_TO_VIDEO -> {
                                        val n = parseDouble(loopSeconds) ?: throw IllegalArgumentException("Length must be a number of seconds")
                                        if (n <= 0 || n > 60) throw IllegalArgumentException("Length must be between 0 and 60 seconds")
                                        val animated = (media?.durationMs ?: 0L) > 0L
                                        val args = mutableListOf("-y")
                                        if (!animated) {
                                            args += "-loop"
                                            args += "1"
                                        }
                                        args += listOf("-i", input.absolutePath, "-t", fx(n),
                                            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p", "-r", "30")
                                        args += if (outContainer == "webm") {
                                            listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "35", "-pix_fmt", "yuv420p", "-an")
                                        } else {
                                            listOf("-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p", "-an")
                                        }
                                        val out = withContext(Dispatchers.IO) {
                                            File(Files.outputDir(context), "sticker_${Files.timestamp()}.${outContainer}")
                                        }
                                        val result = Ffmpeg.run(args + out.absolutePath, (n * 1000).toLong()) { task.progress = it }
                                        if (!result.success) {
                                            logs = result.logs
                                            error("FFmpeg failed (${result.returnCode}). See logs below.")
                                        }
                                        output = out
                                        val bytes = withContext(Dispatchers.IO) { out.length() }
                                        task.message = "Created ${out.name} (${Files.formatBytes(bytes)})"
                                    }
                                }
                            }
                        },
                        enabled = !task.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create")
                    }
                }
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)

            val imageOutput = mode == StickerMode.IMAGE_TO_STICKER || mode == StickerMode.STICKER_TO_IMAGE
            if (output != null && imageOutput) {
                SectionCard("Result") {
                    Text(output!!.name, style = MaterialTheme.typography.titleSmall)
                    Text(Files.formatBytes(output!!.length()), style = MaterialTheme.typography.bodySmall)
                    AsyncImage(
                        model = output,
                        contentDescription = "Result image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    ExportShareRow(output!!)
                }
            } else {
                ResultCard(output, preview = true)
            }
            logs?.let { SectionCard("FFmpeg logs") { OutputBlock(it) } }
        }
        BusyOverlay(task.busy, "Building sticker…", task.progress)
    }
}