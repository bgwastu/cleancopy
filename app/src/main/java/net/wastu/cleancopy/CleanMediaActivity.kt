package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
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
import java.util.Locale

class CleanMediaActivity : ComponentActivity() {
    private var pickerLaunched = false
    private var processingJob: Job? = null
    private var progressState by mutableStateOf(ProcessingState())
    private var completionData by mutableStateOf<CleanResultData?>(null)
    private var preparedMedia: List<PreparedMedia> = emptyList()
    private var activeSessionDirectory: File? = null

    private val currentClipboard by lazy {
        intent.getBooleanExtra(EXTRA_CURRENT_CLIPBOARD, false)
    }
    private var clipboardMimeType: String? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            finish()
        } else {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            process(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debugPath = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_PATH) else null
        val debugSource = debugPath?.let(::copyDebugSource)

        setContent {
            CleanCopyTheme {
                BackHandler(enabled = progressState.isProcessing) { cancelProcessing() }

                if (completionData != null) {
                    CleanResultModal(
                        result = completionData!!,
                        onDismiss = ::discardAndFinish,
                        onCopyToClipboard = { copySelection(0, false) },
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
                        onCopyMedia = ::copySelection,
                        onSaveAndCopyMedia = ::saveAndCopySelection
                    )
                } else {
                    ProcessingOverlay(progressState, ::cancelProcessing)
                }
            }
        }

        if (debugSource != null) {
            pickerLaunched = true
            window.decorView.post { launchProcessing(listOf(debugSource)) }
        } else if (currentClipboard) {
            pickerLaunched = true
            window.decorView.post { launchProcessing(preparedInputUris() ?: resolveInputUris()) }
        } else if (!pickerLaunched) {
            pickerLaunched = true
            val inputUris = preparedInputUris()
            if (inputUris != null) {
                window.decorView.post { launchProcessing(inputUris) }
            } else {
                window.decorView.post { picker.launch(arrayOf("image/*", "video/*")) }
            }
        }
    }

    private fun resolveInputUris(): List<Uri> {
        if (!currentClipboard) return emptyList()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return emptyList()
        clipboardMimeType = (0 until clip.description.mimeTypeCount)
            .map { clip.description.getMimeType(it) }
            .firstOrNull { it.startsWith("image/") || it.startsWith("video/") }
        return buildList {
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
            addClipData(clip)
        }.filter { it.scheme in setOf("content", "file") }.distinct()
    }

    private fun preparedInputUris(): List<Uri>? = intent.getStringArrayListExtra(EXTRA_INPUT_URIS)
        ?.map(Uri::parse)
        ?.takeIf { it.isNotEmpty() }

    private fun launchProcessing(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, "No image or video found in the current clipboard", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            process(uris)
        }
    }

    private fun process(uris: List<Uri>) {
        Log.i(TAG, "Processing ${uris.size} selected media item(s)")
        progressState = ProcessingState(
            isProcessing = true,
            totalItems = uris.size,
            status = "Preparing clean copies..."
        )
        val sessionDirectory = File(cacheDir, "clipboard/${System.currentTimeMillis()}").apply { mkdirs() }
        activeSessionDirectory = sessionDirectory

        processingJob = lifecycleScope.launch {
            try {
                val inputUris = if (currentClipboard) {
                    withContext(Dispatchers.IO) { snapshotClipboardUris(uris, sessionDirectory) }
                } else {
                    uris
                }
                val prepared = mutableListOf<PreparedMedia>()
                var totalRemovedMetadata = 0

                inputUris.forEachIndexed { index, uri ->
                    val inspection = withContext(Dispatchers.IO) {
                        ClipboardMetadataReader.inspect(this@CleanMediaActivity, uri)
                    }
                    val descriptor = MediaTypeDetector.detect(this@CleanMediaActivity, uri)
                    val filenameOnly = descriptor.outputExtension == "gif"
                    val alreadyClean = !filenameOnly &&
                        !descriptor.requiresMp4Normalization &&
                        descriptor.sourceMimeType !in setOf("image/heic", "image/heif") &&
                        inspection.fields.isEmpty()

                    progressState = progressState.copy(
                        currentItem = index + 1,
                        currentType = inspection.kind.name.lowercase(),
                        status = if (filenameOnly) {
                            "Renaming GIF: ${inspection.displayName}"
                        } else if (alreadyClean) {
                            "Already clean: ${inspection.displayName}"
                        } else {
                            "Cleaning ${inspection.kind.name.lowercase()}..."
                        }
                    )

                    if (alreadyClean) {
                        prepared += PreparedMedia(
                            uri = uri,
                            displayName = inspection.displayName,
                            mimeType = descriptor.mimeType,
                            kind = inspection.kind,
                            before = inspection,
                            after = inspection,
                            wasSanitized = false
                        )
                    } else {
                        val output = MediaSanitizer.sanitize(
                            context = this@CleanMediaActivity,
                            source = uri,
                            sessionDirectory = sessionDirectory,
                            itemIndex = index
                        ) { itemProgress ->
                            withContext(Dispatchers.Main.immediate) {
                                progressState = progressState.copy(
                                    progress = ((index + itemProgress) / inputUris.size).coerceIn(0f, 1f)
                                )
                            }
                        }
                        val cleanUri = outputUri(output)
                        val after = withContext(Dispatchers.IO) {
                            ClipboardMetadataReader.inspect(this@CleanMediaActivity, cleanUri)
                        }
                        val removedCount = inspection.fields.size - after.fields.size
                        if (removedCount > 0) totalRemovedMetadata += removedCount

                        prepared += PreparedMedia(
                            uri = cleanUri,
                            displayName = output.file.name,
                            mimeType = output.mimeType,
                            kind = output.kind,
                            before = inspection,
                            after = after,
                            wasSanitized = !filenameOnly
                        )
                    }
                }

                val finalMedia = prepared
                preparedMedia = finalMedia

                val firstItem = finalMedia.first()
                val removedDetails = buildList {
                    val hasLocation = firstItem.before.fields.any { it.label.contains("GPS", ignoreCase = true) || it.label.contains("Location", ignoreCase = true) }
                    if (hasLocation) add("GPS location metadata removed")
                    val hasExif = firstItem.before.fields.any { it.label.contains("Camera", ignoreCase = true) || it.label.contains("Make", ignoreCase = true) || it.label.contains("Date", ignoreCase = true) }
                    if (hasExif) add("Camera & EXIF metadata scrubbed")
                    add("Filename sanitized to prevent leaks")
                    if (totalRemovedMetadata > 0 && isEmpty()) add("$totalRemovedMetadata metadata fields removed")
                }

                val allAlreadyClean = finalMedia.all { !it.wasSanitized }
                progressState = progressState.copy(isProcessing = false)

                completionData = CleanResultData(
                    title = "Cleaned",
                    subtitle = "",
                    kind = firstItem.kind,
                    primaryUri = firstItem.uri,
                    sourceName = firstItem.displayName,
                    removedCount = totalRemovedMetadata,
                    removedDetails = removedDetails,
                    wasAlreadyClean = allAlreadyClean
                )
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    Log.i(TAG, "Cleaning canceled")
                    sessionDirectory.deleteRecursively()
                    finish()
                    return@launch
                }
                Log.e(TAG, "Could not clean selected media", error)
                sessionDirectory.deleteRecursively()
                Toast.makeText(
                    this@CleanMediaActivity,
                    error.message ?: "Could not clean the selected media",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun snapshotClipboardUris(uris: List<Uri>, sessionDirectory: File): List<Uri> {
        val inputDirectory = File(sessionDirectory, "inputs").apply { mkdirs() }
        return uris.mapIndexed { index, source ->
            val name = runCatching {
                contentResolver.query(
                    source,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: extensionForMime(contentResolver.getType(source) ?: clipboardMimeType)
                ?: "bin"
            val safeBaseName = name?.let(::File)?.name?.takeIf { it.isNotBlank() }
            val targetName = safeBaseName?.takeIf { it.substringAfterLast('.', "").isNotBlank() }
                ?: "${safeBaseName ?: "clipboard_$index"}.$extension"
            val target = File(inputDirectory, targetName)
            contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not read clipboard item ${index + 1}")
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
        }
    }

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

    private fun copySelection(index: Int, allItems: Boolean) {
        val selected = selectedMedia(index, allItems)
        if (selected.isEmpty()) return
        val uris = selected.map { it.uri }
        val copied = ClipboardHelper.copyMedia(
            context = this,
            uris = uris,
            mimeType = selected.first().mimeType
        )
        if (!copied) {
            Toast.makeText(this, "Could not copy the cleaned media", Toast.LENGTH_SHORT).show()
            return
        }
        recordHistory(selected, uris)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveAndCopySelection(index: Int, allItems: Boolean) {
        val selected = selectedMedia(index, allItems)
        if (selected.isEmpty()) return
        val savedUris = mutableListOf<Uri>()
        for ((saveIndex, media) in selected.withIndex()) {
            val fallbackExtension = extensionForMime(media.mimeType) ?: "bin"
            val displayName = media.displayName.takeIf { it.contains('.') }
                ?: "cleancopy_${System.currentTimeMillis()}_$saveIndex.$fallbackExtension"
            val saved = DownloadsSaver.saveToDownloads(
                context = this,
                sourceUri = media.uri,
                displayName = displayName,
                mimeType = media.mimeType
            )
            if (saved.isFailure) {
                savedUris.forEach(::deleteSavedUri)
                Toast.makeText(this, "Could not save all items to Downloads", Toast.LENGTH_LONG).show()
                return
            }
            savedUris += saved.getOrThrow()
        }

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
        recordHistory(selected, savedUris)
        activeSessionDirectory?.deleteRecursively()
        Toast.makeText(this, "Saved to Downloads and copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun selectedMedia(index: Int, allItems: Boolean): List<PreparedMedia> =
        if (allItems) preparedMedia else listOfNotNull(preparedMedia.getOrNull(index))

    private fun recordHistory(mediaItems: List<PreparedMedia>, resultUris: List<Uri>) {
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

    private fun outputUri(output: SanitizedMedia) = FileProvider.getUriForFile(
        this,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        output.file
    )

    private fun cancelProcessing() {
        if (progressState.isProcessing) processingJob?.cancel() else finish()
    }

    private fun copyDebugSource(path: String): Uri? = runCatching {
        val source = File(path).takeIf(File::isFile) ?: return@runCatching null
        val extension = source.name.substringAfterLast('.', "bin")
        val fixture = File(cacheDir, "fixtures/debug-media.$extension").apply { parentFile?.mkdirs() }
        source.inputStream().use { input -> fixture.outputStream().use { output -> input.copyTo(output) } }
        FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", fixture)
    }.getOrNull()

    companion object {
        const val EXTRA_DEBUG_PATH = "net.wastu.cleancopy.extra.CLEAN_DEBUG_PATH"
        const val EXTRA_CURRENT_CLIPBOARD = "net.wastu.cleancopy.extra.CURRENT_CLIPBOARD"
        const val EXTRA_INPUT_URIS = "net.wastu.cleancopy.extra.INPUT_URIS"
        private const val TAG = "CleanCopyCleanMedia"
    }
}

private data class PreparedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val kind: MediaKind,
    val before: MediaInspection,
    val after: MediaInspection,
    val wasSanitized: Boolean
)

private data class ProcessingState(
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val currentType: String = "media",
    val status: String = "Opening media picker..."
)

@androidx.compose.runtime.Composable
private fun ProcessingOverlay(state: ProcessingState, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (state.isProcessing) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Cleaning ${state.currentType.replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            if (state.totalItems > 1) {
                                Text(
                                    "Processing item ${state.currentItem} of ${state.totalItems}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    state.status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    OutlinedButton(
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
}
