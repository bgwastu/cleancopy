package com.cleancopy

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

data class LinkCleanResult(
    val original: String,
    val cleaned: String,
    val removedParameters: List<String>,
    val redirects: List<String>,
    val error: String? = null
) {
    val changed: Boolean get() = original != cleaned
}

data class LinkBatchResult(val text: String, val links: List<LinkCleanResult>)

data class LinkRuleProvider(
    val name: String,
    val urlPattern: String,
    val rules: List<String> = emptyList(),
    val rawRules: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val redirections: List<String> = emptyList(),
    val referralMarketing: List<String> = emptyList()
)

object LinkSanitizer {
    private val urlPattern = Pattern.compile("https?://[^\\s<>()\\[\\]{}\\\"]+", Pattern.CASE_INSENSITIVE)
    private val trailingPunctuation = ".,;:!?"
    private val fallbackRules = listOf("utm_.*", "fbclid", "gclid", "dclid", "msclkid", "mc_[a-z]+", "_ga")

    fun containsLink(text: String): Boolean = urlPattern.matcher(text).find()

    fun cleanText(
        input: String,
        providers: List<LinkRuleProvider>,
        removeReferrals: Boolean,
        resolver: ((String) -> LinkCleanResult)? = null
    ): LinkBatchResult {
        val matches = urlPattern.matcher(input)
        val replacements = mutableListOf<Triple<Int, Int, LinkCleanResult>>()
        while (matches.find()) {
            val raw = matches.group()
            val link = raw.trimEnd { it in trailingPunctuation }
            if (link.isBlank()) continue
            val resolved = resolver?.invoke(link)
            val cleaned = cleanUrl(resolved?.cleaned ?: link, providers, removeReferrals)
            val result = cleaned.copy(
                original = link,
                redirects = (resolved?.redirects.orEmpty() + cleaned.redirects),
                error = resolved?.error ?: cleaned.error
            )
            replacements += Triple(matches.start(), matches.start() + link.length, result)
        }
        val text = StringBuilder(input)
        replacements.asReversed().forEach { (start, end, result) -> text.replace(start, end, result.cleaned) }
        return LinkBatchResult(text.toString(), replacements.map { it.third })
    }

    fun cleanUrl(input: String, providers: List<LinkRuleProvider>, removeReferrals: Boolean): LinkCleanResult {
        val parsed = runCatching { URI(input) }.getOrNull()
            ?: return LinkCleanResult(input, input, emptyList(), emptyList(), "Not a valid URL")
        if (parsed.scheme?.lowercase(Locale.US) !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            return LinkCleanResult(input, input, emptyList(), emptyList(), "Only HTTP and HTTPS links can be cleaned")
        }

        var current = input
        val removed = linkedSetOf<String>()
        val redirects = mutableListOf<String>()
        repeat(MAX_REDIRECT_HOPS) {
            val matching = providers.filter { matches(it.urlPattern, current) }
            val redirect = matching.asSequence()
                .filterNot { provider -> provider.exceptions.any { matches(it, current) } }
                .flatMap { provider -> provider.redirections.asSequence().map { provider.name to it } }
                .mapNotNull { (name, rule) ->
                    runCatching { Pattern.compile(rule).matcher(current) }.getOrNull()
                        ?.takeIf { it.find() && it.groupCount() >= 1 }
                        ?.group(1)
                        ?.let { value -> name to decodeUrl(value) }
                }
                .firstOrNull { (_, target) -> isHttpUrl(target) }
            if (redirect != null && redirect.second != current) {
                redirects += redirect.first
                current = redirect.second
                return@repeat
            }

            current = stripParameters(current, fallbackRules, removed)
            matching.forEach { provider ->
                if (provider.exceptions.any { matches(it, current) }) return@forEach
                current = stripParameters(current, provider.rules, removed)
                if (removeReferrals) current = stripParameters(current, provider.referralMarketing, removed)
                provider.rawRules.forEach { rawRule ->
                    current = runCatching { Pattern.compile(rawRule).matcher(current).replaceAll("") }.getOrDefault(current)
                }
            }
            return LinkCleanResult(input, current, removed.toList(), redirects)
        }
        return LinkCleanResult(input, current, removed.toList(), redirects, "Redirect chain limit reached")
    }

