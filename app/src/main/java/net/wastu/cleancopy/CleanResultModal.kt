package net.wastu.cleancopy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PreviewHeight = 200.dp
private const val CollapsedCleanupRows = 3

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
    onSaveAllMedia: (() -> Unit)? = null,
    onSaveToDownloads: (() -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onViewHistory: ((Long) -> Unit)? = null
) {
    val hasMultipleMedia = mediaItems.size > 1
    val pagerState = rememberPagerState(pageCount = { mediaItems.size.coerceAtLeast(1) })
    val currentMediaIndex = pagerState.currentPage.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val headerSubtitle = when {
        hasMultipleMedia -> "${mediaItems.size} media cleaned"
        result.kind == MediaKind.LINK -> result.subtitle
        else -> mediaItems.firstOrNull()?.sourceName ?: result.sourceName
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
                .heightIn(max = 720.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (hasMultipleMedia) {
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    pageSpacing = 10.dp,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PreviewHeight)
                ) { page ->
                    val pageOffset = (
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    ).absoluteValue
                    ResultPreview(
                        result = result,
                        mediaItem = mediaItems[page],
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .graphicsLayer {
                                alpha = lerp(0.55f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
                                scaleY = lerp(0.94f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
                            }
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        repeat(mediaItems.size) { index ->
                            val selected = index == currentMediaIndex
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (selected) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    )
                            )
                        }
                    }
                    Text(
                        "${currentMediaIndex + 1} of ${mediaItems.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ResultPreview(
                    result = result,
                    mediaItem = mediaItems.firstOrNull(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .then(
                            if (result.kind == MediaKind.LINK) Modifier else Modifier.height(PreviewHeight)
                        )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                mediaItems.getOrNull(currentMediaIndex)?.let { mediaItem ->
                    MediaCleanupSummary(mediaItem)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (result.kind != MediaKind.LINK && onCopyMedia != null) {
                                onCopyMedia(currentMediaIndex, false)
                            } else {
                                onCopyToClipboard()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (result.kind == MediaKind.LINK) "Copy to Clipboard" else "Copy media",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

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
                                    onSaveAndCopyMedia(currentMediaIndex, false)
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
                            Text("Save media & copy", style = MaterialTheme.typography.labelLarge)
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

                    if (hasMultipleMedia && onSaveAllMedia != null) {
                        OutlinedButton(
                            onClick = onSaveAllMedia,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Save all (${mediaItems.size}) media", style = MaterialTheme.typography.labelLarge)
                        }
                    }

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
}

@Composable
private fun MediaCleanupSummary(mediaItem: CleanResultMediaItem) {
    val alreadyClean = mediaItem.wasAlreadyClean || mediaItem.cleanedDetails.isEmpty()
    val rows = if (alreadyClean) {
        listOf("No removable metadata found")
    } else {
        mediaItem.cleanedDetails
    }
    val canCollapse = rows.size > CollapsedCleanupRows
    var expanded by remember(mediaItem.uri) { mutableStateOf(false) }
    val visibleRows = if (!canCollapse || expanded) rows else rows.take(CollapsedCleanupRows)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (canCollapse) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "What was cleaned",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (canCollapse) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Show less" else "Show more",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .then(
                        if (expanded && rows.size > 6) {
                            Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleRows.forEach { detail ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = cleanupIcon(detail, alreadyClean),
                            contentDescription = null,
                            tint = if (alreadyClean) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun cleanupIcon(detail: String, alreadyClean: Boolean): ImageVector {
    if (alreadyClean) return Icons.Outlined.CheckCircle
    val text = detail.lowercase()
    return when {
        "location" in text -> Icons.Outlined.LocationOff
        "camera" in text -> Icons.Outlined.PhotoCamera
        "date" in text || "timestamp" in text -> Icons.Outlined.Schedule
        "label" in text || "creator" in text -> Icons.Outlined.Badge
        "filename" in text -> Icons.Outlined.DriveFileRenameOutline
        "embedded" in text || "scrubbed" in text -> Icons.Outlined.CleaningServices
        else -> Icons.Outlined.Tune
    }
}

@Composable
private fun ResultPreview(
    result: CleanResultData,
    mediaItem: CleanResultMediaItem?,
    modifier: Modifier = Modifier
) {
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
                    modifier = modifier,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    ComposeImage(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = sourceName.ifBlank { "Cleaned media preview" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                    )
                }
            } else if (sourceName.isNotBlank()) {
                MediaPlaceholder(
                    icon = Icons.Outlined.Image,
                    label = sourceName,
                    modifier = modifier
                )
            }
        }
        MediaKind.VIDEO -> {
            MediaPlaceholder(
                icon = Icons.Outlined.Movie,
                label = sourceName.ifBlank { "Cleaned video" },
                modifier = modifier
            )
        }
        MediaKind.LINK -> {
            val before = result.originalText ?: result.sourceName
            val after = result.cleanedText ?: before
            if (after.isNotBlank()) {
                Surface(
                    modifier = modifier.fillMaxWidth(),
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

@Composable
private fun MediaPlaceholder(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
