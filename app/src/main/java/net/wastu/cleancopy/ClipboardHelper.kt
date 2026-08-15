package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

object ClipboardHelper {

    /**
     * Copies one or more media URIs to the clipboard with explicit image/video and wildcard MIME types
     * so keyboards (Gboard, Samsung Keyboard) and apps (WhatsApp, Telegram, Discord, Slack, etc.)
     * immediately recognize the clipboard content as media.
     */
    fun copyMedia(
        context: Context,
        uris: List<Uri>,
        label: String = "CleanCopy clean media",
        mimeType: String? = null
    ): Boolean {
        if (uris.isEmpty()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false

        val primaryUri = uris.first()
        val detectedMime = mimeType
            ?: context.contentResolver.getType(primaryUri)
            ?: extensionToMime(primaryUri.lastPathSegment?.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        val mimeTypesList = buildMimeTypesList(detectedMime)

        val description = ClipDescription(label, mimeTypesList.toTypedArray())
        val clip = ClipData(description, ClipData.Item(primaryUri))
        for (i in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[i]))
        }

        return runCatching {
            clipboard.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    /**
     * Copies plain text to the clipboard.
     */
    fun copyText(
        context: Context,
        text: String,
        label: String = "CleanCopy clean text"
    ): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }

    /**
     * Extracts incoming text from any Intent across different sharing apps and contexts.
     * Supports EXTRA_TEXT, EXTRA_PROCESS_TEXT, intent.data, intent.clipData.
     */
    fun extractIncomingText(intent: Intent): String {
        // 1. Check EXTRA_TEXT (CharSequence or String)
        val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (extraText != null) return extraText

        // 2. Check EXTRA_PROCESS_TEXT (text selection context menu)
        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
        if (processText != null) return processText

        // 3. Check Intent data / dataString if it is a web URL
        val dataUri = intent.dataString
        if (!dataUri.isNullOrBlank() && (dataUri.startsWith("http://", ignoreCase = true) || dataUri.startsWith("https://", ignoreCase = true))) {
            return dataUri
        }

        // 4. Check ClipData items for text or web URIs
        val clipData = intent.clipData
        if (clipData != null && clipData.itemCount > 0) {
            val extracted = buildList {
                for (i in 0 until clipData.itemCount) {
                    val item = clipData.getItemAt(i)
                    item.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                    item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()?.let(::add)
                }
            }
            if (extracted.isNotEmpty()) {
                return extracted.joinToString("\n")
            }
        }

        // 5. Fallback to EXTRA_SUBJECT if present
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        if (subject != null && (subject.startsWith("http://", ignoreCase = true) || subject.startsWith("https://", ignoreCase = true))) {
            return subject
        }

        return ""
    }

    /**
     * Extracts incoming media URIs from any Intent across different sharing apps and contexts.
     * Supports ACTION_SEND (EXTRA_STREAM, intent.data, clipData), ACTION_SEND_MULTIPLE.
     */
    fun extractIncomingUris(intent: Intent): List<Uri> = buildList {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null && stream.scheme in setOf("content", "file")) {
                    add(stream)
                }
                intent.data?.let { if (it.scheme in setOf("content", "file")) add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streamList = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!streamList.isNullOrEmpty()) {
                    streamList.filter { it.scheme in setOf("content", "file") }.forEach(::add)
                }
            }
        }

        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (uri != null && uri.scheme in setOf("content", "file")) {
                    add(uri)
                }
            }
        }
    }.distinct()

    fun buildMimeTypesList(detectedMime: String): List<String> = buildList {
        add(detectedMime)
        when {
            detectedMime.startsWith("image/") -> {
                if (detectedMime != "image/*") add("image/*")
            }
            detectedMime.startsWith("video/") -> {
                if (detectedMime != "video/*") add("video/*")
            }
        }
        add("text/uri-list")
    }.distinct()

    fun extensionToMime(extension: String?): String = when (extension?.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}
