package com.cleancopy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image as ComposeImage
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.PagingSource.LoadResult
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanCopyApp(
    selectedTab: Int,
    history: List<ClipboardHistoryEntry>,
    historyEnabled: Boolean,
    rewriteFilename: Boolean,
    outputName: String,
    compressVideo: Boolean,
    selectedHistory: ClipboardHistoryEntry?,
    linkCleaningEnabled: Boolean,
    linkRuleStatus: String?,
    linkRulesUpdating: Boolean,
    onTabSelected: (Int) -> Unit,
    onCleanMedia: () -> Unit,
    onCleanLinks: () -> Unit,
    onCleanCurrentClipboard: () -> Unit,
    onSaveMedia: () -> Unit,
    onHistorySelected: (ClipboardHistoryEntry) -> Unit,
    onHistoryBack: () -> Unit,
    onOpenHistoryMedia: (ClipboardHistoryEntry) -> Unit,
    onCopyHistoryMedia: (ClipboardHistoryEntry) -> Unit,
    onHistoryEnabledChanged: (Boolean) -> Unit,
    onRewriteFilenameChanged: (Boolean) -> Unit,
    onOutputNameChanged: (String) -> Unit,
    onCompressVideoChanged: (Boolean) -> Unit,
    onLinkCleaningEnabledChanged: (Boolean) -> Unit,
    onUpdateLinkRules: () -> Unit
) {
    BackHandler(enabled = selectedHistory != null && selectedTab == 0, onBack = onHistoryBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectedTab == 1 -> "Settings"
                            selectedHistory != null -> "History detail"
                            else -> "CleanCopy"
                        }
                    )
                },
                navigationIcon = if (selectedHistory != null && selectedTab == 0) {
                    {
                        IconButton(onClick = onHistoryBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    if (selectedTab == 0 && selectedHistory != null) {
                        IconButton(onClick = { onCopyHistoryMedia(selectedHistory) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy media")
                        }
                        IconButton(onClick = { onOpenHistoryMedia(selectedHistory) }) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "Open media")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectedHistory == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        icon = { Icon(Icons.Outlined.ContentPaste, "Clipboard") },
                        label = { Text("Clipboard") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        icon = { Icon(Icons.Outlined.Settings, "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        if (selectedTab == 0) {
            if (selectedHistory == null) {
                ClipboardPage(
                    modifier = Modifier.padding(padding),
                    history = history,
                    onCleanMedia = onCleanMedia,
                    onCleanLinks = if (linkCleaningEnabled) onCleanLinks else null,
                    onCleanCurrentClipboard = onCleanCurrentClipboard,
                    onSaveMedia = onSaveMedia,
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
                outputName = outputName,
                compressVideo = compressVideo,
                historyEnabled = historyEnabled,
                linkCleaningEnabled = linkCleaningEnabled,
                linkRuleStatus = linkRuleStatus,
                linkRulesUpdating = linkRulesUpdating,
                onRewriteFilenameChanged = onRewriteFilenameChanged,
                onOutputNameChanged = onOutputNameChanged,
                onCompressVideoChanged = onCompressVideoChanged,
                onHistoryEnabledChanged = onHistoryEnabledChanged,
                onLinkCleaningEnabledChanged = onLinkCleaningEnabledChanged,
                onUpdateLinkRules = onUpdateLinkRules
            )
        }
    }
}

@Composable
private fun ClipboardPage(
    modifier: Modifier,
    history: List<ClipboardHistoryEntry>,
    onCleanMedia: () -> Unit,
    onCleanLinks: (() -> Unit)?,
    onCleanCurrentClipboard: () -> Unit,
    onSaveMedia: () -> Unit,
    onHistorySelected: (ClipboardHistoryEntry) -> Unit
) {
    val pagedHistory = remember(history) {
        Pager(
            config = PagingConfig(
                pageSize = HISTORY_PAGE_SIZE,
                initialLoadSize = HISTORY_PAGE_SIZE,
                prefetchDistance = 3,
                enablePlaceholders = false
            )
        ) { HistoryPagingSource(history) }.flow
    }.collectAsLazyPagingItems()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.ContentPasteSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(42.dp).width(42.dp)
                )
                Text("Create a private media copy", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Remove location and other identifying metadata before you paste or save it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onCleanMedia, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clean and copy media")
                }
                onCleanLinks?.let { cleanLinks ->
                    Button(onClick = cleanLinks, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clean copied links")
                    }
                }
                Button(onClick = onCleanCurrentClipboard, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clean current clipboard")
                }
                Button(onClick = onSaveMedia, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clean and save media")
                }
            }
        }

        if (pagedHistory.itemCount > 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("History", style = MaterialTheme.typography.titleLarge)
                }
            }
            items(pagedHistory.itemCount) { index ->
                pagedHistory[index]?.let { entry ->
                    HistoryRow(entry, onClick = { onHistorySelected(entry) })
                }
            }
            if (pagedHistory.loadState.append is androidx.paging.LoadState.Loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
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
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, entry.clipboardUri) {
        value = if (entry.kind == MediaKind.IMAGE) {
            withContext(Dispatchers.IO) {
                decodePreview(context, Uri.parse(entry.clipboardUri))
            }
        } else {
            null
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        if (bitmap != null) {
            ComposeImage(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = entry.sourceName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
        } else if (entry.kind == MediaKind.VIDEO) {
            var playerError by remember(entry.clipboardUri) { mutableStateOf<String?>(null) }
            val player = remember(entry.clipboardUri) {
                ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            playerError = error.message ?: error.errorCodeName
                            Log.e("CleanCopyVideo", "Could not play ${entry.clipboardUri}", error)
                        }
                    })
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(Uri.parse(entry.clipboardUri))
                            .setMimeType(MimeTypes.VIDEO_MP4)
                            .build()
                    )
                    repeatMode = ExoPlayer.REPEAT_MODE_ONE
                    playWhenReady = true
                    prepare()
                }
            }
            DisposableEffect(player) {
                onDispose { player.release() }
            }
            AndroidView(
                factory = { viewContext ->
                    android.view.LayoutInflater.from(viewContext)
                        .inflate(R.layout.view_video_player, null, false)
                        .also { (it as PlayerView).player = player }
                },
                update = { (it as PlayerView).player = player },
                modifier = Modifier.fillMaxSize()
            )
            if (playerError != null) {
                Text(
                    "Video playback failed: $playerError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (entry.kind == MediaKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Movie,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Text("${formatMediaKind(entry.kind)} preview", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun decodePreview(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > PREVIEW_EDGE_PX ||
        bounds.outHeight / sampleSize > PREVIEW_EDGE_PX
    ) {
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

private const val PREVIEW_EDGE_PX = 1600
private const val HISTORY_PAGE_SIZE = 10

private class HistoryPagingSource(
    private val entries: List<ClipboardHistoryEntry>
) : PagingSource<Int, ClipboardHistoryEntry>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipboardHistoryEntry> = runCatching {
        val page = params.key ?: 0
        val start = page * HISTORY_PAGE_SIZE
        val end = minOf(start + HISTORY_PAGE_SIZE, entries.size)
        LoadResult.Page(
            data = if (start < entries.size) entries.subList(start, end) else emptyList(),
            prevKey = if (page == 0) null else page - 1,
            nextKey = if (end < entries.size) page + 1 else null
        )
    }.getOrElse { LoadResult.Error(it) }

    override fun getRefreshKey(state: PagingState<Int, ClipboardHistoryEntry>): Int? = 0
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
                    if (index != fields.lastIndex) Divider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ClipboardHistoryEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (entry.kind) {
                    MediaKind.IMAGE -> Icons.Outlined.Image
                    MediaKind.VIDEO -> Icons.Outlined.Movie
                    MediaKind.LINK -> Icons.Outlined.Link
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(entry.sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatMediaKind(entry.kind)}  |  ${historyStatus(entry)}  |  ${formatTime(entry.capturedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Divider()
    }
}

private fun historyStatus(entry: ClipboardHistoryEntry): String {
    if (!entry.processed) return "Not processed"
    val removedCount = entry.before.count { beforeField ->
        entry.after.none { afterField ->
            afterField.label == beforeField.label && afterField.value == beforeField.value
        }
    }
    return if (removedCount == 0) "No tracked fields removed" else "$removedCount field(s) removed"
}

@Composable
private fun SettingsPage(
    modifier: Modifier,
    rewriteFilename: Boolean,
    outputName: String,
    compressVideo: Boolean,
    historyEnabled: Boolean,
    linkCleaningEnabled: Boolean,
    linkRuleStatus: String?,
    linkRulesUpdating: Boolean,
    onRewriteFilenameChanged: (Boolean) -> Unit,
    onOutputNameChanged: (String) -> Unit,
    onCompressVideoChanged: (Boolean) -> Unit,
    onHistoryEnabledChanged: (Boolean) -> Unit,
    onLinkCleaningEnabledChanged: (Boolean) -> Unit,
    onUpdateLinkRules: () -> Unit
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
            title = "Rewrite filename",
            supporting = "Use a clean name for derived media",
            checked = rewriteFilename,
            onCheckedChange = onRewriteFilenameChanged
        )
        if (rewriteFilename) {
            OutlinedTextField(
                value = outputName,
                onValueChange = onOutputNameChanged,
                label = { Text("Filename") },
                supportingText = { Text("Saved as $outputName.{ext}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingSwitch(
            icon = Icons.Outlined.Movie,
            title = "Compress videos",
            supporting = "H.264/AAC, 720p maximum",
            checked = compressVideo,
            onCheckedChange = onCompressVideoChanged
        )

        Text("History", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch(
            icon = Icons.Outlined.History,
            title = "Save cleaning history",
            supporting = "Keep cleaned media and old metadata available in the Clipboard tab",
            checked = historyEnabled,
            onCheckedChange = onHistoryEnabledChanged
        )

        Text("Links", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch(
            icon = Icons.Outlined.Link,
            title = "Clean copied links",
            supporting = "Automatically clean link tracking when you use Clean current clipboard",
            checked = linkCleaningEnabled,
            onCheckedChange = onLinkCleaningEnabledChanged
        )
        if (linkCleaningEnabled) {
            Button(onClick = onUpdateLinkRules, enabled = !linkRulesUpdating, modifier = Modifier.fillMaxWidth()) {
                if (linkRulesUpdating) {
                    LinearProgressIndicator(modifier = Modifier.width(24.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (linkRulesUpdating) "Updating rules" else "Update link-cleaning rules")
            }
            linkRuleStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth(0.78f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
