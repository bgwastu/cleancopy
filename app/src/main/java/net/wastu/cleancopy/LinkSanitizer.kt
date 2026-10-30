package net.wastu.cleancopy

import android.content.Context
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
    val error: String? = null,
    val expandedFrom: String? = null
) {
    val changed: Boolean get() = original != cleaned
}

data class LinkBatchResult(val text: String, val links: List<LinkCleanResult>)

data class PathRewrite(val pattern: String, val replacement: String)

data class LinkRuleProvider(
    val name: String,
    val urlPattern: String,
    val rules: List<String> = emptyList(),
    val rawRules: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val redirections: List<String> = emptyList(),
    val referralMarketing: List<String> = emptyList(),
    // Local-only extensions. ClearURLs JSON leaves these empty.
    val keepParams: List<String> = emptyList(),
    val resetQueryOnPaths: List<String> = emptyList(),
    val canonicalPaths: List<PathRewrite> = emptyList()
)

object LinkSanitizer {
    private val urlPattern = Pattern.compile("https?://[^\\s<>\\[\\]{}\\\"]+", Pattern.CASE_INSENSITIVE)
    private val trailingPunctuation = ".,;:!?"
    private val fallbackRules = listOf(
        "utm_.*",
        "fbclid",
        "gclid",
        "gclsrc",
        "dclid",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "_ga",
        "_gl",
        "yclid",
        "twclid",
        "zanpid",
        "_hsenc",
        "_hsmi"
    )
    private val socialRules = listOf(
        LinkRuleProvider("Facebook", "^https?://(?:[a-z]+\\.)?facebook\\.com/", rules = listOf("rdid", "share_url", "mibextid", "sfnsn", "paipv", "eav", "extid")),
        LinkRuleProvider("Instagram", "^https?://(?:www\\.)?instagram\\.com/", rules = listOf("igsh", "igshid")),
        LinkRuleProvider("YouTube", "^https?://(?:www\\.)?(?:youtube\\.com|youtu\\.be)/", rules = listOf("si")),
        LinkRuleProvider("TikTok", "^https?://(?:[a-z]+\\.)?tiktok\\.com/", rules = listOf("_t", "_r", "tt_from", "share_app_id", "share_link_id", "share_item_id", "is_from_webapp", "sender_device", "sender_web_id"))
    )

    private fun trimUrl(raw: String): String {
        var value = raw.trimEnd { it in trailingPunctuation }
        while (value.endsWith(')') && value.count { it == ')' } > value.count { it == '(' }) {
            value = value.dropLast(1).trimEnd { it in trailingPunctuation }
        }
        return value
    }

    fun containsLink(text: String): Boolean = urlPattern.matcher(text).find()

    fun firstLink(text: String): String? {
        val match = urlPattern.matcher(text)
        if (!match.find()) return null
        return trimUrl(match.group())
    }

