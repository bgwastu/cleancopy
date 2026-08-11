package com.cleancopy

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
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
    private val saveToLibrary by lazy {
        intent.getBooleanExtra(EXTRA_SAVE_TO_LIBRARY, false)
    }
    private val currentClipboard by lazy {
        intent.getBooleanExtra(EXTRA_CURRENT_CLIPBOARD, false)
    }
    private var destinationTreeUri: Uri? = null
    private var pendingUris: List<Uri> = emptyList()
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
            if (saveToLibrary) {
                pendingUris = uris
                destinationPicker.launch(null)
            } else {
                process(uris)
            }
        }
    }

    private val destinationPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            finish()
        } else {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destinationTreeUri = treeUri
            launchProcessing(pendingUris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debugPath = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_PATH) else null
        val debugSource = debugPath?.let(::copyDebugSource)
        setContent {
            CleanCopyTheme {
                BackHandler(enabled = progressState.isProcessing) { cancelProcessing() }
                ProcessingOverlay(progressState, ::cancelProcessing)
            }
        }
        if (debugSource != null) {
            pickerLaunched = true
            window.decorView.post { launchProcessing(listOf(debugSource)) }
        } else if (currentClipboard) {
            pickerLaunched = true
            window.decorView.post { launchProcessing(resolveInputUris()) }
        } else if (!pickerLaunched) {
            pickerLaunched = true
            window.decorView.post { picker.launch(arrayOf("image/*", "video/*")) }
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
            repeat(clip.itemCount) { index ->
                val item = clip.getItemAt(index)
                val uri = item.uri ?: item.text?.toString()?.let { text ->
                    runCatching { Uri.parse(text) }.getOrNull()
                }
                if (uri?.scheme == "content" || uri?.scheme == "file") add(uri)
            }
        }
    }

    private fun launchProcessing(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, "No image or video found in the current clipboard", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            process(uris)
        }
    }

    private fun process(uris: List<Uri>) {
        Log.i(TAG, "Processing ${uris.size} selected media item(s), save=$saveToLibrary")
        progressState = ProcessingState(
            isProcessing = true,
            totalItems = uris.size,
            status = "Preparing clean copies..."
        )
        val sessionDirectory = File(cacheDir, "clipboard/${System.currentTimeMillis()}")

        processingJob = lifecycleScope.launch {
            try {
                val inputUris = if (currentClipboard) {
                    withContext(Dispatchers.IO) { snapshotClipboardUris(uris, sessionDirectory) }
                } else {
                    uris
                }
                val prepared = mutableListOf<PreparedMedia>()
                var skippedCount = 0
                inputUris.forEachIndexed { index, uri ->
                    val inspection = withContext(Dispatchers.IO) {
                        ClipboardMetadataReader.inspect(this@CleanMediaActivity, uri)
                    }
                    val descriptor = MediaTypeDetector.detect(this@CleanMediaActivity, uri)
                    val filenameOnly = descriptor.outputExtension == "gif"
                    val alreadyClean = !filenameOnly &&
                        !descriptor.requiresMp4Normalization &&
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
                        skippedCount++
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
                                    progress = ((index + itemProgress) / inputUris.size)
                                        .coerceIn(0f, 1f)
                                )
                            }
                        }
                        val cleanUri = outputUri(output)
                        val after = withContext(Dispatchers.IO) {
                            ClipboardMetadataReader.inspect(this@CleanMediaActivity, cleanUri)
                        }
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

                val finalMedia = if (saveToLibrary) {
                    val treeUri = destinationTreeUri ?: error("No save destination selected")
                    prepared.map { media ->
                        media.copy(
                            uri = withContext(Dispatchers.IO) {
                                saveToDestination(
                                    context = this@CleanMediaActivity,
                                    source = media.uri,
                                    displayName = media.displayName,
                                    mimeType = media.mimeType,
                                    treeUri = treeUri
                                )
                            }
                        )
                    }
                } else {
                    prepared
                }

                if (!saveToLibrary && (currentClipboard || finalMedia.size == 1)) {
                    copyToClipboard(finalMedia.map { it.uri })
                } else if (!saveToLibrary) {
                    shareMedia(finalMedia.map { it.uri })
                }
                finalMedia.filter { it.wasSanitized }.forEach { media ->
                    ClipboardHistoryStore.record(
                        this@CleanMediaActivity,
                        ClipboardHistoryEntry(
                            id = System.currentTimeMillis(),
                            clipboardUri = media.uri.toString(),
                            sourceName = media.before.displayName,
                            kind = media.kind,
                            capturedAt = System.currentTimeMillis(),
                            before = media.before.fields,
                            after = media.after.fields
                        )
                    )
                }

                val action = when {
                    saveToLibrary -> "saved"
                    currentClipboard || finalMedia.size == 1 -> "copied to clipboard"
                    else -> "sent to the share sheet"
                }
                val skippedMessage = if (skippedCount > 0) {
                    " ($skippedCount already clean)"
                } else {
                    ""
                }
                Toast.makeText(
                    this@CleanMediaActivity,
                    "${finalMedia.size} media item${if (finalMedia.size == 1) "" else "s"} $action$skippedMessage",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (_: CancellationException) {
                Log.i(TAG, "Cleaning canceled")
                sessionDirectory.deleteRecursively()
                Toast.makeText(this@CleanMediaActivity, "Cleaning canceled", Toast.LENGTH_SHORT).show()
                finish()
            } catch (error: Throwable) {
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

    private fun copyToClipboard(uris: List<Uri>) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(contentResolver, "CleanCopy clean media", uris.first())
        uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
        clipboard.setPrimaryClip(clip)
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

    private fun shareMedia(uris: List<Uri>) {
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "CleanCopy clean media", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        startActivity(Intent.createChooser(share, "Share cleaned media"))
    }

    private fun saveToDestination(
        context: Context,
        source: Uri,
        displayName: String,
        mimeType: String,
        treeUri: Uri
    ): Uri {
        val destinationDirectory = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open the selected destination")
        val destination = destinationDirectory.createFile(mimeType, displayName)
            ?: error("Could not create the saved media")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(destination.uri)?.use { output ->
                    input.copyTo(output)
                } ?: error("Could not open the saved media")
            } ?: error("Could not read the cleaned media")
            return destination.uri
        } catch (error: Throwable) {
            context.contentResolver.delete(destination.uri, null, null)
            throw error
        }
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
        const val EXTRA_DEBUG_PATH = "com.cleancopy.extra.CLEAN_DEBUG_PATH"
        const val EXTRA_SAVE_TO_LIBRARY = "com.cleancopy.extra.SAVE_TO_LIBRARY"
        const val EXTRA_CURRENT_CLIPBOARD = "com.cleancopy.extra.CURRENT_CLIPBOARD"
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
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (state.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Cleaning ${state.currentType}", style = MaterialTheme.typography.headlineSmall)
                        Text("Item ${state.currentItem} of ${state.totalItems}")
                        LinearProgressIndicator(
                            progress = state.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(state.status, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
