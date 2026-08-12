package net.wastu.cleancopy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = incomingUris(intent)
        if (uris.isNotEmpty()) {
            openMedia(uris)
            return
        }
        val text = incomingText(intent)
        if (text.isNotBlank() && LinkSanitizer.containsLink(text)) {
            cleanLink(text)
            return
        }
        if (text.isNotBlank() || uris.isEmpty()) {
            finish()
            return
        }
    }

    private fun openMedia(uris: List<Uri>) {
        val cleanIntent = Intent(this, CleanMediaActivity::class.java)
                .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, true)
                .putExtra(CleanMediaActivity.EXTRA_COPY_TO_CLIPBOARD, true)
                .putStringArrayListExtra(CleanMediaActivity.EXTRA_INPUT_URIS, ArrayList(uris.map(Uri::toString)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cleanIntent.clipData = intent.clipData
        startActivity(cleanIntent)
        finish()
    }

    private fun cleanLink(text: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LinkSanitizer.cleanText(
                    text,
                    LinkRuleStore.providers(this@ShareReceiverActivity),
                    removeReferrals = false,
                    resolver = NetworkRedirectResolver::resolve
                )
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CleanCopy clean URL", result.text))
            if (result.links.any { it.changed }) {
                val entry = ClipboardHistoryEntry(
                    id = System.currentTimeMillis(),
                    clipboardUri = result.text,
                    sourceName = "Cleaned links",
                    kind = MediaKind.LINK,
                    capturedAt = System.currentTimeMillis(),
                    before = result.links.map { MetadataField("Original link", it.original) },
                    after = result.links.map { MetadataField("Clean link", it.cleaned) }
                )
                ClipboardHistoryStore.record(this@ShareReceiverActivity, entry)
                openHistory(entry.id)
            } else {
                Toast.makeText(this@ShareReceiverActivity, "No tracking found in copied links", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun openHistory(id: Long) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HISTORY_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun incomingText(intent: Intent): String = if (intent.action == Intent.ACTION_SEND) {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    } else ""

    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                ?: intent.clipData?.getItemAt(0)?.uri
        )
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            ?.takeIf { it.isNotEmpty() }
            ?: buildList {
                repeat(intent.clipData?.itemCount ?: 0) { index ->
                    intent.clipData?.getItemAt(index)?.uri?.let(::add)
                }
            }
        else -> emptyList()
    }

}
