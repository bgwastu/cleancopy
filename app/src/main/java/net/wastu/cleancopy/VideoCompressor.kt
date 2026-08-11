package net.wastu.cleancopy

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoCompressor {
    private const val MAX_HEIGHT = 720
    private const val PROGRESS_POLL_MS = 250L

    suspend fun sanitize(
        context: Context,
        source: Uri,
        output: File,
        onProgress: suspend (Float) -> Unit
    ): SanitizedMedia {
        output.parentFile?.mkdirs()
        try {
            val targetHeight = withContext(Dispatchers.IO) {
                sourceHeight(context, source).coerceIn(1, MAX_HEIGHT)
            }
            export(context, source, output, targetHeight, onProgress)
            withContext(Dispatchers.IO) { Mp4MetadataScrubber.scrub(output) }
            onProgress(1f)
            return SanitizedMedia(output, "video/mp4", MediaKind.VIDEO)
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun export(
        context: Context,
        source: Uri,
        output: File,
        targetHeight: Int,
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            var pollingJob: Job? = null
            lateinit var transformer: Transformer
            val mainHandler = Handler(Looper.getMainLooper())

            fun finish(block: (CancellableContinuation<Unit>) -> Unit) {
                pollingJob?.cancel()
                if (continuation.isActive) block(continuation)
            }

            transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        finish { it.resume(Unit) }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        finish { it.resumeWithException(exception) }
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                pollingJob?.cancel()
                mainHandler.post { runCatching { transformer.cancel() } }
            }

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForHeight(targetHeight))
                    )
                )
                .build()

            pollingJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                val progressHolder = ProgressHolder()
                while (isActive && continuation.isActive) {
                    if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            transformer.start(editedMediaItem, output.absolutePath)
        }
    }

    private fun sourceHeight(context: Context, source: Uri): Int {
        val retriever = MediaMetadataRetriever()
        val descriptor = context.contentResolver.openFileDescriptor(source, "r")
        return try {
            if (descriptor != null) {
                retriever.setDataSource(descriptor.fileDescriptor)
            } else {
                retriever.setDataSource(context, source)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: MAX_HEIGHT
        } finally {
            descriptor?.close()
            retriever.release()
        }
    }
}
