package net.wastu.cleancopy

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ClipboardLinkTileAction {
    fun start(context: Context): Boolean {
        if (!LinkCleanupStore.isEnabled(context)) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = runCatching { clipboard.primaryClip?.webText(context).orEmpty() }.getOrDefault("")
        if (!LinkSanitizer.containsLink(text)) return false
        return runCatching {
            context.startService(
                Intent(context, ClipboardLinkService::class.java)
                    .putExtra(ClipboardLinkService.EXTRA_TEXT, text)
            )
        }.isSuccess
    }

    private fun ClipData.webText(context: Context): String = buildList {
        repeat(itemCount) { index ->
            val item = getItemAt(index)
            item.coerceToText(context)?.toString()?.takeIf(String::isNotBlank)?.let(::add)
            item.uri?.takeIf { it.scheme in setOf("http", "https") }?.toString()?.let(::add)
        }
    }.joinToString("\n")
}

class ClipboardLinkService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent?.getStringExtra(EXTRA_TEXT)
        if (input.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        serviceScope.launch {
            try {
                val result = LinkSanitizer.cleanText(
                    input,
                    LinkRuleStore.providers(this@ClipboardLinkService),
                    removeReferrals = false,
                    resolver = NetworkRedirectResolver::resolve
                )
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    if (result.text != input) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("CleanCopy clean links", result.text))
                    }
                    recordCleanedLinks(this@ClipboardLinkService, result)
                }
            } finally {
                withContext(Dispatchers.Main) { stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "net.wastu.cleancopy.extra.CLIPBOARD_TEXT"
    }
}
