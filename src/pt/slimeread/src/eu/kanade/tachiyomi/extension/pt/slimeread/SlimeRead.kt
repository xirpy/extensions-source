package eu.kanade.tachiyomi.extension.pt.slimeread

import eu.kanade.tachiyomi.extension.pt.slimeread.dto.ChapterDto
import eu.kanade.tachiyomi.extension.pt.slimeread.dto.LatestResponseDto
import eu.kanade.tachiyomi.extension.pt.slimeread.dto.MangaInfoDto
import eu.kanade.tachiyomi.extension.pt.slimeread.dto.PageListDto
import eu.kanade.tachiyomi.extension.pt.slimeread.dto.PopularMangaDto
import eu.kanade.tachiyomi.extension.pt.slimeread.dto.toSMangaList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy

class SlimeRead : HttpSource() {

    override val name = "SlimeRead"

    override val baseUrl = "https://slimeread.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client by lazy {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), 2)
            .rateLimitHost(API_URL.toHttpUrl(), 1)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder().add("Origin", baseUrl)

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int) = GET("$API_URL/ranking/semana?nsfw=false", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val items = response.parseAs<List<PopularMangaDto>>()
        val mangaList = items.toSMangaList()
        return MangasPage(mangaList, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$API_URL/books?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<LatestResponseDto>()
        val mangaList = dto.data.toSMangaList()
        val hasNextPage = dto.page < dto.pages
        return MangasPage(mangaList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$API_URL/book/$id", headers))
                .asObservableSuccess()
                .map(::searchMangaByIdParse)
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    private fun searchMangaByIdParse(response: Response): MangasPage {
        val details = mangaDetailsParse(response)
        return MangasPage(listOf(details), false)
    }

    override fun getFilterList() = SlimeReadFilters.FILTER_LIST

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = SlimeReadFilters.getSearchParameters(filters)

        val url = "$API_URL/book_search".toHttpUrl().newBuilder()
            .addIfNotBlank("query", query)
            .addIfNotBlank("genre[]", params.genre)
            .addIfNotBlank("status", params.status)
            .addIfNotBlank("searchMethod", params.searchMethod)
            .apply {
                params.categories.forEach {
                    addQueryParameter("categories[]", it)
                }
            }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =========================== Manga Details ============================
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url.replace("/book/", "/manga/")

    override fun mangaDetailsRequest(manga: SManga) = GET(API_URL + manga.url, headers)

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val info = response.parseAs<MangaInfoDto>()
        thumbnail_url = info.thumbnail_url
        title = info.name
        description = info.description
        genre = info.categories.joinToString()
        status = when (info.status) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            3, 4 -> SManga.CANCELLED
            5 -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListRequest(manga: SManga) =
        GET("$API_URL/book_cap_units_all?manga_id=${manga.url.substringAfterLast("/")}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val items = response.parseAs<List<ChapterDto>>()
        val mangaId = response.request.url.queryParameter("manga_id")!!
        return items.map {
            SChapter.create().apply {
                name = "Cap " + parseChapterNumber(it.number)
                chapter_number = it.number
                scanlator = it.scan?.scan_name
                url = "/book_cap_units?manga_id=$mangaId&cap=${it.number}"
            }
        }.reversed()
    }

    private fun parseChapterNumber(number: Float): String {
        val cap = number + 1F
        val num = "%.2f".format(cap)
            .let { if (cap < 10F) "0$it" else it }
            .replace(",00", "")
            .replace(",", ".")
        return num
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val url = "$baseUrl${chapter.url}".toHttpUrl()
        val id = url.queryParameter("manga_id")!!
        val cap = url.queryParameter("cap")!!.toFloat()
        val num = parseChapterNumber(cap)
        return "$baseUrl/ler/$id/cap-$num"
    }

    // =============================== Pages ================================
    override fun pageListRequest(chapter: SChapter) = GET(API_URL + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.parseAs<List<PageListDto>>().flatMap { it.pages }

        return pages.mapIndexed { index, item ->
            Page(index, "", item.url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromStream(it.body.byteStream())
    }

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) addQueryParameter(query, value)
        return this
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val API_URL = "https://ai3.slimeread.com:8443"
    }
}
