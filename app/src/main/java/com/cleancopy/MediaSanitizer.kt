package com.cleancopy

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaSanitizer {
    suspend fun sanitize(
        context: Context,
        source: Uri,
        sessionDirectory: File,
        itemIndex: Int,
        outputBaseName: String = OutputNameStore.resolve(context, source),
        compressVideo: Boolean = VideoCompressionStore.isEnabled(context),
        onProgress: suspend (Float) -> Unit
    ): SanitizedMedia {
        val descriptor = MediaTypeDetector.detect(context, source)
        val itemDirectory = File(sessionDirectory, "item_$itemIndex").apply { mkdirs() }
        val output = File(itemDirectory, "$outputBaseName.${descriptor.outputExtension}")

        return when (descriptor.kind) {
            MediaKind.IMAGE -> {
                onProgress(0f)
                if (descriptor.outputExtension == "gif") {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(source)?.use { input ->
                            output.outputStream().use { stream -> input.copyTo(stream) }
                        } ?: error("The GIF source could not be opened")
                    }
                    onProgress(1f)
                    SanitizedMedia(output, descriptor.mimeType, MediaKind.IMAGE)
                } else {
                    val image = withContext(Dispatchers.IO) {
                        ImageSanitizer.sanitize(context, source, output).getOrThrow()
                    }
                    onProgress(1f)
                    SanitizedMedia(image.file, image.mimeType, MediaKind.IMAGE)
                }
            }
            MediaKind.VIDEO -> withContext(Dispatchers.IO) {
                VideoSanitizer.sanitize(context, source, output, compressVideo, onProgress)
            }
        }
    }
}
