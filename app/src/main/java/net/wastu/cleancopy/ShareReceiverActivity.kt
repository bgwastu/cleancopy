package net.wastu.cleancopy

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.service.chooser.ChooserAction
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds CleanCopy actions to the system Sharesheet and executes the selected action. */
class ShareReceiverActivity : ComponentActivity() {
    private val saveTextLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingText
        if (uri != null && text != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray())
                }
                launch(Dispatchers.Main) { finishWithMessage("Cleaned text saved") }
            }
        } else {
            finish()
        }
    }

    private var pendingText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_OUTPUT_MODE)
        if (mode != null) {
            execute(mode)
        } else {
            launchSharesheet()
        }
    }

    private fun launchSharesheet() {
        val incomingUris = incomingUris(intent)
        val incomingText = incomingText(intent)
        if (incomingUris.isEmpty() && incomingText.isBlank()) {
            finish()
            return
        }

        val actionBase = Intent(this, ShareReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putStringArrayListExtra(
                EXTRA_INPUT_URIS,
                ArrayList(incomingUris.map(Uri::toString))
            )
            .putExtra(EXTRA_INPUT_TEXT, incomingText)
        actionBase.clipData = intent.clipData

        val sendIntent = Intent(intent).apply {
            component = null
            setPackage(null)
            removeExtra(EXTRA_OUTPUT_MODE)
        }
        val chooser = Intent.createChooser(sendIntent, "CleanCopy").apply {
            putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf(
                    actionIntent(actionBase, CleanMediaActivity.OUTPUT_COPY, 41),
                    actionIntent(actionBase, CleanMediaActivity.OUTPUT_SAVE, 42)
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            chooser.putExtra(
                Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS,
                arrayOf(
                    chooserAction("Copy to clipboard", actionBase, CleanMediaActivity.OUTPUT_COPY, 51),
                    chooserAction("Clean and save", actionBase, CleanMediaActivity.OUTPUT_SAVE, 52)
                )
            )
        }

        startActivity(chooser)
        finish()
    }

    private fun actionIntent(base: Intent, mode: String, requestCode: Int): Intent =
        Intent(base).putExtra(EXTRA_OUTPUT_MODE, mode).also {
            it.putExtra(EXTRA_REQUEST_CODE, requestCode)
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun chooserAction(
        label: String,
        base: Intent,
        mode: String,
        requestCode: Int
    ): ChooserAction = ChooserAction.Builder(
        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_clean_copy_mark),
        label,
        PendingIntent.getActivity(
            this,
            requestCode,
            actionIntent(base, mode, requestCode),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    ).build()

    private fun execute(mode: String) {
        val uris = intent.getStringArrayListExtra(EXTRA_INPUT_URIS)
            ?.map(Uri::parse)
            .orEmpty()
        val text = intent.getStringExtra(EXTRA_INPUT_TEXT).orEmpty()
        if (uris.isNotEmpty()) {
            startActivity(
                Intent(this, CleanMediaActivity::class.java)
                    .putExtra(CleanMediaActivity.EXTRA_SAVE_TO_LIBRARY, mode == CleanMediaActivity.OUTPUT_SAVE)
                    .putExtra(CleanMediaActivity.EXTRA_OUTPUT_MODE, mode)
                    .putStringArrayListExtra(CleanMediaActivity.EXTRA_INPUT_URIS, ArrayList(uris.map(Uri::toString)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .also { cleanIntent -> cleanIntent.clipData = intent.clipData }
            )
            finish()
            return
        }
        if (text.isBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val cleaned = if (LinkSanitizer.containsLink(text)) {
                withContext(Dispatchers.IO) {
                    LinkSanitizer.cleanText(
                        text,
                        LinkRuleStore.providers(this@ShareReceiverActivity),
                        removeReferrals = false,
                        resolver = NetworkRedirectResolver::resolve
                    ).text
                }
            } else {
                text
            }
            if (mode == CleanMediaActivity.OUTPUT_COPY) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CleanCopy cleaned text", cleaned))
                finishWithMessage("Cleaned text copied to clipboard")
            } else {
                pendingText = cleaned
                saveTextLauncher.launch("cleancopy.txt")
            }
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun incomingText(intent: Intent): String = if (intent.action == Intent.ACTION_SEND) {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    } else ""

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

    companion object {
        private const val EXTRA_OUTPUT_MODE = "net.wastu.cleancopy.extra.SHARE_OUTPUT_MODE"
        private const val EXTRA_INPUT_URIS = "net.wastu.cleancopy.extra.SHARE_INPUT_URIS"
        private const val EXTRA_INPUT_TEXT = "net.wastu.cleancopy.extra.SHARE_INPUT_TEXT"
        private const val EXTRA_REQUEST_CODE = "net.wastu.cleancopy.extra.SHARE_REQUEST_CODE"
    }
}
