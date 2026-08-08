package com.byebyemeta.clipboard

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Debug-only metadata assertion helper for files produced on a real device. */
class DebugInspectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cache = cacheDir
        inspect("source", File(cache, "fixtures/clipboard-fixture.jpg"))
        cache.resolve("sanitized").listFiles()
            ?.sortedBy { it.name }
            ?.lastOrNull()
            ?.let { inspect("sanitized", it) }
        finish()
    }

    private fun inspect(label: String, file: File) {
        if (!file.isFile) {
            Log.w(TAG, "$label file missing: ${file.absolutePath}")
            return
        }
        val exif = ExifInterface(file)
        val values = TAGS.joinToString { tag ->
            "$tag=${exif.getAttribute(tag) ?: "<none>"}"
        }
        Log.i(TAG, "$label ${file.length()} bytes: $values")
    }

    companion object {
        private const val TAG = "ByeByeMetaInspect"
        private val TAGS = listOf(
            "GPSLatitude", "GPSLongitude", "DateTimeOriginal", "Make", "Model",
            "Software", "UserComment", "Xmp"
        )
    }
}
