package net.wastu.cleancopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortLinkParsersTest {
    @Test
    fun decodesFullyEncodedBranchIntentFallback() {
        val intent = "intent://product/1?x=1#Intent;scheme=tokopedia;package=com.tokopedia.tkpd;" +
            "S.browser_fallback_url=https%3A%2F%2Fwww.tokopedia.com%2Fstore%2Fproduct" +
            "%3Futm_source%3Dsalinlink%26_branch_match_id%3D1;end"

        assertEquals(
            "https://www.tokopedia.com/store/product?utm_source=salinlink&_branch_match_id=1",
            ShortLinkParsers.parseIntentFallback(intent)
        )
    }

    @Test
    fun keepsAlreadyAbsoluteMapsIntentFallbackEncoded() {
        val intent = "intent://maps.app.goo.gl/x#Intent;package=com.google.android.gms;" +
            "S.browser_fallback_url=https://www.google.com/maps/place/Masjid%2BJami%2BAlfalah" +
            "%2BCadas%2BNgamprah;end"

        assertEquals(
            "https://www.google.com/maps/place/Masjid%2BJami%2BAlfalah%2BCadas%2BNgamprah",
            ShortLinkParsers.parseIntentFallback(intent)
        )
    }

    @Test
    fun parsesMetaRefreshWithLongDelay() {
        val html = "<html><head><meta http-equiv=\"refresh\" " +
            "content=\"400;url=https://www.lazada.co.id/shop/nike?spm=a2o4j.share\"></head></html>"

        assertEquals(
            "https://www.lazada.co.id/shop/nike?spm=a2o4j.share",
            ShortLinkParsers.parseMetaRefresh(html)
        )
    }

    @Test
    fun parsesWindowLocationAndLocationHref() {
        assertEquals(
            "https://www.tokopedia.com/store/product",
            ShortLinkParsers.parseJsLocation(
                "<script>window.location='https://www.tokopedia.com/store/product'</script>"
            )
        )
        assertEquals(
            "https://www.lazada.co.id/shop/nike?spm=1",
            ShortLinkParsers.parseJsLocation("<script>location.href = 'https://www.lazada.co.id/shop/nike?spm=1'</script>")
        )
    }

    @Test
    fun parsesShopeeConfigEscapes() {
        val html = "<script>var CONFIG={" +
            "\"httpUrl\":\"https:\\/\\/shopee.co.id\\/opaanlp\\/1197851737\\/53254262972" +
            "?__mobile__=1\\u0026utm_source=an_1\"};</script>"

        assertEquals(
            "https://shopee.co.id/opaanlp/1197851737/53254262972?__mobile__=1&utm_source=an_1",
            ShortLinkParsers.parseShopeeConfig(html)
        )
    }

    @Test
    fun parsesCanonicalAndOpenGraphUrls() {
        assertEquals(
            "https://www.example.com/item?a=1&b=2",
            ShortLinkParsers.parseCanonical(
                "<meta property=\"og:url\" content=\"https://www.example.com/item?a=1&amp;b=2\">"
            )
        )
        assertEquals(
            "https://www.example.com/canonical",
            ShortLinkParsers.parseCanonical("<link rel=\"canonical\" href=\"https://www.example.com/canonical\">")
        )
    }

    @Test
    fun findsTokopediaUrlInBranchHtml() {
        val html = "<html><body><a href=\"https://www.tokopedia.com/store/product" +
            "?utm_source=salinlink&amp;utm_medium=share\">Open</a></body></html>"

        assertEquals(
            "https://www.tokopedia.com/store/product?utm_source=salinlink&utm_medium=share",
            ShortLinkParsers.parseTokopediaUrl(html)
        )
    }

    @Test
    fun detectsHomepageAndErrorDestinations() {
        assertTrue(ShortLinkParsers.isRootLike("https://shopee.co.id"))
        assertTrue(ShortLinkParsers.isRootLike("https://shopee.co.id/"))
        assertTrue(ShortLinkParsers.isRootLike("https://www.tokopedia.com/error-404.pl"))
        assertTrue(ShortLinkParsers.isRootLike("https://www.google.com/maps"))
        assertTrue(ShortLinkParsers.isRootLike("https://www.tiktok.com/?_r=1"))
        assertFalse(ShortLinkParsers.isRootLike("https://www.tokopedia.com/store/product"))
        assertFalse(ShortLinkParsers.isRootLike("https://www.google.com/maps/place/Fuji/@1,2,15z"))
    }

    @Test
    fun resolvesRelativeRedirectsAgainstTheCurrentUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            ShortLinkParsers.resolveAgainst(
                "https://www.youtube.com/attribution_link?a=1",
                "/watch?v=dQw4w9WgXcQ"
            )
        )
    }

    @Test
    fun decodesHtmlEntitiesInUrls() {
        assertEquals("a=1&b=2", ShortLinkParsers.decodeHtmlEntities("a=1&amp;b=2"))
    }
}
