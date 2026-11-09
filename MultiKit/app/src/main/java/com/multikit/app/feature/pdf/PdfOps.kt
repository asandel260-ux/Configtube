package com.multikit.app.feature.pdf

import android.content.Context
import com.multikit.app.util.Files
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/** Small blocking helpers shared by the PDF tool modes. Call on Dispatchers.IO. */
internal object PdfOps {

    /** Bundle [files] into a ZIP archive at [target] using commons-compress. */
    fun zipFiles(files: List<File>, target: File) {
        ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            files.forEach { file ->
                zip.putArchiveEntry(ZipArchiveEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeArchiveEntry()
            }
            zip.finish()
        }
    }

    /** Export every file to Downloads; returns the number of successes. */
    fun exportAll(context: Context, files: List<File>, onProgress: (Float) -> Unit = {}): Int {
        var ok = 0
        files.forEachIndexed { i, file ->
            if (Files.exportToDownloads(context, file) != null) ok++
            onProgress((i + 1).toFloat() / files.size.coerceAtLeast(1))
        }
        return ok
    }
}