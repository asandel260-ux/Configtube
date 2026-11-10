package com.toolbox.multitool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.CancellationException
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

@UnstableApi
@Composable
fun VideoEditorScreen(onBack: () -> Unit) = EditorScreen("Video editor", true, onBack)

@UnstableApi
@Composable
fun AudioEditorScreen(onBack: () -> Unit) = EditorScreen("Audio editor", false, onBack)

private val resolutions = listOf<Pair<String, Int?>>("Original" to null, "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360)
private val qualities = listOf<Pair<String, Int?>>("Auto" to null, "High" to 8000, "Medium" to 4000, "Low" to 1500, "Tiny" to 700)

@UnstableApi
@Composable
private fun EditorScreen(title: String, video: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var size by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var trim by remember { mutableStateOf(0f..1f) }
    var heightSel by remember { mutableStateOf<Int?>(null) }
    var kbpsSel by remember { mutableStateOf<Int?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var mute by remember { mutableStateOf(false) }
    var extractAudio by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf(if (video) "video/*" else "audio/*")) { l ->
        val u = l.first()
        uri = u; name = Files.displayName(ctx, u); size = Files.size(ctx, u)
        dur = runCatching { durationMs(ctx, u) }.getOrDefault(0L)
        trim = 0f..dur.coerceAtLeast(1L).toFloat()
        rotation = 0; saved.clear(); error = ""
    }

    ToolScaffold(title, onBack) {
        val src = uri
        if (src == null) {
            EmptyState(
                if (video) Icons.Filled.Videocam else Icons.Filled.Audiotrack,
                if (video) "Pick a video" else "Pick an audio file",
                "You will get a live preview, a draggable trim bar and a size estimate before exporting.",
            )
            FilePickButton(if (video) "Choose video" else "Choose audio", onClick = pick)
            return@ToolScaffold
        }

        val audioOnly = !video || extractAudio
        FileInfoCard(name, "${fmtTime(dur)} · ${Files.human(size)}", if (video) Icons.Filled.Videocam else Icons.Filled.Audiotrack, onChange = pick)

        MediaPreview(
            uri = src, isVideo = video, durationMs = dur, trim = trim, onTrim = { trim = it },
            rotation = if (audioOnly) 0 else rotation, muted = mute && !audioOnly,
        )

        if (video) {
            SectionCard("Options") {
                SwitchRow("Remove audio", mute, "Preview is muted too") { mute = it; if (it) extractAudio = false }
                SwitchRow("Extract audio only", extractAudio, "Saves an .m4a instead of a video") { extractAudio = it; if (it) mute = false }
            }
            if (!extractAudio) {
                SectionCard("Resolution") {
                    ChipRow { resolutions.forEach { (label, h) -> FilterChip(heightSel == h, { heightSel = h }, { Text(label) }) } }
                }
                SectionCard("Quality") {
                    ChipRow { qualities.forEach { (label, k) -> FilterChip(kbpsSel == k, { kbpsSel = k }, { Text(label) }) } }
                    Text("Lower quality means a smaller file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionCard("Rotate") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton("Left", { rotation = (rotation + 270) % 360 }, Modifier.weight(1f), Icons.Filled.RotateLeft)
                        Pill("$rotation°", icon = Icons.Filled.ScreenRotation)
                        SecondaryButton("Right", { rotation = (rotation + 90) % 360 }, Modifier.weight(1f), Icons.Filled.RotateRight)
                    }
                }
            }
        }

        val lenMs = (trim.endInclusive - trim.start).toLong().coerceAtLeast(0L)
        val estimate: Long? = when {
            audioOnly -> 128L * 125L * lenMs / 1000L
            kbpsSel != null -> (kbpsSel!! + 128L) * 125L * lenMs / 1000L
            else -> null
        }
        SectionCard("Result") {
            ChipRow {
                Pill(fmtTime(lenMs), icon = Icons.Filled.Timer)
                if (!audioOnly) Pill(resolutions.first { it.second == heightSel }.first, icon = Icons.Filled.AspectRatio)
                Pill(if (estimate != null) "≈ ${Files.human(estimate)}" else "Size: auto", icon = Icons.Filled.Storage)
            }
        }

        Busy(progress, progress?.let { "Exporting… ${(it * 100).toInt()}%" } ?: "")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton(
            if (audioOnly) "Export audio" else "Export video", icon = Icons.Filled.Check, loading = job != null,
            onClick = {
                val ext = if (audioOnly) "m4a" else "mp4"
                val mime = if (audioOnly) "audio/mp4" else "video/mp4"
                val outName = Files.baseName(name) + "_edit.$ext"
                val tmp = Files.tempFile(ctx, outName)
                val opts = MediaOptions(
                    startMs = trim.start.toLong(),
                    endMs = if (trim.endInclusive >= dur - 50) null else trim.endInclusive.toLong(),
                    targetHeight = if (audioOnly) null else heightSel,
                    rotationDegrees = if (audioOnly) 0f else rotation.toFloat(),
                    mute = mute, videoBitrate = if (audioOnly) null else kbpsSel?.times(1000),
                    audioOnly = audioOnly,
                )
                job = scope.launch {
                    progress = 0f; error = ""; saved.clear()
                    runCatching {
                        MediaEngine.export(ctx, src, tmp, opts) { progress = it }
                        Files.saveToDownloads(ctx, tmp, outName, mime)?.let { saved.add(Saved(outName, it, mime)) }
                    }.onFailure { if (it !is CancellationException) error = "Export failed: ${it.message}" }
                    tmp.delete(); progress = null; job = null
                }
            },
        )
        if (job != null) SecondaryButton("Cancel", onClick = { job?.cancel(); job = null; progress = null }, icon = Icons.Filled.Close)
        ResultsCard(saved)
    }
}

private val tagFields = listOf(
    FieldKey.TITLE to "Title", FieldKey.ARTIST to "Artist", FieldKey.ALBUM to "Album", FieldKey.ALBUM_ARTIST to "Album artist",
    FieldKey.YEAR to "Year", FieldKey.GENRE to "Genre", FieldKey.TRACK to "Track number",
)

/** Reads and edits ID3 / MP4 / FLAC / OGG tags, with cover art and a mini player. */
@UnstableApi
@Composable
fun AudioMetaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var size by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var info by remember { mutableStateOf(listOf<String>()) }
    val fields = remember { mutableStateMapOf<FieldKey, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf("audio/*")) { picked ->
        val u = picked.first()
        scope.launch {
            uri = u; fileName = Files.displayName(ctx, u); size = Files.size(ctx, u); error = ""; saved.clear()
            dur = runCatching { durationMs(ctx, u) }.getOrDefault(0L)
            cover = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(ctx, u); r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                catch (_: Exception) { null } finally { r.release() }
            }
            runCatching {
                val file = Files.copyToCache(ctx, u, fileName)
                withContext(Dispatchers.IO) {
                    val af = AudioFileIO.read(file)
                    val tag = af.tag
                    tagFields.forEach { (k, _) -> fields[k] = tag?.getFirst(k).orEmpty() }
                    val h = af.audioHeader
                    info = listOf("${h.bitRate} kbps", "${h.sampleRate} Hz", h.format.toString())
                }
                file.delete()
            }.onFailure { error = "Could not read tags: ${it.message}" }
        }
    }

    ToolScaffold("Music tags", onBack) {
        val src = uri
        if (src == null) {
            EmptyState(Icons.Filled.LibraryMusic, "Pick a song", "Edit title, artist, album and more, with cover art and a player to check you picked the right track.")
            FilePickButton("Choose audio file", onClick = pick)
            return@ToolScaffold
        }
        FileInfoCard(fileName, "${fmtTime(dur)} · ${Files.human(size)}", Icons.Filled.LibraryMusic, onChange = pick)

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = cover
            if (c != null) Image(c.asImageBitmap(), "Cover art", Modifier.size(110.dp).clip(MaterialTheme.shapes.large), contentScale = ContentScale.Crop)
            else Surface(Modifier.size(110.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Album, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fields[FieldKey.TITLE].orEmpty().ifBlank { "Unknown title" }, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(fields[FieldKey.ARTIST].orEmpty().ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                ChipRow { Pill(fmtTime(dur), icon = Icons.Filled.Timer); info.forEach { Pill(it) } }
            }
        }

        MediaPreview(uri = src, isVideo = false, durationMs = dur, trim = null, onTrim = {})

        SectionCard("Tags") {
            tagFields.forEach { (key, label) ->
                OutlinedTextField(fields[key].orEmpty(), { fields[key] = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton("Save tagged copy", icon = Icons.Filled.Save, loading = busy, onClick = {
            scope.launch {
                busy = true; error = ""; saved.clear()
                runCatching {
                    val file = Files.copyToCache(ctx, src, fileName)
                    withContext(Dispatchers.IO) {
                        val af = AudioFileIO.read(file)
                        val tag = af.tagOrCreateAndSetDefault
                        fields.forEach { (k, v) -> if (v.isBlank()) runCatching { tag.deleteField(k) } else tag.setField(k, v) }
                        af.commit()
                    }
                    val outName = Files.baseName(fileName) + "_tagged." + fileName.substringAfterLast('.', "mp3")
                    val mime = ctx.contentResolver.getType(src) ?: "audio/*"
                    Files.saveToDownloads(ctx, file, outName, mime)?.let { saved.add(Saved(outName, it, mime)) }
                    file.delete()
                }.onFailure { error = "Save failed: ${it.message}" }
                busy = false
            }
        })
        ResultsCard(saved)
    }
}
