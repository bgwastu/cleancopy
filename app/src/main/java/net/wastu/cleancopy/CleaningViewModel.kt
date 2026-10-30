package net.wastu.cleancopy

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PreparedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val kind: MediaKind,
    val before: MediaInspection,
    val after: MediaInspection,
    val removedSections: Int,
    val notes: List<String> = emptyList()
) {
    val details: List<String> get() = mediaCleanupDetails(before, after, displayName, true) + notes +
        if (removedSections > 0) listOf("$removedSections metadata ${if (removedSections == 1) "section" else "sections"} removed") else emptyList()
}

/** One retained processing session for picker, clipboard and incoming shares. */
class CleaningViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    var started by mutableStateOf(false)
        private set
    var pickerRequested = false
    var result by mutableStateOf<CleanResultData?>(null)
        private set
    var media by mutableStateOf<List<PreparedMedia>>(emptyList())
        private set
    var status by mutableStateOf("Preparing…")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
    var linkResult: LinkBatchResult? = null
        private set
    private var job: Job? = null
    private var directory: File? = null
    private var exported = false
    private var sources = emptyList<Uri>()
    private var text: String? = null

    fun start(uris: List<Uri> = emptyList(), inputText: String? = null) {
        if (started) return
        started = true
        sources = uris
        text = inputText
        error = null
        job = viewModelScope.launch {
            try {
                if (inputText != null) cleanText(inputText) else cleanMedia(uris)
            } catch (failure: Exception) {
                directory?.deleteRecursively()
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not clean this content. Try another source."
            }
        }
    }

    fun retry(uris: List<Uri> = sources, inputText: String? = text) {
        started = false
        start(uris, inputText)
    }

    fun showInputError(message: String) {
        sources = emptyList()
        text = null
        started = true
        error = message
    }

    private suspend fun cleanText(input: String) {
        status = "Cleaning links…"
        val (cleaningEnabled, batch) = withContext(Dispatchers.IO) {
            require(LinkSanitizer.containsLink(input)) { "Enter an HTTP or HTTPS link to clean" }
            val cleaningEnabled = LinkCleanupStore.isEnabled(context)
            val expandEnabled = cleaningEnabled && ShortLinkExpansionStore.isEnabled(context)
            val cleaned = if (cleaningEnabled) LinkSanitizer.cleanText(
                input,
                LinkRuleStore.providers(context),
                removeReferrals = true,
                resolver = if (expandEnabled) NetworkRedirectResolver::resolve else null
            )
            else LinkBatchResult(input, emptyList())
            cleaningEnabled to cleaned
        }
        linkResult = batch
        val removed = batch.links.sumOf { it.removedParameters.size }
        val changed = batch.links.count { it.changed }
        val expanded = batch.links.count { it.expandedFrom != null }
        val errors = batch.links.mapNotNull { it.error }.distinct()
        val disabled = !cleaningEnabled
        val expansionDetails = batch.links.mapNotNull { it.expandedFrom }.distinct().map { original ->
            val host = runCatching { java.net.URI(original).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: original
            "Expanded $host"
        }
        val removedDetails = expansionDetails +
            batch.links.flatMap { it.removedParameters }.distinct().map { "Removed $it" }
        result = CleanResultData(
            title = when {
                disabled -> "Link cleaning is off"
                errors.isNotEmpty() -> "Link partially checked"
                changed > 0 -> "Links cleaned"
                else -> "Links checked"
            },
            subtitle = when {
                disabled -> "Enable link cleaning in Settings"
                errors.isNotEmpty() && changed == 0 ->
                    "Local cleaning finished; a shortened link could not be expanded."
                changed > 0 -> buildString {
                    append("$changed ${if (changed == 1) "link" else "links"} updated.")
                    if (expanded > 0) {
                        append(" $expanded short ${if (expanded == 1) "link" else "links"} expanded.")
                    }
                    if (removed > 0) {
                        append(" $removed ${if (removed == 1) "parameter" else "parameters"} removed.")
                    }
                }
                else -> "No known tracking parameters found"
            },
            kind = MediaKind.LINK,
            cleanedText = batch.text,
            originalText = input,
            sourceName = input.take(60),
            removedCount = removed,
            removedDetails = removedDetails,
            wasAlreadyClean = changed == 0 && errors.isEmpty() && !disabled,
            warning = errors.joinToString("\n").takeIf { it.isNotBlank() }
        )
    }

    private suspend fun cleanMedia(uris: List<Uri>) {
        require(uris.isNotEmpty()) { "No image or video was found" }
        val session = File(context.cacheDir, "clipboard/${UUID.randomUUID()}").apply { mkdirs() }
        directory = session
        val inputs = File(session, "inputs").apply { mkdirs() }
        val prepared = mutableListOf<PreparedMedia>()
        try {
            uris.forEachIndexed { index, source ->
                status = "Cleaning item ${index + 1} of ${uris.size}…"
                val originalName = withContext(Dispatchers.IO) { ClipboardMetadataReader.displayName(context, source) }
                val localSource = withContext(Dispatchers.IO) {
                    val descriptor = MediaTypeDetector.detect(context, source)
                    val extension = when (descriptor.sourceMimeType) {
                        "image/heic", "image/heif" -> "heic"
                        "video/quicktime" -> "mov"
                        "video/webm" -> "webm"
                        "video/3gpp" -> "3gp"
                        else -> descriptor.outputExtension
                    }
                    val file = File(inputs, "$index.$extension")
                    context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: error("Could not read $originalName. Choose the file again.")
                    FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                }
                val before = withContext(Dispatchers.IO) {
                    ClipboardMetadataReader.inspect(context, localSource).copy(displayName = originalName)
                }
                val name = if (FilenameRewriteStore.isEnabled(context)) index.toString() else OutputNameStore.safeBaseName(originalName, index)
                val output = MediaSanitizer.sanitize(context, localSource, session, index, outputBaseName = name) { localProgress ->
                    withContext(Dispatchers.Main.immediate) { progress = (index + localProgress) / uris.size }
                }
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", output.file)
                val after = withContext(Dispatchers.IO) { ClipboardMetadataReader.inspect(context, uri) }
                prepared += PreparedMedia(uri, output.file.name, output.mimeType, output.kind, before, after, output.metadataSectionsRemoved, output.notes)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                inputs.deleteRecursively()
                uris.filter { it.authority == "${BuildConfig.APPLICATION_ID}.fileprovider" && it.path.orEmpty().startsWith("/clipboard/tile-camera/") }
                    .forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
        media = prepared
        val first = prepared.first()
        val warnings = prepared.flatMap { item -> listOfNotNull(item.before.warning, item.after.warning,
            "Some metadata remains in ${item.displayName}".takeIf { item.after.fields.isNotEmpty() }) }.distinct()
        result = CleanResultData(title = if (warnings.isEmpty()) "Clean copies ready" else "Copies ready with warnings",
            subtitle = if (prepared.size == 1) first.displayName else "${prepared.size} items prepared",
            kind = first.kind, primaryUri = first.uri, sourceName = first.displayName,
            removedCount = prepared.sumOf { (it.before.fields.size - it.after.fields.size).coerceAtLeast(0) },
            warning = warnings.joinToString("\n").takeIf(String::isNotBlank))
    }

    fun retainOutputs(items: List<PreparedMedia> = media, uris: List<Uri> = items.map { it.uri }) {
        if (items.zip(uris).any { (item, uri) -> item.uri == uri }) exported = true
        val now = System.currentTimeMillis()
        items.zip(uris).forEachIndexed { index, (item, uri) ->
            ClipboardHistoryStore.record(context, ClipboardHistoryEntry(now + index, uri.toString(), item.displayName,
                item.kind, now, item.before.fields, item.after.fields))
        }
    }

    fun save(items: List<PreparedMedia>) {
        if (saving || items.isEmpty()) return
        saving = true
        viewModelScope.launch {
            val saved = mutableListOf<Uri>()
            try {
                withContext(Dispatchers.IO) {
                    items.forEach { saved += DownloadsSaver.saveToDownloads(context, it.uri, it.displayName, it.mimeType).getOrThrow() }
                }
                retainOutputs(items, saved)
                notice = "${saved.size} ${if (saved.size == 1) "item" else "items"} saved to Downloads"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (saved.isNotEmpty()) retainOutputs(items.take(saved.size), saved)
                notice = (if (saved.isNotEmpty()) "${saved.size} items saved; the remaining items could not be saved. " else "") +
                    (failure.message ?: "Could not save the media. You can still share it.")
            } finally { saving = false }
        }
    }

    fun discard() {
        job?.cancel()
        if (!exported) directory?.deleteRecursively()
    }

    override fun onCleared() {
        discard()
    }
}
