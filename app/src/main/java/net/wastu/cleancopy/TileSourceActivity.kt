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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
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
        if (url != null) return ClipboardSnapshot(ClipboardKind.URL, url)

        val hasImageMime = (0 until clip.description.mimeTypeCount)
            .any { index -> clip.description.getMimeType(index).startsWith("image/") }
        val imageUri = (0 until clip.itemCount).firstNotNullOfOrNull { index ->
            clip.getItemAt(index).uri?.takeIf { uri ->
                contentResolver.getType(uri)?.startsWith("image/") == true
            }
        }
        if (hasImageMime || imageUri != null) {
            val uri = imageUri ?: (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
            return ClipboardSnapshot(ClipboardKind.IMAGE, uri?.let(::displayName) ?: "Image")
        }
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

private enum class ClipboardKind { NONE, IMAGE, URL }

private data class ClipboardSnapshot(
    val kind: ClipboardKind = ClipboardKind.NONE,
    val detail: String = "None"
)

@Composable
private fun TileSourceChooser(
    clipboardSnapshot: ClipboardSnapshot,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onChoose: () -> Unit,
    onCurrentClipboard: () -> Unit
) {
    val backgroundInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(backgroundInteraction, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(cardInteraction, indication = null, onClick = {}),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        .align(Alignment.CenterHorizontally)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Choose a source",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SourceOption(
                    icon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                    title = "Camera",
                    supporting = "Take a new photo to clean",
                    onClick = onCamera
                )
                SourceOption(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = "Choose photo or video",
                    supporting = "Select one or more items from your device",
                    onClick = onChoose
                )
                SourceOption(
                    icon = {
                        Icon(
                            when (clipboardSnapshot.kind) {
                                ClipboardKind.IMAGE -> Icons.Outlined.Image
                                ClipboardKind.URL -> Icons.Outlined.Link
                                ClipboardKind.NONE -> Icons.Outlined.ContentPaste
                            },
                            contentDescription = null
                        )
                    },
                    title = "Current Clipboard",
                    supporting = clipboardSnapshot.detail,
                    enabled = clipboardSnapshot.kind != ClipboardKind.NONE,
                    onClick = onCurrentClipboard
                )
            }
        }
    }
}

@Composable
private fun SourceOption(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
