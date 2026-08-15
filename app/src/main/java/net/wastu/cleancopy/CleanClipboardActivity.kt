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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Reads and snapshots clipboard content while this foreground activity owns window focus and URI grants. */
class CleanClipboardActivity : ComponentActivity() {
    private var started = false
    private var completionData by mutableStateOf<CleanResultData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CleanCopyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    completionData?.let { data ->
                        CleanResultModal(
                            result = data,
                            onDismiss = { finish() },
                            onShare = { shareText(data.cleanedText ?: "") },
                            onViewHistory = { id -> openHistory(id) }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            processClipboard()
        }
    }

    private fun processClipboard(attempt: Int = 0) {
        if (started) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching {
            if (clipboard.hasPrimaryClip()) clipboard.primaryClip else null
        }.getOrNull()

        if (clip == null) {
            if (attempt < CLIPBOARD_READ_RETRIES) {
                window.decorView.postDelayed({ processClipboard(attempt + 1) }, CLIPBOARD_RETRY_DELAY_MS)
            } else {
                Toast.makeText(this, "Could not read the current clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        started = true

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
                if (result.text != text) {
                    ClipboardHelper.copyText(this@CleanClipboardActivity, result.text, "CleanCopy clean links")
                }
                val historyEntry = recordCleanedLinks(this@CleanClipboardActivity, result)

                val count = result.links.count { it.changed }
                val removedParams = result.links.flatMap { it.removedParameters }.distinct()
                val details = buildList {
                    if (count > 0) {
                        if (removedParams.isNotEmpty()) {
                            add("Stripped: ${removedParams.take(4).joinToString(", ")}")
                        } else {
                            add("$count tracking parameter(s) removed")
                        }
                    }
                }

                completionData = CleanResultData(
                    title = "Cleaned & Copied!",
                    subtitle = if (count > 0) "$count link(s) sanitized" else "No tracking parameters found in link",
                    kind = MediaKind.LINK,
                    cleanedText = result.text,
                    sourceName = text.take(60),
                    removedCount = count,
                    removedDetails = details,
                    wasAlreadyClean = count == 0,
                    historyId = historyEntry?.id
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
                    Toast.makeText(this@CleanClipboardActivity, "No image, video, or link found in the clipboard", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val mediaIntent = Intent(this@CleanClipboardActivity, CleanMediaActivity::class.java).apply {
                        putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, false)
                        putExtra(CleanMediaActivity.EXTRA_CURRENT_CLIPBOARD, true)
                        putStringArrayListExtra(
                            CleanMediaActivity.EXTRA_INPUT_URIS,
                            ArrayList(uris.map(Uri::toString))
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (uris.isNotEmpty()) {
                            clipData = ClipData.newUri(contentResolver, "CleanCopy incoming media", uris.first()).also { cd ->
                                uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                            }
                        }
                    }
                    startActivity(mediaIntent)
                    finish()
                }
            }.onFailure { error ->
                Toast.makeText(this@CleanClipboardActivity, error.message ?: "Could not read the copied media", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "Share cleaned link"))
    }

    private fun openHistory(historyId: Long) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HISTORY_ID, historyId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun ClipData.webText(): String = buildList {
        repeat(itemCount) { index ->
            val item = getItemAt(index)
            item.coerceToText(this@CleanClipboardActivity)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()?.let(::add)
        }
    }.joinToString("\n")

    private fun ClipData.mediaUris(): List<Uri> = buildList {
        fun addClipData(data: ClipData?) {
            if (data == null) return
            repeat(data.itemCount) { index ->
                val item = data.getItemAt(index)
                item.uri?.let(::add)
                item.text?.toString()?.let { text ->
                    runCatching { Uri.parse(text) }.getOrNull()?.let(::add)
                }
                item.intent?.data?.let(::add)
                @Suppress("DEPRECATION")
                item.intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                addClipData(item.intent?.clipData)
            }
        }
        addClipData(this@mediaUris)
    }
        .filter { it.scheme in setOf("content", "file") }
        .distinct()

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

    private companion object {
        const val CLIPBOARD_READ_RETRIES = 10
        const val CLIPBOARD_RETRY_DELAY_MS = 150L
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
    ).also { entry ->
        ClipboardHistoryStore.record(context, entry)
    }
}