    fun cleanText(
        input: String,
        providers: List<LinkRuleProvider>,
        removeReferrals: Boolean,
        resolver: ((String) -> LinkCleanResult)? = null
    ): LinkBatchResult {
        val matches = urlPattern.matcher(input)
        val replacements = mutableListOf<Triple<Int, Int, LinkCleanResult>>()
        val expansions = mutableMapOf<String, LinkCleanResult>()
        val deadline = System.nanoTime() + EXPANSION_BUDGET_NANOS
        while (matches.find()) {
            val raw = matches.group()
            val link = trimUrl(raw)
            if (link.isBlank()) continue
            val local = cleanUrl(link, providers, removeReferrals)
            val resolved = resolver?.let { resolve ->
                expansions[link] ?: run {
                    if (System.nanoTime() >= deadline) {
                        LinkCleanResult(link, link, emptyList(), emptyList(), "Short-link expansion timed out")
                    } else {
                        runCatching { resolve(link) }.getOrElse {
                            LinkCleanResult(link, link, emptyList(), emptyList(), "Could not expand the shortened link")
                        }
                    }
                }.also { expansions[link] = it }
            }
            val target = resolved?.takeIf { it.expandedFrom != null }?.cleaned ?: local.cleaned
            val cleaned = cleanUrl(target, providers, removeReferrals)
            val result = cleaned.copy(
                original = link,
                removedParameters = local.removedParameters + resolved?.removedParameters.orEmpty() + cleaned.removedParameters,
                redirects = local.redirects + resolved?.redirects.orEmpty() + cleaned.redirects,
                expandedFrom = resolved?.expandedFrom,
                error = local.error ?: resolved?.error ?: cleaned.error
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
        val removed = mutableListOf<String>()
        val redirects = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECT_HOPS) {
            if (!visited.add(current)) return LinkCleanResult(input, current, removed, redirects, "Redirect loop detected")
            val matching = (providers + socialRules).filter { matches(it.urlPattern, current) }
            // Exceptions and signed URLs must be checked before even the fallback rules.
            if (matching.any { provider -> provider.exceptions.any { matches(it, current) } } || isSignedUrl(current)) {
                return LinkCleanResult(input, current, removed, redirects)
            }
            val redirect = matching.asSequence()
                .filterNot { provider -> provider.exceptions.any { matches(it, current) } }
                .flatMap { provider -> provider.redirections.asSequence().map { provider.name to it } }
                .mapNotNull { (name, rule) ->
                    runCatching { Pattern.compile(rule).matcher(current) }.getOrNull()
                        ?.takeIf { it.find() && it.groupCount() >= 1 }
                        ?.group(1)
                        ?.let { value -> name to resolveRedirect(current, value) }
                }
                .firstOrNull { (_, target) -> target != null && isHttpUrl(target) }
            if (redirect != null) {
                val target = redirect.second
                if (target != null && target != current) {
                    redirects += redirect.first
                    current = target
                    return@repeat
                }
            }

            matching.forEach { provider -> current = applyCanonicalPaths(current, provider) }
            current = stripParameters(current, fallbackRules, removed)
            matching.forEach { provider ->
                if (provider.exceptions.any { matches(it, current) }) return@forEach
                current = stripProviderParameters(current, provider, removeReferrals, removed)
                provider.rawRules.forEach { rawRule ->
                    current = runCatching { Pattern.compile(rawRule).matcher(current).replaceAll("") }.getOrDefault(current)
                }
            }
            if (current.endsWith("#")) {
                current = current.removeSuffix("#")
            }
            return LinkCleanResult(input, current, removed.toList(), redirects)
        }
        return LinkCleanResult(input, current, removed.toList(), redirects, "Redirect chain limit reached")
    }

    private fun stripParameters(url: String, rules: List<String>, removed: MutableList<String>): String =
        stripQuery(url, rules, resetAll = false, keep = emptyList(), removed = removed)

    private fun stripProviderParameters(
        url: String,
        provider: LinkRuleProvider,
        removeReferrals: Boolean,
        removed: MutableList<String>
    ): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val path = uri.path.orEmpty()
        val resetAll = provider.resetQueryOnPaths.any { matches(it, path) }
        val rules = if (removeReferrals) provider.rules + provider.referralMarketing else provider.rules
        if (!resetAll && rules.isEmpty()) return url
        return stripQuery(url, rules, resetAll, provider.keepParams, removed)
    }

