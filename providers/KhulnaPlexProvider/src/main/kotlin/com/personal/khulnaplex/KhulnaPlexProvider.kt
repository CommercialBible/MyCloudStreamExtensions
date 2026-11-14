package com.personal.khulnaplex

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
 * KhulnaPlexProvider — provider for http://khulnaplex.net/
 *
 * ============================ VERIFICATION STATUS ============================
 * VERIFIED against real, first-party HTML captured from a network that can reach
 * the site (home and series.php).
 *
 * Confirmed markup facts:
 *  - Listings are `div.movie-card`, each containing:
 *      img[alt]        -> title            img[src] -> TMDB poster (image.tmdb.org/t/p/w500/…)
 *      h3              -> title            p        -> synopsis
 *      span.rating     -> rating           span.year OR span.seasons -> year / season count
 *  - The hero carousel is `.hero-slide[data-content-id][data-content-type]` and is the
 *    ONLY element in the rendered HTML that carries a real content id:
 *      h1 -> title, .hero-slide-meta -> rating + year, p -> synopsis,
 *      style background-image (…/w1280/…) -> backdrop.
 *  - Watch URLs are `/watch.php?id=<id>&type=<movie|series>` (seen in the activity feed).
 *  - Sections: /movies.php?category=bangla|english|hindi|animation|tamil|dub,
 *    /series.php, and /search.php?genre=<Genre>.
 *  - The search form posts `client_id` + `movie_name` (client id is the site's own
 *    access token field; it is NOT harvested or guessed anywhere in this provider).
 *
 * IMPORTANT LIMITATION, stated plainly:
 *  The saved markup contained no <script> tags (the browser's MHTML save stripped them),
 *  and the grid cards themselves carry no <a href>. On the live site a card navigates
 *  through a JS click handler, and the content id used for that navigation only exists
 *  inside those scripts. So a grid card exposes title/poster/year but NOT a detail URL.
 *
 *  Therefore:
 *   - Home sections built from hero slides use the real, verified `/watch.php?id=` URLs.
 *   - Grid cards are exposed with a resolved-on-open search URL (see gridCardUrl()).
 *     load() recognises that form and resolves the first matching title.
 *  This is deliberately conservative: it never invents an id and never fabricates a
 *  stream. If the site's search cannot resolve a card, that card simply yields nothing.
 * =============================================================================
 */
class KhulnaPlexProvider : MainAPI() {

    override var mainUrl = "http://khulnaplex.net"
    override var name = "KhulnaPlex"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
    )

    private fun headers(referer: String = "$mainUrl/") = browserHeaders + ("Referer" to referer)

    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "All Movies",
        "$mainUrl/movies.php?category=bangla" to "Bangla Movies",
        "$mainUrl/movies.php?category=hindi" to "Hindi Movies",
        "$mainUrl/movies.php?category=english" to "English Movies",
        "$mainUrl/movies.php?category=tamil" to "Tamil Movies",
        "$mainUrl/movies.php?category=dub" to "Dubbed Movies",
        "$mainUrl/movies.php?category=animation" to "Animation",
        "$mainUrl/series.php" to "TV Series"
    )

    private fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else "$base${if (base.contains("?")) "&" else "?"}page=$page"

    // ---------------------------------------------------------------- main page ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseCards(document, isSeriesPage = request.data.contains("series", true))
        val hasNext = document.select("a[href*=page=]").isNotEmpty()
        return newHomePageResponse(request, items, hasNext)
    }

    // ------------------------------------------------------------------ search ---

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty()) return newSearchResponseList(emptyList(), false)

        // The site searches with `movie_name`; `q` is tried as a fallback because the
        // exact server-side parameter could not be confirmed from the capture.
        val candidates = listOf(
            "$mainUrl/search.php?movie_name=${URLEncoder.encode(q, "UTF-8")}",
            "$mainUrl/search.php?q=${URLEncoder.encode(q, "UTF-8")}"
        )

        for (url in candidates) {
            val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
                ?: continue
            val items = parseCards(document, isSeriesPage = false)
            if (items.isNotEmpty()) return newSearchResponseList(items, false)
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items

    // ----------------------------------------------------------------- parsing ---

    private fun parseCards(document: Document, isSeriesPage: Boolean): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        // Titles already covered by a hero slide (which has a real watch.php URL).
        val heroTitles = HashSet<String>()

        // 1) Hero carousel: the only rendered markup carrying a real content id.
        document.select(".hero-slide[data-content-id]").forEach { slide ->
            val id = slide.attr("data-content-id").trim()
            if (id.isEmpty()) return@forEach
            val kind = slide.attr("data-content-type").trim().ifEmpty { "movie" }

            val title = slide.selectFirst("h1")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach

            val backdrop = Regex("""url\(['"]?([^'")]+)['"]?\)""")
                .find(slide.attr("style"))?.groupValues?.getOrNull(1)

            val year = slide.select(".hero-slide-meta span")
                .map { it.text().trim() }
                .firstOrNull { it.length == 4 && it.all(Char::isDigit) }
                ?.toIntOrNull()

            val watchUrl = "$mainUrl/watch.php?id=$id&type=$kind"
            heroTitles.add(title.lowercase())

            out.add(
                if (kind.equals("series", true)) {
                    newTvSeriesSearchResponse(title, watchUrl, TvType.TvSeries) {
                        posterUrl = backdrop?.let { fixUrl(it) }
                        this.year = year
                    }
                } else {
                    newMovieSearchResponse(title, watchUrl, TvType.Movie) {
                        posterUrl = backdrop?.let { fixUrl(it) }
                        this.year = year
                    }
                }
            )
        }

        // 2) Grid cards: title/poster/year only (no href in the markup - see class docs).
        //    A card whose title already came from a hero slide is skipped, so the verified
        //    /watch.php?id= URL is preferred over the search-resolve fallback.
        document.select("div.movie-card").forEach { card ->
            val result = card.toGridResult(isSeriesPage) ?: return@forEach
            if (result.name.lowercase() in heroTitles) return@forEach
            out.add(result)
        }

        return out.distinctBy { it.name to it.url }
    }

    private fun Element.toGridResult(isSeriesPage: Boolean): SearchResponse? {
        val img = selectFirst("img") ?: return null

        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: img.attr("alt").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val poster = img.attr("src").trim().takeIf { it.isNotEmpty() }
            ?: img.attr("data-src").trim().takeIf { it.isNotEmpty() }

        // A series card shows "N Seasons" in span.seasons; a movie card shows a year in
        // span.year. Both variants were observed in the captures.
        val isSeries = isSeriesPage ||
            selectFirst("span.seasons") != null ||
            selectFirst(".series-badge") != null

        val year = selectFirst("span.year")?.text()?.trim()
            ?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?.toIntOrNull()

        val url = gridCardUrl(title)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        }
    }

    /**
     * Grid cards carry no detail URL in the rendered HTML, so the card points at a
     * provider-internal placeholder that names the title. load()/loadLinks() recognise
     * it and resolve the real watch page through the site's own search.
     *
     * The placeholder is deliberately a fully-qualified URL under mainUrl rather than a
     * custom "resolve:" scheme: CloudStream's response builders pass every url through
     * fixUrl(), which rewrites a scheme-looking value relative to mainUrl
     * ("resolve:Feu" -> "http://khulnaplex.net/resolve:Feu") and would destroy the
     * marker. An absolute URL is passed through untouched.
     */
    private fun gridCardUrl(title: String): String =
        "$mainUrl/resolve?title=" + URLEncoder.encode(title, "UTF-8")

    /** Title carried by a [gridCardUrl] placeholder, or null for a real site URL. */
    private fun resolveTitleParam(url: String): String? {
        val marker = "/resolve?title="
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val raw = url.substring(idx + marker.length).substringBefore('&')
        if (raw.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    // -------------------------------------------------------------------- load ---

    override suspend fun load(url: String): LoadResponse {
        val placeholderTitle = resolveTitleParam(url)
        val resolvedUrl = if (placeholderTitle != null) {
            resolveTitle(placeholderTitle)
                ?: return newMovieLoadResponse(placeholderTitle, url, TvType.Movie, url) {
                    this.plot = "KhulnaPlex exposes no detail URL for this card in the rendered " +
                        "markup, and the site search did not resolve a watch page for " +
                        "\"$placeholderTitle\"."
                }
        } else {
            url
        }

        val document = runCatching { app.get(resolvedUrl, headers = headers(resolvedUrl)).document }
            .getOrNull()

        if (document == null) {
            return newMovieLoadResponse("Unknown", resolvedUrl, TvType.Movie, resolvedUrl) {}
        }

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1, h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: document.selectFirst("img[src*=image.tmdb.org], img[src*=poster], img")
                ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() && it.length > 30 }
            ?: document.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val year = Regex("""((?:19|20)[0-9]{2})""").find(title)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val isSeries = resolvedUrl.contains("type=series", true) || resolvedUrl.contains("series", true)

        if (isSeries) {
            val episodes = document.select("a[href*=watch], a[href*=episode]")
                .mapNotNull { el ->
                    val epUrl = el.attr("href").trim()
                    if (epUrl.isEmpty() || epUrl == resolvedUrl) return@mapNotNull null
                    newEpisode(fixUrl(epUrl)) {
                        this.name = el.text().trim().takeIf { it.isNotEmpty() }
                        this.episode = Regex("""(?i)(?:ep|episode)\D{0,3}(\d{1,4})""")
                            .find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                        this.season = Regex("""(?i)(?:s|season)\D{0,3}(\d{1,2})""")
                            .find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }
                }
                .distinctBy { it.data }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, resolvedUrl, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
        }

        return newMovieLoadResponse(title, resolvedUrl, TvType.Movie, resolvedUrl) {
            posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    /** Resolve a bare title to a watch URL via the site's own search results. */
    private suspend fun resolveTitle(title: String): String? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val candidates = listOf(
            "$mainUrl/search.php?movie_name=$encoded",
            "$mainUrl/search.php?q=$encoded"
        )

        for (searchUrl in candidates) {
            val document = runCatching {
                app.get(searchUrl, headers = headers(searchUrl)).document
            }.getOrNull() ?: continue

            // Prefer a real watch link the search results expose.
            document.select("a[href*=watch.php]").firstOrNull()
                ?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return fixUrl(it) }

            // Otherwise take the best title match from the hero carousel, if any.
            document.select(".hero-slide[data-content-id]").forEach { slide ->
                val name = slide.selectFirst("h1")?.text()?.trim() ?: return@forEach
                if (!name.equals(title, ignoreCase = true)) return@forEach
                val id = slide.attr("data-content-id").trim()
                val kind = slide.attr("data-content-type").trim().ifEmpty { "movie" }
                if (id.isNotEmpty()) return "$mainUrl/watch.php?id=$id&type=$kind"
            }
        }
        return null
    }

    // ------------------------------------------------------------------- links ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val placeholderTitle = resolveTitleParam(data)
        val target = if (placeholderTitle != null) {
            resolveTitle(placeholderTitle) ?: return false
        } else data

        val response = runCatching { app.get(target, headers = headers(target)) }.getOrNull()
            ?: return false

        val document = response.document
        var emitted = false

        // 1) The media file the watch page publishes.
        document.select("video source[src], video[src]")
            .map { it.attr("src").trim() }
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

        // 2) Pages that only expose download.php?file=<path>: rebuild the public media
        //    URL from the file= value. The download endpoint itself is not played.
        Regex("""download\.php\?[^"']*?file=([^"'&]+)""")
            .findAll(response.text)
            .map { it.groupValues[1] }
            .mapNotNull { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { path ->
                val mediaUrl = fixUrl(if (path.startsWith("/")) path else "/$path").replace(" ", "%20")
                callback(
                    newExtractorLink(name, "$name Direct", mediaUrl, linkTypeOf(mediaUrl)) {
                        this.quality = qualityOf(mediaUrl)
                        this.referer = "$mainUrl/"
                    }
                )
                emitted = true
            }

        if (emitted) return true

        // 3) Embedded players via CloudStream's extractor registry.
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
