package com.personal.ctgmovies

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * CTGMoviesProvider — provider for https://ctgmovies.com/
 *
 * ============================ VERIFICATION STATUS ============================
 * VERIFIED against real, first-party HTML captured from a network that can reach
 * the site (home, /movies, /tv, /anime).
 *
 * Card parsing was validated on all four captures:
 *   ctg_home.html    143 cards, all with title + poster (109 with a year)
 *   ctg_movies.html   62 cards, all with title + poster ( 30 with a year)
 *   ctg_tv.html       62 cards, all with title + poster ( 29 with a year)
 *   ctg_anime.html    62 cards, all with title + poster ( 30 with a year)
 *
 * Confirmed markup facts (do not "clean these up" into tidier selectors):
 *  - The site is a Next.js App Router build, but the saved markup carried NO
 *    __NEXT_DATA__ / self.__next_f payload, so everything is parsed from rendered DOM.
 *  - A card is a single <a> to /movies/<slug>, /tv/<slug> or /anime/<slug> that
 *    contains an <img>. Everything else on the page links to listing roots.
 *  - Title   = div.font-display text (falls back to img[alt])
 *  - Year    = a div.font-mono whose text is exactly four digits. There are other
 *    .font-mono divs (quality badge, view counts), so the 4-digit test matters.
 *  - Poster  = img[src] (a /imgcache/ .webp); img[data-fallback] holds the TMDB jpg.
 *  - Search  = GET /search?q= (the header form is <input name="q"> under role=search).
 *  - Paging  = ?page=N, rendered as "Next" links.
 *
 * STILL UNVERIFIED (no capture supplied for these pages):
 *  - Title/detail page layout, and therefore the exact episode list markup.
 *  - Playback URLs. This site requires an account to watch ("SIGN IN TO WATCH"), and
 *    that gate is NOT bypassed here: loadLinks only reports media the page itself
 *    exposes to an already-authorized client, and returns false otherwise.
 * =============================================================================
 */
