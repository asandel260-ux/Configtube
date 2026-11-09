package com.multikit.app.feature.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.multikit.app.util.Ffmpeg
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Raster formats offered by the image tools. GIF is encoded through FFmpeg. */
enum class ImgFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
    BMP("BMP", "bmp", "image/bmp"),
    GIF("GIF", "gif", "image/gif"),
}

data class ImageDims(val width: Int, val height: Int)

/**
 * Shared decode/encode helpers for the image tools.
 * Blocking functions must be called from Dispatchers.IO.
 */
internal object ImageOps {

    const val MAX_DECODE_DIM = 8192

    /** Read only the header to obtain source dimensions (no full decode). */
    fun decodeBounds(context: Context, uri: Uri): ImageDims? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            if (input != null) BitmapFactory.decodeStream(input, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) ImageDims(opts.outWidth, opts.outHeight) else null
    }.getOrNull()

    /** Power-of-two sample size so the decoded bitmap fits inside [maxDim]. */
    fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxDim) sample *= 2
        return sample
    }

    /**
     * Decode an image to a software bitmap. Uses ImageDecoder on API 28+
     * (handles HEIC and the first frame of animated sources), falling back to
     * BitmapFactory with inSampleSize. Longest side is capped at [maxDim].
     */
    suspend fun decode(context: Context, uri: Uri, maxDim: Int = MAX_DECODE_DIM): Bitmap =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val decoded = runCatching {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = info.size.width
                        val h = info.size.height
                        val longest = maxOf(w, h)
                        if (longest > maxDim && longest > 0) {
                            val f = maxDim.toFloat() / longest
                            decoder.setTargetSize(
                                (w * f).toInt().coerceAtLeast(1),
                                (h * f).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                }.getOrNull()
                if (decoded != null) return@withContext decoded
            }
            val bounds = decodeBounds(context, uri)
                ?: throw IllegalArgumentException("Unsupported or corrupt image")
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.width, bounds.height, maxDim)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected image" }
                BitmapFactory.decodeStream(input, null, opts)
                    ?: throw IllegalArgumentException("Could not decode image")
            }
        }

    /** Composite onto white; returns [src] itself when it has no alpha channel. */
    fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    @Suppress("DEPRECATION")
    fun webpCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    /** Encode to PNG/JPEG/WEBP via Bitmap.compress, or BMP via a manual writer. */
    fun compress(bmp: Bitmap, format: ImgFormat, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        if (format == ImgFormat.BMP) return encodeBmp(bmp)
        val cf = when (format) {
            ImgFormat.PNG -> Bitmap.CompressFormat.PNG
            ImgFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImgFormat.WEBP -> webpCompressFormat()
            else -> throw IllegalArgumentException("${format.label} must be encoded with FFmpeg")
        }
        val bos = ByteArrayOutputStream()
        check(bmp.compress(cf, if (format == ImgFormat.PNG) 100 else q, bos)) {
            "Failed to encode ${format.label}"
        }
        return bos.toByteArray()
    }

    /**
     * Best-effort encode under [targetBytes] for JPEG/WEBP: try the requested
     * quality first, then binary-search lower qualities (max 5 attempts).
     * Returns the bytes plus the quality actually used.
     */
    fun compressToTarget(
        bmp: Bitmap,
        format: ImgFormat,
        startQuality: Int,
        targetBytes: Long,
    ): Pair<ByteArray, Int> {
        val start = startQuality.coerceIn(1, 100)
        val first = compress(bmp, format, start)
        if (first.size <= targetBytes) return first to start
        var lo = 1
        var hi = start - 1
        var best: ByteArray? = null
        var bestQ = 0
        var attempts = 0
        while (lo <= hi && attempts < 5) {
            attempts++
            val mid = (lo + hi) / 2
            val candidate = compress(bmp, format, mid)
            if (candidate.size <= targetBytes) {
                best = candidate
                bestQ = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val chosen = best ?: compress(bmp, format, 1)
        return chosen to (bestQ.takeIf { best != null } ?: 1)
    }

    /** Uncompressed 32-bit BGRA BMP (Bitmap.compress has no BMP support). */
    fun encodeBmp(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val imageSize = w * h * 4
        val fileSize = 54 + imageSize
        val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        // BITMAPFILEHEADER
        buf.put('B'.code.toByte())
        buf.put('M'.code.toByte())
        buf.putInt(fileSize)
        buf.putShort(0)
        buf.putShort(0)
        buf.putInt(54)
        // BITMAPINFOHEADER
        buf.putInt(40)
        buf.putInt(w)
        buf.putInt(h)
        buf.putShort(1)
        buf.putShort(32)
        buf.putInt(0) // BI_RGB
        buf.putInt(imageSize)
        buf.putInt(2835) // ~72 dpi
        buf.putInt(2835)
        buf.putInt(0)
        buf.putInt(0)
        // Pixel array, bottom-up rows
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                buf.put((p and 0xFF).toByte())
                buf.put(((p shr 8) and 0xFF).toByte())
                buf.put(((p shr 16) and 0xFF).toByte())
                buf.put(((p shr 24) and 0xFF).toByte())
            }
        }
        return buf.array()
    }

    /** Convert any decodable image file to GIF using the built-in FFmpeg encoder. */
    suspend fun toGif(input: File, output: File) {
        val result = Ffmpeg.run(
            listOf(
                "-i", input.absolutePath,
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-y", output.absolutePath,
            )
        )
        if (!result.success || !output.exists() || output.length() == 0L) {
            output.delete()
            throw IllegalStateException(
                "FFmpeg GIF encoding failed (${result.returnCode}): ${result.logs.takeLast(300)}"
            )
        }
    }

    /** Map a source file name to the format used by "keep original". */
    fun formatForFileName(name: String): ImgFormat =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> ImgFormat.JPEG
            "webp" -> ImgFormat.WEBP
            else -> ImgFormat.PNG
        }

    /** File-system-safe base name for outputs. */
    fun safeBaseName(name: String): String =
        name.substringBeforeLast('.', name)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .ifBlank { "image" }
            .take(48)
}