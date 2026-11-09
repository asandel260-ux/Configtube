package com.multikit.app.util

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class FfmpegResult(
    val success: Boolean,
    val returnCode: String,
    val logs: String,
)

/**
 * Coroutine bridge over the native FFmpeg engine (ffmpeg-kit maintained fork).
 * All heavy work happens off the main thread inside the native library.
 */
object Ffmpeg {

    init {
        // Reduce log noise; warnings and errors are still captured per-session.
        FFmpegKitConfig.enableLogCallback(null)
        FFmpegKitConfig.enableStatisticsCallback(null)
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
    }

    /**
     * Execute FFmpeg with explicit arguments (no shell quoting issues).
     * [durationMs] enables progress reporting (0..1) when the input duration is known.
     */
    suspend fun run(
        args: List<String>,
        durationMs: Long? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): FfmpegResult = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { complete ->
                if (cont.isActive) {
                    cont.resume(
                        FfmpegResult(
                            success = ReturnCode.isSuccess(complete.returnCode),
                            returnCode = complete.returnCode?.toString() ?: "unknown",
                            logs = complete.allLogsAsString ?: "",
                        )
                    )
                }
            },
            { /* per-log callback not needed; logs collected at completion */ },
            { stats ->
                if (durationMs != null && durationMs > 0 && onProgress != null) {
                    val p = (stats.time.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    onProgress(p)
                }
            },
        )
        cont.invokeOnCancellation { session.cancel() }
    }

    /** Probe media metadata (duration, streams, format). Blocking; runs on IO. */
    suspend fun probe(path: String): MediaInformation? = withContext(Dispatchers.IO) {
        runCatching { FFprobeKit.getMediaInformation(path).mediaInformation }.getOrNull()
    }

    /** Duration in milliseconds, or null when unknown. */
    suspend fun durationMs(path: String): Long? {
        val info = probe(path) ?: return null
        val dur = info.duration ?: return null
        return runCatching { (dur.toDouble() * 1000).toLong() }.getOrNull()
    }

    /** Human readable media summary for UI display. */
    suspend fun summary(path: String): String {
        val info = probe(path) ?: return "Could not read media information."
        return buildString {
            appendLine("Format: ${info.format ?: "?"}")
            info.duration?.let { appendLine("Duration: $it s") }
            info.bitrate?.let { appendLine("Bitrate: $it kb/s") }
            info.streams?.forEach { s ->
                val type = s.type ?: "?"
                val codec = s.codec ?: "?"
                val extra = when (type) {
                    "video" -> " ${s.width ?: "?"}x${s.height ?: "?"}"
                    "audio" -> " ${s.sampleRate ?: "?"} Hz"
                    else -> ""
                }
                appendLine("Stream [$type]: $codec$extra")
            }
        }.trim()
    }
}