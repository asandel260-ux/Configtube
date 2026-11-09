package com.multikit.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared file helpers: SAF copying, exports to Downloads, sharing, formatting. */
object Files {

    fun outputDir(context: Context): File =
        File(context.filesDir, "outputs").apply { mkdirs() }

    fun tempFile(context: Context, prefix: String, ext: String): File =
        File.createTempFile(prefix, if (ext.startsWith(".")) ext else ".$ext", context.cacheDir)

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun mime(context: Context, uri: Uri): String {
        context.contentResolver.getType(uri)?.let { return it }
        val ext = displayName(context, uri).substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: guessMime(ext)
    }

    fun guessMime(ext: String): String = when (ext.lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "zip", "epub" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "md", "txt", "log", "csv", "kt", "java", "py", "js", "html", "css" -> "text/plain"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /** Copy a content Uri into the app cache, preserving the extension. Blocking; call on IO. */
    fun copyToCache(context: Context, uri: Uri, nameHint: String? = null): File {
        val name = nameHint ?: displayName(context, uri)
        val ext = name.substringAfterLast('.', "")
        val target = tempFile(context, "input_", if (ext.isNotBlank()) ext else "bin")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file" }
            target.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        return target
    }

    fun readText(context: Context, uri: Uri, maxBytes: Long = 8L * 1024 * 1024): String {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file" }
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (total < maxBytes) {
                val n = input.read(chunk)
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                total += n
            }
            return buffer.toString("UTF-8")
        }
    }

    /** Export a finished file into the public Downloads folder via MediaStore. */
    fun exportToDownloads(context: Context, file: File, mime: String? = null): Uri? {
        val type = mime ?: guessMime(file.extension)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out)
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            return Uri.fromFile(target)
        }
    }

    fun saveTextToDownloads(context: Context, name: String, text: String): Uri? {
        val tmp = tempFile(context, "text_", name.substringAfterLast('.', "txt"))
        tmp.writeText(text, Charsets.UTF_8)
        val renamed = File(tmp.parentFile, name)
        tmp.renameTo(renamed)
        return exportToDownloads(context, renamed, guessMime(name.substringAfterLast('.', "")))
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = guessMime(file.extension)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}