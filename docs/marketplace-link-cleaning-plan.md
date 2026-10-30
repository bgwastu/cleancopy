# Link cleaning for Google Maps, Shopee, Tokopedia, YouTube — plan

Status: implemented in 1.7.0
Target release: 1.7.0
Scope: link cleaning only (media pipeline untouched)
Platforms in scope: **Google Maps, Shopee, Tokopedia, YouTube**

---

## TL;DR

CleanCopy already has a ClearURLs-based parameter cleaner and a small
`NetworkRedirectResolver`. Both have gaps for the four requested platforms:

1. **Shopee Indonesia** — the bundled provider pattern is
   `shopee\.(com|co\.th|tw)` and **does not match `shopee.co.id`**, so `sp_atk`,
   `xptdk`, `publish_id`, etc. survive today.
2. **Tokopedia** — full links are partially covered (`src`, `trkid`, `whid`), but
   `_branch_match_id` / `_branch_referrer` and `extParam` are not; `tokopedia.link`,
   `tokopedia.app.link`, and `tkp.me` are not in the resolver at all.
3. **Google Maps** — no local rules for `g_ep`, `g_st`, `coh`, `entry`, `sca_esv`;
   `maps.app.goo.gl` and `goo.gl/maps` are not expanded. A **desktop UA returns a dead
   200 HTML shell** for `maps.app.goo.gl`, so Maps needs a mobile UA.
4. **YouTube** — `feature`, `si`, `pp`, `kw`, `gclid` are already removed. Missing:
   `is`, `ab_channel`, embed-referrer params, and `attribution_link` unwrapping.
   `youtu.be` is expanded locally to `youtube.com/watch` (no network request), then the
   normal cleaner removes the share parameters.

The plan is two layers:

- **Layer 1 — local site rules in Kotlin**, merged with (not replaced by) the downloaded
  ClearURLs rules. Adds the four providers plus path-aware query policies and Shopee path
  canonicalisation.
- **Layer 2 — a rewritten short-link resolver** with an allowlist, per-host UA, bounded
  I/O, `intent://`/HTML/JSON parsing, and strict safety checks (never substitute a
  homepage, never follow non-HTTP schemes, never contact arbitrary hosts).

Everything is testable offline with captured fixtures; live-site behaviour is verified by
a manual smoke script, not CI.

---

## 1. What exists today

| Piece | Location | Notes |
|---|---|---|
| URL matching + cleaning | `LinkSanitizer.kt` | `urlPattern`, per-provider `rules`, `rawRules`, `exceptions`, `redirections`, `referralMarketing`, signed-URL guard |
| Rules storage | `LinkRuleStore` in `LinkSanitizer.kt` | downloaded ClearURLs JSON (7-day refresh) or bundled `assets/clearurls-rules.json` (206 providers) |
| Shortener resolver | `NetworkRedirectResolver` in `LinkSanitizer.kt` | 14 global shortener hosts, 5 hops, HEAD (GET for Facebook), `Location`/`Refresh` headers only |
| Call site | `CleaningViewModel.cleanText` | `cleanText(input, providers, removeReferrals = true, resolver = NetworkRedirectResolver::resolve)` |
| Settings | `MainScreen.SettingsPage` | "Clean copied links" switch; supporting text already says shortened links need a connection |
| Tests | `LinkSanitizerTest`, `LinkRegressionTest` | pure JVM tests, baseline green |

Current gaps confirmed by reading the code and the bundled/remote rules:

- `shopee.co.id` is not matched by the Shopee provider.
- The resolver's `User-Agent: CleanCopy/1.5` makes Branch.io return a 200 HTML page for
  `tokopedia.link`; it never reaches the product.
- The resolver rejects `intent://` as "unsupported", which is exactly what Branch and
  Google Maps return to Android UAs.
- A resolved-to-homepage URL is treated as success.
- `LinkRuleStore.providers()` **replaces** the bundled list with the downloaded list, so
  patching only the asset would lose the rules on the next refresh.

---

## 2. Research findings

Evidence levels: ✅ verified live during research · 📚 from ClearURLs / AdGuard URL
Tracking · ⚠️ low confidence, verify in QA.

### 2.1 Google Maps

URL shapes:

