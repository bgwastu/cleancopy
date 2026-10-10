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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/** Foreground source chooser opened by the Quick Settings tile. */
class TileSourceActivity : ComponentActivity() {
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null
    private var clipboardSnapshot by mutableStateOf(ClipboardSnapshot())

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            openCleaner(listOf(uri))
        } else {
            pendingCameraFile?.delete()
        }
        pendingCameraUri = null
        pendingCameraFile = null
    }

    private val chooser = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            openCleaner(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        pendingCameraFile = savedInstanceState?.getString(STATE_CAMERA_FILE)?.let(::File)

        setContent {
            CleanCopyTheme {
                TileSourceChooser(
                    clipboardSnapshot = clipboardSnapshot,
                    onDismiss = { finish() },
                    onCamera = ::openCamera,
                    onChoose = { chooser.launch(arrayOf("image/*", "video/*")) },
                    onFocused = { clipboardSnapshot = inspectClipboard() },
                    onCurrentClipboard = {
                        startActivity(Intent(this, CleanClipboardActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clipboardSnapshot = inspectClipboard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CAMERA_URI, pendingCameraUri?.toString())
        outState.putString(STATE_CAMERA_FILE, pendingCameraFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    private fun openCamera() {
        val file = File(cacheDir, "clipboard/tile-camera/${System.currentTimeMillis()}.jpg").apply {
            parentFile?.mkdirs()
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        pendingCameraFile = file
        pendingCameraUri = uri
        runCatching { camera.launch(uri) }
            .onFailure {
                pendingCameraFile?.delete()
                pendingCameraFile = null
                pendingCameraUri = null
                Toast.makeText(this, "No camera app is available", Toast.LENGTH_SHORT).show()
            }
    }

    private fun inspectClipboard(): ClipboardSnapshot {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching {
            if (clipboard.hasPrimaryClip()) clipboard.primaryClip else null
        }.getOrNull() ?: return ClipboardSnapshot()

        val url = (0 until clip.itemCount).firstNotNullOfOrNull { index ->
            val item = clip.getItemAt(index)
            item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()
                ?: item.text?.toString()?.let(LinkSanitizer::firstLink)
        }

        val mediaMime = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }
            .firstOrNull { it.startsWith("image/") || it.startsWith("video/") }
        val mediaUri = (0 until clip.itemCount).firstNotNullOfOrNull { index ->
            clip.getItemAt(index).uri?.takeIf { uri ->
                runCatching { contentResolver.getType(uri)?.let { it.startsWith("image/") || it.startsWith("video/") } }.getOrDefault(false) == true
            }
        }
        if (mediaMime != null || mediaUri != null) {
            val uri = mediaUri ?: (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
            val isVideo = mediaMime?.startsWith("video/") == true || uri?.let { runCatching { contentResolver.getType(it)?.startsWith("video/") }.getOrDefault(false) } == true
            return ClipboardSnapshot(if (isVideo) ClipboardKind.VIDEO else ClipboardKind.IMAGE, uri?.let(::displayName) ?: "Media")
        }
        if (url != null) return ClipboardSnapshot(ClipboardKind.URL, url)
        return ClipboardSnapshot()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Image"

    private fun openCleaner(uris: List<Uri>) {
        if (uris.isEmpty()) return
        startActivity(
            Intent(this, CleanMediaActivity::class.java).apply {
                putStringArrayListExtra(CleanMediaActivity.EXTRA_INPUT_URIS, ArrayList(uris.map(Uri::toString)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "CleanCopy selected media", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
        )
        finish()
    }

    companion object {
        private const val STATE_CAMERA_URI = "camera_uri"
        private const val STATE_CAMERA_FILE = "camera_file"
    }
}

private enum class ClipboardKind { NONE, IMAGE, VIDEO, URL }

private data class ClipboardSnapshot(
    val kind: ClipboardKind = ClipboardKind.NONE,
    val detail: String = "None"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileSourceChooser(
    clipboardSnapshot: ClipboardSnapshot,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onChoose: () -> Unit,
    onFocused: () -> Unit,
    onCurrentClipboard: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) { if (focused) onFocused() }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose a source", style = MaterialTheme.typography.titleLarge)
            SourceOption(icon = { Icon(Icons.Outlined.PhotoCamera, null) }, title = "Camera",
                supporting = "Take a new photo", onClick = onCamera)
            SourceOption(icon = { Icon(Icons.Outlined.FolderOpen, null) }, title = "Choose photos or videos",
                supporting = "Select one or more files", onClick = onChoose)
            SourceOption(icon = {
                Icon(when (clipboardSnapshot.kind) {
                    ClipboardKind.IMAGE -> Icons.Outlined.Image
                    ClipboardKind.VIDEO -> Icons.Outlined.Movie
                    ClipboardKind.URL -> Icons.Outlined.Link
                    ClipboardKind.NONE -> Icons.Outlined.ContentPaste
                }, null)
            }, title = "Current clipboard", supporting = clipboardSnapshot.detail,
                enabled = clipboardSnapshot.kind != ClipboardKind.NONE, onClick = onCurrentClipboard)
        }
    }
}

@Composable
private fun SourceOption(icon: @Composable () -> Unit, title: String, supporting: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(supporting, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
