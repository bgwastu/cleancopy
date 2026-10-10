package net.wastu.cleancopy

import android.content.Context
import android.net.Uri

object OutputNameStore {
    fun resolve(context: Context, source: Uri, counter: Int): String {
        if (FilenameRewriteStore.isEnabled(context)) return counter.toString()
        return safeBaseName(ClipboardMetadataReader.displayName(context, source), counter)
    }

    internal fun safeBaseName(name: String, counter: Int): String = name
        .substringAfterLast('/').substringAfterLast('\\')
        .let { it.substringBeforeLast('.', it) }
        .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
        .trim().trim('.').take(120).ifBlank { counter.toString() }
}
