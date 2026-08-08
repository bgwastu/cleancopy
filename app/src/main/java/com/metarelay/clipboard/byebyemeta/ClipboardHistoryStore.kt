package com.byebyemeta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardHistoryEntry(
    val id: Long,
    val clipboardUri: String,
    val sourceName: String,
    val kind: MediaKind,
    val capturedAt: Long,
    val before: List<MetadataField>,
    val after: List<MetadataField>,
    val processed: Boolean = true
)

object ClipboardHistoryStore {
    private const val PREFS = "clipboard_history"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_DISMISSED_URI = "dismissed_uri"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_ENABLED = "enabled"
    private const val SCHEMA_VERSION = 3
    private const val MAX_ENTRIES = 100

    fun entries(context: Context): List<ClipboardHistoryEntry> = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_ENABLED, true)) return@runCatching emptyList()
        if (preferences.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION) {
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .remove(KEY_ENTRIES)
                .apply()
            return@runCatching emptyList()
        }
        val raw = preferences
            .getString(KEY_ENTRIES, "[]")
            .orEmpty()
        val array = JSONArray(raw)
        buildList {
            repeat(array.length()) { index -> add(fromJson(array.getJSONObject(index))) }
        }
    }.getOrDefault(emptyList())

    fun currentFor(context: Context, clipboardUri: String): ClipboardHistoryEntry? {
        if (!isEnabled(context)) return null
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_DISMISSED_URI, null) == clipboardUri) return null
        return entries(context).firstOrNull { it.clipboardUri == clipboardUri }
    }

    fun isDismissed(context: Context, clipboardUri: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_URI, null) == clipboardUri

    fun record(context: Context, entry: ClipboardHistoryEntry) {
        if (!isEnabled(context)) return
        val updated = (listOf(entry) + entries(context).filterNot { it.clipboardUri == entry.clipboardUri })
            .take(MAX_ENTRIES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, JSONArray().apply {
                updated.forEach { put(toJson(it)) }
            }.toString())
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .remove(KEY_DISMISSED_URI)
            .apply()
    }

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply {
                if (!enabled) {
                    remove(KEY_ENTRIES)
                    remove(KEY_DISMISSED_URI)
                }
            }
            .apply()
    }

    fun dismissCurrent(context: Context, clipboardUri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_URI, clipboardUri)
            .apply()
    }

    private fun toJson(entry: ClipboardHistoryEntry) = JSONObject().apply {
        put("id", entry.id)
        put("uri", entry.clipboardUri)
        put("name", entry.sourceName)
        put("kind", entry.kind.name)
        put("time", entry.capturedAt)
        put("before", fieldsToJson(entry.before))
        put("after", fieldsToJson(entry.after))
        put("processed", entry.processed)
    }

    private fun fromJson(json: JSONObject) = ClipboardHistoryEntry(
        id = json.optLong("id"),
        clipboardUri = json.optString("uri"),
        sourceName = json.optString("name", "Clipboard media"),
        kind = runCatching { MediaKind.valueOf(json.optString("kind")) }.getOrDefault(MediaKind.IMAGE),
        capturedAt = json.optLong("time"),
        before = fieldsFromJson(json.optJSONArray("before")),
        after = fieldsFromJson(json.optJSONArray("after")),
        processed = json.optBoolean("processed", true)
    )

    private fun fieldsToJson(fields: List<MetadataField>) = JSONArray().apply {
        fields.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) }
    }

    private fun fieldsFromJson(array: JSONArray?): List<MetadataField> = buildList {
        if (array == null) return@buildList
        repeat(array.length()) { index ->
            val field = array.optJSONObject(index) ?: return@repeat
            add(MetadataField(field.optString("label"), field.optString("value")))
        }
    }
}