```
https://maps.app.goo.gl/<code>?g_st=com.google.maps.preview.copy
https://goo.gl/maps/<code>
https://www.google.com/maps/place/<Name>/@<lat>,<lng>,<z>/data=!3m1!4b1!4m6!…?entry=ttu&g_ep=…
https://www.google.com/maps?q=<query>&hl=en&gl=id
https://www.google.com/maps/dir/?api=1&origin=…&destination=…&travelmode=driving
https://maps.google.com/?cid=<placeId>
```

Short-link behaviour (✅ live):

| Host | Desktop UA | Mobile Android UA |
|---|---|---|
| `maps.app.goo.gl/<code>` | **200 HTML shell, destination not present** | 302 → `google.com/maps/place/…` or `intent://…;S.browser_fallback_url=…` |
| `goo.gl/maps/<code>` | 302 → `google.com/maps/place/…` | 302 → same |

Observed destination params:

```
?coh=219816&entry=tts&g_ep=EgoyMDI0MDgxNC4xKgBIAVAD
?shorturl=1
```

Remove (📚/✅):

- `g_ep` — 📚 AdGuard removes on `google.*/maps*`
- `g_st` — 📚 AdGuard removes on `maps.app.goo.gl`
- `sca_esv` — 📚 AdGuard removes on `google.*/maps`
- `source` — 📚 ClearURLs google provider + AdGuard Maps rule
- `entry` — ✅ observed entry-point marker; safe to remove
- `coh` — ✅ observed on share links; ⚠️ undocumented, verify the place still resolves
- `utm_*` — ✅ Google explicitly encourages UTM params on Maps URLs, so remove
- `gclid`, `fbclid`, `_gl` — already covered globally

Never remove (functional):

- `data` — encodes the place/feature and coordinates (`!3d<lat>!4d<lng>`, `!1s0x…:0x…`)
- `q`, `query`, `ll`, `sll`, `sspn`, `spn`, `z`, `t`, `layer`
- `cid`, `ftid`
- `hl`, `gl`, `authuser`
- `api`, `origin`, `destination`, `waypoints`, `travelmode`, `dir_action`
- `output`, `pb`, `shorturl`, `dg`

Do **not** use "reset query" for Maps — good and bad params are mixed. Explicit remove
list only.

### 2.2 Shopee Indonesia (`shopee.co.id`)

URL shapes:

```
https://shopee.co.id/<slug>-i.<shopId>.<itemId>?sp_atk=…&xptdk=…
https://shopee.co.id/product/<shopId>/<itemId>
https://shopee.co.id/universal-link/product/<shopId>/<itemId>
https://shopee.co.id/universal-link/opaanlp/<shopId>/<itemId>
https://shopee.co.id/opaanlp/<shopId>/<itemId>          # affiliate landing, not the product
```

Remove (📚 AdGuard + ✅ observed in real redirects):

- `sp_atk`, `xptdk`, `publish_id`, `promotionId`, `stm_source`, `stm_medium`
- `smtt`
- Affiliate: `af_siteid`, `af_sub_siteid`, `af_click_lookback`, `af_reengagement_window`,
  `af_viewthrough_lookback`, `af_dp`, `af_sub1..5`
- `mmp_pid`, `uls_trackid`, `gads_t_sig`, `exp_group`, `is_retargeting`,
  `deep_and_deferred`, `d_id`, `pid`, `c`, `v4`
- `__mobile__`, `is_from_login`, `is_from_app`, `share_id`/`shareId`
- Generic `utm_*`, `gclid`, `fbclid`, `_gl`

Short links (✅ live):

| Host | Behaviour | Result |
|---|---|---|
| `shope.ee/<code>` | 301 | `shopee.co.id/universal-link/opaanlp/<shop>/<item>?af_…` |
| `shp.ee/<code>`, `<cc>.shp.ee/<code>` | 307 | product URL, or `shopee.co.id` root for expired codes |
| `s.shopee.co.id/<code>` | 301 with desktop UA; **200 HTML** with Android UA | affiliate landing |

The 200 HTML contains the destination in a JS config:

```html
<script>var CONFIG={
  httpUrl:"https:\/\/shopee.co.id\/opaanlp\/1197851737\/53254262972?__mobile__=1\u0026…",
  …
};ULS.init(CONFIG)</script>
```

