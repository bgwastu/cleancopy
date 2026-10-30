package net.wastu.cleancopy

/**
 * Site rules that are shipped with the app and merged with the downloaded ClearURLs rules.
 *
 * These use the local-only extensions of [LinkRuleProvider]:
 * - [LinkRuleProvider.keepParams] never removes a functional parameter.
 * - [LinkRuleProvider.resetQueryOnPaths] drops every non-kept parameter on detail pages.
 * - [LinkRuleProvider.canonicalPaths] rewrites paths that are wrappers or landing pages.
 */
internal object LocalLinkRules {
    private val shopeeRules = listOf(
        "sp_atk", "xptdk", "publish_id", "promotionId", "stm_source", "stm_medium",
        "smtt", "mmp_pid", "uls_trackid", "gads_t_sig", "exp_group",
        "af_.*", "is_retargeting", "deep_and_deferred", "d_id", "pid", "c", "v4",
        "__mobile__", "is_from_login", "is_from_app", "share_?id"
    )

    // Google Maps mixes functional and tracking parameters, so only these are removed.
    private val mapsRules = listOf("g_ep", "g_st", "coh", "entry", "sca_esv")

    private val youtubeRules = listOf(
        "is", "ab_channel", "ab_channel_id",
        "embeds_referring_euri", "embeds_referring_origin", "source_ve_path",
        "widget_referrer"
    )

    private val tokopediaProductPath =
        "^/(?!(?:search|discovery|promo|campaign|blog|help|about|p|product|shop|find|topads|" +
            "play|stream|plus|bebas-ongkir|cek-ongkir|mitra|finance|seller|login|register|cart|" +
            "checkout|order|invoice|notifications|inbox|settings|wishlist|official-store|mall|l)" +
            "(?:/|$))[^/]+/[^/]+/?$"

    val providers: List<LinkRuleProvider> = listOf(
        LinkRuleProvider(
            name = "shopee.co.id",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*shopee\\.co\\.id(?:[/:?#]|$)",
            rules = shopeeRules,
            resetQueryOnPaths = listOf(
                "^/product/\\d+/\\d+/?$",
                "^/[^/]+-i\\.\\d+\\.\\d+/?$"
            ),
            canonicalPaths = listOf(
                PathRewrite(
                    "^/(?:universal-link/)?opaanlp/(\\d+)/(\\d+)(?:/.*)?$",
                    "/product/$1/$2"
                ),
                PathRewrite(
                    "^/universal-link/product/(\\d+)/(\\d+)(?:/.*)?$",
                    "/product/$1/$2"
                )
            )
        ),
        LinkRuleProvider(
            name = "tokopedia.com",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*tokopedia\\.com(?:[/:?#]|$)",
            rules = listOf("src", "trkid", "whid", "extParam", "_branch_match_id", "_branch_referrer"),
            resetQueryOnPaths = listOf(tokopediaProductPath)
        ),
        LinkRuleProvider(
            name = "google.com/maps",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*google(?:\\.[a-z]{2,}){1,}/maps(?:[/:?#]|$)",
            rules = mapsRules
        ),
        LinkRuleProvider(
            name = "maps.google.com",
            urlPattern = "^https?://maps\\.google(?:\\.[a-z]{2,}){1,}(?:[/:?#]|$)",
            rules = mapsRules
        ),
        LinkRuleProvider(
            name = "maps.app.goo.gl",
            urlPattern = "^https?://maps\\.app\\.goo\\.gl(?:[/:?#]|$)",
            rules = mapsRules
        ),
        LinkRuleProvider(
            name = "goo.gl/maps",
            urlPattern = "^https?://goo\\.gl/maps/",
            rules = mapsRules
        ),
        LinkRuleProvider(
            // Merges into the downloaded ClearURLs "youtube" provider.
            name = "youtube",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?(youtube\\.com|youtu\\.be)",
            rules = youtubeRules,
            redirections = listOf(
                "^https?://(?:[a-z0-9-]+\\.)*?youtube\\.com/attribution_link\\?.*?u=([^&]*)"
            )
        )
    )
}
