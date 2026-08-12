package net.wastu.cleancopy

import android.content.Context
import android.net.Uri

/** Detects the XMP markers used by Android Motion Photos/Live Moments. */
object MotionPhotoDetector {
    fun isMotionPhoto(context: Context, source: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(source)?.use { input ->
            val bytes = input.readBytes()
            val text = bytes.toString(Charsets.ISO_8859_1)
            (text.contains("Camera:MotionPhoto") || text.contains("GCamera:MicroVideo")) &&
                text.contains("Container:Directory")
        } ?: false
    }.getOrDefault(false)
}
