package eu.kanade.tachiyomi.extension.es.mangasnosekai

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MangasNoSekai : Madara(
    "Mangas No Sekai",
    "https://mangasnosekai.com",
    "es",
    SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
) {
    override val client = super.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2, 1)
        .build()

    override val useNewChapterEndpoint = true

    override val mangaSubString = "manganews"

    override fun popularMangaRequest(page: Int): Request {
        return GET(
            url = "$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=views",
            headers = headers,
            cache = CacheControl.FORCE_NETWORK,
        )
    }

    override fun popularMangaSelector() = "div.page-listing-item > div.row > div"

    override val popularMangaUrlSelector = "a[href]"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            select(popularMangaUrlSelector).first()?.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
            }

            select("figcaption").first()?.let {
                manga.title = it.text()
            }

            select("img").first()?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(
            url = "$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=latest",
            headers = headers,
            cache = CacheControl.FORCE_NETWORK,
        )
    }

    override fun searchPage(page: Int): String {
        return if (page > 1) "page/$page/" else ""
    }

    override fun searchMangaNextPageSelector() = "nav.navigation a.next"

    override val mangaDetailsSelectorTitle = "div.thumble-container p.titleMangaSingle"
    override val mangaDetailsSelectorThumbnail = "div.thumble-container img.img-responsive"
    override val mangaDetailsSelectorDescription = "section#section-sinopsis > p"
    override val mangaDetailsSelectorStatus = "section#section-sinopsis div.d-flex:has(div:contains(Estado)) p"
    override val mangaDetailsSelectorAuthor = "section#section-sinopsis div.d-flex:has(div:contains(Autor)) p"
    override val mangaDetailsSelectorGenre = "section#section-sinopsis div.d-flex:has(div:contains(Generos)) p a"
    override val altNameSelector = "section#section-sinopsis div.d-flex:has(div:contains(Otros nombres)) p"
    override val altName = "Otros nombres: "

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        with(document) {
            selectFirst(mangaDetailsSelectorTitle)?.let {
                manga.title = it.ownText()
            }
            selectFirst(mangaDetailsSelectorAuthor)?.ownText()?.let {
                manga.author = it
            }
            select(mangaDetailsSelectorDescription).let {
                manga.description = it.text()
            }
            select(mangaDetailsSelectorThumbnail).first()?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
            selectFirst(mangaDetailsSelectorStatus)?.ownText()?.let {
                manga.status = when (it) {
                    in completedStatusList -> SManga.COMPLETED
                    in ongoingStatusList -> SManga.ONGOING
                    in hiatusStatusList -> SManga.ON_HIATUS
                    in canceledStatusList -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            val genres = select(mangaDetailsSelectorGenre)
                .map { element -> element.text().lowercase(Locale.ROOT) }
                .toMutableSet()

            manga.genre = genres.toList().joinToString(", ") { genre ->
                genre.replaceFirstChar {
                    if (it.isLowerCase()) {
                        it.titlecase(
                            Locale.ROOT,
                        )
                    } else {
                        it.toString()
                    }
                }
            }

            document.select(altNameSelector).firstOrNull()?.ownText()?.let {
                if (it.isBlank().not() && it.notUpdating()) {
                    manga.description = when {
                        manga.description.isNullOrBlank() -> altName + it
                        else -> manga.description + "\n\n$altName" + it
                    }
                }
            }
        }

        return manga
    }

    override val orderByFilterOptionsValues: Array<String> = arrayOf(
        "",
        "latest2",
        "alphabet",
        "rating",
        "trending",
        "views2",
        "new-manga",
    )

    private fun altChapterRequest(mangaId: String, page: Int): Request {
        val form = FormBody.Builder()
            .add("action", "load_chapters")
            .add("mangaid", mangaId)
            .add("page", page.toString())
            .build()

        val xhrHeaders = headersBuilder()
            .add("Content-Length", form.contentLength().toString())
            .add("Content-Type", form.contentType().toString())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, form)
    }

    private val altChapterListSelector = "div.wp-manga-chapter"
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val mangaUrl = document.location().removeSuffix("/")

        var xhrRequest = xhrChaptersRequest(mangaUrl)
        var xhrResponse = client.newCall(xhrRequest).execute()

        val chapterElements = xhrResponse.asJsoup().select(chapterListSelector())
        if (chapterElements.isEmpty()) {
            val mangaId = document.selectFirst("div.tab-summary > script:containsData(manga_id)")?.data()
                ?.let { MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
                ?: throw Exception("No se pudo obtener el id del manga")

            var page = 1
            do {
                xhrRequest = altChapterRequest(mangaId, page)
                xhrResponse = client.newCall(xhrRequest).execute()
                val xhrDocument = xhrResponse.asJsoup()
                chapterElements.addAll(xhrDocument.select(altChapterListSelector))
                page++
            } while (xhrDocument.select(altChapterListSelector).isNotEmpty())

            countViews(document)
            return chapterElements.map(::altChapterFromElement)
        }

        countViews(document)
        return chapterElements.map(::chapterFromElement)
    }

    private fun altChapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
        name = element.select("div.text-sm").text()
        date_upload = element.select("time").firstOrNull()?.text()?.let {
            parseChapterDate(it)
        } ?: 0
    }

    companion object {
        val MANGA_ID_REGEX = """manga_id\s*=\s*(.*)\s*;""".toRegex()
    }
}
