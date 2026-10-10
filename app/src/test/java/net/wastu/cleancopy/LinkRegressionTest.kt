package net.wastu.cleancopy

import org.junit.Assert.*
import org.junit.Test

class LinkRegressionTest {
    private fun clean(url: String, providers: List<LinkRuleProvider> = emptyList()) = LinkSanitizer.cleanUrl(url, providers, true)

    @Test fun preservesBalancedParenthesesAndSurroundingText() {
        val batch = LinkSanitizer.cleanText("Read (https://en.wikipedia.org/wiki/Function_(mathematics)?utm_source=email).", emptyList(), true)
        assertEquals("Read (https://en.wikipedia.org/wiki/Function_(mathematics)).", batch.text)
        assertEquals(1, batch.links.size)
    }

    @Test fun exceptionsProtectAgainstFallbackRules() {
        val provider = LinkRuleProvider("Protected", "example\\.com", rules = listOf("si"), exceptions = listOf("/signed"))
        val url = "https://example.com/signed?utm_source=required&si=required"
        assertEquals(url, clean(url, listOf(provider)).cleaned)
    }

    @Test fun preservesSignedUrls() {
        listOf("X-Amz-Signature", "X-Goog-Signature", "signature", "sig", "oauth_signature").forEach { key ->
            val url = "https://example.com/file?utm_source=required&$key=abc"
            assertEquals(url, clean(url).cleaned)
        }
    }

    @Test fun removesSiteSpecificParametersOnlyOnTheirSites() {
        assertEquals("https://example.com/?si=required&rdid=id", clean("https://example.com/?si=required&rdid=id").cleaned)
        assertEquals("https://example.com/?mc_currency=USD", clean("https://example.com/?mc_currency=USD&mc_cid=tracker&mc_eid=tracker").cleaned)
        assertEquals("https://youtu.be/abc?t=42", clean("https://youtu.be/abc?si=tracker&t=42").cleaned)
        assertEquals("https://www.instagram.com/p/abc/", clean("https://www.instagram.com/p/abc/?igsh=tracker").cleaned)
        assertEquals("https://www.tiktok.com/@name/video/123", clean("https://www.tiktok.com/@name/video/123?_t=tracker&_r=1").cleaned)
    }

    @Test fun cleansTrackingFragmentsWithoutRemovingAnchorsOrRoutes() {
        assertEquals("https://example.com/#keep=yes", clean("https://example.com/#utm_source=mail&keep=yes").cleaned)
        assertEquals("https://example.com/#/route?keep=yes", clean("https://example.com/#/route?utm_source=mail&keep=yes").cleaned)
        assertEquals("https://example.com/#section-2", clean("https://example.com/#section-2").cleaned)
    }

    @Test fun keepsRawEncodingAndCountsDuplicateTrackingParameters() {
        val result = clean("https://example.com/?q=A%2BB%26C&%75tm_source=a&utm_source=b&id=1&id=2")
        assertEquals("https://example.com/?q=A%2BB%26C&id=1&id=2", result.cleaned)
        assertEquals(listOf("utm_source", "utm_source"), result.removedParameters)
    }

    @Test fun reportsNetworkFailuresAndPreservesLocalCleanup() {
        val result = LinkSanitizer.cleanText("https://example.com/?utm_source=mail", emptyList(), true) {
            LinkCleanResult(it, it, emptyList(), emptyList(), "Offline")
        }.links.single()
        assertEquals("https://example.com/", result.cleaned)
        assertEquals(listOf("utm_source"), result.removedParameters)
        assertEquals("Offline", result.error)
    }

    @Test fun referralRemovalIsExplicit() {
        val provider = LinkRuleProvider("Shop", "example\\.com", referralMarketing = listOf("tag"))
        assertEquals("https://example.com/?id=1", clean("https://example.com/?tag=affiliate&id=1", listOf(provider)).cleaned)
        assertEquals("https://example.com/?tag=affiliate&id=1", LinkSanitizer.cleanUrl("https://example.com/?tag=affiliate&id=1", listOf(provider), false).cleaned)
    }

    @Test fun doesNotDoubleEncodeFacebookQuery() {
        assertEquals("https://m.facebook.com/share/r/a?u=A%26B#part", NetworkRedirectResolver.facebookMobileUrl("https://www.facebook.com/share/r/a?u=A%26B#part"))
    }

    @Test fun malformedUrlsHaveAnError() {
        assertNotNull(clean("https://example.com/%zz").error)
        assertEquals("plain text", LinkSanitizer.cleanText("plain text", emptyList(), true).text)
    }

    @Test fun mimeFamiliesMatchSingleAndMixedShares() {
        assertEquals("video/mp4", ClipboardHelper.commonMimeType(listOf("video/mp4", "video/mp4")))
        assertEquals("image/*", ClipboardHelper.commonMimeType(listOf("image/png", "image/jpeg")))
        assertEquals("*/*", ClipboardHelper.commonMimeType(listOf("image/jpeg", "video/mp4")))
    }

    @Test fun filenamesAreSafeAndPreservedWhenRequested() {
        assertEquals("IMG_20261010", OutputNameStore.safeBaseName("IMG_20261010.jpg", 0))
        assertEquals("my_photo", OutputNameStore.safeBaseName("my:photo.png", 0))
        assertEquals("1", OutputNameStore.safeBaseName(".jpg", 1))
    }
}
