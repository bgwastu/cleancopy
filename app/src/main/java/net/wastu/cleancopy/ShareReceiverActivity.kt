package net.wastu.cleancopy

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
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
import androidx.compose.ui.draw.clip
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
 * scrubs identifying metadata/tracking parameters and asks what to do with the result.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var processingJob: Job? = null
    private var uiState by mutableStateOf<ShareUiState>(ShareUiState.Idle)
    private var preparedMedia: List<PreparedMediaItem> = emptyList()
    private var activeSessionDirectory: File? = null
    private var pendingLinkResult: LinkBatchResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CleanCopyTheme {
                ShareContent(
                    state = uiState,
                    mediaItems = preparedMedia.map { media ->
                        CleanResultMediaItem(
                            uri = media.uri,
                            sourceName = media.displayName,
                            kind = media.kind,
                            cleanedDetails = mediaCleanupDetails(
                                before = media.before,
                                after = media.after,
                                outputName = media.displayName,
                                wasSanitized = media.wasSanitized
                            ),
                            wasAlreadyClean = !media.wasSanitized && media.before.displayName == media.displayName
                        )
                    },
                    onDismiss = ::discardAndFinish,
                    onCancel = {
                        processingJob?.cancel()
                        finish()
                    },
                    onCopyToClipboard = ::copyCompletedResult,
                    onCopyMedia = ::copyMediaSelection,
                    onSaveAndCopyMedia = ::saveAndCopyMediaSelection,
                    onSaveAllMedia = ::saveAllMedia,
                    onOpenInBrowser = { url ->
                        openInBrowser(url)
                    },
                    onShareText = { text -> shareText(text) }
                )
            }
        }

        processIncomingShare()
    }

    private fun copyCompletedResult(result: CleanResultData) {
        val copied = if (result.kind == MediaKind.LINK) {
            result.cleanedText?.let { text ->
                ClipboardHelper.copyText(this, text, "CleanCopy clean links")
            } ?: false
        } else false

        if (!copied) {
            Toast.makeText(this, "Could not copy the cleaned result", Toast.LENGTH_SHORT).show()
            return
        }
        pendingLinkResult?.let { recordCleanedLinks(this, it) }
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun copyMediaSelection(index: Int, allItems: Boolean) {
        val selected = selectedMedia(index, allItems)
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        val copied = ClipboardHelper.copyMedia(this, uris, mimeType = selected.first().mimeType)
        if (!copied) {
            Toast.makeText(this, "Could not copy the cleaned media", Toast.LENGTH_SHORT).show()
            return
        }
        recordMediaHistory(selected, uris)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveAndCopyMediaSelection(index: Int, allItems: Boolean) {
        val selected = selectedMedia(index, allItems)
        if (selected.isEmpty()) return
        val savedUris = saveMediaSelection(selected) ?: return
        val copied = ClipboardHelper.copyMedia(
            context = this,
            uris = savedUris,
            mimeType = selected.first().mimeType
        )
        if (!copied) {
            savedUris.forEach(::deleteSavedUri)
            Toast.makeText(this, "Could not save and copy the cleaned media", Toast.LENGTH_LONG).show()
            return
        }
        recordMediaHistory(selected, savedUris)
        activeSessionDirectory?.deleteRecursively()
        Toast.makeText(this, "Saved to Downloads and copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveAllMedia() {
        val selected = preparedMedia
        if (selected.isEmpty()) return
        val savedUris = saveMediaSelection(selected) ?: return
        recordMediaHistory(selected, savedUris)
        activeSessionDirectory?.deleteRecursively()
        Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveMediaSelection(selected: List<PreparedMediaItem>): List<Uri>? {
        val savedUris = mutableListOf<Uri>()
        for ((saveIndex, media) in selected.withIndex()) {
            val extension = if (media.mimeType.startsWith("video/")) "mp4" else "jpg"
            val displayName = media.displayName.takeIf { it.contains('.') }
                ?: "cleancopy_${System.currentTimeMillis()}_$saveIndex.$extension"
            val saved = DownloadsSaver.saveToDownloads(this, media.uri, displayName, media.mimeType)
            if (saved.isFailure) {
                savedUris.forEach(::deleteSavedUri)
                Toast.makeText(this, "Could not save all items to Downloads", Toast.LENGTH_LONG).show()
                return null
            }
            savedUris += saved.getOrThrow()
        }
        return savedUris
    }

    private fun selectedMedia(index: Int, allItems: Boolean): List<PreparedMediaItem> =
        if (allItems) preparedMedia else listOfNotNull(preparedMedia.getOrNull(index))

    private fun recordMediaHistory(mediaItems: List<PreparedMediaItem>, resultUris: List<Uri>) {
        val capturedAt = System.currentTimeMillis()
        mediaItems.zip(resultUris).forEachIndexed { index, (media, uri) ->
            ClipboardHistoryStore.record(
                this,
                ClipboardHistoryEntry(
                    id = capturedAt + index,
                    clipboardUri = uri.toString(),
                    sourceName = media.before.displayName,
                    kind = media.kind,
                    capturedAt = capturedAt,
                    before = media.before.fields,
                    after = media.after.fields
                )
            )
        }
    }

    private fun deleteSavedUri(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
            else contentResolver.delete(uri, null, null)
        }
    }

    private fun discardAndFinish() {
        activeSessionDirectory?.deleteRecursively()
        finish()
    }

    private fun openInBrowser(url: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(browserIntent) }
            .onFailure {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
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

                pendingLinkResult = result

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
                        title = if (changedCount > 0) "Link Cleaned" else "Link Checked",
                        subtitle = if (changedCount > 0) "$changedCount link(s) sanitized" else "No tracking parameters found",
                        kind = MediaKind.LINK,
                        cleanedText = result.text,
                        originalText = rawText,
                        sourceName = rawText.take(60),
                        removedCount = changedCount,
                        removedDetails = removedDetails,
                        wasAlreadyClean = changedCount == 0
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
        activeSessionDirectory = sessionDirectory

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

                preparedMedia = prepared

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
                        title = "Successfully cleaned!",
                        subtitle = "",
                        kind = firstItem.kind,
                        primaryUri = firstItem.uri,
                        sourceName = firstItem.displayName,
                        removedCount = totalRemovedMetadata,
                        removedDetails = removedDetails,
                        wasAlreadyClean = allAlreadyClean
                    )
                )
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    sessionDirectory.deleteRecursively()
                    return@launch
                }
                Log.e(TAG, "Failed to clean media", error)
                sessionDirectory.deleteRecursively()
                Toast.makeText(this@ShareReceiverActivity, error.message ?: "Failed to clean media", Toast.LENGTH_SHORT).show()
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
    mediaItems: List<CleanResultMediaItem>,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onCopyToClipboard: (CleanResultData) -> Unit,
    onCopyMedia: (Int, Boolean) -> Unit,
    onSaveAndCopyMedia: (Int, Boolean) -> Unit,
    onSaveAllMedia: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onShareText: (String) -> Unit
) {
    when (state) {
        is ShareUiState.Idle -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
        }
        is ShareUiState.Processing -> ShareProcessingSheet(state, onCancel)
        is ShareUiState.Completed -> {
            CleanResultModal(
                result = state.result,
                onDismiss = onDismiss,
                onCopyToClipboard = {
                    onCopyToClipboard(state.result)
                },
                mediaItems = mediaItems,
                onCopyMedia = if (state.result.kind != MediaKind.LINK) onCopyMedia else null,
                onSaveAndCopyMedia = if (state.result.kind != MediaKind.LINK) onSaveAndCopyMedia else null,
                onSaveAllMedia = if (state.result.kind != MediaKind.LINK) onSaveAllMedia else null,
                onOpenInBrowser = if (state.result.kind == MediaKind.LINK) {
                    {
                        state.result.cleanedText?.let(onOpenInBrowser)
                    }
                } else null,
                onShare = if (state.result.kind == MediaKind.LINK) {
                    {
                        state.result.cleanedText?.let(onShareText)
                    }
                } else null
            )
        }
    }
}

@Composable
private fun ShareProcessingSheet(state: ShareUiState.Processing, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(2.dp)
                        )
                )

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.then(
                            Modifier
                                .then(Modifier.padding(0.dp))
                        )
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.totalItems > 1)
                                "Cleaning… (${state.currentItem}/${state.totalItems})"
                            else "Cleaning…",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                androidx.compose.material3.OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
