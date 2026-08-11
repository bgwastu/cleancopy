package com.cleancopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

enum class MediaKind {
    IMAGE,
    VIDEO,
    LINK
}

data class MediaDescriptor(
    val kind: MediaKind,
    val mimeType: String,
    val outputExtension: String,
    val requiresMp4Normalization: Boolean = false
)

object MediaTypeDetector {
    fun detect(context: Context, uri: Uri): MediaDescriptor {
        val mime = context.contentResolver.getType(uri)
            ?.lowercase(Locale.US)
            ?: displayName(context, uri)?.let(::mimeFromName)
            ?: error("The selected item has no recognizable media type")

        return when {
            mime == "image/jpeg" || mime == "image/jpg" ->
                MediaDescriptor(MediaKind.IMAGE, "image/jpeg", "jpg")
            mime == "image/png" ->
                MediaDescriptor(MediaKind.IMAGE, mime, "png")
            mime == "image/webp" ->
                MediaDescriptor(MediaKind.IMAGE, mime, "webp")
            mime == "image/gif" ->
                MediaDescriptor(MediaKind.IMAGE, mime, "gif")
            mime == "image/heic" || mime == "image/heif" ->
                MediaDescriptor(MediaKind.IMAGE, mime, "heic")
            mime == "video/mp4" || mime == "video/x-m4v" || mime == "video/3gpp" ->
                MediaDescriptor(MediaKind.VIDEO, "video/mp4", "mp4")
            mime == "video/quicktime" ->
                MediaDescriptor(MediaKind.VIDEO, "video/mp4", "mp4", requiresMp4Normalization = true)
            mime == "video/webm" ->
                MediaDescriptor(MediaKind.VIDEO, "video/mp4", "mp4", requiresMp4Normalization = true)
            else -> error("Unsupported media format: $mime")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
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

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}
