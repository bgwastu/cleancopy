package net.wastu.cleancopy

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface

data class MetadataField(
    val label: String,
    val value: String
)

data class MediaInspection(
    val displayName: String,
    val kind: MediaKind,
    val fields: List<MetadataField>,
    val warning: String? = null
)

object ClipboardMetadataReader {
    internal val privacyTags = listOf(
        "GPSLatitude" to "GPS latitude",
        "GPSLongitude" to "GPS longitude",
        "DateTimeOriginal" to "Captured",
        "Make" to "Camera make",
        "Model" to "Camera model",
        "Software" to "Software",
        "Artist" to "Artist",
        "Copyright" to "Copyright",
        "ImageDescription" to "Description",
        "UserComment" to "Comment",
        "DateTime" to "Modified date",
        "DateTimeDigitized" to "Digitized date",
        "OffsetTime" to "Time zone",
        "OffsetTimeOriginal" to "Capture time zone",
        "OffsetTimeDigitized" to "Digitized time zone",
        "SubSecTime" to "Date subsecond",
        "SubSecTimeOriginal" to "Capture subsecond",
        "SubSecTimeDigitized" to "Digitized subsecond",
        "LensMake" to "Lens make",
        "LensModel" to "Lens model",
        "LensSerialNumber" to "Lens serial number",
        "BodySerialNumber" to "Camera serial number",
        "CameraOwnerName" to "Camera owner",
        "ImageUniqueID" to "Image identifier",
        "DocumentName" to "Document name",
        "HostComputer" to "Computer",
        "Xmp" to "XMP",
        "GPSLatitudeRef" to "GPS latitude reference",
        "GPSLongitudeRef" to "GPS longitude reference",
        "GPSAltitude" to "GPS altitude",
        "GPSAltitudeRef" to "GPS altitude reference",
        "GPSTimeStamp" to "GPS time",
        "GPSDateStamp" to "GPS date",
        "GPSProcessingMethod" to "GPS method",
        "GPSAreaInformation" to "GPS area",
        "GPSDestBearing" to "GPS destination bearing",
        "GPSDestDistance" to "GPS destination distance",
        "GPSDestLatitude" to "GPS destination latitude",
        "GPSDestLongitude" to "GPS destination longitude",
        "GPSImgDirection" to "GPS direction",
        "GPSMapDatum" to "GPS datum",
        "GPSMeasureMode" to "GPS mode",
        "GPSSpeed" to "GPS speed",
        "GPSStatus" to "GPS status",
        "GPSTrack" to "GPS track"
    )

    fun inspect(context: Context, uri: Uri): MediaInspection {
        val descriptor = MediaTypeDetector.detect(context, uri)
        val fields = runCatching { when (descriptor.kind) {
            MediaKind.IMAGE -> if (descriptor.outputExtension == "gif") emptyList() else readImageFields(context, uri)
            MediaKind.VIDEO -> readVideoFields(context, uri)
            MediaKind.LINK -> emptyList()
        } }
        return MediaInspection(displayName(context, uri), descriptor.kind, fields.getOrDefault(emptyList()),
            fields.exceptionOrNull()?.let { "Some source metadata could not be read" })
    }

    private val videoTags = listOf(
        MediaMetadataRetriever.METADATA_KEY_LOCATION to "Location",
        MediaMetadataRetriever.METADATA_KEY_DATE to "Captured",
        MediaMetadataRetriever.METADATA_KEY_TITLE to "Title",
        MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artist",
        MediaMetadataRetriever.METADATA_KEY_ALBUM to "Album",
        MediaMetadataRetriever.METADATA_KEY_WRITER to "Writer",
        MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Composer",
        MediaMetadataRetriever.METADATA_KEY_YEAR to "Year",
        MediaMetadataRetriever.METADATA_KEY_GENRE to "Genre"
    )

    private fun readVideoFields(context: Context, uri: Uri): List<MetadataField> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return videoTags.mapNotNull { (key, label) ->
                retriever.extractMetadata(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { it != ZERO_VIDEO_DATE }
                    ?.let { MetadataField(label, it) }
            }
        } finally {
            retriever.release()
        }
    }

    private const val ZERO_VIDEO_DATE = "19040101T000000.000Z"

    private fun readImageFields(context: Context, uri: Uri): List<MetadataField> =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image inspection failed" }
            val exif = ExifInterface(input)
            privacyTags.mapNotNull { (tag, label) ->
                exif.getAttribute(tag)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MetadataField(label, it) }
            }
        } ?: error("The image could not be read")

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Clipboard media"
}

fun formatMediaKind(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.VIDEO -> "Video"
    MediaKind.LINK -> "Link"
}
