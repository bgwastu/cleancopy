package net.wastu.cleancopy

import android.content.Context

object ShortLinkExpansionStore {
    private const val PREFS = "link_cleanup"
    private const val KEY_EXPAND = "expand_short_links"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_EXPAND, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPAND, enabled)
            .apply()
    }
}
