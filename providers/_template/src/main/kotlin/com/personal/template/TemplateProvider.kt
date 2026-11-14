/*
 * TEMPLATE PROVIDER — copy this directory, do not build it in place.
 *
 * This file lives at providers/_template/ and is intentionally NOT listed in
 * config/sites.json, so the build tool ignores it. It exists purely as a starting
 * point: see docs/ADDING_A_PROVIDER.md for the copy-and-rename procedure.
 *
 * What to change when you copy this:
 *   1. package  -> com.personal.<yourslug>
 *   2. class    -> <YourName>Provider
 *   3. mainUrl / name / lang
 *   4. mainPage -> the real section URLs you observed on the site
 *   5. parseCards / load / loadLinks selectors -> the site's real markup
 *   6. the plugin file -> <YourName>Plugin
 */
package com.personal.template

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
 * <YourName>Provider — provider for https://example.com/
 *
 * VERIFICATION STATUS: untested template.
 * Only claim a feature works once you have checked it against real markup from the
 * site (see docs/ADDING_A_PROVIDER.md). Update config/sites.json verification notes
 * to match reality; do not mark something verified that you have not seen work.
 */
class TemplateProvider : MainAPI() {

    override var mainUrl = "https://example.com"
    override var name = "<YourName>"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
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
        "$mainUrl/series" to "TV Series"
    )

    private fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else "$base${if (base.contains("?")) "&" else "?"}page=$page"

    // ------------------------------------------------------------------ home page ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseCards(document, request.data)
        val hasNext = document.select("a[rel=next], a[href*=page=]").isNotEmpty()
        return newHomePageResponse(request, items, hasNext)
    }

    // --------------------------------------------------------------------- search ---

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty()) return newSearchResponseList(emptyList(), false)

        val url = "$mainUrl/search?q=${URLEncoder.encode(q, "UTF-8")}"
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)

        return newSearchResponseList(parseCards(document, url), false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items

    // -------------------------------------------------------------------- parsing ---

    /** Collect one card per detail link. Replace the selectors with the site's own. */
    private fun parseCards(document: Document, context: String): List<SearchResponse> {
        val isSeries = context.contains("series", ignoreCase = true)

        return document
            .select("div.card")           // <-- the site's card container
            .mapNotNull { it.toSearchResult(isSeries) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(isSeries: Boolean): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isEmpty()) return null

        val img = selectFirst("img")
        val title = (
            selectFirst("h3, .title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ) ?: return null

        val poster = img?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
            ?: img?.attr("data-src")?.trim()?.takeIf { it.isNotEmpty() }

        val year = Regex("""((?:19|20)[0-9]{2})""").find(text())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val url = fixUrl(href)

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

    // ----------------------------------------------------------------------- load ---

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching { app.get(url, headers = headers(url)).document }.getOrNull()
            ?: return newMovieLoadResponse("Unknown", url, TvType.Movie, url) {}

        val isSeries = url.contains("series", ignoreCase = true) ||
            document.selectFirst("a[href*=episode], .episodes") != null

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?: document.selectFirst("img.poster, img[src*=poster]")
                ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .synopsis, p")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val year = Regex("""((?:19|20)[0-9]{2})""").find(title)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (isSeries) {
            val episodes = document.select("a[href*=episode], a[href*=watch]")
                .mapNotNull { el ->
                    val href = el.attr("href").trim()
                    if (href.isEmpty()) return@mapNotNull null
                    newEpisode(fixUrl(href)) {
                        this.name = el.text().trim().takeIf { it.isNotEmpty() }
                        this.episode = Regex("""(?i)(?:ep|episode)\s*0*(\d{1,4})""")
                            .find(el.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }
                }
                .distinctBy { it.data }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    // ---------------------------------------------------------------------- links ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data, headers = headers(data)).document }
            .getOrNull() ?: return false

        var emitted = false

        // 1) A media file the page publishes directly.
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

        // 2) Embedded players CloudStream already has extractors for.
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
            u.contains("1080") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            u.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
