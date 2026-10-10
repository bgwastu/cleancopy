package net.wastu.cleancopy

import android.content.Context

/** The shared cleaner reads clipboard content only while this activity has window focus. */
class CleanClipboardActivity : CleanMediaActivity() {
    companion object {
        const val EXTRA_INPUT_TEXT = "net.wastu.cleancopy.extra.INPUT_TEXT"
    }
}

fun recordCleanedLinks(context: Context, result: LinkBatchResult): ClipboardHistoryEntry? {
    if (result.links.none { it.changed }) return null
    return ClipboardHistoryEntry(
        id = System.currentTimeMillis(),
        clipboardUri = result.text,
        sourceName = "Cleaned links",
        kind = MediaKind.LINK,
        capturedAt = System.currentTimeMillis(),
        before = result.links.map { MetadataField("Original link", it.original) },
        after = result.links.map { MetadataField("Clean link", it.cleaned) }
    ).also { ClipboardHistoryStore.record(context, it) }
}
