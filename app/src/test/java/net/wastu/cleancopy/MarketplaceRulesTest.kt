package net.wastu.cleancopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceRulesTest {
    private val providers = LinkRuleStore.merge(
        listOf(
            LinkRuleProvider(
                name = "youtube",
                urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?(youtube\\.com|youtu\\.be)",
                rules = listOf("feature", "gclid", "kw", "si", "pp"),
                redirections = listOf(
                    "^https?://(?:[a-z0-9-]+\\.)*?youtube\\.com/redirect\\?.*?q=([^&]*)"
                )
            ),
            LinkRuleProvider(
                name = "google",
                urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?google(?:\\.[a-z]{2,}){1,}",
                rules = listOf("source")
            )
        ),
        LocalLinkRules.providers
    )

    private fun clean(url: String) = LinkSanitizer.cleanUrl(url, providers, removeReferrals = true)

    @Test
    fun removesShopeeIndonesiaTrackingParameters() {
        val result = clean(
            "https://shopee.co.id/Controller-JWS-i.139057740.3147793428" +
                "?sp_atk=abc&xptdk=def&utm_source=share&smtt=0.0.9"
        )

        assertEquals("https://shopee.co.id/Controller-JWS-i.139057740.3147793428", result.cleaned)
        assertTrue(result.removedParameters.containsAll(listOf("sp_atk", "xptdk", "utm_source", "smtt")))
    }

    @Test
    fun rewritesShopeeAffiliateLandingUrlsToProductUrls() {
        val result = clean(
            "https://shopee.co.id/universal-link/opaanlp/313495089/12201545229" +
                "?__mobile__=1&af_siteid=an_1&mmp_pid=an_1&utm_source=an_1"
        )

        assertEquals("https://shopee.co.id/product/313495089/12201545229", result.cleaned)
    }

    @Test
    fun resetsShopeeProductQueryButKeepsSearchParameters() {
        assertEquals(
            "https://shopee.co.id/product/1/2",
            clean("https://shopee.co.id/product/1/2?is_from_login=true&share_id=abc&foo=bar").cleaned
        )
        assertEquals(
            "https://shopee.co.id/search?keyword=laptop&page=1&sortBy=sales",
            clean("https://shopee.co.id/search?keyword=laptop&page=1&sortBy=sales&utm_source=share").cleaned
        )
    }

    @Test
    fun cleansTokopediaSalinlinkAndBranchParameters() {
        val result = clean(
            "https://www.tokopedia.com/toko/produk" +
                "?utm_source=salinlink&utm_medium=share&utm_campaign=PDP-1" +
                "&_branch_match_id=123&_branch_referrer=abc"
        )

        assertEquals("https://www.tokopedia.com/toko/produk", result.cleaned)
        assertTrue(result.removedParameters.containsAll(listOf("_branch_match_id", "_branch_referrer")))
    }

    @Test
    fun keepsTokopediaListingParameters() {
        assertEquals(
            "https://www.tokopedia.com/search?st=product&q=laptop&page=2&refined=true",
            clean("https://www.tokopedia.com/search?st=product&q=laptop&page=2&src=share&refined=true").cleaned
        )
    }

    @Test
    fun cleansMapsPlaceWithoutLosingPlaceData() {
        val result = clean(
            "https://www.google.com/maps/place/Mount+Fuji/@35.3606421,138.7170637,15z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x6019629a42fdc899:0xa6a1fcc916f3a4df" +
                "!8m2!3d35.3606255!4d138.7273634!16zL20vMGNrczA" +
                "?coh=219816&entry=tts&g_ep=Egoy&sca_esv=abc&utm_source=ig&hl=en"
        )

        assertTrue(result.cleaned.contains("/data=!3m1!4b1!4m6!3m5!1s0x6019629a42fdc899:0xa6a1fcc916f3a4df"))
        assertTrue(result.cleaned.endsWith("?hl=en"))
        assertTrue(result.removedParameters.containsAll(listOf("coh", "entry", "g_ep", "sca_esv", "utm_source")))
    }

    @Test
    fun keepsMapsDirectionParameters() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=Jakarta&destination=Bandung&travelmode=driving",
            clean(
                "https://www.google.com/maps/dir/?api=1&origin=Jakarta&destination=Bandung" +
                    "&travelmode=driving&utm_source=share"
            ).cleaned
        )
    }

    @Test
    fun removesYouTubeShareParametersButKeepsPlaybackParameters() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1&index=3&t=42",
            clean(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1&index=3&t=42" +
                    "&si=abc&pp=xyz&feature=share&ab_channel=RickAstley" +
                    "&embeds_referring_euri=https%3A%2F%2Fexample.com"
            ).cleaned
        )
    }

    @Test
    fun cleansShortYouTubeUrlsInPlace() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?t=42",
            clean("https://youtu.be/dQw4w9WgXcQ?si=abc&is=def&pp=xyz&t=42").cleaned
        )
    }

    @Test
    fun unwrapsYouTubeAttributionLinks() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            clean(
                "https://www.youtube.com/attribution_link?a=abc" +
                    "&u=%2Fwatch%3Fv%3DdQw4w9WgXcQ%26feature%3Dshare"
            ).cleaned
        )
    }

    @Test
    fun unwrapsYouTubeRedirectLinks() {
        assertEquals(
            "https://example.com/path?a=1",
            clean(
                "https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.com%2Fpath%3Fa%3D1" +
                    "&event=video_description"
            ).cleaned
        )
    }

    @Test
    fun cleaningIsIdempotent() {
        val urls = listOf(
            "https://shopee.co.id/product/1/2?sp_atk=x&utm_source=y",
            "https://www.tokopedia.com/toko/produk?utm_source=salinlink&_branch_match_id=1",
            "https://www.google.com/maps/place/Fuji/@1,2,15z/data=!3m1!4b1?coh=1&entry=tts",
            "https://www.youtube.com/watch?v=x&si=1&pp=2&t=3"
        )

        urls.forEach { url ->
            val once = clean(url).cleaned
            assertEquals(once, clean(once).cleaned)
        }
    }

    @Test
    fun localRulesMergeIntoDownloadedProviders() {
        val youtube = providers.first { it.name == "youtube" }
        assertTrue(youtube.rules.contains("si"))
        assertTrue(youtube.rules.contains("is"))
        assertTrue(youtube.redirections.any { it.contains("attribution_link") })
    }
}