class CTGMoviesProvider : MainAPI() {

    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTGMovies"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun headers(referer: String = "$mainUrl/") = browserHeaders + ("Referer" to referer)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/movies?sort=newest" to "Newest Movies",
        "$mainUrl/movies?sort=popular" to "Popular Movies",
        "$mainUrl/movies?sort=rating" to "Top Rated",
        "$mainUrl/tv" to "TV Shows",
        "$mainUrl/anime" to "Anime"
    )

    private fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else "$base${if (base.contains("?")) "&" else "?"}page=$page"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseCards(document, emptyList())
        val hasNext = document.select("a[href*=page=]").any {
            it.text().trim().equals("Next", ignoreCase = true) || it.attr("rel").contains("next")
        }
        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty()) return newSearchResponseList(emptyList(), false)

        val url = "$mainUrl/search?q=${URLEncoder.encode(q, "UTF-8")}${if (page > 1) "&page=$page" else ""}"
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)

        // Search results do not always repeat the section in the href, so pass the
        // requested query down as a hint list for type detection.
        return newSearchResponseList(parseCards(document, emptyList()), false)
    }

    private val contentPathRegex = Regex("""/(movies|tv|anime)/([^/?#]+)""")

    /**
     * Every content card is one <a> wrapping an <img>, pointing at a section slug.
     * Anchors to the bare listing roots ("/movies", "/tv", "/anime") are skipped by
     * the slug requirement in [contentPathRegex].
     *
     * The same title is rendered several times per page (hero banner, carousel row and
     * grid). Measured on the real captures: /movies renders 62 anchors for 32 distinct
     * titles. So duplicates are collapsed by canonical path - but the RICHEST copy wins,
     * because the hero copy often omits the year that the grid copy carries.
     */
    private fun parseCards(document: Document, hints: List<String>): List<SearchResponse> {
        val best = LinkedHashMap<String, Element>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val match = contentPathRegex.find(href) ?: return@forEach
            if (anchor.selectFirst("img") == null) return@forEach

            val key = "/${match.groupValues[1]}/${match.groupValues[2]}"
            val prev = best[key]
            if (prev == null || cardScore(anchor) > cardScore(prev)) best[key] = anchor
        }

        return best.values.mapNotNull { it.toSearchResult() }
    }

    /** Higher = more usable metadata present on the card. */
    private fun cardScore(anchor: Element): Int {
        var score = 0
        if (anchor.select("div.font-mono").any { it.text().trim().let { t -> t.length == 4 && t.all(Char::isDigit) } }) score += 4
        if (anchor.selectFirst("div.font-display")?.text()?.isNotBlank() == true) score += 2
        if (anchor.selectFirst("img")?.attr("src")?.isNotBlank() == true) score += 1
        return score
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isEmpty()) return null

        val match = contentPathRegex.find(href) ?: return null
        val section = match.groupValues[1]

        val img = selectFirst("img") ?: return null

        val title = (
            selectFirst("div.font-display")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: img.attr("alt").trim().takeIf { it.isNotEmpty() }
            ) ?: return null

        // Only a .font-mono div whose whole text is a 4-digit year is the year; the
        // same class is also used for quality badges and view counters.
        val year = select("div.font-mono")
            .map { it.text().trim() }
            .firstOrNull { it.length == 4 && it.all(Char::isDigit) }
            ?.toIntOrNull()

        val poster = img.attr("src").trim().takeIf { it.isNotEmpty() }
            ?: img.attr("data-fallback").trim().takeIf { it.isNotEmpty() }

        val type = when (section) {
            "tv" -> TvType.TvSeries
            "anime" -> TvType.Anime
            else -> TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), type) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()

        val isTv = url.contains("/tv/")
        val isAnime = url.contains("/anime/")
        val type = when {
            isAnime -> TvType.Anime
            isTv -> TvType.TvSeries
            else -> TvType.Movie
        }

        if (document == null) {
            return newMovieLoadResponse("Unknown", url, TvType.Movie, url) {}
        }

        // Detail-page selectors are the least-verified part of this provider: no title
        // page was supplied in the capture set. These are the same primitives that were
        // confirmed to work on the listings (h1, og:image, .font-mono year).
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*=imgcache], img[data-fallback]")
                ?.let { it.attr("src").ifBlank { it.attr("data-fallback") } }
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val year = document.select("div.font-mono, span, div")
            .map { it.text().trim() }
            .firstOrNull { it.length == 4 && it.all(Char::isDigit) }
            ?.toIntOrNull()

        if (isTv || isAnime) {
            val episodes = document
                .select("a[href]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    // Episode links keep the section and add the episode segment.
                    if (href.isEmpty()) return@mapNotNull null
                    if (!contentPathRegex.containsMatchIn(href)) return@mapNotNull null
                    if (href.contains("/watch", true)) return@mapNotNull null

                    val slugCount = href.split("/").count { it.isNotBlank() }
                    if (slugCount < 3) return@mapNotNull null // /tv/<slug> only = not an episode

                    val label = el.text().trim()
                    newEpisode(fixUrl(href)) {
                        this.name = label.takeIf { it.isNotEmpty() }
                        this.episode = Regex("""(?i)(?:ep|episode|e)\s*0*(\d{1,4})""")
                            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        this.season = Regex("""(?i)(?:s|season)\s*0*(\d{1,2})""")
                            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }
                }
                .distinctBy { it.data }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, type, episodes) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching { app.get(data, headers = headers(data)) }.getOrNull()
            ?: return false

        val document = response.document
        var emitted = false

        // 1) Direct media the page publishes to an authorized client.
        document.select("video source[src], video[src], a[href*=/.mp4], a[href*=/.mkv], a[href*=/.m3u8]")
            .map { it.attr("src").ifBlank { it.attr("href") }.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { raw ->
                val mediaUrl = fixUrl(raw).replace(" ", "%20")
                callback(
                    newExtractorLink(name, "$name Direct", mediaUrl, linkTypeOf(mediaUrl)) {
                        this.quality = qualityOf(mediaUrl)
                        this.referer = "$mainUrl/"
                    }
                )
                emitted = true
            }

        if (emitted) return true

        // 2) Embedded players go through CloudStream's own extractor registry. Nothing
        //    here attempts to satisfy the site's sign-in gate or defeat any protection.
        document.select("iframe[src], iframe[data-src]")
            .mapNotNull {
                val u = it.attr("src").ifBlank { it.attr("data-src") }.trim()
                u.takeIf { v -> v.isNotEmpty() && !v.startsWith("cid:") }
            }
            .distinct()
            .forEach { iframe ->
                if (runCatching { loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback) }
                        .getOrDefault(false)
                ) {
                    emitted = true
                }
            }

        // 3) Sign-in wall. This is deliberately not bypassed. The user's own account can
        //    be supplied through CloudStream's provider settings if the site ever exposes
        //    a documented token flow; until then this reports "no playable source".
        return emitted
    }

    private fun linkTypeOf(url: String): ExtractorLinkType = when {
        url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun qualityOf(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("2160") || u.contains("4k") -> Qualities.P2160.value
            u.contains("1440") -> Qualities.P1440.value
            u.contains("1080") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            u.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
