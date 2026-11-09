package com.multikit.app.feature.media

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import java.util.Locale

private enum class CircleMode(val label: String) {
    TO_CIRCLE("Video → circle"),
    FROM_CIRCLE("Circle → video"),
}

/** Convert videos to circular alpha "circle message" WebM clips and back to solid video. */
@Composable
fun CircleVideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var mode by remember { mutableStateOf(CircleMode.TO_CIRCLE) }
    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }

    var size by remember { mutableStateOf("512") }
    var maxSeconds by remember { mutableStateOf("") }
    var keepAudio by remember { mutableStateOf(true) }
    var bgColor by remember { mutableStateOf("black") }
    var customHex by remember { mutableStateOf("#000000") }

    fun resetResult() {
        output = null
        logs = null
        task.error = null
        task.message = null
    }

    fun buildCommand(): FfmpegCommand {
        val input = picked ?: throw IllegalArgumentException("Pick a video first")
        val m = media
        return when (mode) {
            CircleMode.TO_CIRCLE -> {
                val s = parseInt(size) ?: throw IllegalArgumentException("Choose a size")
                if (s !in listOf(240, 320, 480, 512)) throw IllegalArgumentException("Size must be 240, 320, 480 or 512")
                val args = mutableListOf("-y", "-i", input.absolutePath)
                var progressMs = m?.durationMs
                if (maxSeconds.isNotBlank()) {
                    val limit = parseDouble(maxSeconds) ?: throw IllegalArgumentException("Max seconds must be a number")
                    if (limit <= 0) throw IllegalArgumentException("Max seconds must be greater than 0")
                    args += "-t"
                    args += fx(limit)
                    progressMs = (limit * 1000).toLong()
                }
                val vf = "crop=min(iw\\,ih):min(iw\\,ih),scale=$s:$s,format=yuva420p," +
                    "geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':" +
                    "a='if(lte(hypot(X-W/2,Y-H/2),W/2),255,0)'"
                args += "-vf"
                args += vf
                args += listOf("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-b:v", "0", "-crf", "30", "-auto-alt-ref", "0")
                args += if (keepAudio && m?.hasAudio == true) listOf("-c:a", "libopus") else listOf("-an")
                FfmpegCommand(args, "webm", progressMs)
            }

            CircleMode.FROM_CIRCLE -> {
                val w = m?.width ?: 512
                val h = m?.height ?: 512
                val dur = m?.durationSec ?: 15.0
                val color = when (bgColor) {
                    "black" -> "black"
                    "white" -> "white"
                    "green" -> "green"
                    "custom" -> {
                        val hex = customHex.trim().removePrefix("#")
                        if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                            throw IllegalArgumentException("Custom color must be a 6-digit hex like #1A2B3C")
                        }
                        "0x" + hex.lowercase(Locale.US)
                    }
                    else -> "black"
                }
                val args = listOf(
                    "-y",
                    "-f", "lavfi", "-i", "color=c=$color:s=${w}x${h}:d=${fx(dur)}",
                    "-i", input.absolutePath,
                    "-filter_complex", "[0:v][1:v]overlay=shortest=1:format=auto[v]",
                    "-map", "[v]",
                    "-map", "1:a?",
                    "-c:v", "mpeg4", "-q:v", "3",
                    "-c:a", "aac",
                    "-shortest",
                )
                FfmpegCommand(args, "mp4", m?.durationMs)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ToolScaffold(title = "Circle Video", onBack = onBack) {
            SectionCard("Mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleMode.entries.forEach { entry ->
                        FilterChip(
                            selected = mode == entry,
                            onClick = { mode = entry; resetResult() },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }

            SectionCard("Input") {
                FilePickButton(
                    if (mode == CircleMode.TO_CIRCLE) "Choose video" else "Choose circle WebM",
                    arrayOf("video/*"),
                ) { uri: Uri ->
                    task.launch(scope) {
                        val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                        val file = withContext(Dispatchers.IO) { Files.copyToCache(context, uri) }
                        picked = file
                        pickedName = name
                        resetResult()
                        summary = Ffmpeg.summary(file.absolutePath)
                        media = probeMedia(file.absolutePath)
                    }
                }
                pickedName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                summary?.let { OutputBlock(it) }
                MediaPlayerCard(picked)
            }

            if (picked != null) {
                SectionCard("Options") {
                    if (mode == CircleMode.TO_CIRCLE) {
                        LabeledDropdown(
                            label = "Circle size (px)",
                            options = listOf("240", "320", "480", "512"),
                            selected = size,
                            optionLabel = { "$it px" },
                            onSelect = { size = it; resetResult() },
                        )
                        LabeledField("Max seconds (blank = full)", maxSeconds, { maxSeconds = it }, keyboardType = KeyboardType.Decimal)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = keepAudio, onCheckedChange = { keepAudio = it; resetResult() })
                            Text("Keep audio (Opus)")
                        }
                        Text(
                            "The circular alpha mask uses the per-pixel geq filter, which is slow. " +
                                "Progress is shown below; shorter clips finish faster.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LabeledDropdown(
                            label = "Background color",
                            options = listOf("black", "white", "green", "custom"),
                            selected = bgColor,
                            optionLabel = { it.replaceFirstChar { c -> c.uppercase() } },
                            onSelect = { bgColor = it; resetResult() },
                        )
                        if (bgColor == "custom") {
                            LabeledField("Custom hex (#RRGGBB)", customHex, { customHex = it })
                        }
                        Text(
                            "Overlays the transparent circle onto a solid color and exports MP4.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = {
                            task.launch(scope) {
                                val command = buildCommand()
                                val out = withContext(Dispatchers.IO) {
                                    File(Files.outputDir(context), "circle_${Files.timestamp()}.${command.ext}")
                                }
                                val result = Ffmpeg.run(command.args + out.absolutePath, command.progressMs) {
                                    task.progress = it
                                }
                                if (!result.success) {
                                    logs = result.logs
                                    error("FFmpeg failed (${result.returnCode}). See logs below.")
                                }
                                val sizeBytes = withContext(Dispatchers.IO) { out.length() }
                                output = out
                                task.message = "Created ${out.name} (${Files.formatBytes(sizeBytes)})"
                            }
                        },
                        enabled = !task.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (mode == CircleMode.TO_CIRCLE) "Make circle video" else "Make solid video")
                    }
                }
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)
            ResultCard(output, preview = true)
            logs?.let { SectionCard("FFmpeg logs") { OutputBlock(it) } }
        }
        BusyOverlay(task.busy, "Rendering circle video…", task.progress)
    }
}