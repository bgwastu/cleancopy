# Link cleaning

CleanCopy removes tracking and share parameters from links and expands known short links.
It works on the text currently in the clipboard, after you choose **Clean copied links**
in the app. Link cleaning is enabled by default.

## Supported sites

| Site | What is cleaned | Short links |
|---|---|---|
| Google Maps | `g_ep`, `g_st`, `coh`, `entry`, `sca_esv`, `source`, `utm_*`, `gclid`, `fbclid` | `maps.app.goo.gl`, `goo.gl/maps` |
| Shopee Indonesia | `sp_atk`, `xptdk`, `publish_id`, `promotionId`, `stm_source`, `stm_medium`, `smtt`, `af_*`, `mmp_pid`, `uls_trackid`, `gads_t_sig`, `exp_group`, `is_retargeting`, `deep_and_deferred`, `d_id`, `pid`, `c`, `v4`, `__mobile__`, `is_from_login`, `is_from_app`, `share_id`, `utm_*` | `shope.ee`, `shp.ee`, `*.shp.ee`, `s.shopee.co.id` |
| Tokopedia | `src`, `trkid`, `whid`, `extParam`, `_branch_match_id`, `_branch_referrer`, `utm_*` | `tokopedia.link`, `tokopedia.app.link`, `tkp.me` |
| YouTube | `feature`, `si`, `is`, `pp`, `kw`, `ab_channel`, `ab_channel_id`, `embeds_referring_euri`, `embeds_referring_origin`, `source_ve_path`, `widget_referrer`, `gclid`, `utm_*` | `youtu.be` is expanded to `youtube.com/watch` |

ClearURLs rules cover the long tail of other sites. Those rules are downloaded and merged
with the site rules shipped in the app, so an update cannot remove the local rules.

## What is deliberately kept

Some parameters are functional and are never removed:

- **Google Maps:** `data` (place and coordinates), `q`/`query`, `ll`, `z`, `cid`, `ftid`,
  `hl`/`gl`, `api`, `origin`, `destination`, `travelmode`, `layer`, `output`, `pb`.
- **YouTube:** `v`, `list`, `index`, `t`/`start`, `app`, `hl`/`gl`, and embed API
  parameters such as `autoplay`, `mute`, `controls`, `loop`, `playlist`, `start`, `end`.
- **Shopee:** search and listing parameters such as `keyword`, `page`, `sortBy`.
- **Tokopedia:** search and listing parameters such as `st`, `q`, `page`, `refined`.

Shopee product pages and Tokopedia product pages drop their whole query string. Search and
listing pages only lose known tracking parameters.

## How short links work

Only known shortener hosts are contacted. Everything else is left untouched without a
network request.

- Shopee and Tokopedia short links are followed through their HTTP redirects. Tokopedia
  uses Branch.io, so the resolver also understands `intent://` links and
  `S.browser_fallback_url`.
- Google Maps short links need a mobile browser User-Agent; with a desktop UA Google
  returns a page that does not contain the destination.
- `tkp.me` and some Shopee affiliate links resolve through a small HTML landing page. The
  resolver reads at most 64 KiB and parses the destination without running JavaScript.
- `youtu.be/<id>` is expanded locally to `https://www.youtube.com/watch?v=<id>` with no
  network request.

Safety rules:

- Only `http` and `https` destinations are followed.
- A redirect to a bare homepage or an error page is rejected; the original short link is
  kept and the result sheet shows a warning.
- Redirects to localhost, IP literals, and private network ranges are rejected.
- Requests use 4-second timeouts, a five-hop limit, and an eight-second budget per batch.
- Short links are not expanded when **Expand short links** is off in Settings.

## Privacy

Local parameter cleaning needs no connection. Short-link expansion contacts the shortener
host (the same service the link points to) to reveal the destination. CleanCopy does not
send the clipboard text, only the individual short link being expanded, and only for
allowlisted hosts.

## Known limitations

- Expired Shopee or Tokopedia short links can resolve to a homepage or a 404 error page;
  CleanCopy keeps the original link and shows a warning instead of substituting a wrong
  page.
- TikTok sometimes refuses server-side expansion and returns its homepage. This is
  detected and treated as a failed expansion. (TikTok is outside the current shortcut set
  but is still cleaned as a normal link through ClearURLs.)
- Google Maps `coh` is not documented by Google. It is removed with the other share
  parameters, but if a place link ever fails to open, remove `coh` from `mapsRules` in
  `LocalLinkRules.kt`.
- `youtu.be` is always expanded. Use the **Expand short links** switch to disable
  expansion entirely.

## Adding a site

1. Add a `LinkRuleProvider` to `LocalLinkRules.kt`.
2. Use `rules` for parameter names (regex, matched against the whole name).
3. Use `resetQueryOnPaths` only for detail pages where every non-kept parameter is
   disposable. Add functional names to `keepParams`.
4. Add a `PathRewrite` to `canonicalPaths` for affiliate or wrapper paths.
5. Add the shortener host to `NetworkRedirectResolver.shortHosts` and choose its
   User-Agent in `userAgentFor`.
6. Add fixtures to `MarketplaceRulesTest`, `ShortLinkParsersTest`, or
   `ShortLinkResolverTest`, then run:

   ```text
   ./gradlew testDebugUnitTest
   ```
