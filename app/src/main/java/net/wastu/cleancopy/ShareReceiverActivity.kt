package net.wastu.cleancopy

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles incoming share intents directly from other apps (images, videos, links, text),
 * scrubs identifying metadata/tracking parameters, copies the clean result to the clipboard,
 * records it to History, and displays a rich completion modal.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var processingJob: Job? = null
    private var uiState by mutableStateOf<ShareUiState>(ShareUiState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CleanCopyTheme {
                ShareContent(
                    state = uiState,
                    onDismiss = { finish() },
                    onCancel = {
                        processingJob?.cancel()
                        finish()
                    },
                    onShareMedia = { uri, mimeType -> shareMedia(uri, mimeType) },
                    onShareText = { text -> shareText(text) },
                    onViewHistory = { historyId -> openHistoryDetail(historyId) }
                )
            }
        }

        processIncomingShare()
    }

    private fun processIncomingShare() {
        val text = ClipboardHelper.extractIncomingText(intent)
        val uris = ClipboardHelper.extractIncomingUris(intent)

        if (uris.isNotEmpty()) {
            processMedia(uris)
        } else if (text.isNotBlank()) {
            processText(text)
        } else {
            Toast.makeText(this, "No supported media or link found to clean", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun processText(rawText: String) {
        uiState = ShareUiState.Processing(
            status = "Cleaning tracking parameters...",
            progress = 0.5f,
            currentItem = 1,
            totalItems = 1
        )

        processingJob = lifecycleScope.launch {
            try {
                val hasLink = LinkSanitizer.containsLink(rawText)
                val result = if (hasLink && LinkCleanupStore.isEnabled(this@ShareReceiverActivity)) {
                    withContext(Dispatchers.IO) {
                        LinkSanitizer.cleanText(
                            rawText,
                            LinkRuleStore.providers(this@ShareReceiverActivity),
                            removeReferrals = false,
                            resolver = NetworkRedirectResolver::resolve
                        )
                    }
                } else {
                    LinkBatchResult(rawText, emptyList())
                }

                // Copy to clipboard
                ClipboardHelper.copyText(this@ShareReceiverActivity, result.text)

                // Record to history if links were cleaned
                val historyEntry = recordCleanedLinks(this@ShareReceiverActivity, result)

                val changedCount = result.links.count { it.changed }
                val removedParams = result.links.flatMap { it.removedParameters }.distinct()
                val removedDetails = buildList {
                    if (changedCount > 0) {
                        if (removedParams.isNotEmpty()) {
                            add("Stripped tracking parameters: ${removedParams.take(4).joinToString(", ")}")
                        } else {
                            add("$changedCount tracking parameter(s) removed")
                        }
                    }
                }

                uiState = ShareUiState.Completed(
                    result = CleanResultData(
                        title = "Cleaned & Copied!",
                        subtitle = if (changedCount > 0) "$changedCount link(s) sanitized" else "Link copied to clipboard",
                        kind = MediaKind.LINK,
                        cleanedText = result.text,
                        sourceName = rawText.take(60),
                        removedCount = changedCount,
                        removedDetails = removedDetails,
                        wasAlreadyClean = changedCount == 0,
                        historyId = historyEntry?.id
                    )
                )
            } catch (error: Throwable) {
                if (error is CancellationException) return@launch
                Log.e(TAG, "Failed to clean text", error)
                Toast.makeText(this@ShareReceiverActivity, error.message ?: "Failed to clean text", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun processMedia(uris: List<Uri>) {
        val total = uris.size
        uiState = ShareUiState.Processing(
            status = "Preparing clean copies...",
            progress = 0.1f,
            currentItem = 1,
            totalItems = total
        )

        val sessionDirectory = File(cacheDir, "clipboard/${System.currentTimeMillis()}").apply { mkdirs() }

        processingJob = lifecycleScope.launch {
            try {
                val prepared = mutableListOf<PreparedMediaItem>()
                var totalRemovedMetadata = 0

                uris.forEachIndexed { index, sourceUri ->
                    val inspection = withContext(Dispatchers.IO) {
                        ClipboardMetadataReader.inspect(this@ShareReceiverActivity, sourceUri)
                    }
                    val descriptor = MediaTypeDetector.detect(this@ShareReceiverActivity, sourceUri)
                    val filenameOnly = descriptor.outputExtension == "gif"
                    val alreadyClean = !filenameOnly &&
                        !descriptor.requiresMp4Normalization &&
                        descriptor.sourceMimeType !in setOf("image/heic", "image/heif") &&
                        inspection.fields.isEmpty()

                    uiState = ShareUiState.Processing(
                        status = if (alreadyClean) "Already clean: ${inspection.displayName}" else "Cleaning ${inspection.kind.name.lowercase()}...",
                        progress = (index.toFloat() + 0.2f) / total,
                        currentItem = index + 1,
                        totalItems = total
                    )

                    if (alreadyClean) {
                        prepared += PreparedMediaItem(
                            uri = sourceUri,
                            displayName = inspection.displayName,
                            mimeType = descriptor.mimeType,
                            kind = inspection.kind,
                            before = inspection,
                            after = inspection,
                            wasSanitized = false
                        )
                    } else {
                        val output = MediaSanitizer.sanitize(
                            context = this@ShareReceiverActivity,
                            source = sourceUri,
                            sessionDirectory = sessionDirectory,
                            itemIndex = index
                        ) { itemProgress ->
                            withContext(Dispatchers.Main.immediate) {
                                uiState = ShareUiState.Processing(
                                    status = "Cleaning ${inspection.kind.name.lowercase()}...",
                                    progress = (index.toFloat() + itemProgress) / total,
                                    currentItem = index + 1,
                                    totalItems = total
                                )
                            }
                        }

                        val cleanUri = FileProvider.getUriForFile(
                            this@ShareReceiverActivity,
                            "${BuildConfig.APPLICATION_ID}.fileprovider",
                            output.file
                        )
                        val after = withContext(Dispatchers.IO) {
                            ClipboardMetadataReader.inspect(this@ShareReceiverActivity, cleanUri)
                        }
                        val removedCount = inspection.fields.size - after.fields.size
                        if (removedCount > 0) totalRemovedMetadata += removedCount

                        prepared += PreparedMediaItem(
                            uri = cleanUri,
                            displayName = output.file.name,
                            mimeType = output.mimeType,
                            kind = output.kind,
                            before = inspection,
                            after = after,
                            wasSanitized = true
                        )
                    }
                }

                val finalUris = prepared.map { it.uri }
                val firstMime = prepared.firstOrNull()?.mimeType

                // Copy to clipboard with multi-MIME types
                ClipboardHelper.copyMedia(
                    context = this@ShareReceiverActivity,
                    uris = finalUris,
                    mimeType = firstMime
                )

                // Record to History
                val historyEntries = prepared.map { media ->
                    ClipboardHistoryEntry(
                        id = System.currentTimeMillis(),
                        clipboardUri = media.uri.toString(),
                        sourceName = media.before.displayName,
                        kind = media.kind,
                        capturedAt = System.currentTimeMillis(),
                        before = media.before.fields,
                        after = media.after.fields
                    )
                }
                historyEntries.forEach { entry ->
                    ClipboardHistoryStore.record(this@ShareReceiverActivity, entry)
                }

                val firstItem = prepared.first()
                val removedDetails = buildList {
                    val hasLocation = firstItem.before.fields.any { it.label.contains("GPS", ignoreCase = true) || it.label.contains("Location", ignoreCase = true) }
                    if (hasLocation) add("GPS location metadata removed")
                    val hasExif = firstItem.before.fields.any { it.label.contains("Camera", ignoreCase = true) || it.label.contains("Make", ignoreCase = true) || it.label.contains("Date", ignoreCase = true) }
                    if (hasExif) add("Camera & EXIF metadata scrubbed")
                    if (FilenameRewriteStore.isEnabled(this@ShareReceiverActivity)) add("Filename sanitized")
                    if (totalRemovedMetadata > 0 && isEmpty()) add("$totalRemovedMetadata metadata fields removed")
                }

                val allAlreadyClean = prepared.all { !it.wasSanitized }
                uiState = ShareUiState.Completed(
                    result = CleanResultData(
                        title = "Cleaned & Copied!",
                        subtitle = if (total == 1) "${formatMediaKind(firstItem.kind)} ready to paste" else "$total media items ready to paste",
                        kind = firstItem.kind,
                        primaryUri = firstItem.uri,
                        sourceName = firstItem.displayName,
                        removedCount = totalRemovedMetadata,
                        removedDetails = removedDetails,
                        wasAlreadyClean = allAlreadyClean,
                        historyId = historyEntries.firstOrNull()?.id
                    )
                )
            } catch (error: Throwable) {
                if (error is CancellationException) return@launch
                Log.e(TAG, "Failed to clean media", error)
                Toast.makeText(this@ShareReceiverActivity, error.message ?: "Failed to clean media", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun shareMedia(uri: Uri, mimeType: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "Cleaned media", uri)
        }
        startActivity(Intent.createChooser(shareIntent, "Share cleaned media"))
    }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "Share cleaned link"))
    }

    private fun openHistoryDetail(historyId: Long) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HISTORY_ID, historyId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val TAG = "CleanCopyShareReceiver"
    }
}

private sealed interface ShareUiState {
    data object Idle : ShareUiState
    data class Processing(
        val status: String,
        val progress: Float,
        val currentItem: Int,
        val totalItems: Int
    ) : ShareUiState
    data class Completed(val result: CleanResultData) : ShareUiState
}

private data class PreparedMediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val kind: MediaKind,
    val before: MediaInspection,
    val after: MediaInspection,
    val wasSanitized: Boolean
)

@Composable
private fun ShareContent(
    state: ShareUiState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onShareMedia: (Uri, String) -> Unit,
    onShareText: (String) -> Unit,
    onViewHistory: (Long) -> Unit
) {
    when (state) {
        is ShareUiState.Idle -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
        }
        is ShareUiState.Processing -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Cleaning in progress...", style = MaterialTheme.typography.titleLarge)
                        if (state.totalItems > 1) {
                            Text("Item ${state.currentItem} of ${state.totalItems}", style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
        is ShareUiState.Completed -> {
            CleanResultModal(
                result = state.result,
                onDismiss = onDismiss,
                onShare = {
                    if (state.result.kind == MediaKind.LINK) {
                        state.result.cleanedText?.let(onShareText)
                    } else {
                        state.result.primaryUri?.let { uri ->
                            val mime = if (state.result.kind == MediaKind.IMAGE) "image/*" else "video/*"
                            onShareMedia(uri, mime)
                        }
                    }
                },
                onViewHistory = onViewHistory
            )
        }
    }
}
