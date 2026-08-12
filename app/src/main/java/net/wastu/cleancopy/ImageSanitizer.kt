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
    val mimeType: String
)

data class SanitizedMedia(
    val file: File,
    val mimeType: String,
    val kind: MediaKind
)

object ImageSanitizer {
    private val removableTags = listOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
        "GPSProcessingMethod", "GPSAreaInformation", "GPSDestBearing",
        "GPSDestDistance", "GPSDestLatitude", "GPSDestLatitudeRef",
        "GPSDestLongitude", "GPSDestLongitudeRef", "GPSImgDirection",
        "GPSImgDirectionRef", "GPSMapDatum", "GPSMeasureMode", "GPSSpeed",
        "GPSSpeedRef", "GPSStatus", "GPSTrack", "GPSTrackRef", "GPSVersionID",
        "DateTime", "DateTimeOriginal", "DateTimeDigitized", "Make", "Model",
        "Software", "Artist", "Copyright", "UserComment", "ImageDescription",
        "DocumentName", "HostComputer", "LensMake", "LensModel", "LensSerialNumber",
        "BodySerialNumber", "CameraOwnerName", "ImageUniqueID", "Xmp"
    )

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

            val motionPhoto = MotionPhotoDetector.isMotionPhoto(context, source)
            val exif = ExifInterface(output)
            removableTags.forEach { tag -> runCatching { exif.setAttribute(tag, null) } }
            exif.saveAttributes()
            if (extension == "jpg" && !motionPhoto) JpegMetadataScrubber.scrubXmp(output)
            onProgress?.invoke(1f)
            SanitizedImage(output, descriptor.mimeType)
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun transcodeHeic(context: Context, source: Uri, output: File) {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, _, _ ->
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