Canonicalisation (✅ verified): `/product/<shop>/<item>` is the real product page;
`/opaanlp/…` and `/universal-link/…` are affiliate landing pages.

```
^/(?:universal-link/)?opaanlp/(\d+)/(\d+)(?:/.*)?$   -> /product/$1/$2
^/universal-link/product/(\d+)/(\d+)(?:/.*)?$        -> /product/$1/$2
```

Product pages work with the query string entirely removed (✅), so detail paths can use
"reset query".

### 2.3 Tokopedia (`tokopedia.com`)

URL shapes:

```
https://www.tokopedia.com/<store>/<slug>?utm_source=salinlink&utm_medium=share
https://www.tokopedia.com/<store>/<slug>?trkid=…&whid=…&src=…
```

Remove:

- 📚 ClearURLs: `src`, `trkid`, `whid`
- 📚 AdGuard: `extParam`, `refined`
- ✅ observed: `utm_*`, `_branch_match_id`, `_branch_referrer`
- `refined` is only safe on product pages; on listing/search pages it can carry filter
  state, so do **not** put it in the global rule list — the product-path reset handles it.

Short links (✅ live):

```
tokopedia.link/<code>
  -> 307 https://tokopedia.app.link/<code>?_p=…        (Branch.io)
     desktop UA  -> 307 https://www.tokopedia.com/<store>/<slug>?utm_source=salinlink&…
     Android UA  -> 307 intent://product/<id>?…;S.browser_fallback_url=<encoded tokopedia URL>;…
     unknown UA  -> 200 HTML embedding the tokopedia.com URL
tkp.me/<code>   -> 200 HTML: <script>window.location='https://www.tokopedia.com/…'</script>
                  invalid codes -> window.location='https://www.tokopedia.com/error-404.pl'
```

Consequences:

- Use a **desktop Chrome UA** for Branch links (cleanest 307 to the product).
- Still parse `intent://` `S.browser_fallback_url` (some Branch links force the app path).
- Parse `window.location` for `tkp.me`.
- Reject `error-404.pl` and any bare `www.tokopedia.com` destination.
- `_branch_match_id` / `_branch_referrer` must be stripped (ClearURLs only removes them
  for reddit).

Detail path detection: product URLs are exactly two path segments (`/<store>/<slug>`).
Everything with a known static first segment (`search`, `discovery`, `promo`, `campaign`,
`blog`, `help`, `about`, `p`, `product`, `shop`, `find`, `topads`, `play`, `stream`,
`plus`, `bebas-ongkir`, `cek-ongkir`, `mitra`, `finance`, `seller`, `login`, `register`,
`cart`, `checkout`, `order`, `invoice`, `notifications`, `inbox`, `settings`, `wishlist`,
`official-store`, `mall`, `l`) stays on the explicit-remove list.

### 2.4 YouTube

URL shapes:

```
https://youtu.be/<id>?si=…&is=…&t=42
https://www.youtube.com/watch?v=<id>&list=<playlist>&index=3&t=42&pp=…&feature=share
https://www.youtube.com/shorts/<id>?feature=share&si=…
https://www.youtube.com/@<handle>?si=…
https://www.youtube.com/redirect?q=<encoded URL>&event=video_description
https://www.youtube.com/attribution_link?a=…&u=%2Fwatch%3Fv%3D<id>%26feature%3Dshare
```

Already removed by the bundled/updated rules:

- `feature`, `gclid`, `kw`, `si`, `pp`
- Global `utm_*`, `fbclid`, `_gl`

Add (📚 AdGuard + observed):

- `is` (new share token on `youtu.be`, analogous to `si`)
- `ab_channel`, `ab_channel_id` (share attribution)
- `embeds_referring_euri`, `embeds_referring_origin`, `source_ve_path` (embed tracking)
- `widget_referrer` (embed tracking)

Never remove (functional):

- `v`, `list`, `index`, `t` / `start` (playlist + timestamp)
- `app=desktop`, `hl`, `gl`, `persist_gl`, `persist_hl` (layout/language preferences)
- Embed API params: `enablejsapi`, `origin`, `autoplay`, `mute`, `controls`, `loop`,
  `playlist`, `end`, `iv_load_policy`, `modestbranding`, `rel`, `cc_load_policy`, `fs`

Wrappers (✅ live):

