package com.toolbox.multitool.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MediaOptions(
    val startMs: Long = 0,
    val endMs: Long? = null,
    val targetHeight: Int? = null,
    val rotationDegrees: Float = 0f,
    val mute: Boolean = false,
    val videoBitrate: Int? = null,
    val audioOnly: Boolean = false,
)

/**
 * Thin wrapper over Media3 Transformer: hardware MediaCodec encode/decode and fully
 * streaming (constant memory regardless of file size).
 */
@UnstableApi
object MediaEngine {

    suspend fun export(
        ctx: Context,
        uri: Uri,
        out: File,
        opts: MediaOptions,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) { // Transformer must be driven from a Looper thread
        val item = MediaItem.Builder().setUri(uri).apply {
            if (opts.startMs > 0 || opts.endMs != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(opts.startMs)
                        .apply { opts.endMs?.let { setEndPositionMs(it) } }
                        .build(),
                )
            }
        }.build()

        val videoEffects = buildList<androidx.media3.common.Effect> {
            opts.targetHeight?.let { add(Presentation.createForHeight(it)) }
            if (opts.rotationDegrees != 0f) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(opts.rotationDegrees).build())
        }

        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(opts.mute && !opts.audioOnly)
            .setRemoveVideo(opts.audioOnly)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val builder = Transformer.Builder(ctx)
            if (opts.audioOnly) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            opts.videoBitrate?.let {
                builder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(it).build())
                        .build(),
                )
            }
            builder.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            val transformer = builder.build()
            transformer.start(edited, out.absolutePath)

            val holder = ProgressHolder()
            val handler = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 250)
                }
            }
            handler.post(poll)
            cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer.cancel() }
        }
    }
}