    private fun stripParameters(url: String, rules: List<String>, removed: MutableSet<String>): String {
        if (rules.isEmpty()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val query = uri.rawQuery ?: return url
        val patterns = rules.mapNotNull { runCatching { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }.getOrNull() }
        if (patterns.isEmpty()) return url
        val kept = query.split("&").filter { part ->
            val rawName = part.substringBefore('=')
            val name = runCatching { URLDecoder.decode(rawName, Charsets.UTF_8.name()) }.getOrDefault(rawName)
            val shouldRemove = patterns.any { it.matcher(name).matches() }
            if (shouldRemove) removed += name
            !shouldRemove
        }
        if (kept.size == query.split("&").size) return url
        val base = url.substringBefore('?')
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    private fun decodeUrl(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    private fun matches(pattern: String, value: String): Boolean = runCatching {
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value).find()
    }.getOrDefault(false)

    private fun isHttpUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme?.lowercase(Locale.US) in setOf("http", "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)

    private const val MAX_REDIRECT_HOPS = 5
}

object LinkCleanupStore {
    private const val PREFS = "link_cleanup"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

object LinkRuleStore {
    private val catalogSources = listOf(
        CatalogSource(
            rulesUrl = "https://rules2.clearurls.xyz/data.minify.json",
            hashUrl = "https://rules2.clearurls.xyz/rules.minify.hash"
        ),
        // ClearURLs publishes the same catalog through independent GitHub and GitLab Pages hosts.
        CatalogSource(
            rulesUrl = "https://rules1.clearurls.xyz/data.minify.json",
            hashUrl = "https://rules1.clearurls.xyz/rules.minify.hash"
        )
    )
    private const val RULES_FILE = "clearurls-rules.json"
    private const val MAX_BYTES = 1_000_000
    private const val MIN_PROVIDERS = 20

    fun providers(context: Context): List<LinkRuleProvider> = runCatching {
        val file = File(context.filesDir, RULES_FILE)
        if (file.exists()) parse(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun update(context: Context): Int {
        var lastError: Throwable? = null
        catalogSources.forEach { source ->
            try {
                val rules = download(source.rulesUrl)
                val expectedHash = download(source.hashUrl).trim().lowercase(Locale.US).substringBefore(' ')
                require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "The rule checksum was invalid" }
                require(sha256(rules) == expectedHash) { "The downloaded rules did not match their checksum" }
                val providers = parse(rules)
                require(providers.size >= MIN_PROVIDERS) { "The downloaded ruleset was incomplete" }
                File(context.filesDir, RULES_FILE).writeText(rules)
                return providers.size
            } catch (error: Throwable) {
                lastError = error
            }
        }
        return try {
            val providers = updateFromGithub()
            File(context.filesDir, RULES_FILE).writeText(providers.first)
            providers.second
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Could not reach link-cleaning updates. Check your connection and try again.",
                error.takeIf { lastError == null } ?: lastError
            )
        }
    }

    private fun download(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.inputStream.bufferedReader().use { reader ->
            val value = reader.readText()
            require(value.toByteArray().size <= MAX_BYTES) { "The ruleset was too large" }
            return value
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun updateFromGithub(): Pair<String, Int> {
        val response = JSONObject(download("https://api.github.com/repos/ClearURLs/Rules/contents/data.min.json"))
        val bytes = Base64.decode(response.getString("content"), Base64.DEFAULT)
        val expectedSha = response.getString("sha")
        val header = "blob ${bytes.size}\u0000".toByteArray()
        val actualSha = MessageDigest.getInstance("SHA-1")
            .digest(header + bytes)
            .joinToString("") { "%02x".format(it) }
        require(actualSha == expectedSha) { "The GitHub ruleset did not match its repository hash" }
        val rules = bytes.toString(Charsets.UTF_8)
        val providers = parse(rules)
        require(providers.size >= MIN_PROVIDERS) { "The downloaded ruleset was incomplete" }
        return rules to providers.size
    }

    private fun parse(raw: String): List<LinkRuleProvider> {
        val providers = JSONObject(raw).getJSONObject("providers")
        return providers.keys().asSequence().mapNotNull { name ->
            val item = providers.optJSONObject(name) ?: return@mapNotNull null
            val pattern = item.optString("urlPattern").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LinkRuleProvider(
                name = name,
                urlPattern = pattern,
                rules = item.strings("rules"),
                rawRules = item.strings("rawRules"),
                exceptions = item.strings("exceptions"),
                redirections = item.strings("redirections"),
                referralMarketing = item.strings("referralMarketing")
            )
        }.toList()
    }

    private fun JSONObject.strings(name: String): List<String> = optJSONArray(name)?.strings().orEmpty()
    private fun JSONArray.strings(): List<String> = buildList { repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) } }

    private data class CatalogSource(val rulesUrl: String, val hashUrl: String)
}

object NetworkRedirectResolver {
    fun resolve(url: String): LinkCleanResult {
        var current = url
        val hops = mutableListOf<String>()
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            var code = runCatching { connection.responseCode }.getOrElse {
                return LinkCleanResult(url, current, emptyList(), hops, it.message)
            }
            if (code == HttpURLConnection.HTTP_BAD_METHOD || code == HttpURLConnection.HTTP_NOT_IMPLEMENTED) {
                connection.disconnect()
                val getConnection = URL(current).openConnection() as HttpURLConnection
                getConnection.connectTimeout = 10_000
                getConnection.readTimeout = 15_000
                getConnection.instanceFollowRedirects = false
                code = runCatching { getConnection.responseCode }.getOrElse {
                    return LinkCleanResult(url, current, emptyList(), hops, it.message)
                }
                val location = if (code in 300..399) getConnection.getHeaderField("Location") else null
                getConnection.disconnect()
                if (location.isNullOrBlank()) return LinkCleanResult(url, current, emptyList(), hops)
                val next = URI(current).resolve(location).toString()
                if (!isHttpNetworkUrl(next)) return LinkCleanResult(url, current, emptyList(), hops, "Redirected to an unsupported URL")
                hops += Uri.parse(current).host.orEmpty()
                current = next
                return@repeat
            }
            val location = if (code in 300..399) connection.getHeaderField("Location") else null
            connection.disconnect()
            if (location.isNullOrBlank()) return LinkCleanResult(url, current, emptyList(), hops)
            val next = URI(current).resolve(location).toString()
            if (!isHttpNetworkUrl(next)) return LinkCleanResult(url, current, emptyList(), hops, "Redirected to an unsupported URL")
            hops += Uri.parse(current).host.orEmpty()
            current = next
        }
        return LinkCleanResult(url, current, emptyList(), hops, "Network redirect limit reached")
    }

    private fun isHttpNetworkUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme?.lowercase(Locale.US) in setOf("http", "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)
}
