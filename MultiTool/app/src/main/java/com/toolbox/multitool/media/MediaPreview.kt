package com.toolbox.multitool.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtTimeMs(ms: Long): String = "%s.%d".format(fmtTime(ms), (ms.coerceAtLeast(0) % 1000) / 100)

private suspend fun frameStrip(ctx: Context, uri: Uri, durMs: Long, n: Int): List<Bitmap> = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        (0 until n).mapNotNull { i ->
            val t = durMs * (2 * i + 1) / (2 * n) * 1000
            runCatching { r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 120) }.getOrNull()
        }
    } finally { r.release() }
}

/**
 * Live player with seek bar and, when [trim] is given, a thumbnail-strip range slider.
 * Playback loops inside the selected range, rotation and mute are previewed live.
 */
@UnstableApi
@Composable
fun MediaPreview(
    uri: Uri,
    isVideo: Boolean,
    durationMs: Long,
    trim: ClosedFloatingPointRange<Float>?,
    onTrim: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    rotation: Int = 0,
    muted: Boolean = false,
) {
    val ctx = LocalContext.current
    val exo = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(exo) { onDispose { exo.release() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    val dur = durationMs.coerceAtLeast(1L)

    DisposableEffect(exo) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    LaunchedEffect(muted) { exo.volume = if (muted) 0f else 1f }
    LaunchedEffect(exo, trim) {
        while (true) {
            if (!scrubbing) pos = exo.currentPosition
            if (trim != null && exo.isPlaying && pos >= trim.endInclusive.toLong()) {
                exo.seekTo(trim.start.toLong()); pos = trim.start.toLong()
            }
            delay(80)
        }
    }
    fun togglePlay() {
        if (playing) exo.pause() else {
            if (trim != null && (pos < trim.start.toLong() || pos >= trim.endInclusive.toLong())) exo.seekTo(trim.start.toLong())
            exo.play()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (isVideo) 16f / 9f else 3f).clip(MaterialTheme.shapes.large).background(Color.Black)
                .clickable { togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = exo
                        }
                    },
                    update = { it.player = exo },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = rotation.toFloat()
                        val s = if (rotation % 180 != 0) 9f / 16f else 1f
                        scaleX = s; scaleY = s
                    },
                )
            } else {
                Icon(Icons.Filled.Audiotrack, null, Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.8f))
            }
            if (!playing) {
                Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(34.dp), tint = Color.White)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { togglePlay() }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
            }
            Slider(
                value = pos.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = { scrubbing = true; pos = it.toLong() },
                onValueChangeFinished = { exo.seekTo(pos); scrubbing = false },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${fmtTime(pos)} / ${fmtTime(dur)}", style = MaterialTheme.typography.labelMedium)
        }

        if (trim != null) {
            val frames by produceState<List<Bitmap>>(emptyList(), uri, dur) { if (isVideo) value = frameStrip(ctx, uri, dur, 8) }
            Text("Trim", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (frames.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(44.dp).clip(MaterialTheme.shapes.small)) {
                        frames.forEach { Image(it.asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) }
                    }
                }
                RangeSlider(
                    value = trim,
                    onValueChange = { r ->
                        val moved = if (r.start != trim.start) r.start else r.endInclusive
                        onTrim(r); exo.seekTo(moved.toLong()); pos = moved.toLong()
                    },
                    valueRange = 0f..dur.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Start ${fmtTimeMs(trim.start.toLong())}", style = MaterialTheme.typography.labelMedium)
                Text("Length ${fmtTimeMs((trim.endInclusive - trim.start).toLong())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("End ${fmtTimeMs(trim.endInclusive.toLong())}", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Start here", onClick = {
                    val s = pos.toFloat().coerceAtMost((trim.endInclusive - 100f).coerceAtLeast(0f))
                    onTrim(s..trim.endInclusive)
                }, modifier = Modifier.weight(1f), icon = Icons.Filled.SkipPrevious)
                TonalButton("End here", onClick = {
                    val e = pos.toFloat().coerceAtLeast(trim.start + 100f).coerceAtMost(dur.toFloat())
                    onTrim(trim.start..e)
                }, modifier = Modifier.weight(1f), icon = Icons.Filled.SkipNext)
                SecondaryButton("Reset", onClick = { onTrim(0f..dur.toFloat()) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
