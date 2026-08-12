package net.wastu.cleancopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object OutputNameStore {
    fun resolve(context: Context, source: Uri, counter: Int): String {
        if (FilenameRewriteStore.isEnabled(context)) return counter.toString()
        val sourceName = runCatching {
            context.contentResolver.query(
                source,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: source.lastPathSegment.orEmpty()
        return sourceName.substringBeforeLast('.').ifBlank { counter.toString() }
    }
}
