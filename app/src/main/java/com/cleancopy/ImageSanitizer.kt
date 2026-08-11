package com.cleancopy

import android.content.Context
import android.net.Uri
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
        outputBaseName: String = OutputNameStore.get(context),
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
            context.contentResolver.openInputStream(source)?.use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            } ?: error("The source image could not be opened")
            onProgress?.invoke(0.7f)

            val exif = ExifInterface(output)
            removableTags.forEach { tag -> runCatching { exif.setAttribute(tag, null) } }
            exif.saveAttributes()
            if (extension == "jpg") JpegMetadataScrubber.scrubXmp(output)
            onProgress?.invoke(1f)
            SanitizedImage(output, descriptor.mimeType)
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
