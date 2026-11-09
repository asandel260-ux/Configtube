package com.toolbox.multitool.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Files {
    fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    fun size(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L

    fun baseName(name: String) = name.substringBeforeLast('.', name)

    /** Streams a content Uri into the app cache (never loads it into memory). */
    suspend fun copyToCache(ctx: Context, uri: Uri, name: String = displayName(ctx, uri)): File =
        withContext(Dispatchers.IO) {
            val out = File(ctx.cacheDir, "in_${System.nanoTime()}_$name")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            out
        }

    fun tempFile(ctx: Context, name: String) = File(ctx.cacheDir, "out_${System.nanoTime()}_$name")

    /** Publishes a file to Downloads/MultiTool via MediaStore (no storage permission needed). */
    suspend fun saveToDownloads(ctx: Context, src: File, displayName: String, mime: String): Uri? =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MultiTool")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0; var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
