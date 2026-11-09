package com.toolbox.multitool.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.toolbox.multitool.core.Files
import com.toolbox.multitool.ui.Busy
import com.toolbox.multitool.ui.LogBox
import com.toolbox.multitool.ui.ToolScaffold
import com.toolbox.multitool.ui.rememberPicker
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

@Composable
fun StickerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var log by remember { mutableStateOf("") }

    val pick = rememberPicker(arrayOf("image/*", "video/*")) { uris ->
        scope.launch {
            log = ""; progress = -1f
            uris.forEach { uri -> runCatching { convert(ctx, uri) }.onSuccess { log += it + "\n" }.onFailure { log += "✗ ${it.message}\n" } }
            progress = null
        }
    }

    ToolScaffold("Telegram sticker", onBack) {
        Text("Images become 512 px WebP stickers (≤512 KB, transparency kept). Videos/GIFs become 3 s VP9 WebM stickers (≤256 KB).")
        Button(onClick = pick) { Text("Choose images or videos") }
        Busy(progress)
        LogBox(log)
        Text("After conversion, send the file to @Stickers in Telegram.", style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun convert(ctx: android.content.Context, uri: Uri): String {
    val name = Files.displayName(ctx, uri)
    val mime = ctx.contentResolver.getType(uri).orEmpty()
    val base = Files.baseName(name)
    if (mime.startsWith("video/") || mime == "image/gif") {
        val runner = FfmpegProvider.runner ?: return "✗ $name: video stickers need an FFmpeg runner (see README)."
        val src = Files.copyToCache(ctx, uri)
        val out = Files.tempFile(ctx, "$base.webm")
        var kbps = 280
        var ok = false
        while (kbps >= 60) {
            ok = runner.run(StickerEngine.videoArgs(src.absolutePath, out.absolutePath, kbps))
            if (!ok || out.length() <= 256 * 1024) break
            kbps = (kbps * 0.75).toInt()
        }
        src.delete()
        if (!ok) return "✗ $name: FFmpeg failed"
        Files.saveToDownloads(ctx, out, "$base.webm", "video/webm")
        return "✓ $base.webm  ${Files.human(out.length())}".also { out.delete() }
    }
    val bmp = StickerEngine.fitTo512(ImageEngine.decode(ctx, uri))
    val bytes = withContext(Dispatchers.Default) { StickerEngine.encodeStatic(bmp) }
    val out = Files.tempFile(ctx, "$base.webp").also { it.writeBytes(bytes) }
    Files.saveToDownloads(ctx, out, "$base.webp", "image/webp")
    return "✓ $base.webp  ${bmp.width}×${bmp.height}  ${Files.human(bytes.size.toLong())}".also { out.delete() }
}
