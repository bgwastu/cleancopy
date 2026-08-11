package com.cleancopy.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log

/** Debug-only foreground clipboard assertion helper. */
class DebugClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inspectClipboard()
    }

    private fun inspectClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val values = clip?.let { (0 until it.itemCount).map { index ->
            val uri = it.getItemAt(index).uri
            "$uri type=${uri?.let(contentResolver::getType)}"
        } } ?: emptyList()
        Log.i(TAG, "Clipboard items=${values.joinToString()}")
        finish()
    }

    companion object {
        private const val TAG = "CleanCopyClipboardCheck"
    }
}
