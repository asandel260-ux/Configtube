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

private enum class AudioOp(val label: String) {
    TRIM("Trim"),
    JOIN("Join"),
    FADE("Fade in / out"),
    VOLUME("Volume"),
    SPEED("Speed"),
    CONVERT("Convert"),
    NORMALIZE("Normalize"),
}

/** Audio trimming, joining, fades, volume, tempo, conversion and loudness normalization. */
@Composable
fun AudioEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var second by remember { mutableStateOf<File?>(null) }
    var secondName by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }

    var op by remember { mutableStateOf(AudioOp.TRIM) }
    var trimStart by remember { mutableStateOf("0") }
    var trimEnd by remember { mutableStateOf("") }
    var fadeIn by remember { mutableStateOf("0") }
    var fadeOut by remember { mutableStateOf("0") }
    var volume by remember { mutableStateOf("1.0") }
    var speed by remember { mutableStateOf("1.0") }
    var convertFormat by remember { mutableStateOf("mp3") }

    fun resetResult() {
        output = null
        logs = null
        task.error = null
        task.message = null
    }

    val mp3 = listOf("-c:a", "libmp3lame", "-q:a", "2")

    suspend fun buildCommand(): FfmpegCommand {
        val input = picked ?: throw IllegalArgumentException("Pick an audio file first")
        val m = media
        val durSec = m?.durationSec
        val base = mutableListOf("-y", "-i", input.absolutePath)

        when (op) {
            AudioOp.TRIM -> {
                val start = parseDouble(trimStart) ?: throw IllegalArgumentException("Start must be a number of seconds")
                val end = if (trimEnd.isBlank()) durSec else parseDouble(trimEnd)
                if (start < 0) throw IllegalArgumentException("Start must be >= 0")
                if (end == null) throw IllegalArgumentException("Enter an end time (seconds)")
                if (end <= start) throw IllegalArgumentException("End must be greater than start")
                if (durSec != null && end > durSec + 0.5) throw IllegalArgumentException("End exceeds audio duration (${fx(durSec)} s)")
                val ext = input.extension.ifBlank { "mp3" }
                val args = listOf("-y", "-ss", fx(start), "-i", input.absolutePath, "-t", fx(end - start), "-c", "copy")
                return FfmpegCommand(args, ext, ((end - start) * 1000).toLong())
            }

            AudioOp.JOIN -> {
                val other = second ?: throw IllegalArgumentException("Pick a second audio file to join")
                val listFile = withContext(Dispatchers.IO) {
                    val f = Files.tempFile(context, "concat_", "txt")
                    f.writeText(
                        "file '${concatEscape(input.absolutePath)}'\n" +
                            "file '${concatEscape(other.absolutePath)}'\n"
                    )
                    f
                }
                val otherDur = probeMedia(other.absolutePath).durationMs ?: 0L
                val args = listOf("-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath) + mp3
                return FfmpegCommand(args, "mp3", (m?.durationMs ?: 0L) + otherDur)
            }

            AudioOp.FADE -> {
                val fi = parseDouble(fadeIn) ?: throw IllegalArgumentException("Fade in must be a number of seconds")
                val fo = parseDouble(fadeOut) ?: throw IllegalArgumentException("Fade out must be a number of seconds")
                if (fi < 0 || fo < 0) throw IllegalArgumentException("Fade values must be >= 0")
                if (durSec == null) throw IllegalArgumentException("Could not read audio duration for fade out")
                if (fi + fo > durSec) throw IllegalArgumentException("Fade in + out exceeds duration (${fx(durSec)} s)")
                val outStart = durSec - fo
                val filter = "afade=t=in:st=0:d=${fx(fi)},afade=t=out:st=${fx(outStart)}:d=${fx(fo)}"
                val args = base + listOf("-af", filter) + mp3
                return FfmpegCommand(args, "mp3", m?.durationMs)
            }

            AudioOp.VOLUME -> {
                val v = parseDouble(volume) ?: throw IllegalArgumentException("Volume must be a number")
                if (v < 0.1 || v > 3.0) throw IllegalArgumentException("Volume must be between 0.1 and 3.0")
                val args = base + listOf("-af", "volume=${fx(v)}") + mp3
                return FfmpegCommand(args, "mp3", m?.durationMs)
            }

            AudioOp.SPEED -> {
                val x = parseDouble(speed) ?: throw IllegalArgumentException("Speed must be a number")
                if (x < 0.25 || x > 4.0) throw IllegalArgumentException("Speed must be between 0.25 and 4")
                val args = base + listOf("-af", atempoChain(x)) + mp3
                return FfmpegCommand(args, "mp3", m?.durationMs)
            }

            AudioOp.CONVERT -> {
                val (codecArgs, ext) = when (convertFormat) {
                    "mp3" -> mp3 to "mp3"
                    "wav" -> listOf("-c:a", "pcm_s16le") to "wav"
                    "m4a" -> listOf("-c:a", "aac", "-b:a", "192k") to "m4a"
                    "ogg" -> listOf("-c:a", "libopus", "-b:a", "128k") to "ogg"
                    "flac" -> listOf("-c:a", "flac") to "flac"
                    else -> throw IllegalArgumentException("Unknown format")
                }
                val args = base + listOf("-vn") + codecArgs
                return FfmpegCommand(args, ext, m?.durationMs)
            }

            AudioOp.NORMALIZE -> {
                val args = base + listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "44100") + mp3
                return FfmpegCommand(args, "mp3", m?.durationMs)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ToolScaffold(title = "Audio Editor", onBack = onBack) {
            SectionCard("Input") {
                FilePickButton("Choose audio", arrayOf("audio/*")) { uri: Uri ->
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
                MediaPlayerCard(picked, heightDp = 120)
            }

            if (picked != null) {
                SectionCard("Operation") {
                    LabeledDropdown(
                        label = "Operation",
                        options = AudioOp.entries.toList(),
                        selected = op,
                        optionLabel = { it.label },
                        onSelect = { op = it; resetResult() },
                    )
                    when (op) {
                        AudioOp.TRIM -> {
                            LabeledField("Start (seconds)", trimStart, { trimStart = it }, keyboardType = KeyboardType.Decimal)
                            LabeledField("End (seconds, blank = full)", trimEnd, { trimEnd = it }, keyboardType = KeyboardType.Decimal)
                            media?.durationSec?.let { Text("Duration: ${fx(it)} s", style = MaterialTheme.typography.bodySmall) }
                        }
                        AudioOp.JOIN -> {
                            Text("Appends a second file to the first (re-encoded to MP3).", style = MaterialTheme.typography.bodySmall)
                            FilePickButton("Choose second audio", arrayOf("audio/*")) { uri: Uri ->
                                task.launch(scope) {
                                    val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                                    val file = withContext(Dispatchers.IO) { Files.copyToCache(context, uri) }
                                    second = file
                                    secondName = name
                                    resetResult()
                                }
                            }
                            secondName?.let { Text("Second: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        AudioOp.FADE -> {
                            LabeledField("Fade in (seconds)", fadeIn, { fadeIn = it }, keyboardType = KeyboardType.Decimal)
                            LabeledField("Fade out (seconds)", fadeOut, { fadeOut = it }, keyboardType = KeyboardType.Decimal)
                            media?.durationSec?.let { Text("Duration: ${fx(it)} s", style = MaterialTheme.typography.bodySmall) }
                        }
                        AudioOp.VOLUME -> LabeledField("Volume (0.1 – 3.0)", volume, { volume = it }, keyboardType = KeyboardType.Decimal)
                        AudioOp.SPEED -> LabeledField("Speed (0.25 – 4x)", speed, { speed = it }, keyboardType = KeyboardType.Decimal)
                        AudioOp.CONVERT -> LabeledDropdown(
                            label = "Output format",
                            options = listOf("mp3", "wav", "m4a", "ogg", "flac"),
                            selected = convertFormat,
                            optionLabel = { it.uppercase() },
                            onSelect = { convertFormat = it; resetResult() },
                        )
                        AudioOp.NORMALIZE -> Text("Single-pass loudness normalization (EBU R128, target -16 LUFS).", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            task.launch(scope) {
                                val command = buildCommand()
                                val out = withContext(Dispatchers.IO) {
                                    File(Files.outputDir(context), "audio_${Files.timestamp()}.${command.ext}")
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
            ResultCard(output, preview = true)
            logs?.let { SectionCard("FFmpeg logs") { OutputBlock(it) } }
        }
        BusyOverlay(task.busy, "Processing audio…", task.progress)
    }
}