- `youtube.com/redirect?q=…` — already unwrapped offline by the ClearURLs YouTube
  `redirections` rule. Add a regression test.
- `youtube.com/attribution_link?a=…&u=%2Fwatch…` — 303 to the watch URL. Add a local
  redirection rule and resolve root-relative targets (`/watch…`) against the current
  host. Then normal cleaning removes `feature`.
- `youtu.be/<id>` is expanded locally to `https://www.youtube.com/watch?v=<id>`. This
  needs no network request; the normal cleaner then strips `si`, `is`, `pp`, `feature`
  while keeping `t`.

---

## 3. Design

### 3.1 Layer 1: local site rules, merged with ClearURLs

`LinkRuleStore.providers()` becomes:

```kotlin
fun providers(context: Context): List<LinkRuleProvider> {
    val remote = downloadedOrBundled(context)
    return merge(remote, LocalLinkRules.providers)
}
```

`merge` groups by provider name, concatenates lists, de-duplicates. Local providers use
distinct names (`shopee.co.id`, `tokopedia.com`, `google.com/maps`, …) so they never
inherit the wrong `urlPattern`. A local provider named `youtube.com` intentionally merges
into the remote one, appending rules and redirections.

This fixes the "update wipes our rules" problem and keeps ClearURLs as the long tail.

### 3.2 Extend `LinkRuleProvider` with path-aware policy

Add optional fields with defaults (the JSON parser ignores them; local rules use them):

```kotlin
data class PathRewrite(val pattern: String, val replacement: String)

data class LinkRuleProvider(
    val name: String,
    val urlPattern: String,
    val rules: List<String> = emptyList(),
    val rawRules: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val redirections: List<String> = emptyList(),
    val referralMarketing: List<String> = emptyList(),
    // local-only extensions
    val keepParams: List<String> = emptyList(),          // regexes; never removed
    val resetQueryOnPaths: List<String> = emptyList(),   // regexes; on match drop all non-kept params
    val canonicalPaths: List<PathRewrite> = emptyList()  // regex replace on raw path
)
```

Processing order inside `cleanUrl` for each hop:

1. exception / signed-URL guard (unchanged)
2. provider `redirections` (unchanged; extend to resolve root-relative targets)
3. **`canonicalPaths`** on each matching provider
4. fallback rules (unchanged)
5. **provider params with `keepParams` + `resetQueryOnPaths`**
6. `rawRules` (unchanged)
7. fragment cleanup / return

`resetQueryOnPaths` records every dropped parameter in `removedParameters`, so the result
sheet stays truthful. Kept params win over remove rules.

Canonicalisation is a regex `replaceFirst` on `URI.rawPath`, then the URL is rebuilt as
`scheme://rawAuthority + newRawPath + ?rawQuery + #rawFragment`. (Do not use the 5-arg
`URI` constructor: it re-quotes raw components.)

### 3.3 Local rule set (`LocalLinkRules.kt`)

```kotlin
object LocalLinkRules {
    private val shopeeRules = listOf(
        "sp_atk", "xptdk", "publish_id", "promotionId", "stm_source", "stm_medium",
        "smtt", "mmp_pid", "uls_trackid", "gads_t_sig", "exp_group",
        "af_.*", "is_retargeting", "deep_and_deferred", "d_id", "pid", "c", "v4",
        "__mobile__", "is_from_login", "is_from_app", "share_?id"
    )
    private val mapsRules = listOf("g_ep", "g_st", "coh", "entry", "sca_esv")
    private val youtubeRules = listOf(
        "is", "ab_channel", "ab_channel_id",
        "embeds_referring_euri", "embeds_referring_origin", "source_ve_path",
        "widget_referrer"
    )

    val providers = listOf(
        LinkRuleProvider(
            name = "shopee.co.id",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*shopee\\.co\\.id(?:[/:?#]|$)",
            rules = shopeeRules,
            resetQueryOnPaths = listOf(
                "^/product/\\d+/\\d+/?$",
                "^/[^/]+-i\\.\\d+\\.\\d+/?$"
            ),
            canonicalPaths = listOf(
                PathRewrite("^/(?:universal-link/)?opaanlp/(\\d+)/(\\d+)(?:/.*)?$", "/product/$1/$2"),
                PathRewrite("^/universal-link/product/(\\d+)/(\\d+)(?:/.*)?$", "/product/$1/$2")
            )
        ),
        LinkRuleProvider(
            name = "tokopedia.com",
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*tokopedia\\.com(?:[/:?#]|$)",
            rules = listOf("src", "trkid", "whid", "extParam", "_branch_match_id", "_branch_referrer"),
            resetQueryOnPaths = listOf(
                // product pages are /<store>/<slug>; skip known static routes
                "^/(?!(?:search|discovery|promo|campaign|blog|help|about|p|product|shop|find|" +
                    "topads|play|stream|plus|bebas-ongkir|cek-ongkir|mitra|finance|seller|login|" +
                    "register|cart|checkout|order|invoice|notifications|inbox|settings|wishlist|" +
                    "official-store|mall|l)(?:/|$))[^/]+/[^/]+/?$"
            )
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
            name = "youtube.com",                 // merges into the ClearURLs provider
            urlPattern = "^https?://(?:[a-z0-9-]+\\.)*?(youtube\\.com|youtu\\.be)",
            rules = youtubeRules,
            redirections = listOf(
                "^https?://(?:[a-z0-9-]+\\.)*?youtube\\.com/attribution_link\\?.*?u=([^&]*)"
            )
        )
    )
}
```

