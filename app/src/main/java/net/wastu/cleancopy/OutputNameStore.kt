package net.wastu.cleancopy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object OutputNameStore {
    private const val PREFS = "output_settings"
    private const val KEY_BASE_NAME = "base_name"
    const val DEFAULT_BASE_NAME = "image"

    fun get(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_BASE_NAME, DEFAULT_BASE_NAME)
        .orEmpty()
        .ifBlank { DEFAULT_BASE_NAME }

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_NAME, normalize(value))
            .apply()
    }

    fun resolve(context: Context, source: Uri): String {
        if (FilenameRewriteStore.isEnabled(context)) return get(context)
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
        return normalize(sourceName)
    }

    fun normalize(value: String): String {
        val normalized = value.trim()
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
        return normalized.ifBlank { DEFAULT_BASE_NAME }
    }
}
