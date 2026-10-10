package net.wastu.cleancopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File

data class SanitizedImage(
    val file: File,
    val mimeType: String,
    val metadataSectionsRemoved: Int = 0,
    val notes: List<String> = emptyList()
)

data class SanitizedMedia(
    val file: File,
    val mimeType: String,
    val kind: MediaKind,
    val metadataSectionsRemoved: Int = 0,
    val notes: List<String> = emptyList()
)

object ImageSanitizer {
    fun sanitize(
        context: Context,
        source: Uri,
        outputFile: File? = null,
        outputBaseName: String = "0",
        onProgress: ((Float) -> Unit)? = null
    ): Result<SanitizedImage> = runCatching {
        val descriptor = MediaTypeDetector.detect(context, source)
        require(descriptor.kind == MediaKind.IMAGE) { "This is not an image" }
        val extension = descriptor.outputExtension
        val outputDir = File(context.cacheDir, "sanitized").apply { mkdirs() }
        val output = outputFile
            ?: File.createTempFile("${outputBaseName}_", ".$extension", outputDir)
        output.parentFile?.mkdirs()
        try {
            if (descriptor.sourceMimeType in setOf("image/heic", "image/heif")) {
                transcodeHeic(context, source, output)
            } else {
                context.contentResolver.openInputStream(source)?.use { input ->
                    output.outputStream().use { out -> input.copyTo(out) }
                } ?: error("The source image could not be opened")
            }
            onProgress?.invoke(0.7f)

            val originalExif = if (extension == "gif") null else runCatching { ExifInterface(output) }.getOrNull()
            val orientation = originalExif?.getAttribute(ExifInterface.TAG_ORIENTATION)
            val motionPhoto = originalExif?.getAttribute(ExifInterface.TAG_XMP)?.let { "MotionPhoto" in it || "MicroVideo" in it } == true
            val removed = ImageMetadataScrubber.scrub(output, extension)
            // Preserve only the rendering orientation, rebuilding a minimal EXIF block.
            if (orientation != null && orientation != ExifInterface.ORIENTATION_NORMAL.toString()) {
                ExifInterface(output).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                    saveAttributes()
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "The cleaned image could not be decoded" }
            onProgress?.invoke(1f)
            SanitizedImage(output, descriptor.mimeType, removed, buildList {
                if (descriptor.sourceMimeType in setOf("image/heic", "image/heif")) add("HEIC image converted to JPEG")
                if (motionPhoto) add("Motion photo exported as a still image")
            })
        } catch (error: Throwable) {
            output.delete()
            if (error is OutOfMemoryError) throw IllegalStateException("This image is too large for the device's available memory")
            throw error
        }
    }

    private fun transcodeHeic(context: Context, source: Uri, output: File) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                require(info.size.width.toLong() * info.size.height * 4 < Runtime.getRuntime().maxMemory() / 3) {
                    "This HEIC image is too large for the device's available memory"
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(source)?.use(BitmapFactory::decodeStream)
                ?: error("HEIC decoding requires device HEIF support")
        }
        bitmap.use { image ->
            output.outputStream().use { stream ->
                check(image.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Could not encode the HEIC image" }
            }
        }
    }

    private inline fun <T> Bitmap.use(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }
}
