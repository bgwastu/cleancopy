package net.wastu.cleancopy

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanCopyApp(
    selectedTab: Int,
    history: List<ClipboardHistoryEntry>,
    clipboardUrl: String?,
    historyEnabled: Boolean,
    rewriteFilename: Boolean,
    compressVideo: Boolean,
    selectedHistory: ClipboardHistoryEntry?,
    linkCleaningEnabled: Boolean,
    quickSettingsPromptDismissed: Boolean,
    onTabSelected: (Int) -> Unit,
    onCleanCurrentClipboard: () -> Unit,
    onChooseSourceToClean: () -> Unit,
    onHistorySelected: (ClipboardHistoryEntry) -> Unit,
    onHistoryBack: () -> Unit,
    onOpenHistoryMedia: (ClipboardHistoryEntry) -> Unit,
    onCopyHistoryMedia: (ClipboardHistoryEntry) -> Unit,
    onShareHistoryMedia: (ClipboardHistoryEntry) -> Unit,
    onHistoryEnabledChanged: (Boolean) -> Unit,
    onRewriteFilenameChanged: (Boolean) -> Unit,
    onCompressVideoChanged: (Boolean) -> Unit,
    onLinkCleaningEnabledChanged: (Boolean) -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onDismissQuickSettingsPrompt: () -> Unit
) {
    BackHandler(enabled = selectedTab == 1) { onTabSelected(0) }
    BackHandler(enabled = selectedHistory != null && selectedTab == 0, onBack = onHistoryBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selectedTab == 1 -> Text("Settings")
                        selectedHistory != null -> Text("History detail")
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_clean_copy_mark),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("CleanCopy", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                navigationIcon = if (selectedTab == 1 || selectedHistory != null) {
                    {
                        IconButton(onClick = { if (selectedTab == 1) onTabSelected(0) else onHistoryBack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    if (selectedTab == 0 && selectedHistory == null) {
                        IconButton(onClick = { onTabSelected(1) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                    if (selectedTab == 0 && selectedHistory != null) {
                        IconButton(onClick = { onShareHistoryMedia(selectedHistory) }) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share result")
                        }
                        IconButton(onClick = { onCopyHistoryMedia(selectedHistory) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy media")
                        }
                        IconButton(onClick = { onOpenHistoryMedia(selectedHistory) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open media")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selectedTab == 0) {
            if (selectedHistory == null) {
                ClipboardPage(
                    modifier = Modifier.padding(padding),
                    history = history,
                    clipboardUrl = clipboardUrl,
                    quickSettingsPromptDismissed = quickSettingsPromptDismissed,
                    onCleanCurrentClipboard = onCleanCurrentClipboard,
                    onChooseSourceToClean = onChooseSourceToClean,
                    onAddQuickSettingsTile = onAddQuickSettingsTile,
                    onDismissQuickSettingsPrompt = onDismissQuickSettingsPrompt,
                    onHistorySelected = onHistorySelected
                )
            } else {
                HistoryDetailPage(
                    modifier = Modifier.padding(padding),
                    entry = selectedHistory
                )
            }
        } else {
            SettingsPage(
                modifier = Modifier.padding(padding),
                rewriteFilename = rewriteFilename,
                compressVideo = compressVideo,
                historyEnabled = historyEnabled,
                linkCleaningEnabled = linkCleaningEnabled,
                onRewriteFilenameChanged = onRewriteFilenameChanged,
                onCompressVideoChanged = onCompressVideoChanged,
                onHistoryEnabledChanged = onHistoryEnabledChanged,
                onLinkCleaningEnabledChanged = onLinkCleaningEnabledChanged
            )
        }
    }
}

@Composable
private fun ClipboardPage(
    modifier: Modifier,
    history: List<ClipboardHistoryEntry>,
    clipboardUrl: String?,
    quickSettingsPromptDismissed: Boolean,
    onCleanCurrentClipboard: () -> Unit,
    onChooseSourceToClean: () -> Unit,
    onAddQuickSettingsTile: () -> Unit,
    onDismissQuickSettingsPrompt: () -> Unit,
    onHistorySelected: (ClipboardHistoryEntry) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Clean before you share", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Remove hidden metadata from photos and videos, or tracking from links.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!quickSettingsPromptDismissed) {
            item {
                QuickSettingsPrompt(
                    onAdd = onAddQuickSettingsTile,
                    onDismiss = onDismissQuickSettingsPrompt
                )
            }
        }

        // Quick Action Buttons
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCleanCurrentClipboard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.ic_clean_copy_mark), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Clean current clipboard", style = MaterialTheme.typography.labelLarge)
                            if (clipboardUrl != null) {
                                val displayUrl = if (clipboardUrl.length > 40) clipboardUrl.take(37) + "…" else clipboardUrl
                                Text(
                                    displayUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onChooseSourceToClean,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Choose a photo, video, or link", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("History", style = MaterialTheme.typography.titleLarge)
                }
            }
            items(history, key = { it.clipboardUri }) { entry ->
                HistoryRow(entry, onClick = { onHistorySelected(entry) })
            }
        }
    }
}

@Composable
private fun QuickSettingsPrompt(
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clean_copy_mark),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Quick access",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Add CleanCopy to Quick Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            TextButton(onClick = onAdd) {
                Text("Add")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss Quick Settings prompt",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun HistoryDetailPage(
    modifier: Modifier,
    entry: ClipboardHistoryEntry
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (entry.kind != MediaKind.LINK) MediaPreview(entry)
        Text(entry.sourceName, style = MaterialTheme.typography.titleLarge)
        Text(
            "Cleaned ${formatTime(entry.capturedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MetadataBlock(
            if (entry.kind == MediaKind.LINK) "Original links" else "Old metadata",
            entry.before,
            if (entry.kind == MediaKind.LINK) "No links were recorded" else "No metadata was recorded"
        )
        if (entry.kind == MediaKind.LINK) {
            MetadataBlock("Cleaned links", entry.after, "No links were cleaned")
        } else {
            MetadataBlock(
                "Location",
                entry.before.filter { it.label == "GPS latitude" || it.label == "GPS longitude" || it.label == "Location" },
                "No location metadata"
            )
        }
    }
}

@Composable
private fun MediaPreview(entry: ClipboardHistoryEntry) {
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        CleanMediaPreview(Uri.parse(entry.clipboardUri), entry.kind, entry.sourceName, Modifier.fillMaxWidth().height(280.dp))
    }
}

@Composable
private fun MetadataBlock(
    title: String,
    fields: List<MetadataField>,
    emptyMessage: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (fields.isEmpty()) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                fields.forEachIndexed { index, field ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            field.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(field.value, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (index != fields.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ClipboardHistoryEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (entry.kind) {
                    MediaKind.IMAGE -> MaterialTheme.colorScheme.primaryContainer
                    MediaKind.VIDEO -> MaterialTheme.colorScheme.tertiaryContainer
                    MediaKind.LINK -> MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (entry.kind) {
                            MediaKind.IMAGE -> Icons.Outlined.Image
                            MediaKind.VIDEO -> Icons.Outlined.Movie
                            MediaKind.LINK -> Icons.Outlined.Link
                        },
                        contentDescription = null,
                        tint = when (entry.kind) {
                            MediaKind.IMAGE -> MaterialTheme.colorScheme.onPrimaryContainer
                            MediaKind.VIDEO -> MaterialTheme.colorScheme.onTertiaryContainer
                            MediaKind.LINK -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.sourceName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = historyStatus(entry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatTime(entry.capturedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun historyStatus(entry: ClipboardHistoryEntry): String {
    if (!entry.processed) return "Not processed"
    val removedCount = entry.before.count { beforeField ->
        entry.after.none { afterField ->
            afterField.label == beforeField.label && afterField.value == beforeField.value
        }
    }
    if (entry.kind != MediaKind.LINK && entry.after.isNotEmpty()) return "Review remaining metadata"
    return if (removedCount == 0) "Prepared" else "$removedCount field(s) removed"
}

@Composable
private fun SettingsPage(
    modifier: Modifier,
    rewriteFilename: Boolean,
    compressVideo: Boolean,
    historyEnabled: Boolean,
    linkCleaningEnabled: Boolean,
    onRewriteFilenameChanged: (Boolean) -> Unit,
    onCompressVideoChanged: (Boolean) -> Unit,
    onHistoryEnabledChanged: (Boolean) -> Unit,
    onLinkCleaningEnabledChanged: (Boolean) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Output", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch(
            icon = Icons.Outlined.ContentCopy,
            title = "Sanitize filename",
            supporting = "Replace the original name with a numbered filename. Turn off to keep the name.",
            checked = rewriteFilename,
            onCheckedChange = onRewriteFilenameChanged
        )
        SettingSwitch(
            icon = Icons.Outlined.Movie,
            title = "Compress videos",
            supporting = "Reduce videos to H.264/AAC, up to 720p. Leave off to preserve quality where possible.",
            checked = compressVideo,
            onCheckedChange = onCompressVideoChanged
        )

        Text("History", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch(
            icon = Icons.Outlined.History,
            title = "Save cleaning history",
            supporting = "Keep recent results and metadata comparisons. Temporary media can become unavailable; save files you need.",
            checked = historyEnabled,
            onCheckedChange = onHistoryEnabledChanged
        )

        Text("Links", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch(
            icon = Icons.Outlined.Link,
            title = "Clean copied links",
            supporting = "Remove known tracking and referral parameters. Rules work offline; shortened links need a connection.",
            checked = linkCleaningEnabled,
            onCheckedChange = onLinkCleaningEnabledChanged
        )

        Text("About", style = MaterialTheme.typography.titleLarge)
        Text("CleanCopy ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
