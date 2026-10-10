package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

open class CleanMediaActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[CleaningViewModel::class.java] }
    private var clipboardAttempt = 0
    private val readsClipboard get() =
        (this is CleanClipboardActivity && !intent.hasExtra(CleanClipboardActivity.EXTRA_INPUT_TEXT)) ||
            intent.getBooleanExtra(EXTRA_CURRENT_CLIPBOARD, false)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) finish() else model.start(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CleanCopyTheme {
                BackHandler { if (!model.saving) close() }
                val notice = model.notice
                LaunchedEffect(notice) {
                    if (notice != null) {
                        Toast.makeText(this@CleanMediaActivity, notice, Toast.LENGTH_LONG).show()
                        model.notice = null
                    }
                }
                if (!model.started) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                } else model.result?.let { data ->
                    CleanResultModal(
                        result = data,
                        onDismiss = ::close,
                        onCopyToClipboard = ::copyText,
                        mediaItems = model.media.map { CleanResultMediaItem(it.uri, it.displayName, it.kind, it.details) },
                        onCopyMedia = if (data.kind != MediaKind.LINK) ::copyMedia else null,
                        onSaveMedia = if (data.kind != MediaKind.LINK && Build.VERSION.SDK_INT >= 29) {
                            { index, all -> model.save(selection(index, all)) }
                        } else null,
                        onShareMedia = if (data.kind != MediaKind.LINK) ::shareMedia else null,
                        onShare = if (data.kind == MediaKind.LINK) ::shareText else null,
                        onOpenInBrowser = if (data.kind == MediaKind.LINK && LinkSanitizer.firstLink(data.cleanedText.orEmpty()) != null) ::openBrowser else null,
                        busy = model.saving
                    )
                } ?: ProcessingSheet(
                    status = model.status,
                    progress = model.progress,
                    error = model.error,
                    onCancel = ::close,
                    onRetry = ::retry
                )
            }
        }
        if (model.started || model.pickerRequested) return
        val text = intent.getStringExtra(CleanClipboardActivity.EXTRA_INPUT_TEXT)
        val explicitUris = intent.getStringArrayListExtra(EXTRA_INPUT_URIS)?.map(Uri::parse).orEmpty()
        val shareUris = ClipboardHelper.extractIncomingUris(intent)
        val debugUri = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_PATH)?.let { Uri.fromFile(File(it)) } else null
        when {
            text != null -> model.start(inputText = text)
            explicitUris.isNotEmpty() -> model.start(explicitUris)
            shareUris.isNotEmpty() -> model.start(shareUris)
            debugUri != null -> model.start(listOf(debugUri))
            this is ShareReceiverActivity -> model.start(inputText = ClipboardHelper.extractIncomingText(intent))
            this is CleanClipboardActivity || intent.getBooleanExtra(EXTRA_CURRENT_CLIPBOARD, false) -> Unit // Read only after focus.
            else -> {
                model.pickerRequested = true
                picker.launch(arrayOf("image/*", "video/*"))
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !model.started && readsClipboard) readClipboard()
    }

    private fun retry() {
        if (readsClipboard) {
            clipboardAttempt = 0
            readClipboard(retry = true)
        } else model.retry()
    }

    private fun readClipboard(retry: Boolean = false) {
        if ((model.started && !retry) || isFinishing) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        if (clip == null && clipboardAttempt++ < 10) {
            window.decorView.postDelayed({ readClipboard(retry) }, 150)
            return
        }
        val uris = clip?.mediaUris().orEmpty()
        val text = clip?.let { data ->
            (0 until data.itemCount).mapNotNull { index ->
                val item = data.getItemAt(index)
                item.text?.toString() ?: item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()
                    ?: item.intent?.let(ClipboardHelper::extractIncomingText)
            }.joinToString("\n")
        }.orEmpty()
        when {
            uris.isNotEmpty() -> if (retry) model.retry(uris, inputText = null) else model.start(uris)
            text.isNotBlank() -> if (retry) model.retry(emptyList(), text) else model.start(inputText = text)
            else -> model.showInputError("No readable image, video, or link found in the clipboard")
        }
    }

    private fun selection(index: Int, all: Boolean) = if (all) model.media else listOfNotNull(model.media.getOrNull(index))

    private fun copyMedia(index: Int, all: Boolean) {
        val items = selection(index, all)
        lifecycleScope.launch {
            val readable = withContext(Dispatchers.IO) { items.isNotEmpty() && items.all { ClipboardHelper.isReadable(this@CleanMediaActivity, it.uri) } }
            val copied = readable && ClipboardHelper.copyMedia(this@CleanMediaActivity, items.map { it.uri })
            if (copied) {
                model.retainOutputs(items)
                model.notice = "Media added to clipboard"
            } else model.notice = "Could not copy this media. Choose the source again."
        }
    }

    private fun copyText() {
        val copied = ClipboardHelper.copyText(this, model.result?.cleanedText.orEmpty())
        if (copied) {
            model.linkResult?.let { recordCleanedLinks(this, it) }
            model.notice = "Text copied to clipboard"
        } else model.notice = "Could not copy the text"
    }

    private fun shareMedia(index: Int, all: Boolean) {
        val items = selection(index, all)
        lifecycleScope.launch {
            runCatching {
                val outgoing = withContext(Dispatchers.IO) {
                    ClipboardHelper.shareMediaIntent(this@CleanMediaActivity, items.map { it.uri }, items.map { it.mimeType })
                }
                startActivity(Intent.createChooser(outgoing, "Share clean media"))
                model.retainOutputs(items)
            }.onFailure { model.notice = "Could not share this media. Choose the source again." }
        }
    }

    private fun shareText() {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, model.result?.cleanedText.orEmpty())
            }, "Share clean links"))
        }.onFailure { model.notice = "No app is available to share this text" }
    }

    private fun openBrowser() {
        val url = LinkSanitizer.firstLink(model.result?.cleanedText.orEmpty()) ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { model.notice = "No browser is available to open this link" }
    }

    private fun close() {
        if (model.saving) return
        model.discard()
        finish()
    }

    companion object {
        const val EXTRA_DEBUG_PATH = "net.wastu.cleancopy.extra.CLEAN_DEBUG_PATH"
        const val EXTRA_CURRENT_CLIPBOARD = "net.wastu.cleancopy.extra.CURRENT_CLIPBOARD"
        const val EXTRA_INPUT_URIS = "net.wastu.cleancopy.extra.INPUT_URIS"
    }
}

internal fun ClipData.mediaUris(): List<Uri> = buildList {
    fun collect(data: ClipData?) {
        if (data == null) return
        repeat(data.itemCount) { index ->
            val item = data.getItemAt(index)
            item.uri?.takeIf { it.scheme in setOf("content", "file") }?.let(::add)
            item.intent?.let { addAll(ClipboardHelper.extractIncomingUris(it)) }
        }
    }
    collect(this@mediaUris)
}.distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingSheet(status: String, progress: Float, error: String?, onCancel: () -> Unit, onRetry: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (error == null) "Preparing clean content" else "Could not finish", style = MaterialTheme.typography.titleLarge)
            Text(error ?: status, style = MaterialTheme.typography.bodyLarge)
            if (error == null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (error != null) Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
