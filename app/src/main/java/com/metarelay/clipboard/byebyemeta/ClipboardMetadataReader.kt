package com.byebyemeta

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface

data class MetadataField(
    val label: String,
    val value: String
)

data class MediaInspection(
    val displayName: String,
    val kind: MediaKind,
    val fields: List<MetadataField>
)

object ClipboardMetadataReader {
    private val privacyTags = listOf(
        "GPSLatitude" to "GPS latitude",
        "GPSLongitude" to "GPS longitude",
        "DateTimeOriginal" to "Captured",
        "Make" to "Camera make",
        "Model" to "Camera model",
        "Software" to "Software",
        "Artist" to "Artist",
        "Copyright" to "Copyright",
        "ImageDescription" to "Description",
        "UserComment" to "Comment"
    )

    fun inspect(context: Context, uri: Uri): MediaInspection {
        val descriptor = MediaTypeDetector.detect(context, uri)
        val fields = when (descriptor.kind) {
            MediaKind.IMAGE -> readImageFields(context, uri)
            MediaKind.VIDEO -> readVideoFields(context, uri)
        }
        return MediaInspection(displayName(context, uri), descriptor.kind, fields)
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

    private fun readVideoFields(context: Context, uri: Uri): List<MetadataField> = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            videoTags.mapNotNull { (key, label) ->
                retriever.extractMetadata(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { it != ZERO_VIDEO_DATE }
                    ?.let { MetadataField(label, it) }
            }
        }
    }.getOrDefault(emptyList())

    private const val ZERO_VIDEO_DATE = "19040101T000000.000Z"

    private fun readImageFields(context: Context, uri: Uri): List<MetadataField> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            privacyTags.mapNotNull { (tag, label) ->
                exif.getAttribute(tag)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MetadataField(label, it) }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun displayName(context: Context, uri: Uri): String = runCatching {
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
}
