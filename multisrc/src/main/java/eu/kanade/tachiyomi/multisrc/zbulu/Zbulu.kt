package eu.kanade.tachiyomi.multisrc.zbulu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class Zbulu(
    override val name: String,
    override val baseUrl: String,
    override val lang: String
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:75.0) Gecko/20100101 Firefox/75.0")
        .add("Content-Encoding", "identity")

    // Decreases calls, helps with Cloudflare
    private fun String.addTrailingSlash() = if (!this.endsWith("/")) "$this/" else this

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga-list/page-$page/", headers)
    }

    override fun popularMangaSelector() = "div.comics-grid > div.entry"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            element.select("h3 a").let {
                setUrlWithoutDomain(it.attr("href").addTrailingSlash())
                title = it.text()
            }
            thumbnail_url = element.select("img").first().attr("abs:src")
        }
    }

    override fun popularMangaNextPageSelector() = "a.next:has(i.fa-angle-right)"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-update/page-$page/", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/?s=$query"
        } else {
            lateinit var ret: String
            lateinit var genre: String
            filters.forEach { filter ->
                when (filter) {
                    is AuthorField -> {
                        if (filter.state.isNotBlank()) {
                            ret = "$baseUrl/author/${filter.state.replace(" ", "-")}/page-$page"
                        }
                    }
                    is GenreFilter -> {
                        if (filter.toUriPart().isNotBlank() && filter.state != 0) {
                            filter.toUriPart().let { genre = if (it == "completed") "completed" else "genre/$it" }
                            ret = "$baseUrl/$genre/page-$page"
                        }
                    }
                }
            }
            ret
        }
        return GET(url, headers)
    }

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // Manga summary page

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.single-comic").first()

        return SManga.create().apply {
            title = infoElement.select("h1").first().text()
            author = infoElement.select("div.author a").text()
            status = parseStatus(infoElement.select("div.update span[style]").text())
            genre = infoElement.select("div.genre a").joinToString { it.text() }
            description = infoElement.select("div.comic-description p").text()
            thumbnail_url = infoElement.select("img").attr("abs:src")
        }
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapters

    override fun chapterListSelector() = "div.go-border"

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        // Chapter list may be paginated, get recursively
        fun addChapters(document: Document) {
            document.select(chapterListSelector()).map { chapters.add(chapterFromElement(it)) }
            document.select("${latestUpdatesNextPageSelector()}:not([id])").firstOrNull()
                ?.let { addChapters(client.newCall(GET(it.attr("abs:href").addTrailingSlash(), headers)).execute().asJsoup()) }
        }

        addChapters(response.asJsoup())
        return chapters
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            element.select("a").let {
                setUrlWithoutDomain(it.attr("href").addTrailingSlash())
                name = it.text()
            }
            date_upload = element.select("div.chapter-date")?.text().toDate()
        }
    }

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("MM/dd/yyyy", Locale.US)
        }
    }

    private fun String?.toDate(): Long {
        return if (this.isNullOrEmpty()) {
            0
        } else {
            // In the event site displays invalid date
            try {
                dateFormat.parse(this)?.time ?: 0
            } catch (_: ParseException) {
                0
            }
        }
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.chapter-content img").mapIndexed { i, img ->
            Page(i, "", img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters

    private class AuthorField : Filter.Text("Author")

    override fun getFilterList() = FilterList(
        Filter.Header("Cannot combine search types!"),
        Filter.Header("Author name must be exact."),
        Filter.Separator("-----------------"),
        AuthorField(),
        GenreFilter()
    )

    // [...document.querySelectorAll('.sub-menu li a')].map(a => `Pair("${a.textContent}", "${a.getAttribute('href')}")`).join(',\n')
    // from $baseUrl
    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("Choose a genre", ""),
            Pair("Action", "action"),
            Pair("Adult", "adult"),
            Pair("Anime", "anime"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Comic", "comic"),
            Pair("Completed", "completed"),
            Pair("Cooking", "cooking"),
            Pair("Doraemon", "doraemon"),
            Pair("Doujinshi", "doujinshi"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Full Color", "full-color"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Live action", "live-action"),
            Pair("Magic", "magic"),
            Pair("Manga", "manga"),
            Pair("Manhua", "manhua"),
            Pair("Manhwa", "manhwa"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mature", "mature"),
            Pair("Mecha", "mecha"),
            Pair("Mystery", "mystery"),
            Pair("One shot", "one-shot"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("School Life", "school-life"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Smut", "smut"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Tragedy", "tragedy"),
            Pair("Trap", "trap"),
            Pair("Webtoons", "webtoons")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
