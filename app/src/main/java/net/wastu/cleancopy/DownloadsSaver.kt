package net.wastu.cleancopy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object DownloadsSaver {
    /** Call from a worker dispatcher. Sharing remains available on Android 8–9. */
    fun saveToDownloads(context: Context, sourceUri: Uri, displayName: String, mimeType: String): Result<Uri> = runCatching {
        if (Build.VERSION.SDK_INT < 29) error("Use Share to save media on Android 8–9")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Could not create a download")
        try {
            val copied = resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                    ?: error("Could not write the download")
            } ?: error("The cleaned file is no longer available")
            check(copied > 0) { "The cleaned file was empty" }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(destination, published, null, null) > 0) { "Could not finish saving the download" }
            destination
        } catch (failure: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            throw failure
        }
    }
}
