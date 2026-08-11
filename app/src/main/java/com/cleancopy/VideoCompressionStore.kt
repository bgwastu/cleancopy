package com.cleancopy

import android.content.Context

object VideoCompressionStore {
    private const val PREFS = "output_settings"
    private const val KEY_COMPRESS_VIDEO = "compress_video"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPRESS_VIDEO, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPRESS_VIDEO, enabled)
            .apply()
    }
}
