package net.wastu.cleancopy

import android.content.Context
import android.net.Uri

object OutputNameStore {
    fun resolve(context: Context, source: Uri, counter: Int): String {
        // Filenames can contain timestamps, device models, or personal identifying info.
        // Always rewrite to clean indexed/counter names to prevent metadata leaks.
        return counter.toString()
    }
}
