# Adding a new provider

This guide is the whole process for adding a site. Adding a provider **never requires
editing an existing provider**, and **never requires editing the GitHub Actions
workflow** — the workflow discovers providers from `config/sites.json`.

---

## TL;DR

```bash
# 1. copy the template
cp -r providers/_template providers/MyNewProvider

# 2. rename the package, classes and plugin
#    providers/MyNewProvider/.../template/TemplateProvider.kt -> mynew/MyNewProvider.kt
#    providers/MyNewProvider/.../template/TemplatePlugin.kt   -> mynew/MyNewPlugin.kt
#    (package com.personal.template -> com.personal.mynew)

# 3. register the site
$EDITOR config/sites.json

# 4. build it locally
python3 tools/build.py build --only MyNewProvider

# 5. push
git add providers/MyNewProvider config/sites.json
git commit -m "feat(MyNewProvider): add <site>"
git push origin main
```

CI then compiles it, adds it to `plugins.json` with its own SHA-256 and file size, and
publishes it to the `builds` branch. Existing plugins are untouched.

---

## 1. Copy and rename the template

`providers/_template/` is **never built** — it is not listed in `config/sites.json`, so the
build tool skips it. Copy it rather than editing it in place.

Pick a package slug that is unique and lowercase. The package name and the plugin class
name are both read from the source at build time, so they must be internally consistent:

| Thing | Template value | Yours |
| --- | --- | --- |
| Directory | `providers/_template/` | `providers/<Name>Provider/` |
| Package | `com.personal.template` | `com.personal.<yourslug>` |
| Provider class | `TemplateProvider` | `<Name>Provider` |
| Plugin class | `TemplatePlugin` | `<Name>Plugin` |
| `mainUrl` | `https://example.com` | the real site root |
| `name` | `<YourName>` | the display name in CloudStream |

