package net.wastu.cleancopy

import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * Expands known short links. Only hosts in [shortHosts] (plus Facebook share links and
 * youtu.be) are ever contacted. Anything else is returned untouched without network access.
 */
object NetworkRedirectResolver {
    internal var http: ShortLinkHttp = HttpShortLinkClient

    private const val MAX_HOPS = 5
    private const val REQUEST_TIMEOUT_MS = 4_000
    private const val MAX_BODY_BYTES = 64 * 1024

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val FACEBOOK_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"

    private val shortHosts = listOf(
        Pattern.compile("^(?:[a-z0-9-]+\\.)?shp\\.ee$"),
        Pattern.compile("^shope\\.ee$"),
        Pattern.compile("^s\\.shopee\\.co\\.id$"),
        Pattern.compile("^tokopedia\\.link$"),
        Pattern.compile("^tokopedia\\.app\\.link$"),
        Pattern.compile("^tkp\\.me$"),
        Pattern.compile("^maps\\.app\\.goo\\.gl$"),
        Pattern.compile("^goo\\.gl$"),
        Pattern.compile(
            "^(?:t\\.co|bit\\.ly|buff\\.ly|tinyurl\\.com|is\\.gd|v\\.gd|ow\\.ly|rebrand\\.ly|" +
                "shorturl\\.at|t\\.ly|rb\\.gy|cutt\\.ly|amzn\\.to|lnkd\\.in|vt\\.tiktok\\.com|vm\\.tiktok\\.com)$"
        )
    )
    private val facebookHosts = setOf("facebook.com", "www.facebook.com", "m.facebook.com")
    private val youtubeId = Regex("[A-Za-z0-9_-]{6,64}")

    fun resolve(url: String): LinkCleanResult {
        expandYouTube(url)?.let { return it }
        val uri = runCatching { URI(url) }.getOrNull() ?: return unchanged(url)
        val host = uri.host?.lowercase(Locale.US) ?: return unchanged(url)
        val path = uri.rawPath.orEmpty()
        if (host in facebookHosts && path.startsWith("/share/")) return resolveFacebook(url)
        if (!isExpandable(host, path)) return unchanged(url)
        return resolveShortLink(url)
    }

    private fun resolveShortLink(url: String): LinkCleanResult {
        var current = url
        val hops = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        return try {
            repeat(MAX_HOPS) {
                if (!visited.add(ShortLinkParsers.withoutFragment(current))) {
                    return failed(url, current, hops, "Redirect loop detected")
                }
                val uri = runCatching { URI(current) }.getOrNull()
                val host = uri?.host?.lowercase(Locale.US).orEmpty()
                val path = uri?.rawPath.orEmpty()
                if (!isExpandable(host, path)) return success(url, current, hops)

                val response = http.get(current, userAgentFor(host), REQUEST_TIMEOUT_MS, MAX_BODY_BYTES)
                when {
                    response.code in 200..299 -> {
                        val destination = destinationFrom(current, host, response)
                            ?: return failed(url, current, hops, "Could not expand this link")
                        hops += host
                        current = destination
                    }

                    response.code in 300..399 -> {
                        val location = response.location
                            ?: return failed(url, current, hops, "The redirect destination was missing")
                        val destination = destinationFromLocation(current, location)
                            ?: return failed(url, current, hops, "Redirected to an unsupported URL")
                        hops += host
                        current = destination
                    }

                    else -> return failed(url, current, hops, "Could not expand this link (HTTP ${response.code})")
                }
            }
            failed(url, current, hops, "Network redirect limit reached")
        } catch (error: Exception) {
            failed(url, current, hops, error.message ?: "Could not expand this link")
        }
    }

    private fun resolveFacebook(url: String): LinkCleanResult {
        var current = facebookMobileUrl(url)
        val hops = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        return try {
            repeat(MAX_HOPS) {
                if (!visited.add(ShortLinkParsers.withoutFragment(current))) {
                    return failed(url, if (hops.isEmpty()) url else current, hops, "Redirect loop detected")
                }
                val response = http.get(current, FACEBOOK_USER_AGENT, REQUEST_TIMEOUT_MS, MAX_BODY_BYTES)
                when {
                    response.code in 200..299 -> {
                        val target = response.refresh?.let(::facebookRefreshTarget)
                        val finalUrl = target ?: if (hops.isEmpty()) url else current
                        val redirects = if (target == null) hops else hops + "Facebook"
                        return if (finalUrl != url) {
                            LinkCleanResult(url, finalUrl, emptyList(), redirects, expandedFrom = url)
                        } else {
                            LinkCleanResult(url, finalUrl, emptyList(), redirects)
                        }
                    }

                    response.code in 300..399 -> {
                        val location = response.location
                            ?: return failed(url, current, hops, "The redirect destination was missing")
                        val next = destinationFromLocation(current, location)
                            ?: return failed(url, current, hops, "Redirected to an unsupported URL")
                        hops += URI(current).host.orEmpty()
                        current = next
                    }

                    else -> return failed(url, current, hops, "Could not expand this link (HTTP ${response.code})")
                }
            }
            failed(url, current, hops, "Network redirect limit reached")
        } catch (error: Exception) {
            failed(url, if (hops.isEmpty()) url else current, hops, error.message ?: "Could not expand this link")
        }
    }

