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
    fun cleansXStatusShareLinks() {
        listOf(
            "https://x.com/jack/status/20?s=20&t=AbCdEf&a=RFELiAObBLe_jLlG8Ms",
            "https://x.com/jack/status/20?s=46&t=x&utm_source=share",
            "https://twitter.com/jack/status/20?s=19&ref_src=twsrc%5Etfw"
        ).forEach { url ->
            assertEquals(url.substringBefore('?'), clean(url).cleaned)
        }
        assertEquals("https://x.com/i/web/status/20", clean("https://x.com/i/web/status/20?s=20&si=1").cleaned)
        assertEquals("https://x.com/jack/status/20/photo/1", clean("https://x.com/jack/status/20/photo/1?s=20").cleaned)
    }

    @Test
    fun keepsXSearchQueryWhileRemovingShareParameters() {
        assertEquals(
            "https://x.com/search?q=cleancopy&f=live",
            clean("https://x.com/search?q=cleancopy&src=typed_query&f=live&s=20&t=abc").cleaned
        )
        assertEquals("https://x.com/jack", clean("https://x.com/jack?s=21&t=abc&a=xyz").cleaned)
    }

    @Test
    fun cleansTikTokVideoAndProfileLinks() {
        assertEquals(
            "https://www.tiktok.com/@name/video/7234567890123456789",
            clean(
                "https://www.tiktok.com/@name/video/7234567890123456789" +
                    "?is_from_webapp=1&sender_device=pc&web_id=7123&_t=ZS-8x&_r=1&share_app_id=1233"
            ).cleaned
        )
        assertEquals(
            "https://www.tiktok.com/@name/photo/7234567890123456789",
            clean("https://www.tiktok.com/@name/photo/7234567890123456789?_d=secCgYIASAHKAE&u_code=abc").cleaned
        )
        assertEquals(
            "https://www.tiktok.com/@name?lang=en",
            clean("https://www.tiktok.com/@name?_t=ZS-8x&_r=1&lang=en&is_from_webapp=1&sender_device=mobile").cleaned
        )
    }

    @Test
    fun cleansInstagramPostsButKeepsCarouselIndex() {
        assertEquals(
            "https://www.instagram.com/reel/DAbCdEfGhIj/",
            clean("https://www.instagram.com/reel/DAbCdEfGhIj/?igsh=MWQ1ZGUxMzBkMA==&utm_source=ig_web_copy_link").cleaned
        )
        assertEquals(
            "https://www.instagram.com/p/DAbCdEfGhIj/?img_index=3",
            clean("https://www.instagram.com/p/DAbCdEfGhIj/?img_index=3&igsh=abc&ig_rid=def").cleaned
        )
        assertEquals(
            "https://www.instagram.com/name/reel/DAbCdEfGhIj/",
            clean("https://www.instagram.com/name/reel/DAbCdEfGhIj/?hl=en&igsh=abc").cleaned
        )
        assertEquals("https://www.instagram.com/name", clean("https://www.instagram.com/name?igsh=abc&utm_source=qr").cleaned)
    }

    @Test
    fun removesNewMapsShareIdentifier() {
        assertEquals(
            "https://www.google.com/maps/place/Monas/@-6.17,106.82,17z?hl=id",
            clean("https://www.google.com/maps/place/Monas/@-6.17,106.82,17z?entry=tts&g_ep=EgoyMDI1&skid=7c2a-uuid&hl=id").cleaned
        )
    }

    @Test
    fun cleansGoogleShareSearchResults() {
        assertEquals(
            "https://www.google.com/search?q=Monas&kgmid=/m/02k6fd",
            clean("https://www.google.com/search?q=Monas&kgmid=/m/02k6fd&shndl=30&shem=ssc&source=sh/x/kp/osrp/m5/1&kgs=abc").cleaned
        )
    }

    @Test
    fun removesTokopediaTikTokShopShareParameters() {
        assertEquals(
            "https://www.tokopedia.com/toko",
            clean("https://www.tokopedia.com/toko?t_id=1&t_st=1&t_pp=homepage&t_efo=x&t_ef=y&t_sm=z&t_spt=w").cleaned
        )
    }

    @Test
    fun cleansOtherShopeeRegions() {
        assertEquals(
            "https://shopee.com.my/Item-i.1.2",
            clean("https://shopee.com.my/Item-i.1.2?sp_atk=a&xptdk=b&share_channel_code=6").cleaned
        )
        assertEquals("https://shopee.sg/product/1/2", clean("https://shopee.sg/universal-link/product/1/2?smtt=0").cleaned)
    }

    @Test
    fun removesTikTokClickIdEverywhere() {
        assertEquals("https://example.com/landing?id=1", clean("https://example.com/landing?ttclid=E.C.P.abc&id=1").cleaned)
    }

    @Test
    fun cleaningIsIdempotent() {
        val urls = listOf(
            "https://shopee.co.id/product/1/2?sp_atk=x&utm_source=y",
            "https://www.tokopedia.com/toko/produk?utm_source=salinlink&_branch_match_id=1",
            "https://www.google.com/maps/place/Fuji/@1,2,15z/data=!3m1!4b1?coh=1&entry=tts",
            "https://www.youtube.com/watch?v=x&si=1&pp=2&t=3",
            "https://x.com/jack/status/20?s=20&t=abc&a=xyz",
            "https://www.tiktok.com/@name/video/1?_t=x&_r=1",
            "https://www.instagram.com/p/abc/?img_index=2&igsh=x"
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