The `@CloudstreamPlugin` annotation must stay on **exactly one** class. `tools/build.py`
uses it to fill in `pluginClassName` in the `.cs3` manifest; if it is missing the build
fails with a clear message, and if there are two the first one found wins (so don't).

## 2. Register the site

Add one object to the `sites` array in `config/sites.json`:

```json
{
  "enabled": true,
  "providerName": "MyNewProvider",
  "displayName": "My New Site",
  "baseUrl": "https://example.com",
  "language": "en",
  "tvTypes": ["Movie", "TvSeries"],
  "description": "What this site offers.",
  "iconUrl": "https://raw.githubusercontent.com/CommercialBible/MyCloudStreamExtensions/builds/icons/MyNewProvider.png",
  "status": 1,
  "verification": {
    "verified": false,
    "verifiedOn": null,
    "notes": "Not yet verified. Describe exactly what you have and have not checked."
  }
}
```

Field notes:

- `enabled` — set to `false` to keep the source in the repo but stop publishing it.
  A disabled provider is dropped from `plugins.json` on the next build.
- `providerName` — **must** match the directory name under `providers/`. This is the
  join key between config and source.
- `status` — the CloudStream plugin status code: `1` OK, `2` slow, `3` beta-only,
  `0` down.
- `tvTypes` — shown as badges in CloudStream. Use `Movie`, `TvSeries`, `Anime`,
  `AsianDrama`, etc.
- `language` — ISO code used for the language filter in CloudStream.
- `iconUrl` — a raw URL to a committed icon (see step 4), or the site's own favicon.

That is the entire registration. Nothing else in the repo has to change.

## 3. Write the parser

Replace the template's placeholder selectors with the site's actual markup. Work from
real HTML you have actually looked at — not from an assumption about what the site
"probably" uses.

Map the site's pages onto the five `MainAPI` methods:

| Method | Purpose | Must return |
| --- | --- | --- |
| `getMainPage(page, request)` | home rows / categories | `newHomePageResponse(request, items, hasNext)` |
| `search(query, page)` | search results | `newSearchResponseList(items, hasNext)` |
| `quickSearch(query)` | live suggestions | `List<SearchResponse>` |
| `load(url)` | detail page | `newMovieLoadResponse` / `newTvSeriesLoadResponse` |
| `loadLinks(data, ...)` | playable sources | `true` if at least one link was emitted |

### Common pitfalls

These are real bugs found while building the providers in this repo:

- **Invalid self-closing tags corrupt the DOM.** `ServerOneProvider`'s site emits
  `<div class="card-body" />`, which makes jsoup nest every following card *inside* the
  previous one. Selecting `div.card` then returns one giant element. Fix: select flat
  siblings (`div.single-item`) instead of walking parent/child.
- **A repeated class is not always the same field.** `CTGMoviesProvider`'s site uses
  `div.font-mono` for the year, the quality badge *and* the view count. Match on shape
  ("text is exactly four digits"), not just on the class.
- **The same card is rendered several times per page.** Hero, carousel and grid copies of
  one title carry *different* amounts of metadata. Deduplicate by canonical path and keep
  the *richest* copy, not the first one.
- **Saved pages are not the live page.** "Save page as MHTML" strips `<script>` tags. If a
  site navigates by JS click handler, the saved copy will have cards with no `href` at all
  — that is a capture artifact, not a parser bug. Check whether the *site* or the *save*
  is at fault before rewriting a parser.
- **Never fabricate an identifier.** If a card genuinely carries no detail URL, either
  omit it or resolve it through the site's own search, and say so in the code. Guessing an
  id produces a broken plugin that looks like it works.
- **Respect `fixUrl`.** CloudStream's response builders pass `url` through `fixUrl()`,
  which rewrites anything that looks like a scheme relative to the base URL
  (`"resolve:Feu"` becomes `"http://site/resolve:Feu"`). Use a fully-qualified URL if you
  need a private marker.

### API notes

- `rating` is deprecated — use `score = Score.from10(7.5)`
- `loadExtractor` must be imported from `com.lagradost.cloudstream3.utils`
- the `SearchResponse` base type has no `year`; the concrete subclasses do

## 4. Add an icon

Commit a PNG at `builds/icons/<ProviderName>.png` (square, 128×128 or larger) and point
`iconUrl` at its raw URL:

```
https://raw.githubusercontent.com/CommercialBible/MyCloudStreamExtensions/builds/icons/<ProviderName>.png
```

The `builds/` directory is published as-is to the `builds` branch, so an icon committed
here is immediately reachable. Use only icons you have the right to use — a site's own
favicon, or artwork you made.

## 5. Verify before you claim it works

Local build:

```bash
python3 tools/build.py build --only MyNewProvider
python3 tools/build.py verify
```

Then check the behaviours, and be specific in the `verification` block about which of
these actually passed:

- [ ] home page loads and every row is non-empty
- [ ] pagination moves to page 2 without repeating page 1
- [ ] every section in `mainPage` returns results (not just the first)
- [ ] search returns the title you typed
- [ ] a poster URL resolves (HTTP 200, `image/*`) — not a 404 or a hotlink block
- [ ] title, year, plot and poster are populated on a detail page
- [ ] series detail lists episodes, and an episode loads
- [ ] `loadLinks` emits at least one playable link for a title you are allowed to watch
- [ ] with no account / no permission, it returns `false` instead of a broken link

Only mark `verified: true` for things you actually exercised. This README's provider table
deliberately distinguishes "live-verified", "listings verified" and "playback gated" —
match that honesty.

## 6. Commit and push

```bash
git add providers/MyNewProvider config/sites.json builds/icons/MyNewProvider.png
git commit -m "feat(MyNewProvider): add <site>"
git push origin main
```

CI builds it and publishes. To confirm:

```bash
curl -s https://raw.githubusercontent.com/CommercialBible/MyCloudStreamExtensions/builds/plugins.json \
  | python3 -c "import json,sys; [print(e['internalName'], 'v'+str(e['version'])) for e in json.load(sys.stdin)]"
```

---

## Updating or fixing an existing provider

Edit only that provider's sources and push. `build.py` hashes each provider's sources
independently, so:

- only the edited provider's version increments
- only its `.cs3` changes
- every other plugin keeps its current version and hash, so CloudStream will not
  re-download them

## Temporarily unpublishing without deleting

Set `"enabled": false` in `config/sites.json`. The next build omits it from
`plugins.json`. The source stays in the repo.

## Removing a provider

Delete the directory from `providers/` and the entry from `config/sites.json`. Note that
anyone who already installed it keeps the plugin until they uninstall it — CloudStream
does not delete installed plugins when they disappear from a repository.

## What happens when a build fails

If your provider does not compile:

- the job reports the compiler output
- your provider's **previous published release is preserved** — `plugins.json` keeps the
  last working entry and the last working `.cs3` is not overwritten
- all other providers still build and publish normally

If *every* provider fails, nothing is published at all and the job fails.
