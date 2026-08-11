package com.cleancopy.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle

/** Debug-only helper for seeding a real content URI into the system clipboard. */
class DebugSeedClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val text = intent.getStringExtra(EXTRA_TEXT)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (uri != null) clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "debug media", uri))
        if (text != null) clipboard.setPrimaryClip(ClipData.newPlainText("debug text", text))
        finish()
    }

    companion object {
        const val EXTRA_URI = "com.cleancopy.extra.DEBUG_CLIPBOARD_URI"
        const val EXTRA_TEXT = "com.cleancopy.extra.DEBUG_CLIPBOARD_TEXT"
    }
}