Notes:

- Shopee detail paths: reset query (verified safe).
- Tokopedia detail paths: reset query with the static-route deny list; the explicit list
  still cleans listing/search URLs.
- Maps: explicit remove only; `data=` is never touched.
- YouTube: explicit remove only; `youtu.be` expands locally to the watch URL first.
- `pid`, `c`, `v4` are short names. They only apply on `shopee.co.id` and are affiliate
  residue there; keep them scoped to that provider.

### 3.4 Layer 2: resolver rewrite

Keep the public entry point (`NetworkRedirectResolver.resolve`) to limit churn, but split
it into:

- `ShortLinkParsers.kt` — pure functions, unit-testable:
  - `parseIntentFallback(location: String): String?`
    - If the captured `S.browser_fallback_url` starts with `http`, return it **as-is**
      (Maps intent fallbacks are already partially encoded; do not double-decode).
    - Otherwise URL-decode once (Branch/Tokopedia encodes the whole URL).
  - `parseMetaRefresh(html: String): String?`
  - `parseJsLocation(html: String): String?`
  - `parseShopeeConfig(html: String): String?` (`"httpUrl":"https:\/\/…\u0026…"`)
  - `parseCanonical(html: String): String?` (`og:url`, `<link rel=canonical>`)
  - `decodeJsUrl`, `decodeHtmlEntities`
  - `isRootLike(url: String): Boolean` (`/`, `/?_r=1`, `/lander`, `error-404.pl`, bare Maps
    root, bare Shopee root)
- `ShortLinkHttp.kt` — injectable HTTP seam:

```kotlin
internal interface ShortLinkHttp {
    fun get(url: String, userAgent: String, timeoutMs: Int, maxBytes: Int): Response
}
internal data class Response(
    val code: Int,
    val location: String?,
    val refresh: String?,
    val contentType: String?,
    val body: String?
)
```

- `NetworkRedirectResolver` — orchestration.

Algorithm:

1. If the URL host is not on the allowlist (or a Facebook share link), return
   `Expansion(finalUrl = url, expanded = false)` with **no network**.
2. Loop up to 5 hops, visited set keyed by URL without fragment:
   - `GET` with the host's UA, `Accept: text/html,…`, 4 s connect/read timeout, body
     capped at 64 KiB. (GET for everything: simplest, and Maps/Branch/Shopee need bodies
     in some UA modes. Bodies are tiny for pure redirectors.)
   - 3xx: follow `Location` (resolve relative). If `Location` is `intent://`, extract
     `S.browser_fallback_url` and continue; otherwise reject.
   - 2xx: try parsers in order: header `Refresh`, meta refresh, JS location, Shopee
     `CONFIG.httpUrl`, canonical/og:url. If a destination is found, continue; otherwise
     stop.
   - 4xx/5xx: error `Could not expand this link (HTTP <code>)`.
