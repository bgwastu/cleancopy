package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.wastu.cleancopy.clipboard.CleanCopyDefaultTileService

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var quickSettingsPromptDismissed by mutableStateOf(false)
    private val openedFromTileSettings by lazy {
        intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES" ||
            intent.getBooleanExtra(EXTRA_TILE_SETTINGS, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quickSettingsPromptDismissed = QuickSettingsPromptStore.isDismissed(this)
        refreshLinkRules()
        setContent {
             var selectedTab by rememberSaveable {
                 mutableIntStateOf(if (openedFromTileSettings) 1 else 0)
             }
            var rewriteFilename by remember(resumeTick) {
                mutableStateOf(FilenameRewriteStore.isEnabled(this@MainActivity))
            }
            var compressVideo by remember(resumeTick) {
                mutableStateOf(VideoCompressionStore.isEnabled(this@MainActivity))
            }
             var selectedHistoryId by rememberSaveable {
                 mutableStateOf(intent.getLongExtra(EXTRA_HISTORY_ID, -1L).takeIf { it >= 0L })
             }
            var historyEnabled by remember(resumeTick) {
                mutableStateOf(ClipboardHistoryStore.isEnabled(this@MainActivity))
            }
            var linkCleaningEnabled by remember(resumeTick) {
                mutableStateOf(LinkCleanupStore.isEnabled(this@MainActivity))
            }
            var history by remember(resumeTick) {
                mutableStateOf(ClipboardHistoryStore.entries(this@MainActivity))
            }
            val selectedHistory = history.firstOrNull { it.id == selectedHistoryId }
            LaunchedEffect(resumeTick) {
                history = ClipboardHistoryStore.entries(this@MainActivity)
            }

            // Read clipboard URL to show preview in the button
            var clipboardUrl by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(resumeTick) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                clipboardUrl = if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) text else null
            }

            var showChooseSourceDialog by remember { mutableStateOf(false) }

            CleanCopyTheme {
                CleanCopyApp(
                    selectedTab = selectedTab,
                    history = history,
                    clipboardUrl = clipboardUrl,
                    historyEnabled = historyEnabled,
                    rewriteFilename = rewriteFilename,
                    compressVideo = compressVideo,
                    selectedHistory = selectedHistory,
                    linkCleaningEnabled = linkCleaningEnabled,
                    quickSettingsPromptDismissed = quickSettingsPromptDismissed,
                    onTabSelected = { selectedTab = it },
                    onCleanCurrentClipboard = { openCleanCurrentClipboard() },
                    onChooseSourceToClean = { showChooseSourceDialog = true },
                    onHistorySelected = { selectedHistoryId = it.id },
                    onHistoryBack = { selectedHistoryId = null },
                    onOpenHistoryMedia = { openHistoryMedia(it) },
                    onCopyHistoryMedia = { copyHistoryMedia(it) },
                    onHistoryEnabledChanged = {
                        historyEnabled = it
                        ClipboardHistoryStore.setEnabled(this@MainActivity, it)
                        history = ClipboardHistoryStore.entries(this@MainActivity)
                        selectedHistoryId = null
                    },
                    onRewriteFilenameChanged = {
                        rewriteFilename = it
                        FilenameRewriteStore.setEnabled(this@MainActivity, it)
                    },
                    onCompressVideoChanged = {
                        compressVideo = it
                        VideoCompressionStore.setEnabled(this@MainActivity, it)
                    },
                    onLinkCleaningEnabledChanged = {
                        linkCleaningEnabled = it
                        LinkCleanupStore.setEnabled(this@MainActivity, it)
                    },
                    onAddQuickSettingsTile = { addQuickSettingsTile() },
                    onDismissQuickSettingsPrompt = {
                        QuickSettingsPromptStore.dismiss(this@MainActivity)
                        quickSettingsPromptDismissed = true
                    }
                )

                if (showChooseSourceDialog) {
                    ChooseSourceDialog(
                        onDismiss = { showChooseSourceDialog = false },
                        onChooseMedia = { openCleanMedia() },
                        onCleanUrl = { url -> openCleanCustomUrl(url) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun openCleanCustomUrl(url: String) {
        startActivity(
            Intent(this, CleanClipboardActivity::class.java)
                .putExtra(CleanClipboardActivity.EXTRA_INPUT_TEXT, url)
        )
    }

    private fun openCleanMedia() {
        startActivity(Intent(this, CleanMediaActivity::class.java))
    }

    private fun openCleanCurrentClipboard() {
        startActivity(Intent(this, CleanClipboardActivity::class.java))
    }

    private fun addQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { startActivity(Intent("android.settings.QUICK_SETTINGS_SETTINGS")) }
            Toast.makeText(this, "In Quick Settings, tap Edit and add CleanCopy", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, CleanCopyDefaultTileService::class.java),
                getString(R.string.tile_cleancopy),
                Icon.createWithResource(this, R.drawable.ic_clean_copy_mark),
                mainExecutor
            ) { result ->
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                        Toast.makeText(this, "CleanCopy added to Quick Settings", Toast.LENGTH_SHORT).show()
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                        Toast.makeText(this, "CleanCopy is already in Quick Settings", Toast.LENGTH_SHORT).show()
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                        Toast.makeText(this, "CleanCopy was not added", Toast.LENGTH_SHORT).show()
                    }
                    else -> Toast.makeText(this, "Could not open the Quick Settings request", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure {
            Toast.makeText(this, "Could not open the Quick Settings request", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_TILE_SETTINGS = "net.wastu.cleancopy.extra.TILE_SETTINGS"
        const val EXTRA_HISTORY_ID = "net.wastu.cleancopy.extra.HISTORY_ID"
    }

    private fun refreshLinkRules() {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { LinkRuleStore.update(this@MainActivity) } }
                .onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        error.message ?: "Could not update link-cleaning rules",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun openHistoryMedia(entry: ClipboardHistoryEntry) {
        val intent = if (entry.kind == MediaKind.LINK) {
            Intent(Intent.ACTION_VIEW, Uri.parse(entry.after.firstOrNull()?.value ?: entry.clipboardUri))
        } else {
            val mimeType = if (entry.kind == MediaKind.IMAGE) "image/*" else "video/*"
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(entry.clipboardUri), mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Open media")) }
            .onFailure {
                Toast.makeText(this, "The saved media is no longer available", Toast.LENGTH_LONG).show()
            }
    }

    private fun copyHistoryMedia(entry: ClipboardHistoryEntry) {
        if (entry.kind == MediaKind.LINK) {
            ClipboardHelper.copyText(this, entry.clipboardUri, entry.sourceName)
        } else {
            ClipboardHelper.copyMedia(this, listOf(Uri.parse(entry.clipboardUri)), entry.sourceName)
        }
        Toast.makeText(this, "${formatMediaKind(entry.kind)} copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
