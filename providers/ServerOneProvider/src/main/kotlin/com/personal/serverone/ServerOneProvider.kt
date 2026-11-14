package com.personal.serverone

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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * ServerOneProvider — provider for the IBCCL-FTP media index at http://103.203.93.2/
 *
 * Everything in this file was derived from the *live* markup of the site, not guessed:
 *  - Home / listing / search cards all use `div.single-item` (30 per listing page). The
 *    site emits invalid self-closing `<div class="card-body" />` tags which break DOM
 *    nesting for every HTML parser, so cards are read with FLAT selects instead of
 *    parent/child traversal. This is deliberate - do not "fix" it into nested queries.
 *  - Section titles on the home page are `h3.title-default-left`, cards follow in
 *    document order (10 sections x 20 cards).
 *  - Listing pagination is `?page=N` and is genuinely distinct per page (verified: page 1
 *    and page 2 share zero ids).
 *  - Search is `GET /movies?search=<q>`. The site's own `/search_movies` AJAX endpoint
 *    returns a Laravel HTTP 500, so it is intentionally NOT used.
 *  - Detail pages expose the real media file in `<video id="video_id"><source src="...">`
 *    served from the storage host (103.203.93.4). Verified: HTTP 206, video/x-matroska.
 *
 * Only publicly reachable, already-public pages are read. No DRM, paywall, login or
 * protection is bypassed anywhere in this provider.
 */
class ServerOneProvider : MainAPI() {

    override var mainUrl = "http://103.203.93.2"
    override var name = "ServerOne"
    override var lang = "hi"

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

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

    /** Category slugs read from the site's own `#category_slug` filter on /movies. */
    private val categories = listOf(
        "action-movie-(mixed)" to "Action Movie",
        "bollywood-(hindi)" to "Bollywood (Hindi)",
        "hollywood-(english)" to "Hollywood (English)",
        "dhallywood-(bangladeshi)" to "Dhallywood (Bangladeshi)",
        "tollywood-(kolkata)" to "Tollywood (Kolkata)",
        "kollywood-(tamil)" to "Kollywood (Tamil)",
        "hallywood-(korean)" to "Hallywood (Korean)",
        "chinawood-(china)" to "Chinawood (China)",
        "hindi-dubbed-movies-(all)" to "Hindi Dubbed",
        "cartoon-animation-(mixed)" to "Animation",
        "comedy-zone-(mixed)" to "Comedy",
        "war-movie" to "War",
        "crime-movie" to "Crime",
        "imdb-top-250-movies" to "IMDb Top 250"
    )

    override val mainPage = mainPageOf(
        *(
            listOf(
                "$mainUrl/movies" to "Latest Movies",
                "$mainUrl/movie__series" to "Movie Series"
            ) + categories.map { (slug, label) -> "$mainUrl/movies?category_slug=$slug" to label }
            ).toTypedArray()
    )

    // ---------------------------------------------------------------- main page ---

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, headers = headers(url)).document
        val items = parseCards(document)
        val hasNext = document.select("ul.pagination a.page-link[href*=page=]").isNotEmpty()
        return newHomePageResponse(request, items, hasNext)
    }

    // ------------------------------------------------------------------ search ---

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), false)

        // /movies?search=  is the only working, site-supported search (verified).
        val document = app.get(
            "$mainUrl/movies?search=${URLEncoder.encode(q, "UTF-8")}",
            headers = headers()
        ).document

        return newSearchResponseList(parseCards(document), false)
    }

    // ----------------------------------------------------------------- parsing ---

    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("div.single-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href*=video-details]")?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val anchor = selectFirst("h3.item-title a")
        val title = (anchor?.attr("title")?.takeIf { it.isNotBlank() }
            ?: anchor?.text()?.takeIf { it.isNotBlank() })
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val year = extractYear(title)

        return if (isSeriesTitle(title)) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                posterUrl = poster?.let { fixUrl(it) }
                this.year = year
            }
        }
    }

    private val yearRegex = Regex("""(?:^|[^0-9])((?:19|20)[0-9]{2})(?:[^0-9]|$)""")

    private fun extractYear(text: String): Int? =
        yearRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private val seriesRegex = Regex("""(?i)\bS[0-9]{1,2}\s*-?\s*E[0-9]{1,4}\b""")

    private fun isSeriesTitle(title: String): Boolean = seriesRegex.containsMatchIn(title)

    private fun seasonEpisode(title: String): Pair<Int?, Int?> {
        val m = Regex("""(?i)\bS([0-9]{1,2})\s*-?\s*E([0-9]{1,4})\b""").find(title)
            ?: return null to null
        return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
    }

    // -------------------------------------------------------------------- load ---

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers(url)).document

        val title = extractDetailTitle(document)
        val poster = document.selectFirst("div.img-container img")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        val plot = document.selectFirst("#description p")
            ?.text()?.takeIf { it.isNotBlank() }
        val year = extractYear(title)
        val tags = categories
            .map { it.second }
            .filter { tag -> title.contains(tag.substringBefore(" ("), ignoreCase = true) }

        if (isSeriesTitle(title)) {
            val (season, episode) = seasonEpisode(title)
            val episodes = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = season
                    this.episode = episode
                    this.posterUrl = poster
                }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags.ifEmpty { null }
        }
    }

    private fun extractDetailTitle(document: Document): String {
        // The main <h3> holds the filename as its own text node, followed by the
        // "Download" button and view counter as child elements -> ownText() is correct.
        document.selectFirst("div.course-details-tab-area h3")
            ?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // Fallback: strip the noisy suffix from the full h3 text.
        document.selectFirst("div.course-details-tab-area h3")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.substringBefore(" Download").trim() }

        return document.select("h3").firstOrNull { it.ownText().isNotBlank() }
            ?.ownText()?.trim()
            ?: "Unknown"
    }

    // ------------------------------------------------------------------- links ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers(data)).document
        var emitted = false

        // The real, already-public media file published in the page's own <video> tag.
        document.select("video source[src], video#video_id source[src]")
            .map { it.attr("src").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { raw ->
                val mediaUrl = encodeMediaUrl(raw)
                callback(
                    newExtractorLink(name, "$name Direct", mediaUrl, linkTypeOf(mediaUrl)) {
                        this.quality = qualityOf(mediaUrl)
                        this.referer = "$mainUrl/"
                    }
                )
                emitted = true
            }

        return emitted
    }

    /**
     * The storage host serves files whose paths contain literal spaces
     * (e.g. "Bollywood (Hindi)/2026/..."). Only spaces need escaping - the parentheses
     * are valid path characters and were verified to work unescaped (HTTP 206).
     */
    private fun encodeMediaUrl(raw: String): String = raw.replace(" ", "%20")

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