    private fun stripQuery(
        url: String,
        rules: List<String>,
        resetAll: Boolean,
        keep: List<String>,
        removed: MutableList<String>
    ): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (!resetAll && rules.isEmpty()) return url
        val removePatterns = rules.mapNotNull(::compilePattern)
        val keepPatterns = keep.mapNotNull(::compilePattern)
        fun shouldRemove(name: String): Boolean {
            if (keepPatterns.any { it.matcher(name).matches() }) return false
            if (resetAll) return true
            return removePatterns.any { it.matcher(name).matches() }
        }
        fun strip(query: String): String = query.split("&").filter { part ->
            val rawName = part.substringBefore('=')
            val name = runCatching { URLDecoder.decode(rawName, Charsets.UTF_8.name()) }.getOrDefault(rawName)
            val remove = shouldRemove(name)
            if (remove) removed += name
            !remove
        }.joinToString("&")
        val query = uri.rawQuery?.let(::strip)
        val fragment = uri.rawFragment?.let { value ->
            when {
                '?' in value -> value.substringBefore('?') + strip(value.substringAfter('?')).let { if (it.isEmpty()) "" else "?$it" }
                '=' in value && !value.startsWith('/') -> strip(value)
                else -> value
            }
        }
        val base = url.substringBefore('#').let { if (uri.rawQuery != null) it.substringBefore('?') else it }
        return base + query?.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty() +
            fragment?.takeIf(String::isNotEmpty)?.let { "#$it" }.orEmpty()
    }

    private fun applyCanonicalPaths(url: String, provider: LinkRuleProvider): String {
        if (provider.canonicalPaths.isEmpty()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val originalPath = uri.rawPath.orEmpty()
        var path = originalPath
        provider.canonicalPaths.forEach { rewrite ->
            path = runCatching { Pattern.compile(rewrite.pattern).matcher(path).replaceFirst(rewrite.replacement) }
                .getOrDefault(path)
        }
        if (path == originalPath) return url
        val authority = uri.rawAuthority ?: return url
        return buildString {
            append(uri.scheme).append("://").append(authority).append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    private fun compilePattern(pattern: String): Pattern? =
        runCatching { Pattern.compile(pattern, Pattern.CASE_INSENSITIVE) }.getOrNull()

    private fun resolveRedirect(current: String, value: String): String? {
        val decoded = decodeUrl(value).replace(" ", "%20")
        return runCatching {
            val target = URI(decoded)
            if (target.isAbsolute) decoded else URI(current).resolve(decoded).toString()
        }.getOrNull()
    }

    private fun isSignedUrl(url: String): Boolean = URI(url).rawQuery.orEmpty().split('&').any {
        decodeUrl(it.substringBefore('=')).lowercase(Locale.US) in setOf("x-amz-signature", "x-goog-signature", "signature", "sig", "oauth_signature")
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
    private const val EXPANSION_BUDGET_NANOS = 8_000_000_000L
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

    fun providers(context: Context): List<LinkRuleProvider> {
        val downloaded = runCatching { parse(File(context.filesDir, RULES_FILE).readText()) }.getOrNull()
        val base = if (downloaded != null && downloaded.size >= MIN_PROVIDERS) downloaded
        else context.assets.open(RULES_FILE).bufferedReader().use { parse(it.readText()) }
        return merge(base, LocalLinkRules.providers)
    }

    internal fun merge(base: List<LinkRuleProvider>, extra: List<LinkRuleProvider>): List<LinkRuleProvider> {
        if (extra.isEmpty()) return base
        val merged = LinkedHashMap<String, LinkRuleProvider>()
        base.forEach { merged[it.name] = it }
        extra.forEach { local ->
            val existing = merged[local.name]
            merged[local.name] = if (existing == null) local else existing.copy(
                rules = (existing.rules + local.rules).distinct(),
                rawRules = (existing.rawRules + local.rawRules).distinct(),
                exceptions = (existing.exceptions + local.exceptions).distinct(),
                redirections = (existing.redirections + local.redirections).distinct(),
                referralMarketing = (existing.referralMarketing + local.referralMarketing).distinct(),
                keepParams = (existing.keepParams + local.keepParams).distinct(),
                resetQueryOnPaths = (existing.resetQueryOnPaths + local.resetQueryOnPaths).distinct(),
                canonicalPaths = (existing.canonicalPaths + local.canonicalPaths).distinct()
            )
        }
        return merged.values.toList()
    }

    fun updateIfStale(context: Context) {
        val file = File(context.filesDir, RULES_FILE)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7 * 24 * 60 * 60 * 1000L) {
            runCatching { update(context) } // Bundled rules remain available offline.
        }
    }

    private fun persist(context: Context, rules: String) {
        val temporary = File(context.filesDir, "$RULES_FILE.tmp")
        temporary.writeText(rules)
        check(temporary.renameTo(File(context.filesDir, RULES_FILE))) { "Could not store the updated rules" }
    }

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
                persist(context, rules)
                return providers.size
            } catch (error: Throwable) {
                lastError = error
            }
        }
        return try {
            val providers = updateFromGithub()
            persist(context, providers.first)
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
        try {
            connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(MAX_BYTES)
                return bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
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

    internal fun parse(raw: String): List<LinkRuleProvider> {
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
                referralMarketing = item.strings("referralMarketing"),
                keepParams = item.strings("keepParams"),
                resetQueryOnPaths = item.strings("resetQueryOnPaths")
            )
        }.toList()
    }

    private fun JSONObject.strings(name: String): List<String> = optJSONArray(name)?.strings().orEmpty()
    private fun JSONArray.strings(): List<String> = buildList { repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) } }

    private data class CatalogSource(val rulesUrl: String, val hashUrl: String)
}
