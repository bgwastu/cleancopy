package net.wastu.cleancopy

import android.content.Context

object QuickSettingsPromptStore {
    private const val PREFS = "quick_settings_prompt"
    private const val KEY_DISMISSED = "dismissed"

    fun isDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()
    }
}
