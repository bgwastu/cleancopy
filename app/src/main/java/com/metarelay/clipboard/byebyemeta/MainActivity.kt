package com.byebyemeta

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
            var history by remember(resumeTick) {
                mutableStateOf(ClipboardHistoryStore.entries(this@MainActivity))
            }
            val selectedHistory = history.firstOrNull { it.id == selectedHistoryId }
            LaunchedEffect(resumeTick) {
                history = ClipboardHistoryStore.entries(this@MainActivity)
            }

            ByeByeMetaTheme {
                ByeByeMetaApp(
                    selectedTab = selectedTab,
                    history = history,
                    historyEnabled = historyEnabled,
                    rewriteFilename = rewriteFilename,
                    outputName = outputName,
                    compressVideo = compressVideo,
                    selectedHistory = selectedHistory,
                    onTabSelected = { selectedTab = it },
                    onCleanMedia = { openCleanMedia(saveToLibrary = false) },
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

    private fun openHistoryMedia(entry: ClipboardHistoryEntry) {
        val mimeType = if (entry.kind == MediaKind.IMAGE) "image/*" else "video/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(entry.clipboardUri), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(intent, "Open media")) }
            .onFailure {
                Toast.makeText(this, "The saved media is no longer available", Toast.LENGTH_LONG).show()
            }
    }

    private fun copyHistoryMedia(entry: ClipboardHistoryEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newUri(contentResolver, entry.sourceName, Uri.parse(entry.clipboardUri))
        )
        Toast.makeText(this, "Media copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
