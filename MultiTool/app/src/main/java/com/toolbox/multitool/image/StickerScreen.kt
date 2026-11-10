package com.toolbox.multitool.image

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Telegram sticker specs:
 *  - static: PNG/WebP, one side exactly 512 px, other side <= 512 px, <= 512 KB
 *  - video : WebM (VP9), <= 512 px side, <= 3 s, <= 30 fps, <= 256 KB, no audio
 */
object StickerEngine {
    const val MAX_STATIC_BYTES = 512 * 1024

    fun fitTo512(bmp: Bitmap): Bitmap {
        val f = 512f / maxOf(bmp.width, bmp.height)
        return ImageEngine.resize(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1))
    }

    /** Lossy WebP, lowering quality until the file fits Telegram's 512 KB limit. Keeps transparency. */
    fun encodeStatic(bmp: Bitmap): ByteArray {
        var q = 95
        while (true) {
            val bos = ByteArrayOutputStream()
            ImageEngine.compress(bmp, ImgFormat.WEBP, q, bos)
            if (bos.size() <= MAX_STATIC_BYTES || q <= 20) return bos.toByteArray()
            q -= 10
        }
    }

    /** FFmpeg arguments for a spec-compliant video sticker (see README: FFmpeg integration point). */
    fun videoArgs(input: String, output: String, bitrateK: Int = 280) = listOf(
        "-y", "-i", input, "-t", "3", "-an",
        "-vf", "scale=512:512:force_original_aspect_ratio=decrease,fps=30",
        "-c:v", "libvpx-vp9", "-b:v", "${bitrateK}k", "-pix_fmt", "yuva420p", "-auto-alt-ref", "0",
        output,
    )
}

/** Plug in a maintained FFmpeg build here; the rest of the app does not depend on it. */
fun interface FfmpegRunner { suspend fun run(args: List<String>): Boolean }
object FfmpegProvider { var runner: FfmpegRunner? = null }

private class StickerPreview(val name: String, val bmp: Bitmap, val bytes: ByteArray)

@Composable
fun StickerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<Pair<Uri, Boolean>>() } // uri to isVideo
    val previews = remember { mutableStateMapOf<Uri, StickerPreview>() }
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }
    val saved = remember { mutableStateListOf<Saved>() }

    val pick = rememberPicker(arrayOf("image/*", "video/*")) { uris ->
        uris.forEach { u ->
            if (picked.none { it.first == u }) {
                val mime = ctx.contentResolver.getType(u).orEmpty()
                picked.add(u to (mime.startsWith("video/") || mime == "image/gif"))
            }
        }
        saved.clear()
    }

    LaunchedEffect(picked.size) {
        picked.toList().forEach { (u, isVideo) ->
            if (!isVideo && u !in previews) runCatching {
                val bmp = StickerEngine.fitTo512(ImageEngine.decode(ctx, u))
                val bytes = withContext(Dispatchers.Default) { StickerEngine.encodeStatic(bmp) }
                previews[u] = StickerPreview(Files.baseName(Files.displayName(ctx, u)), bmp, bytes)
            }
        }
    }

    ToolScaffold("Telegram sticker", onBack) {
        SectionCard("Telegram rules") {
            ChipRow { Pill("512 px side"); Pill("WebP / PNG"); Pill("≤ 512 KB"); Pill("Video: 3 s WebM ≤ 256 KB") }
        }
        if (picked.isEmpty()) {
            EmptyState(Icons.Filled.EmojiEmotions, "Make stickers", "Pick pictures to see the finished sticker on a transparency grid before saving.")
            FilePickButton("Choose images or videos", onClick = pick)
            return@ToolScaffold
        }

        picked.toList().forEach { (u, isVideo) ->
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = previews[u]
                    Box(Modifier.size(104.dp).clip(MaterialTheme.shapes.medium).checkerboard(), contentAlignment = Alignment.Center) {
                        when {
                            p != null -> Image(p.bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            isVideo -> Icon(Icons.Filled.Videocam, null, Modifier.size(36.dp))
                            else -> CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(Files.displayName(ctx, u), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        if (p != null) ChipRow { Pill("${p.bmp.width}×${p.bmp.height}"); Pill("${Files.human(p.bytes.size.toLong())}", icon = Icons.Filled.CheckCircle) }
                        else if (isVideo) Text(
                            if (FfmpegProvider.runner == null) "Video stickers need an FFmpeg runner (see README)." else "Will be converted to a 3 s WebM.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { picked.removeAll { it.first == u }; previews.remove(u) }) { Icon(Icons.Filled.Close, "Remove") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Add more", pick, Modifier.weight(1f), Icons.Filled.AddPhotoAlternate)
            SecondaryButton("Clear", { picked.clear(); previews.clear(); saved.clear() }, Modifier.weight(1f), Icons.Filled.Delete)
        }
        Busy(progress, "Saving stickers…")
        if (log.isNotEmpty()) Text(log, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        PrimaryButton("Save ${picked.size} sticker${if (picked.size == 1) "" else "s"}", icon = Icons.Filled.Check, loading = progress != null, onClick = {
            scope.launch {
                saved.clear(); log = ""; progress = -1f
                picked.toList().forEach { (u, isVideo) ->
                    runCatching {
                        val base = Files.baseName(Files.displayName(ctx, u))
                        if (isVideo) {
                            val runner = FfmpegProvider.runner ?: error("no FFmpeg runner installed")
                            val src = Files.copyToCache(ctx, u)
                            val out = Files.tempFile(ctx, "$base.webm")
                            var kbps = 280; var ok = false
                            while (kbps >= 60) {
                                ok = runner.run(StickerEngine.videoArgs(src.absolutePath, out.absolutePath, kbps))
                                if (!ok || out.length() <= 256 * 1024) break
                                kbps = (kbps * 0.75).toInt()
                            }
                            src.delete()
                            if (!ok) error("FFmpeg failed")
                            Files.saveToDownloads(ctx, out, "$base.webm", "video/webm")?.let { saved.add(Saved("$base.webm", it, "video/webm")) }
                            out.delete()
                        } else {
                            val p = previews[u] ?: error("preview not ready yet")
                            val out = Files.tempFile(ctx, "$base.webp").also { it.writeBytes(p.bytes) }
                            Files.saveToDownloads(ctx, out, "$base.webp", "image/webp")?.let { saved.add(Saved("$base.webp", it, "image/webp")) }
                            out.delete()
                        }
                    }.onFailure { log += "✗ ${Files.displayName(ctx, u)}: ${it.message}\n" }
                }
                progress = null
            }
        })
        ResultsCard(saved)
        TonalButton("Open @Stickers in Telegram", onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Stickers")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, icon = Icons.Filled.Send)
    }
}
