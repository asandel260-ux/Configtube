package com.multikit.app.feature.media

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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

/** Read and edit music metadata (tags), embed cover art and optionally cut length. */
@Composable
fun MusicMetaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = rememberTaskState()

    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var cover by remember { mutableStateOf<File?>(null) }
    var coverName by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<File?>(null) }
    var resultTags by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<String?>(null) }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var track by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var cutLength by remember { mutableStateOf(false) }
    var cutSeconds by remember { mutableStateOf("30") }

    fun resetResult() {
        output = null
        resultTags = null
        logs = null
        task.error = null
        task.message = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ToolScaffold(title = "Music Metadata", onBack = onBack) {
            SectionCard("Input") {
                FilePickButton("Choose audio file", arrayOf("audio/*")) { uri: Uri ->
                    task.launch(scope) {
                        val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                        val file = withContext(Dispatchers.IO) { Files.copyToCache(context, uri) }
                        picked = file
                        pickedName = name
                        cover = null
                        coverName = null
                        resetResult()
                        val m = probeMedia(file.absolutePath)
                        media = m
                        title = m.tags["title"].orEmpty()
                        artist = m.tags["artist"].orEmpty()
                        album = m.tags["album"].orEmpty()
                        albumArtist = m.tags["album_artist"].orEmpty()
                        genre = m.tags["genre"].orEmpty()
                        date = m.tags["date"].orEmpty()
                        track = m.tags["track"].orEmpty()
                        comment = m.tags["comment"].orEmpty()
                    }
                }
                pickedName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                media?.let { m ->
                    val existing = if (m.tags.isEmpty()) "No existing tags found."
                    else m.tags.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                    OutputBlock(existing)
                }
                MediaPlayerCard(picked, heightDp = 120)
            }

            if (picked != null) {
                SectionCard("Tags") {
                    LabeledField("Title", title, { title = it })
                    LabeledField("Artist", artist, { artist = it })
                    LabeledField("Album", album, { album = it })
                    LabeledField("Album artist", albumArtist, { albumArtist = it })
                    LabeledField("Genre", genre, { genre = it })
                    LabeledField("Date / year", date, { date = it }, keyboardType = KeyboardType.Number)
                    LabeledField("Track", track, { track = it }, keyboardType = KeyboardType.Number)
                    LabeledField("Comment", comment, { comment = it }, singleLine = false, minLines = 2)
                }

                SectionCard("Cover art (optional)") {
                    Text("Embedded into MP3 / M4A files as attached picture.", style = MaterialTheme.typography.bodySmall)
                    FilePickButton("Choose cover image", arrayOf("image/*")) { uri: Uri ->
                        task.launch(scope) {
                            val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                            val file = withContext(Dispatchers.IO) { Files.copyToCache(context, uri) }
                            cover = file
                            coverName = name
                            resetResult()
                        }
                    }
                    coverName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                SectionCard("Cut length (optional)") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cutLength, onCheckedChange = { cutLength = it; resetResult() })
                        Text("Trim to a fixed length (re-encodes audio)")
                    }
                    if (cutLength) {
                        LabeledField("Length (seconds)", cutSeconds, { cutSeconds = it }, keyboardType = KeyboardType.Decimal)
                        media?.durationSec?.let { Text("Source duration: ${fx(it)} s", style = MaterialTheme.typography.bodySmall) }
                    }
                }

                Button(
                    onClick = {
                        task.launch(scope) {
                            val input = picked ?: error("Pick an audio file first")
                            val ext = input.extension.ifBlank { "mp3" }.lowercase(Locale.US)
                            val m = media
                            val args = mutableListOf("-y", "-i", input.absolutePath)
                            val embedCover = cover != null && ext in listOf("mp3", "m4a", "mp4", "m4b")
                            if (cover != null && !embedCover) {
                                // Cover requested but container does not support attached pictures.
                                error("Cover embedding is supported for MP3 / M4A only (picked .$ext)")
                            }
                            if (embedCover) {
                                args += "-i"
                                args += cover!!.absolutePath
                            }
                            args += "-map"
                            args += "0:a"
                            if (embedCover) {
                                args += "-map"
                                args += "1:v"
                            }

                            if (cutLength) {
                                val n = parseDouble(cutSeconds)
                                    ?: throw IllegalArgumentException("Length must be a number of seconds")
                                if (n <= 0) throw IllegalArgumentException("Length must be greater than 0")
                                val srcDur = m?.durationSec
                                if (srcDur != null && n > srcDur + 0.5) {
                                    throw IllegalArgumentException("Length exceeds source duration (${fx(srcDur)} s)")
                                }
                                args += "-t"
                                args += fx(n)
                                val codec = when (ext) {
                                    "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "2")
                                    "m4a", "mp4", "m4b" -> listOf("-c:a", "aac", "-b:a", "192k")
                                    "ogg", "opus" -> listOf("-c:a", "libopus", "-b:a", "128k")
                                    "flac" -> listOf("-c:a", "flac")
                                    "wav" -> listOf("-c:a", "pcm_s16le")
                                    else -> listOf("-c:a", "copy")
                                }
                                args += codec
                            } else {
                                args += "-c:a"
                                args += "copy"
                            }

                            if (embedCover) {
                                args += "-c:v"
                                args += "mjpeg"
                                if (ext == "mp3") {
                                    args += "-id3v2_version"
                                    args += "3"
                                }
                                args += "-disposition:v:0"
                                args += "attached_pic"
                            }

                            val fields = linkedMapOf(
                                "title" to title,
                                "artist" to artist,
                                "album" to album,
                                "album_artist" to albumArtist,
                                "genre" to genre,
                                "date" to date,
                                "track" to track,
                                "comment" to comment,
                            )
                            fields.forEach { (key, value) ->
                                if (value.isNotBlank()) {
                                    args += "-metadata"
                                    args += "$key=$value"
                                }
                            }

                            val out = withContext(Dispatchers.IO) {
                                File(Files.outputDir(context), "meta_${Files.timestamp()}.$ext")
                            }
                            val result = Ffmpeg.run(args + out.absolutePath, m?.durationMs) {
                                task.progress = it
                            }
                            if (!result.success) {
                                logs = result.logs
                                error("FFmpeg failed (${result.returnCode}). See logs below.")
                            }
                            output = out
                            val outTags = probeMedia(out.absolutePath).tags
                            resultTags = if (outTags.isEmpty()) "No tags found in the saved file."
                            else outTags.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                            task.message = "Saved ${out.name}"
                        }
                    },
                    enabled = !task.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save metadata")
                }
            }

            ErrorBanner(task.error)
            SuccessBanner(task.message)
            resultTags?.let { SectionCard("Resulting tags") { OutputBlock(it) } }
            ResultCard(output, preview = true)
            logs?.let { SectionCard("FFmpeg logs") { OutputBlock(it) } }
        }
        BusyOverlay(task.busy, "Writing metadata…", task.progress)
    }
}