    /**
     * youtu.be/<id> is already a short form. Expand it to the canonical watch URL and let
     * the normal cleaner remove share parameters such as si/pp/feature.
     */
    private fun expandYouTube(url: String): LinkCleanResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase(Locale.US) != "youtu.be") return null
        val id = uri.rawPath.orEmpty().trim('/')
        if (id.contains('/') || !youtubeId.matches(id)) return null
        val target = buildString {
            append("https://www.youtube.com/watch?v=").append(id)
            uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append('&').append(it) }
            uri.rawFragment?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
        }
        return LinkCleanResult(url, target, emptyList(), listOf("youtu.be"), expandedFrom = url)
    }

    private fun destinationFrom(current: String, host: String, response: ShortLinkResponse): String? {
        response.refresh?.let { refresh ->
            ShortLinkParsers.parseRefreshValue(refresh)?.let { value ->
                destinationFromLocation(current, value)?.let { return it }
            }
        }
        val body = response.body ?: return null
        val raw = ShortLinkParsers.parseMetaRefresh(body)
            ?: ShortLinkParsers.parseJsLocation(body)
            ?: ShortLinkParsers.parseIntentFallback(body)
            ?: ShortLinkParsers.parseShopeeConfig(body)
            ?: if (host == "tokopedia.app.link" || host == "tokopedia.link") {
                ShortLinkParsers.parseTokopediaUrl(body)
            } else {
                null
            }
            ?: ShortLinkParsers.parseCanonical(body)
            ?: return null
        return destinationFromLocation(current, raw)
    }

    private fun destinationFromLocation(current: String, value: String): String? {
        val raw = value.trim()
        val candidate = if (raw.startsWith("intent:", ignoreCase = true)) {
            ShortLinkParsers.parseIntentFallback(raw) ?: return null
        } else {
            raw
        }
        return ShortLinkParsers.resolveAgainst(current, candidate)?.takeIf(::isSafeNetworkUrl)
    }

    private fun isExpandable(host: String, path: String): Boolean {
        if (host == "goo.gl" && !path.startsWith("/maps/")) return false
        return shortHosts.any { it.matcher(host).matches() }
    }

    private fun userAgentFor(host: String): String =
        if (host == "maps.app.goo.gl" || host == "goo.gl") MOBILE_USER_AGENT else DESKTOP_USER_AGENT

    private fun success(original: String, finalUrl: String, hops: List<String>): LinkCleanResult {
        if (ShortLinkParsers.isRootLike(finalUrl)) {
            return failed(original, original, hops, "The link did not reveal a destination")
        }
        return LinkCleanResult(original, finalUrl, emptyList(), hops, expandedFrom = original)
    }

    private fun failed(
        original: String,
        current: String,
        hops: List<String>,
        message: String
    ): LinkCleanResult = LinkCleanResult(original, current, emptyList(), hops, message)

    private fun unchanged(url: String): LinkCleanResult =
        LinkCleanResult(url, url, emptyList(), emptyList())

    private fun isSafeNetworkUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host.isBlank() || uri.userInfo != null) return false
        if (!host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".local") || host == "0.0.0.0") return false
        if (host.startsWith("[") || host.count { it == ':' } > 1) {
            if (host == "[::1]" || host.startsWith("[fe80") || host.startsWith("[fc") || host.startsWith("[fd")) {
                return false
            }
        }
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            if (parts[0] in setOf(0, 10, 127) ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 169 && parts[1] == 254)
            ) {
                return false
            }
        }
        return true
    }

    internal fun facebookMobileUrl(value: String): String = URI(value).let { uri ->
        "https://m.facebook.com" + uri.rawPath + uri.rawQuery?.let { "?" + it }.orEmpty() +
            uri.rawFragment?.let { "#" + it }.orEmpty()
    }

    internal fun facebookRefreshTarget(value: String): String? = runCatching {
        Regex("(?:^|;)\\s*URL=fb://(?:fullscreen_video|video)/(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.let { "https://www.facebook.com/reel/" + it }
    }.getOrNull()
}
