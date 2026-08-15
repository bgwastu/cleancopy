package net.wastu.cleancopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHelperTest {

    @Test
    fun extensionToMime_mapsCommonExtensionsCorrectly() {
        assertEquals("image/jpeg", ClipboardHelper.extensionToMime("jpg"))
        assertEquals("image/jpeg", ClipboardHelper.extensionToMime("jpeg"))
        assertEquals("image/png", ClipboardHelper.extensionToMime("png"))
        assertEquals("image/webp", ClipboardHelper.extensionToMime("webp"))
        assertEquals("image/gif", ClipboardHelper.extensionToMime("gif"))
        assertEquals("image/heic", ClipboardHelper.extensionToMime("heic"))
        assertEquals("video/mp4", ClipboardHelper.extensionToMime("mp4"))
        assertEquals("video/quicktime", ClipboardHelper.extensionToMime("mov"))
        assertEquals("video/webm", ClipboardHelper.extensionToMime("webm"))
        assertEquals("application/octet-stream", ClipboardHelper.extensionToMime("unknown"))
    }

    @Test
    fun buildMimeTypesList_includesSpecificWildcardAndUriListForImages() {
        val types = ClipboardHelper.buildMimeTypesList("image/jpeg")
        assertEquals(listOf("image/jpeg", "image/*", "text/uri-list"), types)
    }

    @Test
    fun buildMimeTypesList_includesSpecificWildcardAndUriListForVideos() {
        val types = ClipboardHelper.buildMimeTypesList("video/mp4")
        assertEquals(listOf("video/mp4", "video/*", "text/uri-list"), types)
    }

    @Test
    fun buildMimeTypesList_handlesGenericTypes() {
        val types = ClipboardHelper.buildMimeTypesList("application/octet-stream")
        assertEquals(listOf("application/octet-stream", "text/uri-list"), types)
    }
}
