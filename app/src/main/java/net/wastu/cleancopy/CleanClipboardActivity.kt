package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Reads and snapshots clipboard content while this foreground activity owns its URI grants. */
class CleanClipboardActivity : ComponentActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post(::processClipboard)
    }

    private fun processClipboard() {
        if (started) return
        started = true
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null) {
            finishWithToast("The clipboard is empty")
            return
        }

        val text = clip.webText()
        if (LinkCleanupStore.isEnabled(this) && LinkSanitizer.containsLink(text)) {
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
                recordLinks(result)
                val count = result.links.count { it.changed }
                finishWithToast(
                    if (count == 0) "No tracking found in copied links" else "$count link(s) cleaned and copied"
                )
            }
            return
        }

        lifecycleScope.launch {
            val snapshots = runCatching {
                withContext(Dispatchers.IO) { snapshotMedia(clip.mediaUris()) }
            }
            snapshots.onSuccess { uris ->
                if (uris.isEmpty()) {
                    finishWithToast("No image, video, or link found in the clipboard")
                } else {
                    startActivity(
                        Intent(this@CleanClipboardActivity, CleanMediaActivity::class.java)
                            .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
                            .putExtra(CleanMediaActivity.EXTRA_CURRENT_CLIPBOARD, true)
                            .putStringArrayListExtra(
                                CleanMediaActivity.EXTRA_INPUT_URIS,
                                ArrayList(uris.map(Uri::toString))
                            )
                    )
                    finish()
                }
            }.onFailure { error ->
                finishWithToast(error.message ?: "Could not read the copied media", Toast.LENGTH_LONG)
            }
        }
    }

    private fun ClipData.webText(): String = buildList {
        repeat(itemCount) { index ->
            val item = getItemAt(index)
            item.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
            item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()?.let(::add)
        }
    }.joinToString("\n")

    private fun ClipData.mediaUris(): List<Uri> = buildList {
        fun addClipData(data: ClipData?) {
            if (data == null) return
            repeat(data.itemCount) { index ->
                val item = data.getItemAt(index)
                item.uri?.let(::add)
                item.intent?.data?.let(::add)
                @Suppress("DEPRECATION")
                item.intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                addClipData(item.intent?.clipData)
            }
        }
        addClipData(this@mediaUris)
    }.filter { it.scheme in setOf("content", "file") }.distinct()

    private fun snapshotMedia(sources: List<Uri>): List<Uri> {
        val directory = File(cacheDir, "clipboard-inputs/${System.currentTimeMillis()}").apply { mkdirs() }
        return sources.mapIndexed { index, source ->
            val displayName = queryDisplayName(source)
            val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: extensionForMime(contentResolver.getType(source))
                ?: source.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                ?: "bin"
            val target = File(directory, "clipboard_$index.${extension.lowercase(Locale.US)}")
            contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: error("The copied media could not be opened")
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun extensionForMime(mimeType: String?): String? = when (mimeType?.lowercase(Locale.US)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "video/mp4", "video/x-m4v" -> "mp4"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        else -> null
    }

    private fun recordLinks(result: LinkBatchResult) {
        if (result.links.none { it.changed }) return
        ClipboardHistoryStore.record(
            this,
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

    private fun finishWithToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
        finish()
    }
}