3. Stop when the current host is not a shortener/landing host.
4. Validate the final URL:
   - `http`/`https`, host non-blank, no user-info
   - reject `localhost`, IP literals, `.local`, private ranges, `0.0.0.0`
   - if root-like, treat as unresolved and keep the original short link
5. Return:

```kotlin
data class Expansion(
    val finalUrl: String,        // original when unresolved
    val expandedFrom: String?,   // original short URL when expanded
    val hops: List<String>,
    val error: String? = null
)
```

Allowlist (regexes):

```text
^(?:[a-z]{2}\.)?shp\.ee$          # optional region subdomain
^shope\.ee$
^s\.shopee\.co\.id$
^tokopedia\.link$
^tokopedia\.app\.link$
^tkp\.me$
^maps\.app\.goo\.gl$
^goo\.gl$                         # path-gated: only /maps/
# existing generic hosts stay: bit.ly, t.co, tinyurl.com, is.gd, buff.ly, ow.ly,
# rebrand.ly, shorturl.at, amzn.to, lnkd.in, rb.gy, cutt.ly, vt/vm.tiktok.com
```

`goo.gl` is only expanded when the path starts with `/maps/`. `youtu.be` is expanded
locally (no network) by `expandYouTube` before the allowlist is consulted.

### 3.5 Per-host User-Agent

| Host | UA | Why |
|---|---|---|
| `maps.app.goo.gl` | Android Chrome (mobile) | desktop returns a dead 200 shell with no destination |
| `goo.gl` (`/maps/`) | Android Chrome (mobile) | consistent with the above; 302 either way |
| Shopee hosts | Desktop Chrome | `s.shopee.co.id` returns 301; `shope.ee` 301 |
| Tokopedia hosts | Desktop Chrome | Branch returns a direct 307 to the product |
| Generic shorteners | Desktop Chrome | fewer interstitials |
| YouTube | no network | `youtu.be` expands locally; wrappers are unwrapped offline |

UA strings are constants in one place, documented, and covered by resolver tests.

### 3.6 Data flow

```text
cleanText(input)
  for each URL match:
    local  = cleanUrl(link, providers, removeReferrals)
    expand = resolver(link)                       // original, not local.cleaned
    target = expand.takeIf { it.expanded }?.finalUrl ?: local.cleaned
    final  = cleanUrl(target, providers, removeReferrals)
    result = LinkCleanResult(original = link, cleaned = final.cleaned,
                             removedParameters = local + expand + final,
                             redirects = local + expand + final,
                             expandedFrom = expand.expandedFrom,
                             error = local.error ?: expand.error ?: final.error)
  rebuild text, caching expansions per URL (no duplicate requests)
```

Two important changes versus today:

- Pass the **original** link to the resolver (`local.cleaned` can strip a token a
  shortener needs, e.g. `g_st` on a Maps link).
- A failed expansion must keep `local.cleaned`, not the raw original.

Add a per-batch deadline (e.g. 8 s) so several links cannot block the UI for a minute.

### 3.7 UI and settings

- `LinkCleanResult` gains `expandedFrom: String?` (default `null`) and `hops`.
- Result sheet:
  - subtitle gains "N short links expanded"
  - details gain `Expanded maps.app.goo.gl` / `Expanded shope.ee` / `Expanded tokopedia.link`
  - warning only when a **recognized** short link failed; normal links never warn
  - `youtu.be` is reported as an expansion (`Expanded youtu.be`)
- Settings → Links:
  - keep "Clean copied links"
  - add sub-switch "Expand short links" (`ShortLinkExpansionStore`, default on) with
    supporting text: "Contacts known link shorteners to reveal the full link."
- `CleaningViewModel` passes the resolver only when both switches are on.

---

## 4. Implementation phases

### Phase 0 — fixtures and failing tests (0.5 day)

Add real captured fixtures and confirm they fail against today's code:

- Shopee `.co.id` with `sp_atk`/`xptdk`
- Shopee affiliate redirect (`shope.ee` → `/universal-link/opaanlp/…`)
- Tokopedia salinlink + `_branch_match_id`/`_branch_referrer`
- Google Maps place URL with `coh`/`entry`/`g_ep`, plus a `maps.app.goo.gl` redirect
- YouTube `si`/`is`/`pp`/`feature` and `attribution_link`

### Phase 1 — rules layer (1 day)

