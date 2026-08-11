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
        if (uri != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "debug media", uri))
        }
        finish()
    }

    companion object {
        const val EXTRA_URI = "com.cleancopy.extra.DEBUG_CLIPBOARD_URI"
    }
}
