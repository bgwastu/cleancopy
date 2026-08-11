package com.cleancopy

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Routes a foreground clipboard request without introducing a background clipboard monitor. */
class CleanClipboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.takeIf(LinkSanitizer::containsLink)
        if (text != null && LinkCleanupStore.isEnabled(this)) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LinkSanitizer.cleanText(
                        text,
                        LinkRuleStore.providers(this@CleanClipboardActivity),
                        removeReferrals = false,
                        resolver = NetworkRedirectResolver::resolve
                    )
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("CleanCopy clean links", result.text))
                val count = result.links.count { it.changed }
                if (count > 0) {
                    ClipboardHistoryStore.record(
                        this@CleanClipboardActivity,
                        ClipboardHistoryEntry(
                            id = System.currentTimeMillis(),
                            clipboardUri = result.text,
                            sourceName = "Cleaned links",
                            kind = MediaKind.LINK,
                            capturedAt = System.currentTimeMillis(),
                            before = result.links.map { MetadataField("Original link", it.original) },
                            after = result.links.map { MetadataField("Clean link", it.cleaned) }
                        )
                    )
                }
                Toast.makeText(
                    this@CleanClipboardActivity,
                    if (count == 0) "No tracking found in copied links" else "$count link(s) cleaned and copied",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        } else {
            startActivity(
                Intent(this, CleanMediaActivity::class.java)
                    .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
                    .putExtra(CleanMediaActivity.EXTRA_CURRENT_CLIPBOARD, true)
            )
            finish()
        }
    }
}
