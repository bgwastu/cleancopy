package com.byebyemeta

import android.content.Context

object FilenameRewriteStore {
    private const val PREFS = "output_settings"
    private const val KEY_REWRITE_FILENAME = "rewrite_filename"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_REWRITE_FILENAME, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REWRITE_FILENAME, enabled)
            .apply()
    }
}
