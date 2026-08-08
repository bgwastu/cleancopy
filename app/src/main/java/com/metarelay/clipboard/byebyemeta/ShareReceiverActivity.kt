package com.byebyemeta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = incomingUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val sessionDirectory = File(cacheDir, "shared/${System.currentTimeMillis()}")
                    uris.mapIndexed { index, source ->
                        val before = ClipboardMetadataReader.inspect(this@ShareReceiverActivity, source)
                        val filenameOnly = MediaTypeDetector.detect(this@ShareReceiverActivity, source)
                            .outputExtension == "gif"
                        if (before.fields.isEmpty() && !filenameOnly) {
                            PreparedMedia(source, before, before, wasSanitized = false)
                        } else {
                            val output = MediaSanitizer.sanitize(
                                context = this@ShareReceiverActivity,
                                source = source,
                                sessionDirectory = sessionDirectory,
                                itemIndex = index
                            ) { }
                            val cleanUri = FileProvider.getUriForFile(
                                this@ShareReceiverActivity,
                                "${BuildConfig.APPLICATION_ID}.fileprovider",
                                output.file
                            )
                            val after = ClipboardMetadataReader.inspect(this@ShareReceiverActivity, cleanUri)
                            PreparedMedia(cleanUri, before, after, wasSanitized = !filenameOnly)
                        }
                    }
                }
            }
            result.onSuccess { prepared ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newUri(
                    contentResolver,
                    "ByeByeMeta clean media",
                    prepared.first().uri
                )
                prepared.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
                clipboard.setPrimaryClip(clip)
                prepared.filter { it.wasSanitized }.forEach { media ->
                    ClipboardHistoryStore.record(
                        this@ShareReceiverActivity,
                        ClipboardHistoryEntry(
                            id = System.currentTimeMillis(),
                            clipboardUri = media.uri.toString(),
                            sourceName = media.before.displayName,
                            kind = media.before.kind,
                            capturedAt = System.currentTimeMillis(),
                            before = media.before.fields,
                            after = media.after.fields
                        )
                    )
                }
                Toast.makeText(
                    this@ShareReceiverActivity,
                    "Clean media copied to clipboard",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@ShareReceiverActivity,
                    error.message ?: "Could not clean this media",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                ?: intent.clipData?.getItemAt(0)?.uri
        )
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            ?.takeIf { it.isNotEmpty() }
            ?: buildList {
                repeat(intent.clipData?.itemCount ?: 0) { index ->
                    intent.clipData?.getItemAt(index)?.uri?.let(::add)
                }
            }
        else -> emptyList()
    }

    private data class PreparedMedia(
        val uri: Uri,
        val before: MediaInspection,
        val after: MediaInspection,
        val wasSanitized: Boolean
    )
}
