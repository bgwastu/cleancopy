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
        "__mobile__", "is_from_login", "is_from_app", "share_?id", "share_channel_code"
    )

    // Google Maps mixes functional and tracking parameters, so only these are removed.
    private val mapsRules = listOf("g_ep", "g_st", "coh", "entry", "sca_esv", "skid")

    // Added to results opened from share.google links.
    private val googleShareRules = listOf("shndl", "shem", "kgs", "shmd")

    // s = client that copied the link, t/a/si = per-share identifiers.
    private val xRules = listOf("s", "t", "a", "si", "cn", "ref_src", "ref_url", "src", "cxt", "mx")

    private val tiktokRules = listOf(
        "is_from_webapp", "is_copy_url", "sender_device", "sender_web_id", "web_id",
        "share_.*", "sharer_language", "social_share_type", "tt_from", "u_code", "preview_pb",
        "_d", "_t", "_r", "checksum", "sec_uid", "sec_user_id", "iid", "ugbiz_name",
        "enter_from", "enter_method", "refer", "embed_source", "link_reflow_popup_iteration_sharer",
        "timestamp", "user_id", "source"
    )

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

    private val shopeeDomains =
        "shopee\\.(?:co\\.id|com\\.my|sg|ph|vn|co\\.th|tw|com\\.br|com\\.mx|com\\.co|cl)"

    val providers: List<LinkRuleProvider> = listOf(
        LinkRuleProvider(
            name = "shopee.co.id",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*$shopeeDomains(?:[/:?#]|$)",
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
            rules = listOf(
                "src", "trkid", "whid", "extParam", "_branch_match_id", "_branch_referrer",
                // Share parameters added since Tokopedia moved onto TikTok Shop links.
                "t_id", "t_st", "t_pp", "t_efo", "t_ef", "t_sm", "t_spt"
            ),
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
            name = "google.com/search share",
            urlPattern = "^https?://(?:www\\.)?google(?:\\.[a-z]{2,}){1,}/search(?:[?#]|$)",
            rules = googleShareRules
        ),
        LinkRuleProvider(
            // ClearURLs only knows twitter.com, so this covers x.com as well.
            name = "x.com",
            urlPattern = "^https?://(?:(?:www|mobile)\\.)?(?:x|twitter)\\.com(?:[/:?#]|$)",
            rules = xRules,
            resetQueryOnPaths = listOf(
                "^/[^/]+/status/\\d+(?:/(?:photo|video)/\\d+)?/?$",
                "^/i/(?:web/)?status/\\d+/?$"
            )
        ),
        LinkRuleProvider(
            // Merges into the downloaded ClearURLs "tiktok.com" provider.
            name = "tiktok.com",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?tiktok\\.com",
            rules = tiktokRules,
            resetQueryOnPaths = listOf(
                "^/@[^/]+/(?:video|photo)/\\d+/?$",
                "^/v/\\d+(?:\\.html)?/?$"
            )
        ),
        LinkRuleProvider(
            // Merges into the downloaded ClearURLs "instagram" provider.
            name = "instagram",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?instagram\\.com",
            rules = listOf("igsh", "igshid", "ig_rid", "ig_mid"),
            keepParams = listOf("img_index"),
            resetQueryOnPaths = listOf(
                "^/(?:[^/]+/)?(?:p|reel|reels|tv)/[^/]+/?$",
                "^/stories/[^/]+/\\d+/?$"
            )
        ),
        LinkRuleProvider(
            // threads.net now redirects to threads.com; both are still shared.
            name = "threads",
            urlPattern = "^https?://(?:www\\.)?threads\\.(?:com|net)(?:[/:?#]|$)",
            rules = listOf("xmt", "slof", "igsh", "igshid"),
            resetQueryOnPaths = listOf("^/@[^/]+/post/[^/]+(?:/media)?/?$")
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
