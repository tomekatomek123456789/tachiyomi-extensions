package eu.kanade.tachiyomi.extension.all.magicaltranslators

import eu.kanade.tachiyomi.multisrc.guya.Guya
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.Response

class MagicalTranslatorsFactory : SourceFactory {
    override fun createSources() = listOf(
        MagicalTranslatorsEN(),
        MagicalTranslatorsPL(),
    )
}

abstract class MagicalTranslatorsCommon(lang: String) :
    Guya("Magical Translators", "https://mahoushoujobu.com", lang) {

    protected abstract fun filterMangasPage(mangasPage: MangasPage): MangasPage

    override fun popularMangaParse(response: Response): MangasPage =
        filterMangasPage(super.popularMangaParse(response))

    override fun latestUpdatesParse(response: Response): MangasPage =
        filterMangasPage(super.latestUpdatesParse(response))

    override fun proxySearchMangaParse(response: Response, query: String): MangasPage =
        filterMangasPage(super.proxySearchMangaParse(response, query))

    override fun searchMangaParseWithSlug(response: Response, slug: String): MangasPage =
        filterMangasPage(super.searchMangaParseWithSlug(response, slug))

    override fun searchMangaParse(response: Response, query: String): MangasPage =
        filterMangasPage(super.searchMangaParse(response, query))
}

class MagicalTranslatorsEN : MagicalTranslatorsCommon("en") {
    override fun filterMangasPage(mangasPage: MangasPage): MangasPage = mangasPage.copy(
        mangas = mangasPage.mangas.filterNot { it.url.endsWith("-PL") }
    )
}

class MagicalTranslatorsPL : MagicalTranslatorsCommon("pl") {
    override fun filterMangasPage(mangasPage: MangasPage): MangasPage = mangasPage.copy(
        mangas = mangasPage.mangas.filter { it.url.endsWith("-PL") }
    )
}