- Extend `LinkRuleProvider` + `stripParameters` (`keepParams`, `resetQueryOnPaths`,
  `canonicalPaths`).
- Add `LocalLinkRules.kt` and `LinkRuleStore.merge`.
- Parse the new optional fields in `LinkRuleStore.parse` (forward compatible).
- Extend redirection resolution to accept root-relative targets (YouTube attribution).
- Make `MarketplaceRulesTest` green; keep existing tests green.

### Phase 2 — resolver (1 day)

- Add `ShortLinkParsers.kt` + pure tests with captured HTML/intent fixtures.
- Add `ShortLinkHttp.kt` seam + `HttpUrlConnection` implementation (per-host UA,
  timeouts, 64 KiB cap, no cookie handler changes).
- Rewrite `NetworkRedirectResolver` around the allowlist, parsers, root guard, SSRF guard,
  hop limit, deadline.
- Update `LinkSanitizer.cleanText` to use `Expansion`, dedupe per batch, pass the original
  link.
- Tests with a fake HTTP client: chain, intent, HTML landing, root rejection, 404, loop,
  non-HTTP destination, private IP, deadline, per-host UA.

### Phase 3 — UI/settings/docs (0.5 day)

- `LinkCleanResult.expandedFrom`, subtitle/details/warning.
- `ShortLinkExpansionStore`, Settings sub-switch, `MainActivity`/`MainScreen` plumbing.
- `CleaningViewModel` wiring + enable/disable behaviour.
- `README.md`: the four supported platforms, short-link expansion, privacy note.
- `docs/link-cleaning.md`: supported hosts, what is removed, how to add a site, known
  limitations (expired links, Maps `coh`, YouTube `youtu.be` is not expanded).
- `scripts/link-smoke.sh` (manual, no CI).
- `./gradlew lintDebug testDebugUnitTest assembleDebug`; manual QA.
- Bump `releaseVersion` to `1.7.0`; release notes.

Total: ~3 days.

---

## 5. Test plan

### Unit tests (JVM, no network)

`MarketplaceRulesTest`

- Shopee `.co.id` regression: `sp_atk`/`xptdk` removed (today: fail).
- Shopee affiliate params (`af_*`, `mmp_pid`, `uls_trackid`, `gads_t_sig`, `smtt`,
  `__mobile__`, …) removed.
- Shopee canonicalisation: `/universal-link/opaanlp/1/2` → `/product/1/2`, query gone;
  `/<slug>-i.1.2` keeps its path, query gone.
- Shopee listing URL keeps `keyword`, `page`, `sortBy`.
- Tokopedia salinlink cleaned; `_branch_*` removed.
- Tokopedia listing URL keeps `st`, `q`, `page`; `src` removed; `refined` kept.
- Google Maps: `g_ep`, `g_st`, `coh`, `entry`, `sca_esv`, `utm_*` removed;
  `data`, `cid`, `ftid`, `q`, `ll`, `z`, `hl`, `gl`, `origin`, `destination`,
  `travelmode` preserved.
- Google Maps direction/embed URLs keep functional params.
- YouTube: `si`, `is`, `pp`, `feature`, `ab_channel`, embed-referrer params removed;
  `v`, `list`, `index`, `t`, `app`, `hl` preserved.
- YouTube `attribution_link` unwraps to the watch URL, then cleans `feature`.
- YouTube `redirect?q=` unwraps to the external URL.
- Idempotence: `clean(clean(url)) == clean(url)`.
- `removedParameters` exactly matches the dropped names (no duplicates lost).

`ShortLinkParsersTest`

- Tokopedia `intent://…;S.browser_fallback_url=<fully encoded>;…` → decoded https URL.
- Google Maps `intent://…;S.browser_fallback_url=https://www.google.com/maps/place/…;…`
  → returned **without** double-decoding `%2B`.
- Shopee `CONFIG.httpUrl` with `\/` and `\u0026` escapes.
- `tkp.me` `window.location='…'` and error-404 page.
- `isRootLike` for `/`, `/?_r=1`, `/lander`, `error-404.pl`, `https://shopee.co.id`,
  `https://www.google.com/maps`.
- HTML entity and JS escape decoding.

`ShortLinkResolverTest` (fake `ShortLinkHttp`)

