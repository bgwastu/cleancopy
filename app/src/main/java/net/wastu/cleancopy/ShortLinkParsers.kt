package net.wastu.cleancopy

import java.net.URI
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * Pure parsers for the destinations embedded in short-link landing pages.
 * No network access, no JavaScript execution.
 */
internal object ShortLinkParsers {
    private val intentFallback = Pattern.compile("S\\.browser_fallback_url=([^;]+)", Pattern.CASE_INSENSITIVE)
    private val metaTag = Pattern.compile("<meta[^>]*>", Pattern.CASE_INSENSITIVE)
    private val linkTag = Pattern.compile("<link[^>]*>", Pattern.CASE_INSENSITIVE)
    private val metaRefreshTag = Pattern.compile(
        "<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*>",
        Pattern.CASE_INSENSITIVE
    )
    private val contentAttribute = Pattern.compile("content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val hrefAttribute = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val refreshUrl = Pattern.compile("url\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE)
    private val windowLocation = Pattern.compile(
        "window\\.location(?:\\s*\\.\\s*href)?\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val locationAssignment = Pattern.compile(
        "location\\.(?:href|replace)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val locationReplaceCall = Pattern.compile(
        "location\\.replace\\(\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val shopeeConfig = Pattern.compile("\"httpUrl\"\\s*:\\s*\"(https:[^\"]+)\"", Pattern.CASE_INSENSITIVE)
    private val tokopediaUrl = Pattern.compile("https://www\\.tokopedia\\.com/[^\"'<>\\\\ ]+", Pattern.CASE_INSENSITIVE)

    fun parseIntentFallback(value: String): String? {
        val match = intentFallback.matcher(value)
        if (!match.find()) return null
        val raw = match.group(1)?.trim()?.let(::decodeHtmlEntities) ?: return null
        // Branch/Tokopedia encodes the whole URL. Google Maps embeds an already absolute URL
        // whose own %XX escapes must survive, so only decode when it is not absolute.
        val candidate = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
        else decodeUrl(raw)
        return candidate.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    fun parseMetaRefresh(html: String): String? {
        val tags = metaRefreshTag.matcher(html)
        while (tags.find()) {
            val content = contentAttribute.matcher(tags.group()).takeIf { it.find() }?.group(1) ?: continue
            val value = parseRefreshValue(content) ?: continue
            if (value.isNotBlank()) return decodeHtmlEntities(value)
        }
        return null
    }

    fun parseRefreshValue(value: String): String? =
        refreshUrl.matcher(value).takeIf { it.find() }?.group(1)?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() }

    fun parseJsLocation(html: String): String? {
        listOf(windowLocation, locationAssignment, locationReplaceCall).forEach { pattern ->
            val match = pattern.matcher(html)
            if (match.find()) {
                val value = match.group(1)?.trim() ?: return@forEach
                if (value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith("/")) {
                    return decodeHtmlEntities(decodeJsUrl(value))
                }
            }
        }
        return null
    }

    fun parseShopeeConfig(html: String): String? {
        val match = shopeeConfig.matcher(html)
        if (!match.find()) return null
        return decodeHtmlEntities(decodeJsUrl(match.group(1) ?: return null))
    }

    fun parseCanonical(html: String): String? {
        val metas = metaTag.matcher(html)
        while (metas.find()) {
            val tag = metas.group()
            if (tag.contains("og:url", ignoreCase = true) || tag.contains("twitter:url", ignoreCase = true)) {
                contentAttribute.matcher(tag).takeIf { it.find() }?.group(1)?.let { return decodeHtmlEntities(it) }
            }
        }
        val links = linkTag.matcher(html)
        while (links.find()) {
            val tag = links.group()
            if (tag.contains("canonical", ignoreCase = true)) {
                hrefAttribute.matcher(tag).takeIf { it.find() }?.group(1)?.let { return decodeHtmlEntities(it) }
            }
        }
        return null
    }

    fun parseTokopediaUrl(html: String): String? =
        tokopediaUrl.matcher(html).takeIf { it.find() }?.group()?.let(::decodeHtmlEntities)?.trimEnd('.', ',', ';', ')')

    fun decodeJsUrl(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003d", "=", ignoreCase = true)
        .replace("\\u003f", "?", ignoreCase = true)
        .replace("\\u0025", "%", ignoreCase = true)
        .replace("\\u0023", "#", ignoreCase = true)

    fun decodeHtmlEntities(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&")
        .replace("&#x26;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)

    fun decodeUrl(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    fun resolveAgainst(current: String, value: String): String? {
        val decoded = decodeHtmlEntities(value).trim().replace(" ", "%20")
        if (decoded.isBlank()) return null
        return runCatching {
            val target = URI(decoded)
            if (target.isAbsolute) decoded else URI(current).resolve(decoded).toString()
        }.getOrNull()
    }

    fun withoutFragment(value: String): String = value.substringBefore('#')

    fun isRootLike(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty()
        if (path.isEmpty() || path == "/") return true
        if (path == "/lander" || path.startsWith("/lander/")) return true
        if (path == "/error-404.pl" || path.endsWith("/error-404.pl")) return true
        if ((path == "/maps" || path == "/maps/") && uri.rawQuery.isNullOrEmpty()) return true
        return false
    }

}
