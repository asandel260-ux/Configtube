package com.toolbox.multitool.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import com.toolbox.multitool.ui.*
import com.toolbox.multitool.ui.*
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.Busy
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold
import com.toolbox.multitool.ui.rememberPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

internal fun durationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally { r.release() }
}

/** "1:23.5", "83", "01:02:03" -> milliseconds */
internal fun parseTime(s: String): Long? {
    if (s.isBlank()) return null
    val parts = s.trim().split(":").map { it.toDoubleOrNull() ?: return null }
    return (parts.fold(0.0) { acc, v -> acc * 60 + v } * 1000).toLong()
}

@UnstableApi
@Composable
fun VideoEditorScreen(onBack: () -> Unit) = EditorScreen("Video editor", video = true, onBack = onBack)

@UnstableApi
@Composable
fun AudioEditorScreen(onBack: () -> Unit) = EditorScreen("Audio editor", video = false, onBack = onBack)

@UnstableApi
@Composable
private fun EditorScreen(title: String, video: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var dur by remember { mutableLongStateOf(0L) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var rotation by rememberSaveable { mutableIntStateOf(0) }
    var mute by rememberSaveable { mutableStateOf(false) }
    var extractAudio by rememberSaveable { mutableStateOf(false) }
    var bitrate by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    val pick = rememberPicker(arrayOf(if (video) "video/*" else "audio/*")) {
        uri = it.first(); name = Files.displayName(ctx, it.first())
        dur = runCatching { durationMs(ctx, it.first()) }.getOrDefault(0L)
        end = ""; start = ""; log = ""
    }

    ToolScaffold(title, onBack) {
        FilePickButton(if (uri == null) "Choose file" else "Change file", onClick = pick)
        if (uri != null) Text("$name · ${"%.1f".format(dur / 1000.0)} s", style = MaterialTheme.typography.bodyMedium)

        SectionCard("Trim") {
            Text("Format mm:ss or seconds. Leave empty to keep everything.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("Start") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it }, label = { Text("End") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        if (video) {
            SectionCard("Output") {
                OutlinedTextField(height, { height = it.filter(Char::isDigit) }, label = { Text("Height px (e.g. 720)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bitrate, { bitrate = it.filter(Char::isDigit) }, label = { Text("Bitrate kbps (blank = auto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Rotate", style = MaterialTheme.typography.labelLarge)
                ChipRow { listOf(0, 90, 180, 270).forEach { r -> FilterChip(rotation == r, { rotation = r }, { Text("$r°") }) } }
            }
            SectionCard("Options") {
                SwitchRow("Remove audio", mute) { mute = it }
                SwitchRow("Extract audio only (.m4a)", extractAudio) { extractAudio = it }
            }
        } else {
            Text("Output is AAC (.m4a).", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = uri != null && job == null, onClick = {
                val src = uri ?: return@Button
                val audioOnly = !video || extractAudio
                val ext = if (audioOnly) "m4a" else "mp4"
                val outName = Files.baseName(name) + "_edit.$ext"
                val tmp = Files.tempFile(ctx, outName)
                val opts = MediaOptions(
                    startMs = parseTime(start) ?: 0L,
                    endMs = parseTime(end),
                    targetHeight = height.toIntOrNull(),
                    rotationDegrees = rotation.toFloat(),
                    mute = mute,
                    videoBitrate = bitrate.toIntOrNull()?.times(1000),
                    audioOnly = audioOnly,
                )
                job = scope.launch {
                    progress = 0f; log = ""
                    runCatching {
                        MediaEngine.export(ctx, src, tmp, opts) { progress = it }
                        Files.saveToDownloads(ctx, tmp, outName, if (audioOnly) "audio/mp4" else "video/mp4")
                    }.onSuccess { log = "✓ $outName  ${Files.human(tmp.length())}\nSaved to Downloads/MultiTool" }
                        .onFailure { log = "✗ ${it.message}" }
                    tmp.delete(); progress = null; job = null
                }
            }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Export") }
            if (job != null) OutlinedButton(onClick = { job?.cancel(); job = null; progress = null }) { Text("Cancel") }
        }
        Busy(progress, "Exporting…")
        LogBox(log)
    }
}

/** Reads and edits ID3/MP4/FLAC/OGG tags. Length changes are done in the Audio editor (trim). */
@Composable
fun AudioMetaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    val fields = remember { mutableStateMapOf(FieldKey.TITLE to "", FieldKey.ARTIST to "", FieldKey.ALBUM to "", FieldKey.ALBUM_ARTIST to "", FieldKey.YEAR to "", FieldKey.GENRE to "", FieldKey.TRACK to "") }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(arrayOf("audio/*")) { picked ->
        scope.launch {
            val u = picked.first(); uri = u; fileName = Files.displayName(ctx, u); log = ""
            runCatching {
                val file = Files.copyToCache(ctx, u, fileName)
                withContext(Dispatchers.IO) {
                    val af = AudioFileIO.read(file)
                    val tag = af.tag
                    fields.keys.toList().forEach { k -> fields[k] = tag?.getFirst(k).orEmpty() }
                    val h = af.audioHeader
                    info = "Length ${h.trackLength} s · ${h.bitRate} kbps · ${h.sampleRate} Hz · ${h.format}"
                }
                file.delete()
            }.onFailure { log = "✗ Could not read tags: ${it.message}" }
        }
    }

    ToolScaffold("Music tags", onBack) {
        FilePickButton("Choose audio file", onClick = pick)
        if (uri != null) {
            Text(fileName, style = MaterialTheme.typography.titleSmall)
            Text(info, style = MaterialTheme.typography.bodySmall)
            fields.keys.sortedBy { it.ordinal }.forEach { key ->
                OutlinedTextField(fields[key].orEmpty(), { fields[key] = it }, label = { Text(key.name.lowercase().replace('_', ' ')) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val file = Files.copyToCache(ctx, uri!!, fileName)
                        withContext(Dispatchers.IO) {
                            val af = AudioFileIO.read(file)
                            val tag = af.tagOrCreateAndSetDefault
                            fields.forEach { (k, v) -> if (v.isBlank()) runCatching { tag.deleteField(k) } else tag.setField(k, v) }
                            af.commit()
                        }
                        Files.saveToDownloads(ctx, file, Files.baseName(fileName) + "_tagged." + fileName.substringAfterLast('.', "mp3"), ctx.contentResolver.getType(uri!!) ?: "audio/*")
                        file.delete()
                    }.onSuccess { log = "✓ Saved a tagged copy to Downloads/MultiTool" }.onFailure { log = "✗ ${it.message}" }
                }
            }) { Text("Save copy with new tags") }
        }
        LogBox(log)
    }
}
