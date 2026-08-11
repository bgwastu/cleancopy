package com.cleancopy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selectedTab by rememberSaveable { mutableIntStateOf(0) }
            var rewriteFilename by remember(resumeTick) {
                mutableStateOf(FilenameRewriteStore.isEnabled(this@MainActivity))
            }
            var outputName by remember(resumeTick) {
                mutableStateOf(OutputNameStore.get(this@MainActivity))
            }
            var compressVideo by remember(resumeTick) {
                mutableStateOf(VideoCompressionStore.isEnabled(this@MainActivity))
            }
            var selectedHistoryId by rememberSaveable { mutableStateOf<Long?>(null) }
            var historyEnabled by remember(resumeTick) {
                mutableStateOf(ClipboardHistoryStore.isEnabled(this@MainActivity))
            }
            var linkCleaningEnabled by remember(resumeTick) {
                mutableStateOf(LinkCleanupStore.isEnabled(this@MainActivity))
            }
            var linkRuleStatus by rememberSaveable { mutableStateOf<String?>(null) }
            var linkRulesUpdating by rememberSaveable { mutableStateOf(false) }
            var history by remember(resumeTick) {
                mutableStateOf(ClipboardHistoryStore.entries(this@MainActivity))
            }
            val selectedHistory = history.firstOrNull { it.id == selectedHistoryId }
            LaunchedEffect(resumeTick) {
                history = ClipboardHistoryStore.entries(this@MainActivity)
            }

            CleanCopyTheme {
                CleanCopyApp(
                    selectedTab = selectedTab,
                    history = history,
                    historyEnabled = historyEnabled,
                    rewriteFilename = rewriteFilename,
                    outputName = outputName,
                    compressVideo = compressVideo,
                    selectedHistory = selectedHistory,
                    linkCleaningEnabled = linkCleaningEnabled,
                    linkRuleStatus = linkRuleStatus,
                    linkRulesUpdating = linkRulesUpdating,
                    onTabSelected = { selectedTab = it },
                    onCleanMedia = { openCleanMedia(saveToLibrary = false) },
                    onCleanLinks = { cleanClipboardLinks() },
                    onCleanCurrentClipboard = { openCleanCurrentClipboard() },
                    onSaveMedia = { openCleanMedia(saveToLibrary = true) },
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
                    onOutputNameChanged = {
                        outputName = OutputNameStore.normalize(it)
                        OutputNameStore.set(this@MainActivity, outputName)
                    },
                    onCompressVideoChanged = {
                        compressVideo = it
                        VideoCompressionStore.setEnabled(this@MainActivity, it)
                    },
                    onLinkCleaningEnabledChanged = {
                        linkCleaningEnabled = it
                        LinkCleanupStore.setEnabled(this@MainActivity, it)
                    },
                    onUpdateLinkRules = {
                        linkRulesUpdating = true
                        lifecycleScope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { LinkRuleStore.update(this@MainActivity) }
                            }
                            linkRuleStatus = result.fold(
                                onSuccess = { "Updated $it link-cleaning providers" },
                                onFailure = { it.message ?: "Could not update link-cleaning rules" }
                            )
                            linkRulesUpdating = false
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun openCleanMedia(saveToLibrary: Boolean) {
        startActivity(
            Intent(this, CleanMediaActivity::class.java)
                .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, saveToLibrary)
        )
    }

    private fun openCleanCurrentClipboard() {
        startActivity(Intent(this, CleanClipboardActivity::class.java))
    }

    private fun cleanClipboardLinks() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        if (!LinkSanitizer.containsLink(text)) {
            Toast.makeText(this, "No web link found in the clipboard", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LinkSanitizer.cleanText(
                    text,
                    LinkRuleStore.providers(this@MainActivity),
                    removeReferrals = false,
                    resolver = NetworkRedirectResolver::resolve
                )
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("CleanCopy clean links", result.text))
            val count = result.links.count { it.changed }
            if (count > 0) {
                ClipboardHistoryStore.record(
                    this@MainActivity,
                    ClipboardHistoryEntry(
                        id = System.currentTimeMillis(),
                        clipboardUri = result.text,
                        sourceName = "Cleaned links",
                        kind = MediaKind.LINK,
                        capturedAt = System.currentTimeMillis(),
                        before = result.links.map { MetadataField("Original link", it.original) },
                        after = result.links.map { MetadataField("Clean link", it.cleaned) }
                    )
                )
            }
            Toast.makeText(
                this@MainActivity,
                if (count == 0) "No tracking found in copied links" else "$count link(s) cleaned and copied",
                Toast.LENGTH_SHORT
            ).show()
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
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (entry.kind == MediaKind.LINK) {
            clipboard.setPrimaryClip(ClipData.newPlainText(entry.sourceName, entry.clipboardUri))
        } else {
            clipboard.setPrimaryClip(
                ClipData.newUri(contentResolver, entry.sourceName, Uri.parse(entry.clipboardUri))
            )
        }
        Toast.makeText(this, "${formatMediaKind(entry.kind)} copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
