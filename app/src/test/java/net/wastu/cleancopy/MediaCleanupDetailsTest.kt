package net.wastu.cleancopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCleanupDetailsTest {
    @Test
    fun `reports cleanup categories for one specific item`() {
        val before = MediaInspection(
            displayName = "IMG_20260922.jpg",
            kind = MediaKind.IMAGE,
            fields = listOf(
                MetadataField("GPS latitude", "-6.2"),
                MetadataField("Camera model", "Phone"),
                MetadataField("Captured", "2026:09:22")
            )
        )

        assertEquals(
            listOf(
                "Location metadata removed",
                "Camera details removed",
                "Dates and timestamps removed",
                "Original filename replaced"
            ),
            mediaCleanupDetails(
                before = before,
                after = before.copy(displayName = "0.jpg", fields = emptyList()),
                outputName = "0.jpg",
                wasSanitized = true
            )
        )
    }

    @Test
    fun `returns no details for an unchanged clean item`() {
        val inspection = MediaInspection("photo.jpg", MediaKind.IMAGE, emptyList())

        assertTrue(
            mediaCleanupDetails(
                before = inspection,
                after = inspection,
                outputName = "photo.jpg",
                wasSanitized = false
            ).isEmpty()
        )
    }
}
