package com.multikit.app.feature.media

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

private enum class VideoOp(val label: String) {
    TRIM("Trim"),
    CROP("Crop"),
    ROTATE("Rotate"),
    SPEED("Speed"),
    MUTE("Mute"),
    CONVERT("Convert"),
    RESIZE("Resize"),
    FRAME("Extract frame"),
    AUDIO("Extract audio"),
}

/** Full-featured video editor backed by the native FFmpeg engine. */
@Composable
fun VideoEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }

    var op by remember { mutableStateOf(VideoOp.TRIM) }
    var trimStart by remember { mutableStateOf("0") }
    var trimEnd by remember { mutableStateOf("") }
    var cropX by remember { mutableStateOf("0") }
    var cropY by remember { mutableStateOf("0") }
    var cropW by remember { mutableStateOf("") }
    var cropH by remember { mutableStateOf("") }
    var rotate by remember { mutableStateOf("90") }
    var speed by remember { mutableStateOf("1.5") }
    var container by remember { mutableStateOf("mp4") }
    var resizeWidth by remember { mutableStateOf("1280") }
    var frameTime by remember { mutableStateOf("0") }
    var audioFormat by remember { mutableStateOf("mp3") }

    fun resetResult() {
        output = null
        logs = null
        task.error = null
        task.message = null
    }

    // Build and validate the ffmpeg command for the current operation.
    fun buildCommand(): FfmpegCommand {
        val input = picked ?: throw IllegalArgumentException("Pick a video first")
        val m = media
        val durSec = m?.durationSec
        val hasAudio = m?.hasAudio ?: true
        val base = mutableListOf("-y", "-i", input.absolutePath)
        val reencode = listOf("-c:v", "mpeg4", "-q:v", "3") +
            if (hasAudio) listOf("-c:a", "aac") else listOf("-an")

        when (op) {
            VideoOp.TRIM -> {
                val start = parseDouble(trimStart)
                    ?: throw IllegalArgumentException("Start must be a number of seconds")
                val end = if (trimEnd.isBlank()) durSec else parseDouble(trimEnd)
                if (start < 0) throw IllegalArgumentException("Start must be >= 0")
                if (end == null) throw IllegalArgumentException("Enter an end time (seconds)")
                if (end <= start) throw IllegalArgumentException("End must be greater than start")
                if (durSec != null && end > durSec + 0.5) {
                    throw IllegalArgumentException("End exceeds video duration (${fx(durSec)} s)")
                }
                val ext = input.extension.ifBlank { "mp4" }
                val args = listOf("-y", "-ss", fx(start), "-i", input.absolutePath,
                    "-t", fx(end - start), "-c", "copy", "-map", "0")
                return FfmpegCommand(args, ext, ((end - start) * 1000).toLong())
            }

            VideoOp.CROP -> {
                val x = parseInt(cropX) ?: throw IllegalArgumentException("X must be a whole number")
                val y = parseInt(cropY) ?: throw IllegalArgumentException("Y must be a whole number")
                val w = if (cropW.isBlank()) m?.width else parseInt(cropW)
                val h = if (cropH.isBlank()) m?.height else parseInt(cropH)
                if (x < 0 || y < 0) throw IllegalArgumentException("X and Y must be >= 0")
                if (w == null || h == null || w <= 0 || h <= 0) {
                    throw IllegalArgumentException("Enter a positive crop width and height")
                }
                val srcW = m?.width
                val srcH = m?.height
                if (srcW != null && x + w > srcW) throw IllegalArgumentException("Crop exceeds video width ($srcW)")
                if (srcH != null && y + h > srcH) throw IllegalArgumentException("Crop exceeds video height ($srcH)")
                val ew = if (w % 2 == 0) w else w - 1
                val eh = if (h % 2 == 0) h else h - 1
                val args = base + listOf("-vf", "crop=$ew:$eh:$x:$y") + reencode
                return FfmpegCommand(args, "mp4", m?.durationMs)
            }

            VideoOp.ROTATE -> {
                val filter = when (rotate) {
                    "90" -> "transpose=1"
                    "180" -> "transpose=1,transpose=1"
                    "270" -> "transpose=2"
                    else -> throw IllegalArgumentException("Choose 90, 180 or 270")
                }
                val args = base + listOf("-vf", filter) + reencode
                return FfmpegCommand(args, "mp4", m?.durationMs)
            }

            VideoOp.SPEED -> {
                val x = parseDouble(speed) ?: throw IllegalArgumentException("Speed must be a number")
                if (x < 0.25 || x > 4.0) throw IllegalArgumentException("Speed must be between 0.25 and 4")
                val args = base + listOf("-vf", "setpts=PTS/${fx(x)}") +
                    (if (hasAudio) listOf("-af", atempoChain(x)) else emptyList()) + reencode
                return FfmpegCommand(args, "mp4", m?.durationMs)
            }

            VideoOp.MUTE -> {
                val args = base + listOf("-c:v", "copy", "-an", "-map", "0:v:0")
                return FfmpegCommand(args, "mp4", m?.durationMs)
            }

            VideoOp.CONVERT -> {
                when (container) {
                    "mp4", "mkv" -> {
                        val args = base + reencode
                        return FfmpegCommand(args, container, m?.durationMs)
                    }
                    "webm" -> {
                        val args = base + listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "30") +
                            (if (hasAudio) listOf("-c:a", "libopus") else listOf("-an"))
                        return FfmpegCommand(args, "webm", m?.durationMs)
                    }
                    "gif" -> {
                        val filter = "[0:v]fps=12,scale=480:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse"
                        val args = base + listOf("-filter_complex", filter, "-an", "-loop", "0")
                        return FfmpegCommand(args, "gif", m?.durationMs)
                    }
                    else -> throw IllegalArgumentException("Unknown container")
                }
            }

            VideoOp.RESIZE -> {
                var w = parseInt(resizeWidth) ?: throw IllegalArgumentException("Width must be a whole number")
                if (w < 16 || w > 8192) throw IllegalArgumentException("Width must be between 16 and 8192")
                if (w % 2 != 0) w += 1
                val args = base + listOf("-vf", "scale=$w:-2") + reencode
                return FfmpegCommand(args, "mp4", m?.durationMs)
            }

            VideoOp.FRAME -> {
                val t = parseDouble(frameTime) ?: throw IllegalArgumentException("Time must be a number of seconds")
                if (t < 0) throw IllegalArgumentException("Time must be >= 0")
                if (durSec != null && t > durSec) throw IllegalArgumentException("Time exceeds video duration (${fx(durSec)} s)")
                val args = listOf("-y", "-ss", fx(t), "-i", input.absolutePath, "-frames:v", "1")
                return FfmpegCommand(args, "png", m?.durationMs)
            }

            VideoOp.AUDIO -> {
                if (!hasAudio) throw IllegalArgumentException("This video has no audio track")
                return if (audioFormat == "mp3") {
                    val args = base + listOf("-vn", "-c:a", "libmp3lame", "-q:a", "2")
                    FfmpegCommand(args, "mp3", m?.durationMs)
                } else {
                    val args = base + listOf("-vn", "-c:a", "copy")
                    FfmpegCommand(args, "m4a", m?.durationMs)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ToolScaffold(title = "Video Editor", onBack = onBack) {
            SectionCard("Input") {
                FilePickButton("Choose video", arrayOf("video/*")) { uri: Uri ->
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
                if (pickedName != null) {
                    Text(pickedName!!, style = MaterialTheme.typography.titleSmall)
                }
                summary?.let { OutputBlock(it) }
                MediaPlayerCard(picked)
            }

            if (picked != null) {
                SectionCard("Operation") {
                    LabeledDropdown(
                        label = "Operation",
                        options = VideoOp.entries.toList(),
                        selected = op,
                        optionLabel = { it.label },
                        onSelect = {
                            op = it
                            resetResult()
                        },
                    )
                    when (op) {
                        VideoOp.TRIM -> {
                            LabeledField("Start (seconds)", trimStart, { trimStart = it }, keyboardType = KeyboardType.Decimal)
                            LabeledField("End (seconds, blank = full)", trimEnd, { trimEnd = it }, keyboardType = KeyboardType.Decimal)
                            media?.durationSec?.let {
                                Text("Duration: ${fx(it)} s", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        VideoOp.CROP -> {
                            LabeledField("X", cropX, { cropX = it }, keyboardType = KeyboardType.Number)
                            LabeledField("Y", cropY, { cropY = it }, keyboardType = KeyboardType.Number)
                            LabeledField("Width (blank = full)", cropW, { cropW = it }, keyboardType = KeyboardType.Number)
                            LabeledField("Height (blank = full)", cropH, { cropH = it }, keyboardType = KeyboardType.Number)
                            if (media?.width != null) {
                                Text("Source: ${media?.width} x ${media?.height}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        VideoOp.ROTATE -> LabeledDropdown(
                            label = "Rotation",
                            options = listOf("90", "180", "270"),
                            selected = rotate,
                            optionLabel = { "$it° clockwise" },
                            onSelect = { rotate = it; resetResult() },
                        )
                        VideoOp.SPEED -> {
                            LabeledField("Speed (0.25 – 4x)", speed, { speed = it }, keyboardType = KeyboardType.Decimal)
                        }
                        VideoOp.MUTE -> Text("Removes the audio track, keeps video unchanged.", style = MaterialTheme.typography.bodySmall)
                        VideoOp.CONVERT -> LabeledDropdown(
                            label = "Output container",
                            options = listOf("mp4", "mkv", "webm", "gif"),
                            selected = container,
                            optionLabel = { it.uppercase() },
                            onSelect = { container = it; resetResult() },
                        )
                        VideoOp.RESIZE -> LabeledField("Target width (px)", resizeWidth, { resizeWidth = it }, keyboardType = KeyboardType.Number)
                        VideoOp.FRAME -> LabeledField("Frame time (seconds)", frameTime, { frameTime = it }, keyboardType = KeyboardType.Decimal)
                        VideoOp.AUDIO -> LabeledDropdown(
                            label = "Audio format",
                            options = listOf("mp3", "m4a"),
                            selected = audioFormat,
                            optionLabel = { it.uppercase() },
                            onSelect = { audioFormat = it; resetResult() },
                        )
                    }

                    Button(
                        onClick = {
                            val command = try {
                                buildCommand()
                            } catch (e: IllegalArgumentException) {
                                task.error = e.message
                                null
                            }
                            if (command != null) {
                                logs = null
                                task.launch(scope) {
                                    val out = withContext(Dispatchers.IO) {
                                        File(Files.outputDir(context), "video_${Files.timestamp()}.${command.ext}")
                                    }
                                    val result = Ffmpeg.run(command.args + out.absolutePath, command.progressMs) {
                                        task.progress = it
                                    }
                                    if (!result.success) {
                                        logs = result.logs
                                        error("FFmpeg failed (${result.returnCode}). See logs below.")
                                    }
                                    val size = withContext(Dispatchers.IO) { out.length() }
                                    output = out
                                    task.message = "Created ${out.name} (${Files.formatBytes(size)})"
                                }
                            }
                        },
                        enabled = !task.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Run ${op.label}")
                    }
                }
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)
            // The extracted frame is a PNG (no media player); everything else previews.
            ResultCard(output, preview = op != VideoOp.FRAME)
            logs?.let {
                SectionCard("FFmpeg logs") { OutputBlock(it) }
            }
        }
        BusyOverlay(task.busy, "Processing video…", task.progress)
    }
}