- 301 → 307 → 200 product: expanded (Shopee).
- `tokopedia.link` → `app.link` → intent fallback: expanded.
- `maps.app.goo.gl` with mobile-UA 302: expanded; assert the request used the mobile UA.
- `s.shopee.co.id` with desktop-UA 301: expanded; assert the request used the desktop UA.
- 200 with no recognisable destination: unresolved.
- Root destination: keep original, warning.
- 404 / 500: keep original, warning.
- Loop: detected.
- `intent://` without fallback, `javascript:`, `data:`, `file:`: rejected.
- `http://127.0.0.1/`, `http://192.168.1.1/`, `http://localhost/`: rejected.
- Hop limit and deadline.

### Manual QA checklist

1. Shopee product link with `sp_atk`/`xptdk` → clean; opens the product.
2. `https://shope.ee/…` → expanded product link, no `af_*`/`utm_*`.
3. `https://s.shopee.co.id/…` → expanded product link.
4. `https://tokopedia.link/…` → expanded `tokopedia.com` link, no `_branch_*`.
5. Tokopedia salinlink → clean; opens the product.
6. `https://maps.app.goo.gl/…` → expanded place link; **pin still opens the correct place**
   with `data=` intact; `coh`/`entry`/`g_ep`/`g_st` gone.
7. `https://goo.gl/maps/…` → same.
8. Maps directions link (`/maps/dir/?api=1&origin=…&destination=…`) → params preserved.
9. `https://youtu.be/<id>?si=…&is=…&t=42` → `https://www.youtube.com/watch?v=<id>&t=42`.
10. YouTube watch URL with `pp`, `feature`, `ab_channel` → cleaned; `list`/`index` kept.
11. `youtube.com/redirect?q=…` → external URL.
12. Airplane mode: local cleaning still works, short links warn, no crash.
13. Settings: turning "Expand short links" off performs no network requests.

---

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Rules rot as sites change | Conservative lists + fixtures + manual smoke script; ClearURLs keeps the long tail |
| A functional param gets removed (Maps `data`, YouTube `list`) | Explicit remove lists, never reset Maps/YouTube queries; fixtures assert preservation |
| Maps desktop UA returns a dead page | Per-host mobile UA for `maps.app.goo.gl`/`goo.gl` |
| Maps `coh` turns out functional | ⚠️ Remove only after QA confirms the place still resolves; drop from the list if not |
| Shortener returns a homepage / error page | Root-like guard; keep the original link and warn |
| Privacy: shorteners are contacted | Allowlist only, opt-out switch, docs; never contact arbitrary hosts |
| SSRF / localhost redirects | Reject non-HTTP schemes, IP literals, private ranges, `.local` |
| Slow or hanging requests | 4 s timeouts, 5 hops, 64 KiB cap, 8 s batch deadline, cancellation |
| Custom UA breaks sites | Per-host UA constants, documented and tested |
| Rules refresh overwrites local rules | Merge by provider name instead of replacing |
| Intent fallback double-decoded | Return already-absolute URLs as-is; decode only fully encoded values |

---

## 7. Decisions (confirmed)

1. **Expansion default**: on by default with an opt-out switch.
2. **Shopee path canonicalisation**: `/opaanlp/…` and `/universal-link/…` are rewritten to
   `/product/<shop>/<id>`.
3. **Google Maps `coh`**: removed with the other share parameters; QA confirms the place
   still resolves.
4. **YouTube `youtu.be`**: expanded to `https://www.youtube.com/watch?v=<id>`.

---

## 8. Definition of done

- [x] `shopee.co.id` tracking params are removed (regression test).
- [x] Shopee/Tokopedia/Maps/YouTube fixtures produce clean, working links.
- [x] `shope.ee`, `shp.ee`, `s.shopee.co.id`, `tokopedia.link`, `tkp.me`,
      `maps.app.goo.gl`, `goo.gl/maps` expand via redirect, intent fallback, or HTML
      landing, with root/error-page protection.
- [x] Maps `data=` and YouTube functional params are never removed.
- [x] No network request when "Expand short links" is off, or for non-shortener hosts.
- [x] Result sheet reports expansions and failed expansions.
- [x] `lintDebug`, `testDebugUnitTest`, `assembleDebug` pass.
- [x] README + `docs/link-cleaning.md` updated, version bumped to 1.7.0.
