package net.wastu.cleancopy

/** Builds a concise, per-item explanation for the cleaned-media result sheet. */
fun mediaCleanupDetails(
    before: MediaInspection,
    after: MediaInspection,
    outputName: String,
    wasSanitized: Boolean
): List<String> {
    val removedFields = before.fields.filter { beforeField ->
        after.fields.none { afterField ->
            afterField.label == beforeField.label && afterField.value == beforeField.value
        }
    }
    val accountedFor = mutableSetOf<MetadataField>()

    fun takeMatching(vararg labels: String): Boolean {
        val matches = removedFields.filter { field ->
            labels.any { label -> field.label.contains(label, ignoreCase = true) }
        }
        accountedFor += matches
        return matches.isNotEmpty()
    }

    return buildList {
        if (takeMatching("GPS", "Location")) add("Location metadata removed")
        if (takeMatching("Camera", "Make", "Model", "Software")) add("Camera details removed")
        if (takeMatching("Captured", "Date", "Year")) add("Dates and timestamps removed")
        if (takeMatching("Artist", "Copyright", "Description", "Comment", "Title", "Album", "Writer", "Composer", "Genre")) {
            add("Labels and creator details removed")
        }

        val otherCount = removedFields.count { it !in accountedFor }
        if (otherCount > 0) {
            add("$otherCount other metadata ${if (otherCount == 1) "field" else "fields"} removed")
        }

        if (before.displayName != outputName) add("Original filename replaced")

        // The sanitizer also strips embedded tags that the lightweight inspector cannot expose.
        if (isEmpty() && wasSanitized) {
            add(
                when (before.kind) {
                    MediaKind.IMAGE -> "Embedded image metadata scrubbed"
                    MediaKind.VIDEO -> "Video container metadata scrubbed"
                    MediaKind.LINK -> "Embedded metadata scrubbed"
                }
            )
        }
    }
}
