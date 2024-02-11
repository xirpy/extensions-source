package eu.kanade.tachiyomi.extension.en.firstkissdashmanga

import eu.kanade.tachiyomi.multisrc.madara.Madara

class FirstKissDashManga : Madara("1st Kiss-Manga (unoriginal)", "https://1st-kissmanga.online", "en") {
    override val useNewChapterEndpoint = false

    override fun searchPage(page: Int): String = if (page == 1) "" else "page/$page/"
}
