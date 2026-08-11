package net.wastu.cleancopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSanitizerTest {
    @Test
    fun removesGlobalTrackingParametersAndPreservesDuplicates() {
        val result = LinkSanitizer.cleanUrl(
            "https://example.com/article?id=1&utm_source=newsletter&id=2&fbclid=secret#read",
            emptyList(),
            removeReferrals = false
        )

        assertEquals("https://example.com/article?id=1&id=2#read", result.cleaned)
        assertEquals(listOf("utm_source", "fbclid"), result.removedParameters)
    }

    @Test
    fun cleansEveryUrlInTextWithoutChangingSurroundingPunctuation() {
        val result = LinkSanitizer.cleanText(
            "Read https://one.example/?utm_medium=email, then https://two.example/?keep=yes&gclid=abc.",
            emptyList(),
            removeReferrals = false
        )

        assertEquals(
            "Read https://one.example/, then https://two.example/?keep=yes.",
            result.text
        )
        assertEquals(2, result.links.size)
    }

    @Test
    fun removesCommonTrackingIdentifiersFromSocialSharingLinksInOneBatch() {
        val result = LinkSanitizer.cleanText(
            "Facebook https://www.facebook.com/Meta?fbclid=click " +
                "TikTok https://www.tiktok.com/@scout2015/video/6718335390845095173?utm_source=share " +
                "Instagram https://www.instagram.com/p/CG0UU3JgjHo/?utm_source=ig_web_copy_link " +
                "LinkedIn https://www.linkedin.com/company/linkedin?utm_medium=share " +
                "X https://x.com/Android?gclid=click",
            emptyList(),
            removeReferrals = false
        )

        assertEquals(5, result.links.size)
        assertTrue(result.links.all { it.changed })
        assertEquals(
            "Facebook https://www.facebook.com/Meta TikTok https://www.tiktok.com/@scout2015/video/6718335390845095173 " +
                "Instagram https://www.instagram.com/p/CG0UU3JgjHo/ LinkedIn https://www.linkedin.com/company/linkedin X https://x.com/Android",
            result.text
        )
    }

    @Test
    fun unwrapsOnlyDeclaredProviderRedirects() {
        val provider = LinkRuleProvider(
            name = "Example redirector",
            urlPattern = "example-redirector\\.test",
            redirections = listOf("https?://example-redirector\\.test/go\\?target=([^&]+)")
        )

        val result = LinkSanitizer.cleanUrl(
            "https://example-redirector.test/go?target=https%3A%2F%2Fshop.example%2Fitem%3Futm_source%3Dad",
            listOf(provider),
            removeReferrals = false
        )

        assertEquals("https://shop.example/item", result.cleaned)
        assertEquals(listOf("Example redirector"), result.redirects)
        assertTrue(result.removedParameters.contains("utm_source"))
    }

    @Test
    fun leavesUnsupportedSchemesUntouched() {
        val result = LinkSanitizer.cleanUrl("mailto:hello@example.com?utm_source=test", emptyList(), false)

        assertFalse(result.changed)
        assertTrue(result.error?.contains("HTTP") == true)
    }

    @Test
    fun doesNotResolveNormalInstagramUrlsThroughTheNetwork() {
        val url = "https://www.instagram.com/reels/Db09qqcPagb/"

        assertEquals(url, NetworkRedirectResolver.resolve(url).cleaned)
    }

    @Test
    fun replacesFacebookShareLinksWithResolvedCanonicalLinks() {
        val shareUrl = "https://www.facebook.com/share/r/1ELDAtuxZq/"
        val canonicalUrl = "https://www.facebook.com/reel/1293868375968987"

        val result = LinkSanitizer.cleanText(
            shareUrl,
            emptyList(),
            removeReferrals = false,
            resolver = { LinkCleanResult(shareUrl, canonicalUrl, emptyList(), listOf("Facebook")) }
        )

        assertEquals(canonicalUrl, result.text)
        assertTrue(result.links.single().changed)
    }

    @Test
    fun convertsFacebookMobileRefreshTargetsToReels() {
        assertEquals(
            "https://www.facebook.com/reel/1293868375968987",
            NetworkRedirectResolver.facebookRefreshTarget(
                "0; URL=fb://fullscreen_video/1293868375968987?loop=false"
            )
        )
    }
}
