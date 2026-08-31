package net.wastu.cleancopy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object DownloadsSaver {

    /**
     * Saves a media item directly into the device's public Downloads directory without launching SAF tree pickers.
     * Uses MediaStore.Downloads with IS_PENDING flag on Android 10+ (API 29+), with graceful fallback for legacy versions.
     */
    fun saveToDownloads(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        mimeType: String
    ): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val destinationUri = resolver.insert(collection, contentValues)
                ?: error("Failed to create download entry in MediaStore")

            try {
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(destinationUri)?.use { output ->
                        input.copyTo(output)
                    } ?: error("Failed to open destination output stream")
                } ?: error("Failed to read source cleaned file")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(destinationUri, contentValues, null, null)
                destinationUri
            } catch (error: Throwable) {
                resolver.delete(destinationUri, null, null)
                throw error
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply {
                mkdirs()
            }
            var targetFile = File(downloadsDir, displayName)
            var counter = 1
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            while (targetFile.exists()) {
                val nextName = if (ext.isNotBlank()) "${base}_$counter.$ext" else "${base}_$counter"
                targetFile = File(downloadsDir, nextName)
                counter++
            }

            resolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Failed to write to public Downloads directory")
            Uri.fromFile(targetFile)
        }
    }
}
