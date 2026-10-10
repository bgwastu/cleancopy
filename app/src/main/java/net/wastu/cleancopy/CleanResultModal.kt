package net.wastu.cleancopy

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class CleanResultData(
    val title: String = "Clean result",
    val subtitle: String = "",
    val kind: MediaKind = MediaKind.IMAGE,
    val primaryUri: Uri? = null,
    val cleanedText: String? = null,
    val originalText: String? = null,
    val sourceName: String = "",
    val removedCount: Int = 0,
    val removedDetails: List<String> = emptyList(),
    val wasAlreadyClean: Boolean = false,
    val isSaved: Boolean = false,
    val historyId: Long? = null,
    val warning: String? = null
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
    onCopyMedia: ((Int, Boolean) -> Unit)? = null,
    onSaveMedia: ((Int, Boolean) -> Unit)? = null,
    onShareMedia: ((Int, Boolean) -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    busy: Boolean = false
) {
    val pager = rememberPagerState(pageCount = { mediaItems.size.coerceAtLeast(1) })
    val current = pager.currentPage.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })
    val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.9f).toDp() }
    val headerSubtitle = if (result.kind != MediaKind.LINK && mediaItems.size == 1)
        mediaItems.first().sourceName else result.subtitle
    val actionShape = RoundedCornerShape(16.dp)
    val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (headerSubtitle.isNotBlank()) Text(headerSubtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "Close result") }
            }
            if (result.warning != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(result.warning, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (result.kind == MediaKind.LINK) {
                var showOriginal by rememberSaveable { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(result.cleanedText.orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (result.originalText != result.cleanedText) {
                    TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "Hide original" else "Compare original") }
                    if (showOriginal) SelectionContainer { Text(result.originalText.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
                }
                if (result.removedDetails.isNotEmpty()) CleanupRows(result.removedDetails)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onCopyToClipboard, modifier = actionModifier, shape = actionShape, enabled = !busy) {
                        ActionLabel(Icons.Outlined.ContentCopy, "Copy to clipboard")
                    }
                    if (onShare != null) FilledTonalButton(onClick = onShare, modifier = actionModifier, shape = actionShape, enabled = !busy) {
                        ActionLabel(Icons.Outlined.Share, "Share links")
                    }
                    if (onOpenInBrowser != null) OutlinedButton(onClick = onOpenInBrowser, modifier = actionModifier, shape = actionShape, enabled = !busy) {
                        ActionLabel(Icons.AutoMirrored.Outlined.OpenInNew,
                            if (LinkSanitizer.firstLink(result.cleanedText.orEmpty()) == result.cleanedText) "Open link" else "Open first link")
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    if (mediaItems.size > 1) {
                        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                            val item = mediaItems[index]
                            CleanMediaPreview(item.uri, item.kind, item.sourceName, Modifier.fillMaxSize(), active = index == current)
                        }
                    } else {
                        CleanMediaPreview(mediaItems.firstOrNull()?.uri ?: result.primaryUri, result.kind, result.sourceName,
                            Modifier.fillMaxSize())
                    }
                }
                val item = mediaItems.getOrNull(current)
                if (mediaItems.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item?.sourceName.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${current + 1} of ${mediaItems.size}", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cleanup details", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            CleanupRows(item.cleanedDetails.ifEmpty { listOf("No known metadata found") })
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onCopyMedia != null) Button(onClick = { onCopyMedia(current, false) },
                        modifier = actionModifier, shape = actionShape, enabled = !busy) {
                        ActionLabel(Icons.Outlined.ContentCopy, "Copy to clipboard")
                    }
                    if (onSaveMedia != null) FilledTonalButton(onClick = { onSaveMedia(current, mediaItems.size > 1) },
                        modifier = actionModifier, shape = actionShape, enabled = !busy) {
                        ActionLabel(Icons.Outlined.FileDownload,
                            if (busy) "Saving…" else if (mediaItems.size > 1) "Save all to Downloads" else "Save to Downloads")
                    }
                    if (onShareMedia != null) {
                        OutlinedButton(onClick = { onShareMedia(current, false) }, modifier = actionModifier, shape = actionShape, enabled = !busy) {
                            ActionLabel(Icons.Outlined.Share, "Share clean media")
                        }
                        if (mediaItems.size > 1) OutlinedButton(onClick = { onShareMedia(current, true) },
                            modifier = actionModifier, shape = actionShape, enabled = !busy) {
                            ActionLabel(Icons.Outlined.Share, "Share all (${mediaItems.size})")
                        }
                    }
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActionLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(label)
}

@Composable
private fun CleanupRows(details: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        details.forEach { detail ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(cleanupIcon(detail), contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun cleanupIcon(detail: String): ImageVector {
    val text = detail.lowercase()
    return when {
        "could not" in text || "incomplete" in text || "remain" in text -> Icons.Outlined.WarningAmber
        "no known" in text -> Icons.Outlined.Info
        text.startsWith("removed ") -> Icons.Outlined.LinkOff
        "location" in text || "gps" in text -> Icons.Outlined.LocationOff
        "camera" in text || "lens" in text -> Icons.Outlined.PhotoCamera
        "date" in text || "timestamp" in text -> Icons.Outlined.Schedule
        "label" in text || "creator" in text -> Icons.Outlined.Badge
        "filename" in text -> Icons.Outlined.DriveFileRenameOutline
        "heic" in text || "image" in text || "jpeg" in text -> Icons.Outlined.Image
        "video" in text || "compressed" in text || "converted" in text -> Icons.Outlined.Movie
        "section" in text || "embedded" in text -> Icons.Outlined.CleaningServices
        else -> Icons.Outlined.Tune
    }
}
