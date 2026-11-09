package com.toolbox.multitool.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

enum class ImgFormat(val label: String, val ext: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
}

object ImageEngine {
    /** Decodes any format the OS supports (JPEG, PNG, WebP, HEIF, AVIF on 12+, GIF first frame). EXIF-aware. */
    suspend fun decode(ctx: Context, uri: Uri, maxSide: Int? = null): Bitmap = withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (maxSide != null) {
                val s = maxOf(info.size.width, info.size.height)
                if (s > maxSide) {
                    val f = maxSide.toFloat() / s
                    decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                }
            }
        }
    }

    fun compress(bmp: Bitmap, format: ImgFormat, quality: Int, out: OutputStream) {
        val cf = when (format) {
            ImgFormat.PNG -> Bitmap.CompressFormat.PNG
            ImgFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImgFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= 30)
                    if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bmp.compress(cf, quality, out)
    }

    /** JPEG has no alpha; flatten onto white so transparent PNGs don't turn black. */
    fun flattenForJpeg(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return out
    }

    fun resize(bmp: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(bmp, w, h, true)

    fun write(bmp: Bitmap, format: ImgFormat, quality: Int, file: File) {
        val b = if (format == ImgFormat.JPEG) flattenForJpeg(bmp) else bmp
        file.outputStream().buffered().use { compress(b, format, quality, it) }
    }
}
