package net.wastu.cleancopy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortLinkResolverTest {
    @After
    fun resetClient() {
        NetworkRedirectResolver.http = HttpShortLinkClient
    }

    private fun use(handler: (String) -> ShortLinkResponse): FakeHttp {
        val fake = FakeHttp(handler)
        NetworkRedirectResolver.http = fake
        return fake
    }
    private val providers = LinkRuleStore.merge(
        listOf(
            LinkRuleProvider(
                name = "youtube",
                urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?(youtube\\.com|youtu\\.be)",
                rules = listOf("feature", "gclid", "kw", "si", "pp"),
                redirections = listOf(
                    "^https?://(?:[a-z0-9-]+\\.)*?youtube\\.com/redirect\\?.*?q=([^&]*)"
                )
            )
        ),
        LocalLinkRules.providers
    )

    private fun response(
        code: Int,
        location: String? = null,
        refresh: String? = null,
        body: String? = null
    ) = ShortLinkResponse(code, location, refresh, if (body != null) "text/html; charset=utf-8" else null, body)

    @Test
    fun expandsShopeeAffiliateShortLinkWithDesktopUserAgent() {
        val fake = use { url ->
            if (url == "https://shope.ee/abc") {
                response(301, location = "https://shopee.co.id/universal-link/opaanlp/1/2?af_siteid=an_1")
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://shope.ee/abc")

        assertEquals("https://shopee.co.id/universal-link/opaanlp/1/2?af_siteid=an_1", result.cleaned)
        assertEquals("https://shope.ee/abc", result.expandedFrom)
        assertTrue(fake.requests.first().second.contains("Macintosh"))
    }

    @Test
    fun expandsShopeeConfigLandingPage() {
        use { url ->
            if (url == "https://s.shopee.co.id/abc") {
                response(
                    200,
                    body = """<script>var CONFIG={"httpUrl":"https:\/\/shopee.co.id\/opaanlp\/1\/2?__mobile__=1\u0026af_siteid=x"};</script>"""
                )
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://s.shopee.co.id/abc")

        assertEquals("https://shopee.co.id/opaanlp/1/2?__mobile__=1&af_siteid=x", result.cleaned)
        assertNotNull(result.expandedFrom)
    }

    @Test
    fun expandsTokopediaBranchChain() {
        use { url ->
            when (url) {
                "https://tokopedia.link/abc" ->
                    response(307, location = "https://tokopedia.app.link/abc?_p=1")

                "https://tokopedia.app.link/abc?_p=1" ->
                    response(
                        307,
                        location = "https://www.tokopedia.com/store/product" +
                            "?utm_source=salinlink&_branch_match_id=1"
                    )

                else -> response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://tokopedia.link/abc")

        assertEquals(
            "https://www.tokopedia.com/store/product?utm_source=salinlink&_branch_match_id=1",
            result.cleaned
        )
        assertNotNull(result.expandedFrom)
    }

    @Test
    fun expandsTokopediaIntentFallback() {
        use { url ->
            when {
                url == "https://tokopedia.link/abc" ->
                    response(307, location = "https://tokopedia.app.link/abc")

                url.startsWith("https://tokopedia.app.link/abc") ->
                    response(
                        307,
                        location = "intent://product/1?x=1#Intent;scheme=tokopedia;" +
                            "S.browser_fallback_url=https%3A%2F%2Fwww.tokopedia.com%2Fstore%2Fproduct;end"
                    )

                else -> response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://tokopedia.link/abc")

        assertEquals("https://www.tokopedia.com/store/product", result.cleaned)
        assertNotNull(result.expandedFrom)
    }

    @Test
    fun expandsTkpMeJavaScriptLanding() {
        use { url ->
            if (url == "https://tkp.me/abc") {
                response(200, body = """<script>window.location='https://www.tokopedia.com/store/product'</script>""")
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://tkp.me/abc")

        assertEquals("https://www.tokopedia.com/store/product", result.cleaned)
        assertNotNull(result.expandedFrom)
    }

    @Test
    fun expandsMapsShortLinkWithMobileUserAgent() {
        val fake = use { url ->
            if (url == "https://maps.app.goo.gl/abc") {
                response(
                    302,
                    location = "https://www.google.com/maps/place/Mount+Fuji/@1,2,15z/data=!3m1!4b1"
                )
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://maps.app.goo.gl/abc")

        assertEquals("https://www.google.com/maps/place/Mount+Fuji/@1,2,15z/data=!3m1!4b1", result.cleaned)
        assertNotNull(result.expandedFrom)
        assertTrue(fake.requests.first().second.contains("Android"))
    }

    @Test
    fun rejectsHomepageDestinations() {
        use { url ->
            if (url == "https://maps.app.goo.gl/abc") {
                response(302, location = "https://www.google.com/maps")
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://maps.app.goo.gl/abc")

        assertNull(result.expandedFrom)
        assertNotNull(result.error)
    }

    @Test
    fun reportsHttpErrors() {
        use { response(404) }

        val result = NetworkRedirectResolver.resolve("https://t.co/abc")

        assertNull(result.expandedFrom)
        assertNotNull(result.error)
    }

    @Test
    fun doesNotContactUnknownHosts() {
        val fake = use { response(500) }

        val result = NetworkRedirectResolver.resolve("https://example.com/path?utm_source=x")

        assertEquals("https://example.com/path?utm_source=x", result.cleaned)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun expandsYouTubeShortUrlWithoutNetwork() {
        val fake = use { response(500) }

        val result = NetworkRedirectResolver.resolve("https://youtu.be/dQw4w9WgXcQ?si=abc&t=42")

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc&t=42", result.cleaned)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc&t=42", result.expandedFrom)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun rejectsPrivateNetworkDestinations() {
        use { url ->
            if (url == "https://shope.ee/abc") {
                response(302, location = "http://192.168.1.1/admin")
            } else {
                response(404)
            }
        }

        val result = NetworkRedirectResolver.resolve("https://shope.ee/abc")

        assertNull(result.expandedFrom)
        assertNotNull(result.error)
    }

    @Test
    fun expandsAndCleansShopeeShortLinkEndToEnd() {
        use { url ->
            if (url == "https://shope.ee/abc") {
                response(301, location = "https://shopee.co.id/universal-link/opaanlp/1/2?af_siteid=an_1")
            } else {
                response(404)
            }
        }

        val batch = LinkSanitizer.cleanText(
            "Cek ini https://shope.ee/abc",
            providers,
            removeReferrals = true,
            resolver = NetworkRedirectResolver::resolve
        )

        assertEquals("Cek ini https://shopee.co.id/product/1/2", batch.text)
        assertEquals("https://shope.ee/abc", batch.links.single().expandedFrom)
    }

    @Test
    fun expandsYouTubeShortLinkEndToEnd() {
        val batch = LinkSanitizer.cleanText(
            "https://youtu.be/dQw4w9WgXcQ?si=abc&is=def&pp=xyz&t=42",
            providers,
            removeReferrals = true,
            resolver = NetworkRedirectResolver::resolve
        )

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42", batch.text)
        assertNotNull(batch.links.single().expandedFrom)
    }

    private class FakeHttp(private val handler: (String) -> ShortLinkResponse) : ShortLinkHttp {
        val requests = mutableListOf<Pair<String, String>>()

        override fun get(url: String, userAgent: String, timeoutMs: Int, maxBytes: Int): ShortLinkResponse {
            requests += url to userAgent
            return handler(url)
        }
    }
}
