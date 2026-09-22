package net.wastu.cleancopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton

data class CleanResultData(
    val title: String = "Clean Result",
    val subtitle: String = "Review cleaned content & choose an action",
    val kind: MediaKind = MediaKind.IMAGE,
    val primaryUri: Uri? = null,
    val cleanedText: String? = null,
    val originalText: String? = null,
    val sourceName: String = "",
    val removedCount: Int = 0,
    val removedDetails: List<String> = emptyList(),
    val wasAlreadyClean: Boolean = false,
    val isSaved: Boolean = false,
    val historyId: Long? = null
)

data class CleanResultMediaItem(
    val uri: Uri,
    val sourceName: String,
    val kind: MediaKind,
    val cleanedDetails: List<String> = emptyList(),
    val wasAlreadyClean: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanResultModal(
    result: CleanResultData,
    onDismiss: () -> Unit,
    onCopyToClipboard: () -> Unit,
    mediaItems: List<CleanResultMediaItem> = emptyList(),
    onCopyMedia: ((index: Int, allItems: Boolean) -> Unit)? = null,
    onSaveAndCopyMedia: ((index: Int, allItems: Boolean) -> Unit)? = null,
    onSaveAndCopy: (() -> Unit)? = null,
    onSaveToDownloads: (() -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onViewHistory: ((Long) -> Unit)? = null
) {
    var currentMediaIndex by remember(mediaItems) { mutableIntStateOf(0) }
    var allMediaSelected by remember(mediaItems) { mutableStateOf(false) }
    val hasMultipleMedia = mediaItems.size > 1
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val headerSubtitle = when {
        hasMultipleMedia -> "${mediaItems.size} media items ready"
        result.kind == MediaKind.LINK -> result.subtitle
        else -> mediaItems.firstOrNull()?.sourceName ?: result.sourceName
    }
    val headerIcon = when (result.kind) {
        MediaKind.IMAGE -> Icons.Outlined.Image
        MediaKind.VIDEO -> Icons.Outlined.Movie
        MediaKind.LINK -> Icons.Outlined.Link
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (headerSubtitle.isNotBlank()) {
                        Text(
                            text = headerSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Preview Content (Image / Video / Link)
                    if (hasMultipleMedia) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { currentMediaIndex-- },
                                enabled = currentMediaIndex > 0
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Previous media")
                            }
                            Text(
                                "${currentMediaIndex + 1} of ${mediaItems.size}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            IconButton(
                                onClick = { currentMediaIndex++ },
                                enabled = currentMediaIndex < mediaItems.lastIndex
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Next media")
                            }
                        }
                    }

                    ResultPreview(
                        result = result,
                        mediaItem = mediaItems.getOrNull(currentMediaIndex),
                        compact = hasMultipleMedia
                    )

                    mediaItems.getOrNull(currentMediaIndex)?.let { mediaItem ->
                        MediaCleanupSummary(mediaItem)
                    }

                    // Action Buttons tailored for Link vs Media
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (hasMultipleMedia) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (!allMediaSelected) {
                                    Button(
                                        onClick = { allMediaSelected = false },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("Current item") }
                                } else {
                                    OutlinedButton(
                                        onClick = { allMediaSelected = false },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("Current item") }
                                }
                                if (allMediaSelected) {
                                    Button(
                                        onClick = { allMediaSelected = true },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("All ${mediaItems.size} items") }
                                } else {
                                    OutlinedButton(
                                        onClick = { allMediaSelected = true },
                                        modifier = Modifier.weight(1f).height(44.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text("All ${mediaItems.size} items") }
                                }
                            }
                        }

                        // Copy is always explicit; cleaning never changes the clipboard on its own.
                        Button(
                            onClick = {
                                if (result.kind != MediaKind.LINK && onCopyMedia != null) {
                                    onCopyMedia(currentMediaIndex, allMediaSelected)
                                } else {
                                    onCopyToClipboard()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when {
                                    result.kind == MediaKind.LINK -> "Copy to Clipboard"
                                    allMediaSelected -> "Copy all"
                                    hasMultipleMedia -> "Copy current"
                                    else -> "Copy only"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // Media can be persisted and copied as one action.
                        if (result.kind == MediaKind.LINK) {
                            if (onOpenInBrowser != null) {
                                FilledTonalButton(
                                    onClick = onOpenInBrowser,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text("Open in Browser", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        } else if (onSaveAndCopyMedia != null || onSaveAndCopy != null) {
                            FilledTonalButton(
                                onClick = {
                                    if (onSaveAndCopyMedia != null) {
                                        onSaveAndCopyMedia(currentMediaIndex, allMediaSelected)
                                    } else {
                                        onSaveAndCopy?.invoke()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    when {
                                        allMediaSelected -> "Save all & copy"
                                        hasMultipleMedia -> "Save current & copy"
                                        else -> "Save & Copy"
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        } else if (onSaveToDownloads != null) {
                            FilledTonalButton(
                                onClick = onSaveToDownloads,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                    shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Save to Downloads", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        // 3. Share Button
                        if (onShare != null) {
                            OutlinedButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (result.kind == MediaKind.LINK) "Share Clean Link" else "Share Clean Media",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 4. Optional View History
                        if (onViewHistory != null && result.historyId != null) {
                            TextButton(
                                onClick = { onViewHistory(result.historyId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("View in CleanCopy", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
        }
    }
}

@Composable
private fun MediaCleanupSummary(mediaItem: CleanResultMediaItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "What was cleaned",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (mediaItem.wasAlreadyClean || mediaItem.cleanedDetails.isEmpty()) {
                    "No removable metadata found"
                } else {
                    mediaItem.cleanedDetails.joinToString("  •  ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ResultPreview(result: CleanResultData, mediaItem: CleanResultMediaItem?, compact: Boolean) {
    val context = LocalContext.current
    val kind = mediaItem?.kind ?: result.kind
    val primaryUri = mediaItem?.uri ?: result.primaryUri
    val sourceName = mediaItem?.sourceName ?: result.sourceName

    when (kind) {
        MediaKind.IMAGE -> {
            val bitmap by produceState<Bitmap?>(initialValue = null, primaryUri) {
                value = primaryUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        decodeThumb(context, uri)
                    }
                }
            }
            if (bitmap != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 120.dp else 160.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    ComposeImage(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Cleaned media preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                }
            } else if (sourceName.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            sourceName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        MediaKind.VIDEO -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sourceName.ifBlank { "Cleaned video" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        MediaKind.LINK -> {
            val before = result.originalText ?: result.sourceName
            val after = result.cleanedText ?: before
            if (after.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (before.isNotBlank()) {
                            Text("Before", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                before,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        Text("After", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            after,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun decodeThumb(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 600 || bounds.outHeight / sampleSize > 600) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
}.getOrNull()
