package net.wastu.cleancopy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.util.Locale

object ClipboardHelper {

    /** Publishes readable media URIs. The receiving app decides which MIME types it can paste. */
    fun copyMedia(
        context: Context,
        uris: List<Uri>,
        label: String = "CleanCopy clean media",
        mimeType: String? = null
    ): Boolean {
        if (uris.isEmpty()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false

        return runCatching {
            check(uris.all { isReadable(context, it) }) { "A cleaned file is no longer available" }
            val mimeTypes = uris.flatMap { uri ->
                buildMimeTypesList(mimeType ?: context.contentResolver.getType(uri)
                    ?: extensionToMime(uri.lastPathSegment?.substringAfterLast('.', "")))
            }.distinct()
            val clip = ClipData(ClipDescription(label, mimeTypes.toTypedArray()), ClipData.Item(uris.first()))
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipboard.setPrimaryClip(clip)
            true
        }.getOrDefault(false)
    }

    fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    }.getOrDefault(false)

    fun shareMediaIntent(context: Context, uris: List<Uri>, mimeTypes: List<String>): Intent {
        require(uris.isNotEmpty() && uris.all { isReadable(context, it) }) { "The cleaned media is no longer available" }
        val type = commonMimeType(mimeTypes)
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData(ClipDescription("CleanCopy clean media", mimeTypes.distinct().toTypedArray()), ClipData.Item(uris.first())).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    internal fun commonMimeType(types: List<String>): String {
        if (types.distinct().size == 1) return types.first()
        val families = types.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
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
                val stream = runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
                if (stream != null && stream.scheme in setOf("content", "file")) {
                    add(stream)
                }
                intent.data?.let { if (it.scheme in setOf("content", "file")) add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamList = runCatching { IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
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
